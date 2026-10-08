"""Status-transition request/response schemas (backend.yaml
`endpoints.patch-incident-status`).

``StatusUpdateRequest`` validates the PATCH /api/incidents/{id}/status body:
a SINGLE required ``new_status`` field constrained to the three lifecycle
values (BR-017) with ``extra="forbid"`` so no comment/owner/timestamp field
can ride along — the mass-assignment defense for the audit trail (BR-012).
Whether the value is a LEGAL transition is decided by the service (409,
INSTR-003); anything outside the fixed set or absent fails here with the
structured 422 (``loc ["body", "new_status"]``).

``StatusUpdateResponse`` pairs the updated incident with the transition's
``latest_history`` row. ``IncidentOut`` is the incident WITHOUT the ``history``
list (the detail timeline is not part of this endpoint's contract), and
``latest_history`` reuses the existing
:class:`~app.domain.schemas.incident.HistoryEntry` verbatim — one definition
of the history columns, never duplicated.

Per design.md Decision 3 the ``new_status`` constraint is a plain
``Literal[...]`` — NOT the ``app.domain.enums`` str enum — so the 422 keeps
FastAPI/Pydantic's structured, field-located shape (``literal_error``).
"""

import datetime
import typing
import uuid

from pydantic import BaseModel, ConfigDict

from app.domain.schemas.incident import HistoryEntry


class StatusUpdateRequest(BaseModel):
    """PATCH /api/incidents/{id}/status request body — one field, no more.

    ``extra="forbid"`` rejects any additional body field (BR-012): the
    transition's actor is the token subject (INSTR-005) and the timestamp is
    server-set, so a client-supplied ``{"changed_by": ...}`` is a 422
    ``extra_forbidden`` entry, never a write.
    """

    model_config = ConfigDict(extra="forbid")

    new_status: typing.Literal["pending", "in_progress", "resolved"]  # BR-017


class IncidentOut(BaseModel):
    """The updated incident WITHOUT its history timeline.

    Exactly the eight success-fixture fields (id, title, description,
    category, priority, status, created_at, updated_at): declaring them
    structurally filters the serialized output, so the immutable history can
    never ride along on the PATCH response. ``from_attributes=True`` builds it
    straight from the committed ORM row.
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    title: str
    description: str
    category: str
    priority: str
    status: str
    created_at: datetime.datetime
    updated_at: datetime.datetime


class StatusUpdateResponse(BaseModel):
    """PATCH /api/incidents/{id}/status 200 payload.

    ``incident`` is the post-transition row and ``latest_history`` is the
    exactly-one row appended by the same transaction (INSTR-005, BR-025) —
    read from the inserted row, not a stale re-query, so the response matches
    the committed state.
    """

    incident: IncidentOut
    latest_history: HistoryEntry
