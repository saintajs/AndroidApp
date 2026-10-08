"""Unit tests for app/domain/services/status_service.py (US-004, testing.yaml
"StatusService (transition matrix)" component).

``StatusService`` is exercised against in-memory fake doubles of its two
repository ports and its session — no database, no network, no environment
variables (the run stays clean under
``env -u DATABASE_URL -u JWT_SECRET -u ENVIRONMENT -u TEST_DATABASE_URL``;
``app.core.ratelimit``/``app.main`` are deliberately never imported here).

Covered behaviour:
- The FULL 3×3 transition matrix, cell-by-cell (BR-023/024): the only legal
  moves are ``pending -> in_progress`` and ``in_progress -> resolved`` — every
  other cell (repeats, backwards, skipped, and all three moves out of the
  terminal ``resolved`` state) raises ``InvalidTransitionError``.
- Validate-before-write (INSTR-003): on every ILLEGAL cell the exception is
  raised BEFORE any repository write — the history fake's ``append`` and the
  incident fake's ``set_status`` record ZERO calls.
- Legal cells orchestrate one unit of work (INSTR-005, BR-025): exactly one
  ``IncidentHistory`` row appended with ``previous_status``/``new_status``/
  ``changed_by`` correct, ``set_status`` called with the new status and an
  explicit tz-aware ``updated_at`` (no ORM ``onupdate``), and a SINGLE commit;
  the returned row is the one append received (never a re-query).
- A nonexistent incident id maps to ``IncidentNotFoundError`` ("Incidencia no
  encontrada", safe 404 BR-010) with no write and no matrix evaluation.
"""

import datetime
import uuid

import pytest

from app.domain.exceptions import IncidentNotFoundError, InvalidTransitionError
from app.domain.models import Incident, IncidentHistory
from app.domain.services.status_service import StatusService

_ACTOR_ID = uuid.uuid4()
_MISSING_ID = uuid.uuid4()


class FakeSession:
    """In-memory session double counting commits — no database.

    ``StatusService`` grabs the session off ``incident_repository._session``
    and uses it ONLY as the transaction boundary (a single ``commit``); the
    repositories stage on it, so a commit counter is all the behaviour the
    unit of work needs.
    """

    def __init__(self) -> None:
        self.commit_calls = 0

    def commit(self) -> None:
        self.commit_calls += 1


class FakeIncidentRepository:
    """Non-owner-scoped in-memory ``IncidentRepository`` double.

    Records every ``get_by_id``/``set_status`` call. ``get_by_id`` answers
    from a seeded id → ``Incident`` mapping (support advances ANY employee's
    incident, INSTR-004 — there is no owner filter on this path);
    ``set_status`` only RECORDS, never mutates, so "no write" is directly
    observable as an empty call list.
    """

    def __init__(self, session: FakeSession, *incidents: Incident) -> None:
        self._session = session
        self._incidents = {incident.id: incident for incident in incidents}
        self.get_by_id_calls: list[uuid.UUID] = []
        self.set_status_calls: list[tuple[Incident, str, datetime.datetime]] = []

    def get_by_id(self, incident_id: uuid.UUID) -> Incident | None:
        self.get_by_id_calls.append(incident_id)
        return self._incidents.get(incident_id)

    def set_status(
        self, incident: Incident, new_status: str, *, updated_at: datetime.datetime
    ) -> None:
        self.set_status_calls.append((incident, new_status, updated_at))


class FakeHistoryRepository:
    """In-memory ``HistoryRepository`` double recording appended entries."""

    def __init__(self, session: FakeSession) -> None:
        self._session = session
        self.appended: list[IncidentHistory] = []

    def append(self, entry: IncidentHistory) -> None:
        self.appended.append(entry)


def _make_incident(*, incident_id: uuid.UUID, status: str) -> Incident:
    """Build an in-memory ``Incident`` ORM instance sitting at ``status``."""
    return Incident(
        id=incident_id,
        owner_id=uuid.uuid4(),
        title="Impresora rota",
        description="La impresora de la oficina no imprime nada",
        category="equipment",
        priority="high",
        status=status,
    )


class _Harness:
    """Bundle of service + fakes for one test, built fresh per scenario."""

    def __init__(self, *, current_status: str) -> None:
        self.session = FakeSession()
        self.incident_id = uuid.uuid4()
        self.incident = _make_incident(incident_id=self.incident_id, status=current_status)
        self.incident_repo = FakeIncidentRepository(self.session, self.incident)
        self.history_repo = FakeHistoryRepository(self.session)
        self.service = StatusService(self.incident_repo, self.history_repo)

    def apply(self, new_status: str) -> IncidentHistory:
        return self.service.apply_transition(self.incident_id, new_status, _ACTOR_ID)

    def assert_no_write(self) -> None:
        """INSTR-003: validate-before-write — both write ports saw ZERO calls."""
        assert self.history_repo.appended == []
        assert self.incident_repo.set_status_calls == []


def _assert_invalid_transition(harness: _Harness, new_status: str) -> None:
    """The shared illegal-cell assertion: the exact route-mapped 409 detail
    string is raised and NO repository write (history append OR incident
    status update) is invoked — validation happens before any write."""
    with pytest.raises(InvalidTransitionError) as excinfo:
        harness.apply(new_status)

    assert str(excinfo.value) == "Transición de estado no válida"
    harness.assert_no_write()
    assert harness.session.commit_calls == 0


# ---------------------------------------------------------------------------
# The 3×3 matrix, cell-by-cell (BR-023/024)
# ---------------------------------------------------------------------------
# Row 1: current status = pending


def test_status_service_pending_to_pending_repeat_raises_invalid_transition_no_write() -> None:
    """Cell (pending, pending): repeating the current status is not in the
    matrix — 409-equivalent, both tables untouched."""
    _assert_invalid_transition(_Harness(current_status="pending"), "pending")


def test_status_service_pending_to_in_progress_appends_history_and_commits_once() -> None:
    """Cell (pending, in_progress): the FIRST legal move — exactly one history
    row (previous "pending" → new "in_progress", changed_by the actor), the
    incident status update carrying an explicit tz-aware ``updated_at``, and a
    single unit-of-work commit; the returned row is the appended one."""
    harness = _Harness(current_status="pending")

    returned = harness.apply("in_progress")

    (entry,) = harness.history_repo.appended  # exactly one append
    assert entry is returned
    assert entry.incident_id == harness.incident_id
    assert entry.previous_status == "pending"
    assert entry.new_status == "in_progress"
    assert entry.changed_by == _ACTOR_ID
    incident, new_status, updated_at = harness.incident_repo.set_status_calls[0]
    assert incident is harness.incident  # the ORM object the lookup returned
    assert new_status == "in_progress"
    assert updated_at.tzinfo is not None  # explicit tz-aware stamp, no ORM onupdate
    assert isinstance(updated_at, datetime.datetime)
    assert harness.session.commit_calls == 1  # single commit, all-or-nothing


def test_status_service_pending_to_resolved_skip_raises_invalid_transition_no_write() -> None:
    """Cell (pending, resolved): skipping ``in_progress`` is not in the matrix
    — 409-equivalent, both tables untouched."""
    _assert_invalid_transition(_Harness(current_status="pending"), "resolved")


# Row 2: current status = in_progress


def test_status_service_in_progress_to_pending_backwards_raises_and_writes_nothing() -> None:
    """Cell (in_progress, pending): the linear matrix has no backwards move —
    409-equivalent, both tables untouched."""
    _assert_invalid_transition(_Harness(current_status="in_progress"), "pending")


def test_status_service_in_progress_to_in_progress_repeat_raises_invalid_transition_no_write() -> (
    None
):
    """Cell (in_progress, in_progress): repeating the current status is not in
    the matrix — 409-equivalent, both tables untouched."""
    _assert_invalid_transition(_Harness(current_status="in_progress"), "in_progress")


def test_status_service_in_progress_to_resolved_appends_history_and_commits_once() -> None:
    """Cell (in_progress, resolved): the SECOND (and last) legal move — exactly
    one history row (previous "in_progress" → new "resolved", changed_by the
    actor), the incident status update carrying an explicit tz-aware
    ``updated_at``, and a single unit-of-work commit; the returned row is the
    appended one."""
    harness = _Harness(current_status="in_progress")

    returned = harness.apply("resolved")

    (entry,) = harness.history_repo.appended  # exactly one append
    assert entry is returned
    assert entry.incident_id == harness.incident_id
    assert entry.previous_status == "in_progress"
    assert entry.new_status == "resolved"
    assert entry.changed_by == _ACTOR_ID
    incident, new_status, updated_at = harness.incident_repo.set_status_calls[0]
    assert incident is harness.incident  # the ORM object the lookup returned
    assert new_status == "resolved"
    assert updated_at.tzinfo is not None  # explicit tz-aware stamp, no ORM onupdate
    assert isinstance(updated_at, datetime.datetime)
    assert harness.session.commit_calls == 1  # single commit, all-or-nothing


# Row 3: current status = resolved (terminal, BR-024)


def test_status_service_resolved_to_pending_raises_invalid_transition_no_write() -> None:
    """Cell (resolved, pending): nothing leaves ``resolved`` — 409-equivalent,
    both tables untouched."""
    _assert_invalid_transition(_Harness(current_status="resolved"), "pending")


def test_status_service_resolved_to_in_progress_raises_invalid_transition_no_write() -> None:
    """Cell (resolved, in_progress): reopening a resolved incident is not in
    the matrix — 409-equivalent, both tables untouched."""
    _assert_invalid_transition(_Harness(current_status="resolved"), "in_progress")


def test_status_service_resolved_to_resolved_terminal_raises_invalid_transition_no_write() -> None:
    """Cell (resolved, resolved): ``resolved`` is terminal (BR-024) — even the
    identity move is rejected, both tables untouched."""
    _assert_invalid_transition(_Harness(current_status="resolved"), "resolved")


# ---------------------------------------------------------------------------
# Nonexistent incident (safe 404, BR-010)
# ---------------------------------------------------------------------------


def test_status_service_missing_incident_raises_not_found_with_no_write_or_matrix() -> None:
    """A nonexistent id surfaces as ``IncidentNotFoundError`` with the exact
    route-mapped 404 detail BEFORE any matrix evaluation or write: the lookup
    is the only repository interaction, both write ports record ZERO calls and
    nothing is committed."""
    harness = _Harness(current_status="pending")

    with pytest.raises(IncidentNotFoundError) as excinfo:
        harness.service.apply_transition(_MISSING_ID, "in_progress", _ACTOR_ID)

    assert str(excinfo.value) == "Incidencia no encontrada"
    assert harness.incident_repo.get_by_id_calls == [_MISSING_ID]
    harness.assert_no_write()
    assert harness.session.commit_calls == 0
