"""SQLAlchemy engine/session wiring and the ``get_db`` yield dependency.

The engine and sessionmaker are built lazily, on first use from
:class:`app.core.config.Settings` — importing this module never requires
environment variables to be set and never opens a connection (Decision 5).
This module is the single session gateway repositories (US-003+) consume;
no queries are written here.
"""

from collections.abc import Iterator
from functools import lru_cache

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import Settings


@lru_cache(maxsize=1)
def _get_sessionmaker() -> sessionmaker[Session]:
    """Build (once) the sessionmaker from the settings-driven sync engine."""
    settings = Settings()
    engine: Engine = create_engine(settings.DATABASE_URL, pool_pre_ping=True)
    return sessionmaker(bind=engine, class_=Session, expire_on_commit=False)


def get_db() -> Iterator[Session]:
    """Yield one session per request, closing it (rolling back on error) on exit."""
    session = _get_sessionmaker()()
    try:
        yield session
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
