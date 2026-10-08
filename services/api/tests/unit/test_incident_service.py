"""Unit tests for app/domain/services/incident_service.py (US-003, testing.yaml
"IncidentService / HistoryRepository unit level").

``IncidentService`` is exercised against in-memory fake doubles of its two
repository ports and its session — no database, no network, no environment
writes (the run stays clean under
``env -u DATABASE_URL -u JWT_SECRET -u ENVIRONMENT -u TEST_DATABASE_URL``;
``app.core.ratelimit``/``app.main`` are deliberately never imported here).

Covered behaviour:
- ``create`` builds the incident from ONLY the validated client fields with
  ``owner_id`` = the passed token subject and ``status`` server-set to
  ``"pending"`` (INSTR-002, BR-011) — the schema carries no ``status`` /
  ``owner_id`` field to override.
- ``create`` appends EXACTLY one initial history entry with
  ``previous_status=None``, ``new_status="pending"``, ``changed_by`` = creator
  (INSTR-005), flushes BEFORE building it (the fake session materializes the
  PK only during flush, so the entry carrying the incident id proves the
  order) and commits EXACTLY once (INSTR-011, all-or-nothing).
- ``list_for_owner`` passes ``None`` to the repository for ``"all"`` and for
  the absent parameter, and the literal status through for the three valid
  values (FUNC-004, BR-017) — the owner filter is never optional.
- ``get_detail_for_owner`` maps a repository ``None`` — missing id OR another
  owner's id — to the SAME ``IncidentNotFoundError`` (BR-010, no existence
  leak), and on success passes the ``(incident, history)`` pair through in the
  repository's ascending order.
"""

import uuid

import pytest
from sqlalchemy import Select

from app.domain.exceptions import IncidentNotFoundError
from app.domain.models import Incident, IncidentHistory
from app.domain.schemas.incident import IncidentCreate
from app.domain.services.incident_service import IncidentService

_OWNER_ID = uuid.uuid4()
_OTHER_OWNER_ID = uuid.uuid4()


class FakeSession:
    """In-memory session double counting add/flush/commit calls — no database.

    Mimics the one server behaviour the service's unit of work relies on:
    ``flush`` materializes the ``gen_random_uuid()`` primary key (ids stay
    ``None`` until then), so tests can prove the flush happened before the
    history row was built. ``scalars`` records the executed statement and
    returns a canned result (unused by ``IncidentService`` — its reads go
    through the fake repositories — but keeps the double session-shaped).
    """

    def __init__(self) -> None:
        self.added: list[object] = []
        self.flush_calls = 0
        self.commit_calls = 0
        self.scalars_statements: list[Select[tuple[IncidentHistory]]] = []
        self._scalars_result: list[IncidentHistory] = []

    def add(self, obj: object) -> None:
        self.added.append(obj)

    def flush(self) -> None:
        self.flush_calls += 1
        for obj in self.added:
            if isinstance(obj, Incident) and obj.id is None:
                obj.id = uuid.uuid4()

    def commit(self) -> None:
        self.commit_calls += 1

    def scalars(self, statement: Select[tuple[IncidentHistory]]) -> list[IncidentHistory]:
        self.scalars_statements.append(statement)
        return list(self._scalars_result)


class FakeIncidentRepository:
    """Owner-scoped in-memory ``IncidentRepository`` double.

    Records every ``list_by_owner``/``get_for_owner`` call so tests can assert
    the exact filter argument the service resolved, and answers
    ``get_for_owner`` with the single owner-scoped predicate the real port
    uses: a match requires BOTH id and owner, so a foreign id is the same
    ``None`` as a missing one.
    """

    def __init__(self, session: FakeSession, *incidents: Incident) -> None:
        self._session = session
        self._incidents = list(incidents)
        self.list_calls: list[tuple[uuid.UUID, str | None]] = []
        self.get_calls: list[tuple[uuid.UUID, uuid.UUID]] = []

    def list_by_owner(self, owner_id: uuid.UUID, status: str | None) -> list[Incident]:
        self.list_calls.append((owner_id, status))
        return [incident for incident in self._incidents if incident.owner_id == owner_id]

    def get_for_owner(self, owner_id: uuid.UUID, incident_id: uuid.UUID) -> Incident | None:
        self.get_calls.append((owner_id, incident_id))
        return next(
            (
                incident
                for incident in self._incidents
                if incident.id == incident_id and incident.owner_id == owner_id
            ),
            None,
        )

    def add(self, incident: Incident) -> None:
        self._session.add(incident)


class FakeHistoryRepository:
    """In-memory ``HistoryRepository`` double recording appended entries."""

    def __init__(self, session: FakeSession, *history: IncidentHistory) -> None:
        self._session = session
        self._history = list(history)
        self.appended: list[IncidentHistory] = []
        self.listed_incident_ids: list[uuid.UUID] = []

    def append(self, entry: IncidentHistory) -> None:
        self.appended.append(entry)
        self._session.add(entry)

    def list_for_incident(self, incident_id: uuid.UUID) -> list[IncidentHistory]:
        self.listed_incident_ids.append(incident_id)
        return [entry for entry in self._history if entry.incident_id == incident_id]


def _make_data() -> IncidentCreate:
    """A valid ``IncidentCreate`` body — the four client-owned fields only."""
    return IncidentCreate(
        title="Impresora rota",
        description="La impresora de la oficina no imprime nada",
        category="equipment",
        priority="high",
    )


def _make_incident(*, owner_id: uuid.UUID, incident_id: uuid.UUID | None = None) -> Incident:
    """Build an in-memory ``Incident`` ORM instance."""
    return Incident(
        id=incident_id,
        owner_id=owner_id,
        title="Impresora rota",
        description="La impresora de la oficina no imprime nada",
        category="equipment",
        priority="high",
        status="pending",
    )


@pytest.fixture
def session() -> FakeSession:
    """Fresh fake session per test: call counters never leak between tests."""
    return FakeSession()


@pytest.fixture
def incident_repo(session: FakeSession) -> FakeIncidentRepository:
    return FakeIncidentRepository(session)


@pytest.fixture
def history_repo(session: FakeSession) -> FakeHistoryRepository:
    return FakeHistoryRepository(session)


@pytest.fixture
def service(
    incident_repo: FakeIncidentRepository, history_repo: FakeHistoryRepository
) -> IncidentService:
    return IncidentService(incident_repo, history_repo)


def test_create_builds_a_pending_incident_owned_by_the_token_subject(
    service: IncidentService,
) -> None:
    """BR-011 / INSTR-002: the lifecycle fields are server-set — ``owner_id``
    is the passed token subject and ``status`` is exactly ``"pending"``, never
    a client-controlled value; the validated body fields are copied verbatim."""
    data = _make_data()

    incident = service.create(_OWNER_ID, data)

    assert incident.owner_id == _OWNER_ID
    assert incident.status == "pending"
    assert incident.title == data.title
    assert incident.description == data.description
    assert incident.category == data.category
    assert incident.priority == data.priority


def test_incident_create_schema_has_no_server_owned_field_to_mass_assign() -> None:
    """INSTR-002 (structural): the create schema declares exactly the four
    client-owned fields — no ``status``/``owner_id``/``id`` field exists that a
    caller could use to override what the service sets server-side."""
    assert set(IncidentCreate.model_fields) == {"title", "description", "category", "priority"}


def test_create_stages_the_incident_before_flushing(
    service: IncidentService, session: FakeSession, incident_repo: FakeIncidentRepository
) -> None:
    """Unit-of-work order: ``repository.add`` stages the incident on the shared
    session, then the service's single flush materializes its id."""
    incident = service.create(_OWNER_ID, _make_data())

    assert session.added[0] is incident
    assert incident_repo._incidents == []  # the fake stores nothing itself
    assert session.flush_calls == 1
    assert incident.id is not None  # materialized by the flush


def test_create_appends_exactly_one_initial_history_entry(
    service: IncidentService,
    history_repo: FakeHistoryRepository,
) -> None:
    """INSTR-005 / BR-025: creation appends EXACTLY one history entry with
    ``previous_status`` NULL, ``new_status`` "pending" and ``changed_by`` set
    to the creator — never any other user."""
    service.create(_OWNER_ID, _make_data())

    assert len(history_repo.appended) == 1
    entry = history_repo.appended[0]
    assert entry.previous_status is None
    assert entry.new_status == "pending"
    assert entry.changed_by == _OWNER_ID


def test_create_flushes_before_history_and_commits_exactly_once(
    service: IncidentService, session: FakeSession, history_repo: FakeHistoryRepository
) -> None:
    """INSTR-011 (all-or-nothing): the single flush runs BEFORE the history row
    is built — the fake session only materializes ids during flush, so the
    entry carrying the incident's id proves the order — and exactly ONE commit
    lands both rows in the same transaction."""
    incident = service.create(_OWNER_ID, _make_data())

    assert incident.id is not None
    (entry,) = history_repo.appended
    assert entry.incident_id == incident.id
    assert session.added == [incident, entry]
    assert session.flush_calls == 1
    assert session.commit_calls == 1


@pytest.mark.parametrize(
    ("status", "expected"), [("all", None), (None, None)], ids=["all", "absent"]
)
def test_list_for_owner_passes_no_filter_to_the_repository_for_all_and_absent(
    service: IncidentService,
    incident_repo: FakeIncidentRepository,
    status: str | None,
    expected: str | None,
) -> None:
    """FUNC-004 / BR-017: the route's default ``"all"`` and the absent query
    parameter both resolve to ``None`` — the repository receives NO status
    predicate on top of the (always present) owner filter."""
    result = service.list_for_owner(_OWNER_ID, status)

    assert incident_repo.list_calls == [(_OWNER_ID, expected)]
    assert result == []  # no seed incidents for this owner


@pytest.mark.parametrize("status", ["pending", "in_progress", "resolved"])
def test_list_for_owner_passes_the_literal_status_through(
    service: IncidentService,
    incident_repo: FakeIncidentRepository,
    status: str,
) -> None:
    """FUNC-004 / BR-017: a valid literal status reaches the repository
    unchanged, still scoped to the owner — the service never drops it."""
    owned = _make_incident(owner_id=_OWNER_ID)
    incident_repo._incidents.append(owned)

    result = service.list_for_owner(_OWNER_ID, status)

    assert incident_repo.list_calls == [(_OWNER_ID, status)]
    assert result == [owned]  # the repository's answer is passed through


def test_list_for_owner_is_always_scoped_to_the_requested_owner(
    incident_repo: FakeIncidentRepository, history_repo: FakeHistoryRepository
) -> None:
    """INSTR-001 / BR-009: the owner filter comes from the argument (the token
    subject), never from the client payload, and is forwarded on every call."""
    service = IncidentService(incident_repo, history_repo)

    service.list_for_owner(_OWNER_ID, "pending")
    service.list_for_owner(_OTHER_OWNER_ID, None)

    assert [owner for owner, _ in incident_repo.list_calls] == [_OWNER_ID, _OTHER_OWNER_ID]


def test_get_detail_for_owner_missing_id_raises_incident_not_found(
    service: IncidentService, incident_repo: FakeIncidentRepository
) -> None:
    """BR-010 (missing): a never-existing id surfaces as the typed domain
    exception — never an HTTP error below the routes layer."""
    foreign = _make_incident(owner_id=_OTHER_OWNER_ID, incident_id=uuid.uuid4())
    incident_repo._incidents.append(foreign)

    with pytest.raises(IncidentNotFoundError):
        service.get_detail_for_owner(_OWNER_ID, uuid.uuid4())


def test_get_detail_for_owner_foreign_incident_raises_the_same_exception_type(
    service: IncidentService, incident_repo: FakeIncidentRepository
) -> None:
    """BR-010 (foreign): another employee's id is the same repository ``None``
    and raises the SAME exception — the two cases are indistinguishable to the
    caller (exactly ``IncidentNotFoundError``, not a subclass nor a different
    type, and the same message the route maps to the byte-identical 404)."""
    foreign = _make_incident(owner_id=_OTHER_OWNER_ID, incident_id=uuid.uuid4())
    incident_repo._incidents.append(foreign)
    foreign_id = foreign.id

    with pytest.raises(IncidentNotFoundError) as missing:
        service.get_detail_for_owner(_OWNER_ID, uuid.uuid4())

    with pytest.raises(IncidentNotFoundError) as foreign_case:
        service.get_detail_for_owner(_OWNER_ID, foreign_id)

    assert type(missing.value) is type(foreign_case.value) is IncidentNotFoundError
    assert str(missing.value) == str(foreign_case.value) == "Incidencia no encontrada"


def test_get_detail_for_owner_lookup_is_owner_scoped(
    service: IncidentService, incident_repo: FakeIncidentRepository
) -> None:
    """BR-009 / BR-010: the port receives BOTH the owner (token subject) and
    the id — the service never issues an ownerless lookup."""
    incident = _make_incident(owner_id=_OWNER_ID, incident_id=uuid.uuid4())
    incident_repo._incidents.append(incident)

    service.get_detail_for_owner(_OWNER_ID, incident.id)

    assert incident_repo.get_calls == [(_OWNER_ID, incident.id)]


def test_get_detail_for_owner_success_returns_incident_and_history_ascending(
    service: IncidentService,
    incident_repo: FakeIncidentRepository,
    history_repo: FakeHistoryRepository,
) -> None:
    """FUNC-005 / INSTR-005 / BR-021: on success the service returns the
    ``(incident, history)`` tuple with the history EXACTLY in the order the
    repository returned it (``changed_at`` ASC is the repository's job)."""
    incident = _make_incident(owner_id=_OWNER_ID, incident_id=uuid.uuid4())
    incident_repo._incidents.append(incident)
    first = IncidentHistory(
        incident_id=incident.id, previous_status=None, new_status="pending", changed_by=_OWNER_ID
    )
    second = IncidentHistory(
        incident_id=incident.id,
        previous_status="pending",
        new_status="in_progress",
        changed_by=_OWNER_ID,
    )
    history_repo._history = [first, second]  # already ASC by changed_at

    returned_incident, returned_history = service.get_detail_for_owner(_OWNER_ID, incident.id)

    assert returned_incident is incident
    assert returned_history == [first, second]
    assert history_repo.listed_incident_ids == [incident.id]
