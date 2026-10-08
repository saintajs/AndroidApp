"""Unit tests for app/core/ratelimit.py (US-002, Q-004 / BR-007 / CONF-003).

All window-boundary behaviour is driven by an injected :class:`FakeClock` —
the suite never sleeps and never depends on real time. Limits are always
passed explicitly (5 failures / 900 s window) so no test depends on the
ambient environment or ``Settings`` defaults.

Covered behaviour:

- the 5th recorded failure trips the budget and ``check_or_raise`` raises
  ``RateLimitExceededError`` with the correct Retry-After seconds (BR-007).
- per-IP and per-email counters are fully independent (Q-004): exhausting
  one never affects the other, in either direction.
- Retry-After mid-window is the ceil of the remaining window seconds (min 1).
- advancing the fake clock past the 900 s window releases a tripped budget.
- ``reset()`` restores the full budget.
- "success never records a failure" is structural: the class has no
  success-recording API at all (CONF-003) and ``check_or_raise`` itself
  consumes no budget — ``record_failure`` is the only mutation path.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

import pytest

from app.domain.exceptions import RateLimitExceededError

if TYPE_CHECKING:
    from app.core.ratelimit import LoginRateLimiter

IP_MAX = 5
EMAIL_MAX = 5
WINDOW_SECONDS = 900
FULL_WINDOW_RETRY_AFTER = WINDOW_SECONDS

# app/core/ratelimit.py builds a Settings()-backed module singleton at import
# time, so the FIRST import of that module needs the mandatory variables to
# exist. Dummy values are enough (nothing in these tests touches the
# singleton); the environment is controlled exclusively through monkeypatch,
# mirroring the unit tier's ``api_app`` fixture in tests/unit/conftest.py.
_DUMMY_ENV: dict[str, str] = {
    "DATABASE_URL": "postgresql+psycopg://unit:unit@localhost:1/unit",
    "JWT_SECRET": "unit-test-secret",
    "ENVIRONMENT": "unit",
}


@pytest.fixture(autouse=True)
def _ratelimit_module_importable(monkeypatch: pytest.MonkeyPatch) -> None:
    """Guarantee ``app.core.ratelimit`` imports cleanly under its import-time
    ``Settings()`` singleton before any test body runs."""
    for name, value in _DUMMY_ENV.items():
        monkeypatch.setenv(name, value)
    from app.core import ratelimit  # noqa: F401 - the import IS the side effect


class FakeClock:
    """Injectable monotonic clock: tests advance ``now`` manually (no sleeps)."""

    def __init__(self, start: float = 0.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _make_limiter(clock: FakeClock) -> LoginRateLimiter:
    """Limiter with explicit, env-independent limits and the fake clock.

    The class is imported lazily (first import builds the module-level
    ``Settings()``-backed singleton — see ``_ratelimit_module_importable``).
    """
    from app.core import ratelimit

    return ratelimit.LoginRateLimiter(
        clock=clock,
        ip_max=IP_MAX,
        email_max=EMAIL_MAX,
        window_seconds=WINDOW_SECONDS,
    )


def _record_failures(limiter: LoginRateLimiter, *, client_ip: str, email: str, count: int) -> None:
    """Record ``count`` failures for one (ip, email) pair at the current time."""
    for _ in range(count):
        limiter.record_failure(client_ip=client_ip, email=email)


def _assert_tripped(
    limiter: LoginRateLimiter, *, client_ip: str, email: str, retry_after: int
) -> None:
    """Assert the (ip, email) check raises ``RateLimitExceededError`` carrying
    exactly ``retry_after`` as its Retry-After seconds."""
    with pytest.raises(RateLimitExceededError) as excinfo:
        limiter.check_or_raise(client_ip=client_ip, email=email)

    assert excinfo.value.retry_after_seconds == retry_after


# ---------------------------------------------------------------------------
# Budget trip point: the 5th failure is the first to exhaust (BR-007)
# ---------------------------------------------------------------------------


def test_four_failures_within_window_still_pass_check() -> None:
    """BR-007: with a budget of 5, four failures do NOT trip either counter."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=4)

    # Must not raise.
    limiter.check_or_raise(client_ip="10.0.0.1", email="empleado@minisoporte.test")


def test_fifth_failure_trips_budget_with_full_window_retry_after() -> None:
    """BR-007: the 5th failure within the window exhausts the budget; the next
    check raises with Retry-After == 900 s (all failures happened at t=0, so
    the oldest failure leaves the window exactly one full window later)."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=5)

    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="empleado@minisoporte.test",
        retry_after=FULL_WINDOW_RETRY_AFTER,
    )


# ---------------------------------------------------------------------------
# Independence of the per-IP and per-email counters (Q-004)
# ---------------------------------------------------------------------------


def test_tripped_email_budget_leaves_other_email_budgets_untouched() -> None:
    """Q-004: exhausting the budget on (ip1, emailA) leaves emailB's OWN
    per-email budget untouched — a fresh ip asking about emailB still passes.
    (ip1, emailB) does raise, but only because every recorded failure counts
    against the per-IP counter too (BR-007 design), not because of any
    leakage between the two counter structures."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    _record_failures(limiter, client_ip="10.0.0.1", email="a@minisoporte.test", count=5)

    # emailA's per-email budget is spent...
    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="a@minisoporte.test",
        retry_after=FULL_WINDOW_RETRY_AFTER,
    )
    # ...and so is ip1's per-IP budget (both counters saw all 5 failures)...
    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="b@minisoporte.test",
        retry_after=FULL_WINDOW_RETRY_AFTER,
    )
    # ...but emailB's per-email budget is untouched: fresh ip + emailB passes.
    limiter.check_or_raise(client_ip="10.0.0.2", email="b@minisoporte.test")


def test_five_failures_across_distinct_emails_trip_per_ip_budget() -> None:
    """Q-004: five failures from ONE ip across five DIFFERENT emails never
    exhaust any single per-email budget but DO exhaust the per-IP budget — a
    brand-new email from that ip is blocked, while the same email from a
    different ip still passes."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    for i in range(5):
        limiter.record_failure(client_ip="10.0.0.1", email=f"user{i}@minisoporte.test")

    # Per-IP budget tripped for a brand-new email from the same ip...
    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="brand-new@minisoporte.test",
        retry_after=FULL_WINDOW_RETRY_AFTER,
    )

    # ...and the per-email budgets are untouched: same email, different ip passes.
    limiter.check_or_raise(client_ip="10.0.0.2", email="user0@minisoporte.test")


def test_five_failures_one_email_across_distinct_ips_trip_email_budget_only() -> None:
    """Q-004 (vice versa): five failures for ONE email from five different
    ips exhaust the per-email budget only — a fresh ip asking about the same
    email is blocked, while any other email from one of the used ips passes."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    for i in range(5):
        limiter.record_failure(client_ip=f"10.0.1.{i}", email="victim@minisoporte.test")

    # Per-email budget tripped even for a brand-new ip...
    _assert_tripped(
        limiter,
        client_ip="10.0.9.9",
        email="victim@minisoporte.test",
        retry_after=FULL_WINDOW_RETRY_AFTER,
    )

    # ...and the per-IP budgets are untouched: another email from a used ip passes.
    limiter.check_or_raise(client_ip="10.0.1.0", email="other@minisoporte.test")


# ---------------------------------------------------------------------------
# Retry-After correctness mid-window (ceil of remaining window seconds)
# ---------------------------------------------------------------------------


def test_retry_after_mid_window_is_ceiled_remaining_seconds() -> None:
    """Retry-After is ceil(window - (now - oldest_failure)); fractional
    remaining time rounds UP (299.5 s elapsed of 900 s → 601 s left)."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=5)

    clock.advance(299.5)
    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="empleado@minisoporte.test",
        retry_after=601,
    )


def test_retry_after_near_window_end_is_at_least_one_second() -> None:
    """Almost the whole window elapsed (899.99 s of 900 s) → Retry-After is
    ceil(0.01) == 1 (the value never drops below one second)."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=5)

    clock.advance(899.99)
    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="empleado@minisoporte.test",
        retry_after=1,
    )


# ---------------------------------------------------------------------------
# Window release and reset
# ---------------------------------------------------------------------------


def test_advancing_clock_past_window_releases_tripped_budget() -> None:
    """A tripped budget is released once every failure timestamp has left the
    sliding window — at t=901 s the 900 s window is fully clear again."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=5)
    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="empleado@minisoporte.test",
        retry_after=FULL_WINDOW_RETRY_AFTER,
    )

    clock.advance(901)

    # Must not raise — the window has fully elapsed.
    limiter.check_or_raise(client_ip="10.0.0.1", email="empleado@minisoporte.test")


def test_failures_stop_counting_at_exactly_the_window_boundary() -> None:
    """Pruning is ``now - ts >= window``: failures recorded at t=0 still trip
    the check at t=899.99 (one tick before the boundary)."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=5)

    clock.advance(WINDOW_SECONDS - 0.01)
    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="empleado@minisoporte.test",
        retry_after=1,
    )


def test_reset_clears_all_counters_restoring_full_budget() -> None:
    """``reset()`` clears both per-IP and per-email state — afterwards the
    full budget is available again for the SAME (ip, email) pair."""
    clock = FakeClock()
    limiter = _make_limiter(clock)
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=5)
    _assert_tripped(
        limiter,
        client_ip="10.0.0.1",
        email="empleado@minisoporte.test",
        retry_after=FULL_WINDOW_RETRY_AFTER,
    )

    limiter.reset()

    # Must not raise — state is fully cleared.
    limiter.check_or_raise(client_ip="10.0.0.1", email="empleado@minisoporte.test")
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=4)
    limiter.check_or_raise(client_ip="10.0.0.1", email="empleado@minisoporte.test")


# ---------------------------------------------------------------------------
# Success never records a failure (CONF-003 — structural)
# ---------------------------------------------------------------------------


def test_check_or_raise_consumes_no_budget() -> None:
    """CONF-003: ``check_or_raise`` never mutates state, so a caller may check
    any number of times and still has the FULL failure budget afterwards."""
    clock = FakeClock()
    limiter = _make_limiter(clock)

    for _ in range(10):
        limiter.check_or_raise(client_ip="10.0.0.1", email="empleado@minisoporte.test")

    # All five budget slots are still available after ten pure checks.
    _record_failures(limiter, client_ip="10.0.0.1", email="empleado@minisoporte.test", count=4)
    limiter.check_or_raise(client_ip="10.0.0.1", email="empleado@minisoporte.test")


def test_no_success_recording_api_exists_on_limiter() -> None:
    """CONF-003 is structural: the class exposes NO success-recording method —
    ``record_failure`` is the only mutation path, so a successful sign-in
    cannot consume the failure budget even by accident."""
    from app.core import ratelimit

    public_api = {name for name in vars(ratelimit.LoginRateLimiter) if not name.startswith("_")}

    assert public_api == {"check_or_raise", "record_failure", "reset"}
