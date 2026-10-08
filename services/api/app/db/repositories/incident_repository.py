"""Incident repository port — the only place a ``select()`` against ``incidents``
is written.

SQLAlchemy adapter consumed by
:class:`app.domain.services.incident_service.IncidentService`. Owner-scoped
queries filter by ``owner_id`` (INSTR-001, BR-009); US-004 additionally adds
``get_by_id``, the ONLY non-owner lookup, because the support transition route
is intentionally not owner-scoped (support advances any employee's incident,
INSTR-004 — and employees can never reach it, so owner isolation holds).
Transaction boundaries belong to the ``get_db`` session dependency, so no
commit/rollback happens here — ``add`` and ``set_status`` only stage.
"""

import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.domain.models import Incident


class IncidentRepository:
    """Owner-scoped reads and staging inserts over the ``incidents`` table."""

    def __init__(self, session: Session) -> None:
        """Bind the repository to the request-scoped session."""
        self._session = session

    def list_by_owner(self, owner_id: uuid.UUID, status: str | None) -> list[Incident]:
        """Return the owner's incidents, newest first.

        ``status`` is a residual predicate on top of the owner filter
        (``ix_incidents_owner_created``); ``None`` means no filter — the
        caller resolves ``"all"`` to ``None`` before calling.
        """
        stmt = select(Incident).where(Incident.owner_id == owner_id)
        if status is not None:
            stmt = stmt.where(Incident.status == status)
        stmt = stmt.order_by(Incident.created_at.desc())
        return list(self._session.scalars(stmt))

    def get_for_owner(self, owner_id: uuid.UUID, incident_id: uuid.UUID) -> Incident | None:
        """Return the incident with this id, or ``None``.

        One ``WHERE id == :id AND owner_id == :owner_id`` predicate, so a
        missing id and another user's id are the same ``None`` (BR-010, no
        existence leak).
        """
        stmt = select(Incident).where(
            Incident.id == incident_id,
            Incident.owner_id == owner_id,
        )
        return self._session.scalar(stmt)

    def add(self, incident: Incident) -> None:
        """Stage a new incident on the session; the service commits."""
        self._session.add(incident)

    def get_by_id(self, incident_id: uuid.UUID) -> Incident | None:
        """Return the incident with this id regardless of owner, or ``None``.

        US-004's ONLY non-owner lookup: the support caller may advance ANY
        employee's incident (backend.yaml validation bullet 4, INSTR-004), so
        a plain ``WHERE id == :id`` predicate is correct here. Never use it on
        an employee-facing path — owner-scoped reads go through
        :meth:`get_for_owner` (INSTR-001).
        """
        stmt = select(Incident).where(Incident.id == incident_id)
        return self._session.scalar(stmt)

    def set_status(
        self, incident: Incident, new_status: str, updated_at: datetime.datetime
    ) -> None:
        """Stage a status update on the ORM instance; the service commits.

        Sets ``status`` and ``updated_at`` together inside the caller's
        transaction — the column has NO ORM ``onupdate`` (see
        :mod:`app.domain.models`: "onupdate is applied by the service layer"),
        so the service passes the timestamp explicitly and it lands in the
        SAME commit as the history insert (INSTR-003, INSTR-005).
        """
        incident.status = new_status
        incident.updated_at = updated_at
