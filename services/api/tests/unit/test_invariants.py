"""Static invariant tests (testing.yaml — "Config centralization and
schema-single-source invariants", CA-05).

Grep-style filesystem assertions with no mocking:

* ``os.environ`` / ``os.getenv`` may appear ONLY inside
  ``app/core/config.py`` — every other module receives configuration via the
  DI-provided settings object.
* No ad-hoc SQL schema may exist outside ``migrations/versions/`` — neither
  ``*.sql`` files anywhere else in the API member, nor schema-defining raw
  DDL in ``app/**``. The sanctioned exception, ``text("gen_random_uuid()")``
  server defaults, is an expression and never matches a DDL statement token.

The scans anchor to the repository layout relative to this test file, so they
behave identically when run from any working directory.
"""

from pathlib import Path

API_ROOT = Path(__file__).resolve().parents[2]  # .../services/api
APP_ROOT = API_ROOT / "app"
CONFIG_MODULE = APP_ROOT / "core" / "config.py"
MIGRATIONS_VERSIONS = API_ROOT / "migrations" / "versions"

# Schema-defining raw DDL statement tokens (the schema's single source of
# truth is the Alembic versions package). ``gen_random_uuid()`` server
# defaults — the sanctioned exception — contain none of these.
DDL_TOKENS = ("CREATE TABLE", "CREATE INDEX", "ALTER TABLE", "DROP TABLE")


def _python_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def test_scan_roots_exist() -> None:
    """Guard against a vacuous pass: the scanned trees must really exist."""
    assert APP_ROOT.is_dir(), f"app/ not found at {APP_ROOT}"
    assert CONFIG_MODULE.is_file(), f"config module not found at {CONFIG_MODULE}"
    assert MIGRATIONS_VERSIONS.is_dir(), f"migrations/versions/ not found at {MIGRATIONS_VERSIONS}"


def test_environment_is_read_only_in_core_config() -> None:
    """``os.environ``/``os.getenv`` appear only inside app/core/config.py."""
    offenders = [
        f"{path.relative_to(API_ROOT)}:{number}: {line.strip()}"
        for path in sorted(APP_ROOT.rglob("*.py"))
        if path != CONFIG_MODULE
        for number, line in enumerate(_python_lines(path), start=1)
        if "os.environ" in line or "os.getenv" in line
    ]
    assert offenders == [], (
        "os.environ/os.getenv must appear only in app/core/config.py "
        f"(CA-05); offenders: {offenders}"
    )


def test_no_ad_hoc_sql_files_outside_migrations() -> None:
    """No ``*.sql`` file exists anywhere in the API member outside
    migrations/versions/ — the schema ships solely as Alembic migrations."""
    offenders = [
        str(path)
        for path in sorted(API_ROOT.rglob("*.sql"))
        if MIGRATIONS_VERSIONS not in path.parents
    ]
    assert offenders == [], (
        f"ad-hoc SQL files outside migrations/versions/ are forbidden; offenders: {offenders}"
    )


def test_no_schema_ddl_in_app_code() -> None:
    """No module under app/ declares schema via raw SQL DDL — the ORM is the
    only DB access path in application code; DDL lives solely in
    migrations/versions/."""
    offenders = [
        f"{path.relative_to(API_ROOT)}:{number}: {line.strip()}"
        for path in sorted(APP_ROOT.rglob("*.py"))
        for number, line in enumerate(_python_lines(path), start=1)
        if any(token in line.upper() for token in DDL_TOKENS)
    ]
    assert offenders == [], (
        "raw schema DDL in app/** is forbidden (schema single source = "
        f"migrations/versions/); offenders: {offenders}"
    )
