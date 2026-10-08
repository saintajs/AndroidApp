"""Status-transition route (US-004 — backend.yaml ``endpoints.patch-incident-status``).

One thin handler over the
:class:`~app.domain.services.status_service.StatusService` use case:
``PATCH /api/incidents/{incident_id}/status`` advances an incident through the
linear lifecycle matrix (``pending -> in_progress -> resolved``; ``resolved``
terminal). Access is declared ON the signature via
:func:`~app.api.deps.require_role` (CONF-011): the
``require_role("support")`` dependency sits in the FastAPI dependency chain,
so the 403 for an employee caller fires BEFORE this handler body performs any
incident lookup — mechanically guaranteeing role-before-lookup, and the
uniform ``{"detail": "No autorizado"}`` body never leaks id existence
(INSTR-004, BR-022). 401/403 token translations live in :mod:`app.api.deps`
and are not duplicated here.

The handler calls EXACTLY one service method and maps the typed domain
failures to their response codes with the exact Spanish detail strings:
:class:`~app.domain.exceptions.ForbiddenRoleError` -> 403 (defensive only —
``require_role`` already guards), :class:`~app.domain.exceptions.IncidentNotFoundError`
-> 404 and :class:`~app.domain.exceptions.InvalidTransitionError` -> 409.
422 body validation stays FastAPI-default and is never caught (INSTR-011);
a non-UUID path id is likewise rejected 422 by FastAPI
(``loc ["path", "incident_id"]``) before the handler runs.

No business logic and no ORM queries live here: the linear matrix and the
single atomic commit belong to the service, the schemas carry the validation,
and the typed response models (``IncidentOut`` without ``history``,
``HistoryEntry`` reused verbatim) do the serialization filtering
structurally. The module-local dependency providers below mirror the
:mod:`app.api.routes.incidents` precedent: both repositories are built over
the SAME ``get_db`` request-scoped session, which the service captures for
its single commit (INSTR-005).
"""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api.deps import require_role
from app.db.repositories.history_repository import HistoryRepository
from app.db.repositories.incident_repository import IncidentRepository
from app.db.session import get_db
from app.domain.exceptions import (
    ForbiddenRoleError,
    IncidentNotFoundError,
    InvalidTransitionError,
)
from app.domain.models import User
from app.domain.schemas.incident import HistoryEntry
from app.domain.schemas.status import (
    IncidentOut,
    StatusUpdateRequest,
    StatusUpdateResponse,
)
from app.domain.services.status_service import StatusService

router = APIRouter(prefix="/api", tags=["statuses"])

_FORBIDDEN_DETAIL = "No autorizado"
_NOT_FOUND_DETAIL = "Incidencia no encontrada"
_INVALID_TRANSITION_DETAIL = "Transición de estado no válida"


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


def get_status_service(
    incident_repository: Annotated[IncidentRepository, Depends(get_incident_repository)],
    history_repository: Annotated[HistoryRepository, Depends(get_history_repository)],
) -> StatusService:
    """Build the status-transition use case over both ports and their shared session."""
    return StatusService(incident_repository, history_repository)


@router.patch("/incidents/{incident_id}/status", response_model=StatusUpdateResponse)
def update_incident_status(
    incident_id: uuid.UUID,
    payload: StatusUpdateRequest,
    current_user: Annotated[User, Depends(require_role("support"))],
    service: Annotated[StatusService, Depends(get_status_service)],
    incident_repository: Annotated[IncidentRepository, Depends(get_incident_repository)],
) -> StatusUpdateResponse:
    """Advance one incident to ``payload.new_status`` as the support actor.

    ``StatusUpdateRequest`` carries the field-located 422 validation (the
    ``new_status`` ``Literal`` + ``extra="forbid"``, BR-017/BR-012) and the
    ``require_role("support")`` dependency fires 403 BEFORE any lookup
    (INSTR-004, BR-022). The service validates the linear matrix, then commits
    the status update and exactly one immutable history row atomically
    (INSTR-005, BR-025) and returns the row inserted by THAT transaction
    (design.md Risk 3) — the response's ``latest_history`` is validated off it
    directly, never a re-query. The updated ``Incident`` is re-read through
    the already-bound incident port over the SAME request-scoped session
    (mirroring the POST /api/incidents precedent of re-reading through the
    bound port), so server-set ``updated_at`` comes from the committed row
    instead of being fabricated here.
    """
    try:
        history = service.apply_transition(incident_id, payload.new_status, current_user.id)
    except ForbiddenRoleError as exc:
        # Defensive only: require_role("support") already rejected non-support
        # callers in the dependency chain, before this handler ran.
        raise HTTPException(status_code=403, detail=_FORBIDDEN_DETAIL) from exc
    except IncidentNotFoundError as exc:
        raise HTTPException(status_code=404, detail=_NOT_FOUND_DETAIL) from exc
    except InvalidTransitionError as exc:
        raise HTTPException(status_code=409, detail=_INVALID_TRANSITION_DETAIL) from exc
    incident = incident_repository.get_by_id(incident_id)
    if incident is None:  # pragma: no cover — the service just committed it.
        raise HTTPException(status_code=404, detail=_NOT_FOUND_DETAIL)
    return StatusUpdateResponse(
        incident=IncidentOut.model_validate(incident),
        latest_history=HistoryEntry.model_validate(history),
    )
