"""Unit tests for app/core/config.py (design.md Decision 2 / CA-03).

Every test controls the process environment exclusively through monkeypatch
(delenv/setenv) and chdirs into a tmp directory so the optional local-dev
``.env`` file can never satisfy a "missing" variable. The autouse fixture in
tests/unit/conftest.py clears the ``get_settings`` lru_cache around each test.
"""

from pathlib import Path

import pytest
from pydantic import ValidationError

from app.core.config import Settings, get_settings

MANDATORY_VARS = ("DATABASE_URL", "JWT_SECRET", "ENVIRONMENT")


def _unset_all(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """Remove every mandatory variable from the env and escape any local .env."""
    monkeypatch.chdir(tmp_path)  # env_file=".env" is resolved against the CWD
    for name in MANDATORY_VARS:
        monkeypatch.delenv(name, raising=False)


@pytest.mark.parametrize("missing_var", MANDATORY_VARS)
def test_missing_variable_fails_fast_naming_it(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, missing_var: str
) -> None:
    """Instantiating Settings with one mandatory variable missing raises
    immediately and the message names exactly that variable."""
    _unset_all(monkeypatch, tmp_path)
    for name in MANDATORY_VARS:
        if name != missing_var:
            monkeypatch.setenv(name, f"unit-test-value-for-{name.lower()}")

    with pytest.raises(ValidationError) as excinfo:
        Settings()

    message = str(excinfo.value)
    assert missing_var in message
    for present_var in MANDATORY_VARS:
        if present_var != missing_var:
            assert f"{present_var}\n" not in message


def test_no_variable_falls_back_to_a_default(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """With nothing in the environment (and no .env), all three variables are
    reported missing — a missing variable never falls back to a default."""
    _unset_all(monkeypatch, tmp_path)

    with pytest.raises(ValidationError) as excinfo:
        Settings()

    message = str(excinfo.value)
    for name in MANDATORY_VARS:
        assert name in message


def test_settings_reads_the_environment(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """When all mandatory variables are present, Settings exposes their values."""
    _unset_all(monkeypatch, tmp_path)
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://u:p@localhost:1/unit")
    monkeypatch.setenv("JWT_SECRET", "unit-secret")
    monkeypatch.setenv("ENVIRONMENT", "unit")

    settings = Settings()

    assert settings.DATABASE_URL == "postgresql+psycopg://u:p@localhost:1/unit"
    assert settings.JWT_SECRET == "unit-secret"
    assert settings.ENVIRONMENT == "unit"


def test_get_settings_is_a_cached_dependency(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """get_settings() returns the same cached instance within one env state."""
    _unset_all(monkeypatch, tmp_path)
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://u:p@localhost:1/unit")
    monkeypatch.setenv("JWT_SECRET", "unit-secret")
    monkeypatch.setenv("ENVIRONMENT", "unit")

    assert get_settings() is get_settings()
