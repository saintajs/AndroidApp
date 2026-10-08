"""Status-transition use case (US-004): the linear matrix + one unit of work.

``StatusService`` is the only place the incident lifecycle rules live; it
sits between the thin route handler and the repositories (backend.yaml
``services.StatusService``, design.md Decision 2). The linear matrix
(``pending -> in_progress -> resolved``; ``resolved`` terminal) is validated
BEFORE any write — a backwards, skipped, repeated, or out-of-``resolved``
transition raises :class:`~app.domain.exceptions.InvalidTransitionError` and
leaves both tables untouched (INSTR-003, BR-023/024). All SQLAlchemy queries
live in :class:`~app.db.repositories.incident_repository.IncidentRepository`
and :class:`~app.db.repositories.history_repository.HistoryRepository`; this
module never imports ``select`` and never touches HTTP — domain failures are
raised as typed exceptions and the route maps them to status codes.

The lookup on this path is NOT owner-scoped: support may advance ANY
employee's incident (INSTR-004), so it goes through
:meth:`~app.db.repositories.incident_repository.IncidentRepository.get_by_id`
— the ONLY non-owner lookup in the codebase. Owner isolation stays intact
because employees can never reach this service (the route's
``require_role("support")`` dependency, task 5, short-circuits with 403
before any lookup; the service does NOT duplicate the role check — it trusts
its caller's role was enforced upstream, exactly like ``IncidentService``
trusts the token-derived owner id).

``apply_transition`` orchestrates the unit of work: it stages the
``incident_history`` row and the ``incidents`` status/``updated_at`` update
and commits ONCE, so both land together or not at all (INSTR-005, BR-025).
``changed_at`` is never set here: ``server_default=func.now()`` fills it.
``updated_at`` IS set explicitly (``datetime.datetime.now(datetime.timezone.utc)``)
because the column has NO ORM ``onupdate`` (see ``app.domain.models``:
"onupdate is applied by the service layer") and the repository's
``set_status`` signature takes a concrete ``datetime.datetime``.

Return-type contract for ``apply_transition``: the freshly appended
:class:`~app.domain.models.IncidentHistory` ORM row — NOT a Pydantic model
(the route assembles ``StatusUpdateResponse`` from it via ``from_attributes``,
same precedent as ``IncidentService`` returning ORM objects). The row is the
one inserted by THIS transaction (design.md Risk 3): it is returned directly
rather than re-queried through ``HistoryRepository.get_latest``, so the
response's ``latest_history`` always matches the committed state exactly.
``actor_id`` is passed as the caller's ``uuid.UUID`` token subject (backend.yaml
sketches ``actor: CurrentUser``; the raw id is what the ORM
``IncidentHistory.changed_by`` column needs, and the route already holds the
``CurrentUser``).
"""

import datetime
import uuid

from app.db.repositories.history_repository import HistoryRepository
from app.db.repositories.incident_repository import IncidentRepository
from app.domain.exceptions import IncidentNotFoundError, InvalidTransitionError
from app.domain.models import IncidentHistory

#: The linear transition matrix (BR-023): each status maps to its single
#: legal successor. ``resolved`` is terminal — it has no successor, so ANY
#: ``new_status`` on a resolved incident is illegal (BR-024). Every
#: backwards/skipped/repeated move is absent from the mapping and therefore
#: rejected.
_ALLOWED_TRANSITIONS: dict[str, str] = {
    "pending": "in_progress",
    "in_progress": "resolved",
}


class StatusService:
    """The status-transition use case over the incident/history ports.

    Constructor DI mirrors :class:`~app.domain.services.incident_service.IncidentService`:
    both repositories are built by the API layer over the SAME request-scoped
    session, which this service captures in order to orchestrate the unit of
    work (a single commit). The repositories themselves stay staged-only.
    """

    def __init__(
        self, incident_repository: IncidentRepository, history_repository: HistoryRepository
    ) -> None:
        """Bind the use case to its two ports and their shared session."""
        self._incident_repository = incident_repository
        self._history_repository = history_repository
        # deps builds BOTH repositories from the same request-scoped
        # ``get_db`` session, so either port carries it. The transaction
        # boundary (commit) belongs to this service per design.md Decision 2
        # — the repositories commit nothing.
        self._session = incident_repository._session

    def apply_transition(
        self, incident_id: uuid.UUID, new_status: str, actor_id: uuid.UUID
    ) -> IncidentHistory:
        """Advance ``incident_id`` to ``new_status`` as ``actor_id`` (US-004).

        ``new_status`` arrives already schema-validated (the
        ``StatusUpdateRequest`` ``Literal``), so it is a plain ``str`` here.
        Order of operations:

        1. Load the incident through the non-owner ``get_by_id`` (support
           advances any employee's incident, INSTR-004); ``None`` raises
           :class:`IncidentNotFoundError` (safe 404, BR-010).
        2. Validate the linear matrix against ``incident.status`` BEFORE any
           write — the only legal moves are the ones in
           ``_ALLOWED_TRANSITIONS``; everything else raises
           :class:`InvalidTransitionError` with both tables untouched
           (INSTR-003, BR-023/024).
        3. Stage ONE ``incident_history`` row (``previous_status`` = the
           pre-transition status, ``changed_by`` = the token subject) and the
           ``incidents`` status + ``updated_at`` update, then commit ONCE —
           all-or-nothing (INSTR-005, BR-025). ``changed_at`` is
           server-set (``server_default=func.now()``); ``updated_at`` is
           stamped explicitly because the column has no ORM ``onupdate``.

        Returns the freshly appended :class:`~app.domain.models.IncidentHistory`
        row — the same transaction's inserted row, not a re-query (design.md
        Risk 3) — so the route's ``latest_history`` matches the committed
        state exactly.
        """
        incident = self._incident_repository.get_by_id(incident_id)
        if incident is None:
            raise IncidentNotFoundError("Incidencia no encontrada")
        self._validate_transition(incident.status, new_status)

        entry = IncidentHistory(
            incident_id=incident.id,
            previous_status=incident.status,
            new_status=new_status,
            changed_by=actor_id,
        )
        self._history_repository.append(entry)
        # No ORM onupdate on ``updated_at``: the service stamps it explicitly
        # so it lands in the SAME commit as the history insert (INSTR-003).
        self._incident_repository.set_status(
            incident, new_status, updated_at=datetime.datetime.now(datetime.timezone.utc)
        )
        # Single commit: history row + incident update, all-or-nothing
        # (INSTR-005, BR-025). After commit the ORM objects are expired and
        # refresh on access, so the returned row carries the server-generated
        # ``id``/``changed_at``.
        self._session.commit()
        return entry

    def _validate_transition(self, current_status: str, new_status: str) -> None:
        """Reject any move outside the linear matrix BEFORE any write.

        A transition is legal only when the current status has a successor
        and that successor is exactly ``new_status`` — this single predicate
        rejects backwards moves (``in_progress -> pending``), skipped moves
        (``pending -> resolved``), repeats (``pending -> pending``), and
        anything out of the terminal ``resolved`` state (BR-023/024).
        """
        if _ALLOWED_TRANSITIONS.get(current_status) != new_status:
            raise InvalidTransitionError("Transición de estado no válida")
