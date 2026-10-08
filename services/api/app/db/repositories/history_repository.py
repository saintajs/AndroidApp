"""History repository port — the only place a ``select()`` against
``incident_history`` is written.

SQLAlchemy adapter consumed by
:class:`app.domain.services.incident_service.IncidentService`. Append-only by
contract (INSTR-005, BR-012): no update or delete method exists. Transaction
boundaries belong to the ``get_db`` session dependency, so no commit/rollback
happens here — ``append`` only stages, and the service commits it together
with the incident insert.
"""

import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.domain.models import IncidentHistory


class HistoryRepository:
    """Append-only access to the per-incident status timeline."""

    def __init__(self, session: Session) -> None:
        """Bind the repository to the request-scoped session."""
        self._session = session

    def append(self, entry: IncidentHistory) -> None:
        """Stage a history entry on the session; the service commits."""
        self._session.add(entry)

    def list_for_incident(self, incident_id: uuid.UUID) -> list[IncidentHistory]:
        """Return the incident's history ascending by ``changed_at``."""
        stmt = (
            select(IncidentHistory)
            .where(IncidentHistory.incident_id == incident_id)
            .order_by(IncidentHistory.changed_at.asc())
        )
        return list(self._session.scalars(stmt))

    def get_latest(self, incident_id: uuid.UUID) -> IncidentHistory | None:
        """Return the incident's most recent history entry, or ``None``.

        US-004 read-only addition (no mutation method exists, INSTR-005):
        ``changed_at`` DESC over ``ix_incident_history_incident_changed``
        (incident_id, changed_at), limited to one row — the descriptor for the
        PATCH response's ``latest_history``.
        """
        stmt = (
            select(IncidentHistory)
            .where(IncidentHistory.incident_id == incident_id)
            .order_by(IncidentHistory.changed_at.desc())
            .limit(1)
        )
        return self._session.scalar(stmt)
