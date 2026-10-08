"""In-memory sliding-window failure rate limiter for sign-in attempts.

Two fully INDEPENDENT counters — one per client IP and one per submitted
email (Q-004, BR-007) — track FAILED authentication attempts only (CONF-003):
there is no success-recording API on this class at all. Tripping one counter
never affects the other. When a budget is exhausted,
:meth:`LoginRateLimiter.check_or_raise` raises
:class:`~app.domain.exceptions.RateLimitExceededError` carrying the
``Retry-After`` seconds until the tripped counter's window resets.

The clock is fully injectable for tests (fake clocks; this module NEVER
sleeps). Limits and window default from the :class:`~app.core.config.Settings`
fields ``LOGIN_RATELIMIT_IP_MAX`` / ``LOGIN_RATELIMIT_EMAIL_MAX`` /
``LOGIN_RATELIMIT_WINDOW_SECONDS``. Routes consume the module-level
``login_rate_limiter`` singleton via ``Depends``.
"""

import math
import time
from collections import defaultdict, deque
from collections.abc import Callable

from app.core.config import Settings
from app.domain.exceptions import RateLimitExceededError


class LoginRateLimiter:
    """Sliding-window failure counters per client IP and per submitted email.

    A recorded failure counts against its budget while ``now - ts <
    window_seconds``; timestamps are pruned on every access. Counters are
    plain deques — thread-safety is not required (sync single-process stack).
    """

    def __init__(
        self,
        *,
        clock: Callable[[], float] | None = None,
        ip_max: int | None = None,
        email_max: int | None = None,
        window_seconds: int | None = None,
    ) -> None:
        self._clock: Callable[[], float] = clock if clock is not None else time.monotonic
        self._ip_max = ip_max if ip_max is not None else Settings().LOGIN_RATELIMIT_IP_MAX
        self._email_max = (
            email_max if email_max is not None else Settings().LOGIN_RATELIMIT_EMAIL_MAX
        )
        self._window_seconds = (
            window_seconds
            if window_seconds is not None
            else Settings().LOGIN_RATELIMIT_WINDOW_SECONDS
        )
        self._ip_failures: dict[str, deque[float]] = defaultdict(deque)
        self._email_failures: dict[str, deque[float]] = defaultdict(deque)

    def check_or_raise(self, *, client_ip: str, email: str) -> None:
        """Raise :class:`RateLimitExceededError` when either budget is exhausted.

        The per-IP and per-email windows are pruned, then checked
        independently; ``Retry-After`` is the seconds (ceil, min 1) until the
        tripped counter's oldest failure leaves the window.
        """
        now = self._clock()
        self._prune(self._ip_failures[client_ip], now)
        self._prune(self._email_failures[email], now)
        if len(self._ip_failures[client_ip]) >= self._ip_max:
            raise RateLimitExceededError(
                retry_after_seconds=self._retry_after(self._ip_failures[client_ip], now)
            )
        if len(self._email_failures[email]) >= self._email_max:
            raise RateLimitExceededError(
                retry_after_seconds=self._retry_after(self._email_failures[email], now)
            )

    def record_failure(self, *, client_ip: str, email: str) -> None:
        """Append one failure timestamp to BOTH the per-IP and per-email window."""
        now = self._clock()
        self._prune(self._ip_failures[client_ip], now)
        self._prune(self._email_failures[email], now)
        self._ip_failures[client_ip].append(now)
        self._email_failures[email].append(now)

    def reset(self) -> None:
        """Test seam: clear all recorded state."""
        self._ip_failures.clear()
        self._email_failures.clear()

    def _prune(self, failures: deque[float], now: float) -> None:
        """Drop timestamps whose window has fully elapsed (``now - ts >= window``)."""
        while failures and now - failures[0] >= self._window_seconds:
            failures.popleft()

    def _retry_after(self, failures: deque[float], now: float) -> int:
        """Seconds (ceil, min 1) until ``failures``' oldest entry exits the window."""
        remaining = self._window_seconds - (now - failures[0])
        return max(1, math.ceil(remaining))


login_rate_limiter = LoginRateLimiter()
"""Module-level singleton injected into routes via ``Depends``."""
