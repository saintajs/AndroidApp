"""Incident request/response schemas (backend.yaml `endpoints`: list-incidents,
create-incident, get-incident-detail).

``IncidentCreate`` validates the POST /api/incidents body and is the
mass-assignment defense (INSTR-002): it declares ``extra="forbid"`` and carries
NO ``status``/``owner_id``/``id`` fields, so a client-supplied
``{"status": "resolved", "owner_id": "..."}`` is rejected with a 422
``extra_forbidden`` entry instead of silently overriding server-owned values.
The lifecycle fields are always server-set: ``status`` starts at ``"pending"``
(BR-011) and the owner is the token subject (INSTR-002, BR-012).

``IncidentListItem`` / ``IncidentDetail`` / ``HistoryEntry`` are typed response
models (``from_attributes=True`` so they build straight from the ORM rows).
Declaring EXACTLY their fields is what structurally filters the serialized
output: ``description`` can never appear in a list item (FUNC-003) and the
detail timeline carries only the history columns.

Per design.md Decision 3 the category/priority/status constraints are plain
``Literal[...]`` types here — NOT the ``app.domain.enums`` str enums — so the
422 payloads keep FastAPI/Pydantic's structured, field-located shape
(BR-013..016, BR-017, INSTR-011) without enum-member noise.
"""

import datetime
import typing
import uuid

from pydantic import BaseModel, ConfigDict, Field

StatusFilter = typing.Literal["pending", "in_progress", "resolved", "all"]
"""GET /api/incidents ``status`` query value (FUNC-004, BR-017).

A plain ``Literal`` query parameter makes FastAPI emit the structured 422 with
``loc ["query", "status"]`` for any other value; ``"all"`` (the route's
default) means no filter.
"""


class IncidentCreate(BaseModel):
    """POST /api/incidents request body — the four client-owned fields, no more.

    Length bounds mirror the DB CHECK constraints (BR-013: title 5-100,
    BR-014: description 10-1000, inclusive) so an invalid body fails at the
    schema with a field-located 422 entry and never reaches the database
    (INSTR-011, BR-018). ``extra="forbid"`` rejects unknown fields — the
    mass-assignment defense (INSTR-002).
    """

    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=5, max_length=100)
    description: str = Field(min_length=10, max_length=1000)
    category: typing.Literal["systems", "equipment", "access", "other"]  # BR-015
    priority: typing.Literal["low", "medium", "high"]  # BR-016


class IncidentListItem(BaseModel):
    """One row of the GET /api/incidents array — WITHOUT ``description``.

    Omitting the field here (not trimming it later) is what guarantees
    ``description`` can never serialize into a list item (FUNC-003).
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    title: str
    category: str
    priority: str
    status: str
    created_at: datetime.datetime
    updated_at: datetime.datetime


class HistoryEntry(BaseModel):
    """One status transition of the GET /api/incidents/{id} timeline.

    ``previous_status`` is ``None`` on the initial-creation entry (BR-025);
    ``changed_by`` is the creator there (INSTR-005). The route assembles
    entries ordered ``changed_at`` ASC (INSTR-005, BR-021).
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    previous_status: str | None
    new_status: str
    changed_by: uuid.UUID
    changed_at: datetime.datetime


class IncidentDetail(BaseModel):
    """GET /api/incidents/{id} payload — the full incident plus its history.

    The full field set (``description`` included) is the owner-visible detail
    view; the chronological ``history`` list is ordered by the repository.
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
    history: list[HistoryEntry]
