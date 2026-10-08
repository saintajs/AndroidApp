"""Unit-tier-only fixtures (owned by the unit tier — NOT tests/conftest.py).

Everything here is PostgreSQL-free: the environment is controlled exclusively
through ``monkeypatch`` (testing.yaml rule — no direct ``os.environ`` reads) and
the app under test is built with ``create_app()`` and driven via httpx
``ASGITransport`` (no live server, no real network).
"""

from collections.abc import Iterator
from typing import TYPE_CHECKING

import pytest

from app.core.config import get_settings

if TYPE_CHECKING:
    from fastapi import FastAPI

# Dummy values are sufficient: create_app() only instantiates Settings() at
# factory time (fail fast) and never opens a database connection.
_DUMMY_ENV: dict[str, str] = {
    "DATABASE_URL": "postgresql+psycopg://unit:unit@localhost:1/unit",
    "JWT_SECRET": "unit-test-secret",
    "ENVIRONMENT": "unit",
}


@pytest.fixture(autouse=True)
def _clear_settings_cache() -> Iterator[None]:
    """``get_settings`` is lru_cache'd — clear it around every test so the
    monkeypatched environment is always what ``Settings()`` actually sees."""
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.fixture
def anyio_backend() -> str:
    """Run anyio-marked async tests (httpx ASGITransport) on asyncio."""
    return "asyncio"


@pytest.fixture
def api_app(monkeypatch: pytest.MonkeyPatch) -> "FastAPI":
    """Build an app instance with valid dummy settings set via monkeypatch.

    ``app.main`` is imported lazily: importing it executes the module-level
    ``app = create_app()``, which instantiates ``Settings()`` — so the dummy
    environment must exist *before* the import for the suite to be runnable
    without DATABASE_URL/JWT_SECRET/ENVIRONMENT exported.
    """
    for name, value in _DUMMY_ENV.items():
        monkeypatch.setenv(name, value)

    from app.main import create_app

    return create_app()
