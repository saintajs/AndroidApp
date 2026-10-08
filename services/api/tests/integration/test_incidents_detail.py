"""Integration tests: GET /api/incidents/{incident_id} — detail, history, safe 404 (US-003).

Exercises the ``get-incident-detail`` endpoint of ``backend.yaml`` through the
real FastAPI app built by :func:`app.main.create_app`, on the same harness as
``test_incidents_list.py`` (task 7) and ``test_incidents_create.py`` (task 8):
the database comes from ``TEST_DATABASE_URL`` (Alembic ``upgrade head`` per
session, applied by ``tests/conftest.py``), every test runs inside the
per-test rolled-back transaction of the ``db`` fixture, and ``get_db`` is
overridden so the app's repositories and ``IncidentService`` share the exact
session the fixture users/incidents are seeded through.

Bearer tokens are minted in-process with
:func:`app.core.security.create_access_token` — the ``get_settings_dep``
override carries the same JWT secret, so ``get_current_user`` decodes them.

Asserted behaviour (spec CA-07..CA-08, INSTR-005/012, BR-010/012/021/025):
- CA-07: the owner fetch of an own incident returns 200 with the stored
  ``description`` (the detail view is the only response that carries it), the
  incident's ``status``, and the ``history`` timeline ASCENDING by
  ``changed_at`` — seeded with multiple entries inserted in REVERSE
  ``changed_at`` order so the ordering proof comes from the repository's
  ``ORDER BY changed_at ASC``, never from insertion order. Each entry
  serializes exactly ``{id, previous_status, new_status, changed_by,
  changed_at}`` and the initial entry has ``previous_status`` NULL (BR-025).
- CA-08a: a random (missing) UUID returns 404 with the body EXACTLY
  ``{"detail": "Incidencia no encontrada"}`` — compared to the literal dict.
- CA-08b: another employee's incident id requested with A's token returns 404
  that is BYTE-IDENTICAL to the missing-id 404 (``response.content`` compared
  as byte strings) — no existence leak (BR-010).
- 422 (CA-08): a non-UUID path id is rejected by FastAPI BEFORE the handler
  with a structured entry located at ``["path", "incident_id"]`` — the loc is
  named after the actual route parameter (``incident_id`` in
  ``app/api/routes/incidents.py``), not the ``"id"`` sketched in the fixture.
- 401: a missing Authorization header is the uniform ``No autenticado`` body.
- INSTR-005/BR-012 guard: after every exercised flow (owner fetch, foreign-id
  404, missing-id 404), the seeded ``incident_history`` rows are UNCHANGED —
  same count, same ids, same field values. No read flow may update or delete
  history.

Import note (same as the other integration modules): ``app.main`` builds a
module-level app and calls ``Settings()`` at import time, so those imports
are DEFERRED into the fixtures below — this module must be collectable with
the mandatory environment variables unset (the env-free skip gate of the
fast tier).
"""

import datetime
import uuid
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.core.security import create_access_token, hash_password
from app.domain.models import Incident, IncidentHistory, User

pytestmark = pytest.mark.integration

# The SAME 32+ byte secret the integration run environment exports as
# JWT_SECRET — tokens issued in-process must decode with the DI-provided value.
JWT_SECRET = "integration-harness-only-secret-please-32b"

_EMPLOYEE_A_EMAIL = "detalle-a@minisoporte.test"
_EMPLOYEE_B_EMAIL = "detalle-b@minisoporte.test"
_UNAUTHENTICATED_DETAIL = "No autenticado"
_NOT_FOUND_DETAIL = "Incidencia no encontrada"

#: The exact key set every history entry must serialize (INSTR-005) — no
#: ``incident_id`` column, no ``updated_at``, nothing else.
_HISTORY_ENTRY_KEYS = {"id", "previous_status", "new_status", "changed_by", "changed_at"}

#: The exact 422 location for a non-UUID path id — FastAPI names the loc
#: segment after the route's parameter (``incident_id``), NOT the backend.yaml
#: fixture sketch's ``"id"``.
_NON_UUID_LOC = ["path", "incident_id"]

_INCIDENT_BASE_PATH = "/api/incidents"

_UTC = datetime.timezone.utc


def _ts(*, days: int, hour: int, minute: int) -> datetime.datetime:
    """Build a deterministic tz-aware UTC timestamp."""
    return datetime.datetime(2026, 10, days, hour, minute, tzinfo=_UTC)


@pytest.fixture
def client(db: Session, test_database_url: str) -> Iterator[TestClient]:
    """TestClient over ``create_app()`` wired to the per-test transaction."""
    from app.api.deps import get_settings_dep
    from app.db.session import get_db
    from app.main import create_app

    settings = Settings(DATABASE_URL=test_database_url, JWT_SECRET=JWT_SECRET, ENVIRONMENT="test")

    def override_get_db() -> Iterator[Session]:
        yield db

    application = create_app()
    application.dependency_overrides[get_db] = override_get_db
    application.dependency_overrides[get_settings_dep] = lambda: settings
    with TestClient(application) as test_client:
        yield test_client
    application.dependency_overrides.clear()


def _create_user(db: Session, *, email: str) -> User:
    """Insert one employee fixture user inside the per-test transaction."""
    user = User(
        id=uuid.uuid4(),
        email=email,
        password_hash=hash_password(f"integration-pass-{email}"),
        role="employee",
        active=True,
    )
    db.add(user)
    db.flush()
    return user


@pytest.fixture
def employee_a(db: Session) -> User:
    """Employee A — the owner whose detail/history is fetched in this module."""
    return _create_user(db, email=_EMPLOYEE_A_EMAIL)


@pytest.fixture
def employee_b(db: Session) -> User:
    """Employee B — owns the foreign incident that must 404 for A."""
    return _create_user(db, email=_EMPLOYEE_B_EMAIL)


def _auth_header(user: User) -> dict[str, str]:
    """Mint a valid HS256 bearer token for this user in-process."""
    token = create_access_token(sub=str(user.id), role=user.role, secret=JWT_SECRET)
    return {"Authorization": f"Bearer {token}"}


def _seed_incident_with_history(
    db: Session, *, owner_id: uuid.UUID
) -> tuple[Incident, list[IncidentHistory]]:
    """Seed one incident with TWO history entries at EXPLICIT timestamps.

    The entries are inserted in REVERSE chronological order (the later
    transition first), so the API's history ordering can only come from the
    repository's ``ORDER BY changed_at ASC`` — never from insertion order.
    ``changed_at`` is set explicitly so the assertion never depends on clock
    resolution or equal ``server_default=now()`` values; the incident's
    ``status`` matches the last transition ("in_progress").
    """
    t0 = _ts(days=4, hour=9, minute=0)  # NULL -> pending (creation)
    t1 = _ts(days=4, hour=15, minute=30)  # pending -> in_progress

    incident = Incident(
        id=uuid.uuid4(),
        owner_id=owner_id,
        title="Pantalla tactil no responde",
        description="La pantalla tactil deja de responder a los minutos",
        category="equipment",
        priority="high",
        status="in_progress",
        created_at=t0,
        updated_at=t1,
    )
    db.add(incident)
    db.flush()

    creation = IncidentHistory(
        id=uuid.uuid4(),
        incident_id=incident.id,
        previous_status=None,  # BR-025: NULL marks the creation entry
        new_status="pending",
        changed_by=owner_id,
        changed_at=t0,
    )
    transition = IncidentHistory(
        id=uuid.uuid4(),
        incident_id=incident.id,
        previous_status="pending",
        new_status="in_progress",
        changed_by=owner_id,
        changed_at=t1,
    )
    # REVERSE order on purpose: newest row staged first.
    db.add(transition)
    db.add(creation)
    db.flush()
    return incident, [creation, transition]


# ---------------------------------------------------------------------------
# CA-07 — owner fetch: 200 with description + history ascending by changed_at
# ---------------------------------------------------------------------------


def test_owner_detail_returns_description_and_history_ascending(
    client: TestClient, employee_a: User, db: Session
) -> None:
    """CA-07/BR-021/BR-025: the full detail with the ASC history timeline."""
    incident, seeded_history = _seed_incident_with_history(db, owner_id=employee_a.id)

    response = client.get(f"{_INCIDENT_BASE_PATH}/{incident.id}", headers=_auth_header(employee_a))

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["id"] == str(incident.id)
    assert body["description"] == incident.description  # detail carries it (FUNC-005)
    assert body["title"] == incident.title
    assert body["category"] == incident.category
    assert body["priority"] == incident.priority
    assert body["status"] == "in_progress"

    history = body["history"]
    assert len(history) == 2
    changed_ats = [
        datetime.datetime.fromisoformat(str(entry["changed_at"]).replace("Z", "+00:00"))
        for entry in history
    ]
    assert changed_ats == sorted(changed_ats)  # ASC by changed_at (INSTR-005, BR-021)
    # Deterministic proof — repository ORDER BY, NOT insertion order
    # (the newest entry was staged first):
    assert [entry["id"] for entry in history] == [
        str(seeded_history[0].id),  # creation: NULL -> pending, t0
        str(seeded_history[1].id),  # transition: pending -> in_progress, t1
    ]
    # Every entry serializes EXACTLY the five public keys:
    assert all(set(entry) == _HISTORY_ENTRY_KEYS for entry in history)
    # The initial entry: previous_status NULL, changed_by = the owner.
    initial = history[0]
    assert initial["previous_status"] is None  # BR-025
    assert initial["new_status"] == "pending"
    assert initial["changed_by"] == str(employee_a.id)
    # The follow-up transition chains onto the creation status:
    assert history[1]["previous_status"] == "pending"
    assert history[1]["new_status"] == "in_progress"


# ---------------------------------------------------------------------------
# CA-08a — a missing UUID returns the exact uniform 404 body
# ---------------------------------------------------------------------------


def test_missing_uuid_returns_exact_not_found_body(client: TestClient, employee_a: User) -> None:
    """CA-08a: a random (never seeded) UUID is the literal 404 dict."""
    response = client.get(f"{_INCIDENT_BASE_PATH}/{uuid.uuid4()}", headers=_auth_header(employee_a))

    assert response.status_code == 404, response.text
    assert response.json() == {"detail": _NOT_FOUND_DETAIL}


# ---------------------------------------------------------------------------
# CA-08b — a foreign id is BYTE-IDENTICAL to the missing-id 404 (BR-010)
# ---------------------------------------------------------------------------


def test_foreign_incident_id_404_is_byte_identical_to_missing(
    client: TestClient, employee_a: User, employee_b: User, db: Session
) -> None:
    """CA-08b/BR-010: no existence leak — same status AND same byte string.

    A requests B's incident id with A's token; the response body must equal
    the missing-id body byte for byte, so an attacker cannot distinguish
    "does not exist" from "exists but is not yours".
    """
    foreign_incident, _ = _seed_incident_with_history(db, owner_id=employee_b.id)

    foreign = client.get(
        f"{_INCIDENT_BASE_PATH}/{foreign_incident.id}", headers=_auth_header(employee_a)
    )
    missing = client.get(f"{_INCIDENT_BASE_PATH}/{uuid.uuid4()}", headers=_auth_header(employee_a))

    assert foreign.status_code == missing.status_code == 404
    assert foreign.content == missing.content  # BYTE-identical, not just equal JSON
    assert foreign.json() == {"detail": _NOT_FOUND_DETAIL}


# ---------------------------------------------------------------------------
# 422 — a non-UUID path id never reaches the handler
# ---------------------------------------------------------------------------


def test_non_uuid_path_id_yields_422_located_to_path_incident_id(
    client: TestClient, employee_a: User
) -> None:
    """CA-08/INSTR-011: ``not-a-uuid`` is a structured 422 at the path param.

    The loc is named after the ACTUAL route parameter (``incident_id`` in
    ``app/api/routes/incidents.py``) — not the fixture sketch's ``"id"``.
    """
    response = client.get(f"{_INCIDENT_BASE_PATH}/not-a-uuid", headers=_auth_header(employee_a))

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    assert len(detail) == 1
    entry = detail[0]
    assert entry["loc"] == _NON_UUID_LOC
    assert entry["type"] == "uuid_parsing"
    assert entry["input"] == "not-a-uuid"


# ---------------------------------------------------------------------------
# 401 — the uniform unauthenticated body without a bearer token
# ---------------------------------------------------------------------------


def test_detail_without_authorization_header_returns_uniform_401(client: TestClient) -> None:
    """The detail sits behind the same bearer guard as every /api route."""
    response = client.get(f"{_INCIDENT_BASE_PATH}/{uuid.uuid4()}")

    assert response.status_code == 401
    assert response.json() == {"detail": _UNAUTHENTICATED_DETAIL}


# ---------------------------------------------------------------------------
# INSTR-005 / BR-012 — history is append-only: no read flow mutates it
# ---------------------------------------------------------------------------


def test_history_rows_unchanged_after_all_exercised_flows(
    client: TestClient, employee_a: User, employee_b: User, db: Session
) -> None:
    """INSTR-005/BR-012: every exercised flow leaves the history rows intact.

    Owner fetch (200), foreign-id 404 and missing-id 404 are all exercised;
    afterwards the database rows for the seeded incident are identical to the
    seeded values — count, ids, statuses, changed_by and changed_at — proving
    no read flow updated or deleted history.
    """
    incident, seeded_history = _seed_incident_with_history(db, owner_id=employee_a.id)
    foreign_incident, _ = _seed_incident_with_history(db, owner_id=employee_b.id)
    seeded_snapshot = [
        (
            entry.id,
            entry.incident_id,
            entry.previous_status,
            entry.new_status,
            entry.changed_by,
            entry.changed_at,
        )
        for entry in seeded_history
    ]

    owner_response = client.get(
        f"{_INCIDENT_BASE_PATH}/{incident.id}", headers=_auth_header(employee_a)
    )
    foreign_response = client.get(
        f"{_INCIDENT_BASE_PATH}/{foreign_incident.id}", headers=_auth_header(employee_a)
    )
    missing_response = client.get(
        f"{_INCIDENT_BASE_PATH}/{uuid.uuid4()}", headers=_auth_header(employee_a)
    )

    assert owner_response.status_code == 200, owner_response.text
    assert foreign_response.status_code == missing_response.status_code == 404

    rows = db.scalars(
        select(IncidentHistory).where(IncidentHistory.incident_id == incident.id)
    ).all()
    row_snapshot = [
        (
            row.id,
            row.incident_id,
            row.previous_status,
            row.new_status,
            row.changed_by,
            row.changed_at,
        )
        for row in rows
    ]
    assert len(rows) == len(seeded_snapshot) == 2  # no row appended, updated or deleted
    assert sorted(row_snapshot) == sorted(seeded_snapshot)
