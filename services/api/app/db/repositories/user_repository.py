"""User repository port — the only place a ``select()`` against ``users`` is written.

SQLAlchemy adapter consumed by :class:`app.domain.services.auth_service.AuthService`
and the auth dependency (``app.api.deps.get_current_user``). Transaction
boundaries belong to the ``get_db`` session dependency, so no commit/rollback
happens here.
"""

import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.domain.models import User


class UserRepository:
    """Read-only lookups over the ``users`` table."""

    def __init__(self, session: Session) -> None:
        """Bind the repository to the request-scoped session."""
        self._session = session

    def get_by_email(self, email: str) -> User | None:
        """Return the user with exactly this email, or ``None``."""
        stmt = select(User).where(User.email == email)
        return self._session.scalar(stmt)

    def get_by_id(self, user_id: uuid.UUID) -> User | None:
        """Return the user with this primary key, or ``None``."""
        stmt = select(User).where(User.id == user_id)
        return self._session.scalar(stmt)
