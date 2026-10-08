"""Environment-only application settings (design.md Decision 2, CA-03/CA-05).

This module is the ONLY place in the codebase allowed to read configuration from
the process environment — and it does so exclusively through pydantic-settings,
never via ``os.environ``/``os.getenv``. Environment variables take precedence over
the optional local-dev ``.env`` file; every mandatory variable must be provided
with no default, so a missing variable fails fast at instantiation naming it.
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Environment-sourced configuration (design.md Decision 2).

    The three mandatory variables (DATABASE_URL, JWT_SECRET, ENVIRONMENT) stay
    default-less so a missing one fails fast at instantiation naming it; the
    LOGIN_RATELIMIT_* throttling knobs (US-002: CONF-003, Q-004, BR-007) carry
    documented defaults; the DEMO_* seed variables (US-010) are optional here —
    only ``python -m app.seed.demo_seed`` requires them, and it validates them
    itself.
    """

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    DATABASE_URL: str
    JWT_SECRET: str
    ENVIRONMENT: str

    # Failed sign-in throttling: max failed attempts per client IP in the sliding window.
    LOGIN_RATELIMIT_IP_MAX: int = 5
    # Failed sign-in throttling: max failed attempts per submitted email in the window.
    LOGIN_RATELIMIT_EMAIL_MAX: int = 5
    # Failed sign-in throttling: sliding-window length in seconds.
    LOGIN_RATELIMIT_WINDOW_SECONDS: int = 900

    # Seed-only demo credentials (US-010): no invented values — default None.
    # Optional at application startup; mandatory ONLY for `python -m
    # app.seed.demo_seed`, which validates them itself and aborts naming every
    # missing variable.
    DEMO_EMPLOYEE_PASSWORD: str | None = None
    DEMO_SUPPORT_PASSWORD: str | None = None
    DEMO_SUPPORT_EMAIL: str | None = None


@lru_cache
def get_settings() -> Settings:
    """Cached DI dependency returning the process-wide :class:`Settings`."""
    return Settings()
