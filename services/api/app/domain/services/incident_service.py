"""Incident use cases (US-003): owner-scoped list, create-as-Pending, detail.

``IncidentService`` is the only place the incident business rules live; it
sits between the thin route handlers and the repositories (backend.yaml
``services.IncidentService``). The owner scope ALWAYS comes from the token
subject passed by the route — never from a client-owned value (INSTR-001,
BR-009). All SQLAlchemy queries live in
:class:`~app.db.repositories.incident_repository.IncidentRepository` and
:class:`~app.db.repositories.history_repository.HistoryRepository`; this
module never imports ``select`` and never touches HTTP — domain failures are
raised as :class:`~app.domain.exceptions.IncidentNotFoundError` and the route
maps them to status codes.

``create`` orchestrates the unit of work: it stages the ``incidents`` row and
the initial ``incident_history`` entry and commits ONCE, so a failure (e.g. a
CHECK violation) rolls both back — all-or-nothing (INSTR-005, INSTR-011).
The lifecycle fields are server-set: ``status`` starts at ``"pending"``
(BR-011), ``previous_status`` is ``None`` on the initial entry, and
``changed_by`` is the owner.

Return-type contract for ``get_detail_for_owner``:
``tuple[Incident, list[IncidentHistory]]`` — the ORM incident plus its
history ordered ``changed_at`` ASC. The route layer (task 5) assembles the
``IncidentDetail`` response model from these two objects (it must NOT reuse
the service's output model directly: Pydantic builds ``IncidentDetail`` via
``from_attributes``).
"""

import uuid

from app.db.repositories.history_repository import HistoryRepository
from app.db.repositories.incident_repository import IncidentRepository
from app.domain.exceptions import IncidentNotFoundError
from app.domain.models import Incident, IncidentHistory
from app.domain.schemas.incident import IncidentCreate

#: Query values that mean "no status predicate" (FUNC-004, BR-017): the route's
#: default ``"all"`` and the absent parameter both resolve to ``None``.
_NO_FILTER_VALUES = frozenset({None, "all"})


class IncidentService:
    """Incident use cases over the owner-scoped ports.

    Constructor DI mirrors :class:`~app.domain.services.auth_service.AuthService`:
    both repositories are built by the API layer over the SAME request-scoped
    session, which this service captures in order to orchestrate the unit of
    work (flush before the history insert needs the server-generated id; a
    single commit). The repositories themselves stay staged-only.
    """

    def __init__(
        self, incident_repository: IncidentRepository, history_repository: HistoryRepository
    ) -> None:
        """Bind the use case to its two ports and their shared session."""
        self._incident_repository = incident_repository
        self._history_repository = history_repository
        # deps.get_incident_service builds BOTH repositories from the same
        # request-scoped ``get_db`` session, so either port carries it. The
        # transaction boundary (flush/commit) belongs to this service per
        # design.md Decision 1 — the repositories commit nothing.
        self._session = incident_repository._session

    def list_for_owner(self, owner_id: uuid.UUID, status: str | None) -> list[Incident]:
        """List ``owner_id``'s incidents, newest first (FUNC-002).

        ``status`` is resolved here so the repository keeps a single raw
        predicate: ``None`` or ``"all"`` means no filter, any other valid
        value (validated upstream by the schema) narrows the query. The owner
        filter is never optional — the list can never cross owners (INSTR-001).
        """
        resolved = None if status in _NO_FILTER_VALUES else status
        return self._incident_repository.list_by_owner(owner_id, resolved)

    def create(self, owner_id: uuid.UUID, data: IncidentCreate) -> Incident:
        """Create an incident owned by ``owner_id``, starting ``"pending"``.

        Builds the row from ONLY the validated client fields (mass-assignment
        defense, INSTR-002), sets ``status="pending"`` explicitly server-side
        (BR-011), and appends EXACTLY one initial history entry
        (``previous_status=None``, ``new_status="pending"``,
        ``changed_by=owner_id``, INSTR-005). One flush materializes the
        server-generated ``incident.id`` (``gen_random_uuid()``) before the
        history row is built; one commit lands both rows in the same
        transaction — a failure leaves no partial incident (INSTR-011,
        design.md Decision 4). ``created_at``/``updated_at``/``changed_at``
        are never set here: ``server_default=func.now()`` fills them, so
        ``created_at == updated_at`` at creation (CA-04). Returns the
        committed ORM :class:`~app.domain.models.Incident`.
        """
        incident = Incident(
            owner_id=owner_id,
            title=data.title,
            description=data.description,
            category=data.category,
            priority=data.priority,
            status="pending",  # server-set (BR-011) — never client-supplied
        )
        self._incident_repository.add(incident)
        # The PK is a server default: flush so INSERT (and gen_random_uuid())
        # runs and ``incident.id`` is populated BEFORE the history row.
        self._session.flush()
        self._history_repository.append(
            IncidentHistory(
                incident_id=incident.id,
                previous_status=None,
                new_status="pending",
                changed_by=owner_id,
            )
        )
        # Single commit: incident + initial history, all-or-nothing (INSTR-011).
        self._session.commit()
        return incident

    def get_detail_for_owner(
        self, owner_id: uuid.UUID, incident_id: uuid.UUID
    ) -> tuple[Incident, list[IncidentHistory]]:
        """Fetch one incident and its history for its owner (FUNC-005).

        The lookup is owner-scoped, so a non-existent id and another
        employee's id are the same ``None`` — raised as
        :class:`IncidentNotFoundError` either way (BR-010, no existence leak).
        Returns ``(incident, history)`` with the history ordered ``changed_at``
        ASC (INSTR-005, BR-021); the route assembles the ``IncidentDetail``
        response from the pair.
        """
        incident = self._incident_repository.get_for_owner(owner_id, incident_id)
        if incident is None:
            raise IncidentNotFoundError("Incidencia no encontrada")
        history = self._history_repository.list_for_incident(incident_id)
        return incident, history
