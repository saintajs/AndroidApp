"""Incidents routes (US-003 — backend.yaml ``endpoints``).

Three thin handlers over the
:class:`~app.domain.services.incident_service.IncidentService` use cases:
``GET /api/incidents`` (owner-scoped list with the optional ``status`` filter),
``POST /api/incidents`` (create-as-``"pending"``) and
``GET /api/incidents/{incident_id}`` (owner-only detail with the
chronological history). Every handler declares the shared bearer-token guard
via :data:`~app.api.deps.CurrentUserDep` — the 401/403 token translations
live in :mod:`app.api.deps` and are not duplicated here — calls EXACTLY one
service method, and maps the single domain failure
(:class:`~app.domain.exceptions.IncidentNotFoundError`) to the uniform safe
404 body ``{"detail": "Incidencia no encontrada"}`` — the same response for a
missing id and another employee's id, so no existence leaks (BR-010). 422
validation stays FastAPI-default and is never caught (INSTR-011).

No business logic and no ORM queries live here: the owner scope comes from the
token subject (INSTR-001, BR-009), the ``status`` literal + ``IncidentCreate``
schemas carry the validation, and the typed response models
(``IncidentListItem`` without ``description``, ``IncidentDetail`` with the
``history`` timeline) do the serialization filtering structurally. The
module-local dependency providers below mirror the
:mod:`app.api.deps` precedent: both repositories are built over the SAME
``get_db`` request-scoped session, which the service captures for its single
commit (INSTR-005).
"""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api.deps import CurrentUserDep
from app.db.repositories.history_repository import HistoryRepository
from app.db.repositories.incident_repository import IncidentRepository
from app.db.session import get_db
from app.domain.exceptions import IncidentNotFoundError
from app.domain.models import Incident, IncidentHistory
from app.domain.schemas.incident import (
    HistoryEntry,
    IncidentCreate,
    IncidentDetail,
    IncidentListItem,
    StatusFilter,
)
from app.domain.services.incident_service import IncidentService

router = APIRouter(prefix="/api", tags=["incidents"])

_NOT_FOUND_DETAIL = "Incidencia no encontrada"


def get_incident_repository(
    session: Annotated[Session, Depends(get_db)],
) -> IncidentRepository:
    """Bind the incidents port to the request-scoped session."""
    return IncidentRepository(session)


def get_history_repository(
    session: Annotated[Session, Depends(get_db)],
) -> HistoryRepository:
    """Bind the append-only history port to the SAME request-scoped session."""
    return HistoryRepository(session)


def get_incident_service(
    incident_repository: Annotated[IncidentRepository, Depends(get_incident_repository)],
    history_repository: Annotated[HistoryRepository, Depends(get_history_repository)],
) -> IncidentService:
    """Build the incident use case over both ports and their shared session."""
    return IncidentService(incident_repository, history_repository)


def _as_detail(incident: Incident, history: list[IncidentHistory]) -> IncidentDetail:
    """Assemble the ``IncidentDetail`` response from the service's pair.

    ``Incident`` has no ``history`` attribute, so
    ``IncidentDetail.model_validate(incident)`` alone cannot build the model:
    the flat incident fields are validated ``from_attributes`` off the ORM row
    and the ``history`` timeline is validated entry by entry from the rows the
    service returned (ordered ``changed_at`` ASC — INSTR-005, BR-021). Pure
    response assembly; the service owns all data access.
    """
    return IncidentDetail(
        id=incident.id,
        title=incident.title,
        description=incident.description,
        category=incident.category,
        priority=incident.priority,
        status=incident.status,
        created_at=incident.created_at,
        updated_at=incident.updated_at,
        history=[HistoryEntry.model_validate(entry) for entry in history],
    )


@router.get("/incidents", response_model=list[IncidentListItem])
def list_incidents(
    current_user: CurrentUserDep,
    service: Annotated[IncidentService, Depends(get_incident_service)],
    status: StatusFilter = "all",
) -> list[IncidentListItem]:
    """List the authenticated owner's incidents, newest first (FUNC-002).

    The ``status`` query is a ``Literal`` (default ``"all"``), so any other
    value is rejected by FastAPI with the structured 422
    ``loc ["query", "status"]`` (BR-017) before this handler runs; resolving
    ``"all"`` to no filter happens inside the service. ``IncidentListItem``
    structurally omits ``description`` (FUNC-003) and there is no pagination
    envelope (BR-020).
    """
    incidents = service.list_for_owner(current_user.id, status)
    return [IncidentListItem.model_validate(incident) for incident in incidents]


@router.post("/incidents", status_code=201, response_model=IncidentDetail)
def create_incident(
    payload: IncidentCreate,
    current_user: CurrentUserDep,
    service: Annotated[IncidentService, Depends(get_incident_service)],
    history_repository: Annotated[HistoryRepository, Depends(get_history_repository)],
) -> IncidentDetail:
    """Create an incident owned by the token subject, starting ``"pending"``.

    ``IncidentCreate`` carries the field-located 422 validation
    (BR-013..016) and the ``extra="forbid"`` mass-assignment defense
    (INSTR-002); ``status``/``owner_id``/``id`` are never client-owned. The
    service commits the incident and its initial history entry atomically
    (INSTR-005, INSTR-011) and returns the committed ``Incident``; the 201
    body is ``IncidentDetail``-shaped, so the single initial history entry is
    RE-READ through the already-bound history port (one repository call whose
    query lives in the repository, not here) — the server-generated
    ``gen_random_uuid()`` ids and timestamps are never fabricated in this
    layer.
    """
    incident = service.create(current_user.id, payload)
    history = history_repository.list_for_incident(incident.id)
    return _as_detail(incident, history)


@router.get("/incidents/{incident_id}", response_model=IncidentDetail)
def get_incident(
    incident_id: uuid.UUID,
    current_user: CurrentUserDep,
    service: Annotated[IncidentService, Depends(get_incident_service)],
) -> IncidentDetail:
    """Return one incident plus its history, only to its owner (FUNC-005).

    A non-UUID path id is rejected 422 by FastAPI (``loc ["path", ...]``)
    before the handler runs. :class:`IncidentNotFoundError` collapses a
    missing id and a foreign id into the SAME 404 body (BR-010).
    """
    try:
        incident, history = service.get_detail_for_owner(current_user.id, incident_id)
    except IncidentNotFoundError as exc:
        raise HTTPException(status_code=404, detail=_NOT_FOUND_DETAIL) from exc
    return _as_detail(incident, history)
