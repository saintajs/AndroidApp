"""Negative-config unit tests for the demo seed (US-010, testing.yaml
components_under_test — Settings negative-config slice + source invariant).

PostgreSQL-free by construction: the seed is exercised against a RECORDING
fake session (the plain-fakes style of ``test_status_service.py``), the
environment is controlled exclusively through ``monkeypatch`` (never real
``os.environ`` reads), and every case constructs a fresh ``Settings`` after
``monkeypatch`` has placed exactly the variables the scenario needs — so the
whole suite stays green under
``env -u DATABASE_URL -u JWT_SECRET -u ENVIRONMENT -u TEST_DATABASE_URL``.
The unit conftest's autouse fixture clears the ``get_settings`` lru_cache
around every test, so the monkeypatched environment is always what
``Settings()`` actually sees.

Covered behaviour (CA-03 / CA-04 / CA-08 / BR-033):
- Each DEMO_* variable missing independently aborts with a message naming
  THAT variable and creates nothing (3-case matrix).
- A blank-after-strip value counts as missing too (same abort, zero writes).
- ``ENVIRONMENT=production`` refuses to run FIRST — even when demo variables
  are also missing — and creates nothing.
- ``Settings`` keeps JWT_SECRET default-less (missing → construction fails
  naming it, no substituted default) while the DEMO_* fields default to
  ``None`` so normal startup works without demo secrets.
- Source invariant: no plaintext demo-password default exists anywhere under
  ``app/`` (walked with ``ast`` — docstrings/comment mentions of the variable
  NAMES are fine; only a real string-value assignment or a literal passed to
  ``hash_password`` is a violation).
"""

import ast
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.seed.demo_seed import run_demo_seed

API_ROOT = Path(__file__).resolve().parents[2]  # .../services/api
APP_ROOT = API_ROOT / "app"

# The three mandatory variables the fast tier strips from the process env —
# every ``Settings()`` construction below monkeypatches all the ones its
# scenario needs, so nothing ever leaks in from the real environment.
_MANDATORY_ENV: dict[str, str] = {
    "DATABASE_URL": "postgresql+psycopg://unit:unit@localhost:1/unit",
    "JWT_SECRET": "unit-test-secret",
    "ENVIRONMENT": "test",
}

_DEMO_ENV: dict[str, str] = {
    "DEMO_EMPLOYEE_PASSWORD": "employee-demo-throwaway-1",
    "DEMO_SUPPORT_PASSWORD": "support-demo-throwaway-2",
    "DEMO_SUPPORT_EMAIL": "soporte@minisoporte.test",
}


def _seed_full_env(monkeypatch: pytest.MonkeyPatch, *, environment: str = "test") -> None:
    """Place the mandatory variables plus all three DEMO_* variables."""
    for name, value in _MANDATORY_ENV.items():
        monkeypatch.setenv(name, value)
    monkeypatch.setenv("ENVIRONMENT", environment)
    for name, value in _DEMO_ENV.items():
        monkeypatch.setenv(name, value)


class RecordingSession:
    """Session double recording every unit-of-work call — no database.

    ``run_demo_seed`` consumes it ONLY as the write boundary (``add`` staging,
    ``flush`` UUID assignment, single ``commit``) and as the lookup port
    (``scalar``), so recording the calls is all the behaviour the abort
    scenarios need to prove "creates nothing".
    """

    def __init__(self) -> None:
        self.added: list[object] = []
        self.commit_calls = 0
        self.flush_calls = 0
        self.scalar_calls: list[object] = []

    def add(self, obj: object) -> None:
        self.added.append(obj)

    def commit(self) -> None:
        self.commit_calls += 1

    def flush(self) -> None:
        self.flush_calls += 1

    def scalar(self, statement: object) -> None:
        self.scalar_calls.append(statement)


def _assert_created_nothing(session: RecordingSession) -> None:
    """The abort gates must fire before ANY session interaction."""
    assert session.added == []
    assert session.commit_calls == 0
    assert session.flush_calls == 0
    assert session.scalar_calls == []


# ---------------------------------------------------------------------------
# Missing-variable abort matrix (CA-03)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "missing",
    ["DEMO_EMPLOYEE_PASSWORD", "DEMO_SUPPORT_PASSWORD", "DEMO_SUPPORT_EMAIL"],
)
def test_missing_demo_variable_aborts_naming_it_and_creates_nothing(
    monkeypatch: pytest.MonkeyPatch, missing: str
) -> None:
    """Each DEMO_* variable missing independently (the other two set): the
    seed aborts with a message naming THAT variable and the fake session
    recorded ZERO adds/flushes/scalars/commits — nothing is created."""
    _seed_full_env(monkeypatch)
    monkeypatch.delenv(missing)
    settings = Settings()
    session = RecordingSession()

    with pytest.raises(SystemExit) as excinfo:
        run_demo_seed(session, settings)

    message = str(excinfo.value)
    assert missing in message
    assert "Missing required environment variable(s)" in message
    _assert_created_nothing(session)


def test_blank_demo_value_counts_as_missing_and_creates_nothing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A whitespace-only DEMO_* value counts as missing (blank-after-strip):
    the same abort names the variable and nothing is created — an empty
    default can never sneak past the gate."""
    _seed_full_env(monkeypatch)
    monkeypatch.setenv("DEMO_EMPLOYEE_PASSWORD", "   ")
    settings = Settings()
    session = RecordingSession()

    with pytest.raises(SystemExit) as excinfo:
        run_demo_seed(session, settings)

    assert "DEMO_EMPLOYEE_PASSWORD" in str(excinfo.value)
    assert "Missing required environment variable(s)" in str(excinfo.value)
    _assert_created_nothing(session)


# ---------------------------------------------------------------------------
# Production refusal (CA-04) — and its precedence over the missing-var check
# ---------------------------------------------------------------------------


def test_production_environment_refuses_to_run_with_full_demo_config(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``ENVIRONMENT=production`` with every DEMO_* variable set: the seed
    refuses with a clear refusal message and creates nothing."""
    _seed_full_env(monkeypatch, environment="production")
    settings = Settings()
    session = RecordingSession()

    with pytest.raises(SystemExit) as excinfo:
        run_demo_seed(session, settings)

    message = str(excinfo.value)
    assert "production" in message
    assert "ENVIRONMENT" in message
    assert "Refusing to run the demo seed" in message
    _assert_created_nothing(session)


def test_production_refusal_precedes_the_missing_variable_check(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Gate ordering: ``ENVIRONMENT=production`` AND all demo variables
    missing — the PRODUCTION refusal message wins, not the missing-variable
    abort (the refusal must hold regardless of which demo variables are set)."""
    _seed_full_env(monkeypatch, environment="production")
    for name in _DEMO_ENV:
        monkeypatch.delenv(name)
    settings = Settings()
    session = RecordingSession()

    with pytest.raises(SystemExit) as excinfo:
        run_demo_seed(session, settings)

    message = str(excinfo.value)
    assert "production" in message
    assert "Missing required environment variable(s)" not in message
    _assert_created_nothing(session)


# ---------------------------------------------------------------------------
# Settings negative config (CA-08) — no DB, no seed
# ---------------------------------------------------------------------------


def test_missing_jwt_secret_fails_settings_construction_naming_it(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A missing JWT_SECRET fails ``Settings()`` construction with a
    ``ValidationError`` naming the field as ``missing`` — no default is ever
    substituted (CA-08)."""
    _seed_full_env(monkeypatch)
    monkeypatch.delenv("JWT_SECRET")

    with pytest.raises(ValidationError) as excinfo:
        Settings()

    errors = excinfo.value.errors()
    assert any(
        error["loc"] == ("JWT_SECRET",) and error["type"] == "missing" for error in errors
    ), f"expected a missing-JWT_SECRET error, got: {errors}"
    assert "JWT_SECRET" in str(excinfo.value)


def test_demo_fields_default_to_none_so_normal_startup_works(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """With ONLY the three mandatory variables set, ``Settings()`` constructs
    and every DEMO_* field is ``None`` (no invented values) — normal startup
    works without demo secrets; the cached ``get_settings()`` sees the same."""
    for name, value in _MANDATORY_ENV.items():
        monkeypatch.setenv(name, value)
    monkeypatch.setenv("ENVIRONMENT", "test")
    get_settings.cache_clear()

    settings = Settings()
    assert settings.DEMO_EMPLOYEE_PASSWORD is None
    assert settings.DEMO_SUPPORT_PASSWORD is None
    assert settings.DEMO_SUPPORT_EMAIL is None
    assert get_settings().DEMO_EMPLOYEE_PASSWORD is None
    assert get_settings().DEMO_SUPPORT_PASSWORD is None
    assert get_settings().DEMO_SUPPORT_EMAIL is None


# ---------------------------------------------------------------------------
# Source invariant: no plaintext demo-password default anywhere (CA-03/CA-08)
# ---------------------------------------------------------------------------


def test_app_source_root_exists_for_invariant_scan() -> None:
    """Guard against a vacuous pass: the scanned tree must really exist."""
    assert APP_ROOT.is_dir(), f"app/ not found at {APP_ROOT}"


def _password_assign_violations() -> list[str]:
    """Assignments giving a DEMO password a real string VALUE under app/.

    ``ast``-based so docstrings and comments that merely MENTION the variable
    names never false-positive — only a genuine value assignment violates.
    ``= None`` (config.py's optional default) is explicitly allowed.
    """
    names = {"DEMO_EMPLOYEE_PASSWORD", "DEMO_SUPPORT_PASSWORD"}
    offenders: list[str] = []
    for path in sorted(APP_ROOT.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if not isinstance(node, (ast.Assign, ast.AnnAssign)):
                continue
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            assigned = {
                target.id
                for target in targets
                if isinstance(target, ast.Name) and target.id in names
            }
            if not assigned or not isinstance(node.value, ast.Constant):
                continue
            value = node.value.value
            if isinstance(value, str) and value.strip():
                offenders.append(f"{path.relative_to(API_ROOT)}:{node.lineno}: {sorted(assigned)}")
    return offenders


def _hash_password_literal_violations() -> list[str]:
    """Calls passing a string LITERAL straight to ``hash_password`` in app/seed/.

    Scoped to the seed slice — that is where a literal argument would be a
    demo-password default; the sanctioned ``_dummy_hash()`` timing constant in
    auth_service.py (BR-002, burns bcrypt cost on absent-email sign-in) is not
    a demo credential. The only sanctioned seed callsite shape derives the
    argument from an env-supplied settings value (a Name), never a secret.
    """
    offenders: list[str] = []
    for path in sorted((APP_ROOT / "seed").rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            func = node.func
            name = func.id if isinstance(func, ast.Name) else getattr(func, "attr", "")
            if name != "hash_password" or not node.args:
                continue
            first = node.args[0]
            if (
                isinstance(first, ast.Constant)
                and isinstance(first.value, str)
                and first.value.strip()
            ):
                offenders.append(f"{path.relative_to(API_ROOT)}:{node.lineno}: hash_password(...)")
    return offenders


def test_no_plaintext_demo_password_default_exists_in_app_source() -> None:
    """BR-033/BR-029: no module under app/ assigns a plaintext default to
    DEMO_EMPLOYEE_PASSWORD / DEMO_SUPPORT_PASSWORD or hands a literal secret
    to ``hash_password`` — passwords exist only as bcrypt hashes computed from
    env-supplied values at seed time."""
    assign_offenders = _password_assign_violations()
    hash_offenders = _hash_password_literal_violations()
    assert assign_offenders == [], (
        "plaintext DEMO password default in app/** is forbidden (BR-033); "
        f"offenders: {assign_offenders}"
    )
    assert hash_offenders == [], (
        "string literal passed to hash_password in app/seed/** is forbidden (BR-029); "
        f"offenders: {hash_offenders}"
    )
