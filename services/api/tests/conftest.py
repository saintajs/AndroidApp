"""Shared fixtures for the MiniSoporte API test suite (testing.yaml harness).

Integration tier contract:
- The database comes from ``TEST_DATABASE_URL`` (never Docker as the only path).
  When the variable is unset, a Testcontainers fallback is attempted only if the
  library is importable and Docker works; otherwise the integration tier is
  skipped cleanly — Docker is never required to pass.
- A session-scoped fixture applies ``alembic upgrade head`` exactly once so the
  schema is always migration-defined (never ``create_all``).
- A function-scoped fixture binds a :class:`~sqlalchemy.orm.Session` to a
  connection-level transaction and rolls it back after every test: tests never
  commit persistent state.

This conftest sits outside ``app/`` on purpose: the CA-05 invariant (``os.environ``
only in ``app/core/config.py``) is scanned against ``app/``, and testing.yaml
permits the test harness itself to read the process environment here.
"""

import importlib
import importlib.util
import os
from collections.abc import Iterator
from pathlib import Path

import alembic.command
import alembic.config
import pytest
from sqlalchemy import Engine, create_engine, inspect
from sqlalchemy.engine import Inspector
from sqlalchemy.orm import Session

BASE_DIR = Path(__file__).resolve().parents[1]

# Keys that migrations/env.py's Settings() requires when alembic is driven
# programmatically; missing ones are injected for the upgrade only.
_MANDATORY_ENV_KEYS = ("DATABASE_URL", "JWT_SECRET", "ENVIRONMENT")
_TEST_ENVIRONMENT = "test"
_TEST_JWT_SECRET = "integration-harness-only"  # noqa: S105 - test fixture value

_POSTGRES_TESTCONTAINER_IMAGE = "postgres:16.15"


@pytest.fixture(scope="session")
def test_database_url() -> Iterator[str]:
    """Resolve the integration database URL, or skip the tier.

    Priority: ``TEST_DATABASE_URL`` (the only critical path), then a
    Testcontainers fallback for dev machines with Docker, then a clean skip.
    """
    url = os.environ.get("TEST_DATABASE_URL")
    if url:
        yield url
        return
    if importlib.util.find_spec("testcontainers") is None:
        pytest.skip(
            "TEST_DATABASE_URL is unset and testcontainers is not installed — "
            "the integration tier never requires Docker to pass"
        )
    postgres_module = importlib.import_module("testcontainers.postgres")
    container = postgres_module.PostgresContainer(_POSTGRES_TESTCONTAINER_IMAGE)
    container.start()
    try:
        yield str(container.get_connection_url(driver="psycopg"))
    finally:
        container.stop()


def _apply_migrations(url: str) -> None:
    """Run ``alembic upgrade head`` once against the integration database.

    ``migrations/env.py`` builds its URL from ``Settings().DATABASE_URL``, so the
    harness injects ``TEST_DATABASE_URL`` into the process environment (plus any
    missing mandatory Settings key) for the duration of the upgrade and restores
    the original environment afterwards.
    """
    original_values = {key: os.environ.get(key) for key in _MANDATORY_ENV_KEYS}
    os.environ["DATABASE_URL"] = url
    os.environ.setdefault("JWT_SECRET", _TEST_JWT_SECRET)
    os.environ.setdefault("ENVIRONMENT", _TEST_ENVIRONMENT)
    try:
        alembic_cfg = alembic.config.Config(str(BASE_DIR / "alembic.ini"))
        alembic_cfg.set_main_option("script_location", str(BASE_DIR / "migrations"))
        alembic.command.upgrade(alembic_cfg, "head")
    finally:
        for key, original in original_values.items():
            if original is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = original


@pytest.fixture(scope="session")
def engine(test_database_url: str) -> Iterator[Engine]:
    """Session-scoped engine over the migration-applied integration database."""
    _apply_migrations(test_database_url)
    test_engine = create_engine(test_database_url)
    try:
        yield test_engine
    finally:
        test_engine.dispose()


@pytest.fixture
def db(engine: Engine) -> Iterator[Session]:
    """Per-test session bound to a connection-level transaction.

    Every test runs inside the transaction and it is rolled back afterwards, so
    tests never leave persistent rows behind (per-test rollback isolation).
    """
    with engine.connect() as connection:
        transaction = connection.begin()
        session = Session(bind=connection, join_transaction_mode="create_savepoint")
        try:
            yield session
        finally:
            session.close()
            transaction.rollback()


@pytest.fixture
def introspector(engine: Engine) -> Iterator[Inspector]:
    """SQLAlchemy inspector for schema introspection of the migrated database."""
    with engine.connect() as connection:
        yield inspect(connection)
