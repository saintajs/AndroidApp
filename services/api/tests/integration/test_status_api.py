"""Integration tests: PATCH /api/incidents/{id}/status — support-only lifecycle (US-004).

Exercises the ``patch-incident-status`` endpoint of ``backend.yaml`` through the
real FastAPI app built by :func:`app.main.create_app`, on the same harness as
``test_incidents_list.py`` (US-003): the database comes from
``TEST_DATABASE_URL`` (Alembic ``upgrade head`` per session, applied by
``tests/conftest.py``), every test runs inside the per-test rolled-back
transaction of the ``db`` fixture, and ``get_db`` is overridden so the app's
repositories share the exact session the fixture users/incidents are seeded
through (nothing is ever persisted). ``StatusService.apply_transition`` DOES
commit — inside ``join_transaction_mode="create_savepoint"`` that commits a
savepoint, so the outer transaction's final rollback still discards everything.

Bearer tokens are minted in-process with
:func:`app.core.security.create_access_token` — the ``get_settings_dep``
override carries the same JWT secret, so ``get_current_user`` decodes them.

Asserted behaviour (spec INSTR-003/004/005, BR-010/012/017/022/023/024/025,
CA-01/CA-02; expected bodies verbatim from ``backend.yaml`` ``error_fixtures``):
- 401: a missing Authorization header is the uniform ``No autenticado`` body.
- Employee-403 uniformity (INSTR-004/BR-022): an employee token gets EXACTLY
  403 ``{"detail": "No autorizado"}`` for an existing id, a nonexistent UUID,
  and a malformed id — the three bodies are BYTE-EQUAL (no lookup
  distinguishability; ``require_role`` fires before any path validation).
- Support + nonexistent UUID -> 404 ``{"detail": "Incidencia no encontrada"}``
  (safe shape, BR-010).
- 422 structured shape: ``new_status`` absent or outside the fixed set is a
  field-located ``detail`` list entry at ``["body", "new_status"]``; an extra
  body field is rejected (BR-012, ``extra="forbid"``).
- Legal journey (CA-01/CA-02): support advances ``pending -> in_progress ->
  resolved``; each 200 body has EXACTLY ``{incident, latest_history}`` with the
  fixture key sets; exactly 2 history rows are appended on top of the creation
  baseline; ``incidents.status``/``updated_at`` land in the same transaction.
- Illegal transitions (BR-023/024): every backwards/skipped/repeated/out-of-
  ``resolved`` cell is 409 ``{"detail": "Transición de estado no válida"}``
  and leaves status + history untouched; an identical repeat PATCH is 409.

Import note (same as ``test_incidents_list.py``): ``app.main`` builds a
module-level app and calls ``Settings()`` at import time, so those imports are
DEFERRED into the fixtures below — this module must be collectable with the
mandatory environment variables unset (the env-free skip gate of the fast tier).
"""

import datetime
import uuid
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.core.security import create_access_token, hash_password
from app.domain.models import Incident, IncidentHistory, User

pytestmark = pytest.mark.integration

# The SAME 32+ byte secret the integration run environment exports as
# JWT_SECRET — tokens issued in-process must decode with the DI-provided value.
JWT_SECRET = "integration-harness-only-secret-please-32b"

_EMPLOYEE_EMAIL = "empleado-status@minisoporte.test"
_SUPPORT_EMAIL = "soporte-status@minisoporte.test"

# Error bodies verbatim from backend.yaml test_access_points.error_fixtures.
_NO_AUTH_DETAIL = "No autenticado"
_FORBIDDEN_DETAIL = "No autorizado"
_NOT_FOUND_DETAIL = "Incidencia no encontrada"
_INVALID_TRANSITION_DETAIL = "Transición de estado no válida"

#: The exact 200 key sets (backend.yaml success_fixture structure) — and nothing else.
_INCIDENT_KEYS = {
    "id",
    "title",
    "description",
    "category",
    "priority",
    "status",
    "created_at",
    "updated_at",
}
_HISTORY_KEYS = {"id", "previous_status", "new_status", "changed_by", "changed_at"}

#: Every illegal matrix cell (BR-023/024): backwards, skipped, repeated, or out
#: of the terminal ``resolved`` state — parametrized seed status -> new_status.
_ILLEGAL_TRANSITIONS = [
    ("pending", "resolved"),  # skipped
    ("in_progress", "pending"),  # backwards
    ("pending", "pending"),  # repeated
    ("resolved", "in_progress"),  # out of resolved
    ("resolved", "pending"),  # out of resolved
    ("resolved", "resolved"),  # out of resolved, repeated
]

_UTC = datetime.timezone.utc


def _ts(*, day: int, hour: int, minute: int) -> datetime.datetime:
    """Build a deterministic tz-aware UTC timestamp (2026-10-06 baseline)."""
    return datetime.datetime(2026, 10, 6, hour, minute, tzinfo=_UTC)


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


def _create_user(db: Session, *, email: str, role: str) -> User:
    """Insert one fixture user inside the per-test transaction."""
    user = User(
        id=uuid.uuid4(),
        email=email,
        password_hash=hash_password(f"integration-pass-{email}"),
        role=role,
        active=True,
    )
    db.add(user)
    db.flush()
    return user


def _create_incident(
    db: Session,
    *,
    owner_id: uuid.UUID,
    title: str,
    description: str,
    category: str,
    priority: str,
    status: str,
    created_at: datetime.datetime,
) -> Incident:
    """Insert one incident row with EXPLICIT deterministic timestamps.

    ``created_at``/``updated_at`` are set explicitly so the journey's
    "``updated_at`` changed" assertion compares against a known baseline; the
    values satisfy the table's CHECK constraints.
    """
    incident = Incident(
        id=uuid.uuid4(),
        owner_id=owner_id,
        title=title,
        description=description,
        category=category,
        priority=priority,
        status=status,
        created_at=created_at,
        updated_at=created_at,
    )
    db.add(incident)
    db.flush()
    return incident


def _create_creation_entry(db: Session, *, incident: Incident, changed_by: uuid.UUID) -> None:
    """Insert the creation-baseline history row (US-003 precedent).

    ``previous_status`` NULL marks the initial entry (BR-025); ``changed_at``
    is pinned to the seed timestamp so the server-set ``changed_at`` of
    transition rows is always strictly later (deterministic ASC ordering).
    """
    entry = IncidentHistory(
        id=uuid.uuid4(),
        incident_id=incident.id,
        previous_status=None,
        new_status=incident.status,
        changed_by=changed_by,
        changed_at=incident.created_at,
    )
    db.add(entry)
    db.flush()


@pytest.fixture
def employee(db: Session) -> User:
    """The employee who owns every seeded incident (never support on this route)."""
    return _create_user(db, email=_EMPLOYEE_EMAIL, role="employee")


@pytest.fixture
def support_user(db: Session) -> User:
    """The support agent whose token subject is the transitions' ``changed_by``."""
    return _create_user(db, email=_SUPPORT_EMAIL, role="support")


@pytest.fixture
def incidents(db: Session, employee: User) -> dict[str, Incident]:
    """One incident per status owned by the employee, each with its creation row."""
    seeded: dict[str, Incident] = {}
    for index, (status, title, category, priority) in enumerate(
        [
            ("pending", "Impresora sin toner", "equipment", "medium"),
            ("in_progress", "Acceso al almacen caido", "access", "high"),
            ("resolved", "Correo sin conexion", "systems", "low"),
        ]
    ):
        incident = _create_incident(
            db,
            owner_id=employee.id,
            title=title,
            description=f"Descripcion determinista de la incidencia {index} de prueba",
            category=category,
            priority=priority,
            status=status,
            created_at=_ts(day=6, hour=8, minute=index * 10),
        )
        _create_creation_entry(db, incident=incident, changed_by=employee.id)
        seeded[status] = incident
    db.flush()
    return seeded


def _auth_header(user: User) -> dict[str, str]:
    """Mint a valid HS256 bearer token for this user in-process."""
    token = create_access_token(sub=str(user.id), role=user.role, secret=JWT_SECRET)
    return {"Authorization": f"Bearer {token}"}


def _history_count(db: Session, incident_id: uuid.UUID) -> int:
    """Direct ORM count of the history rows of one incident."""
    return db.query(IncidentHistory).filter(IncidentHistory.incident_id == incident_id).count()


# ---------------------------------------------------------------------------
# 401 — the uniform unauthenticated body without a bearer token
# ---------------------------------------------------------------------------


def test_status_without_authorization_header_returns_uniform_401(client: TestClient) -> None:
    """The transition route sits behind the same bearer guard as every /api route."""
    response = client.patch(
        f"/api/incidents/{uuid.uuid4()}/status", json={"new_status": "in_progress"}
    )

    assert response.status_code == 401
    assert response.json() == {"detail": _NO_AUTH_DETAIL}


# ---------------------------------------------------------------------------
# Employee-403 uniformity (INSTR-004/BR-022) — no lookup distinguishability
# ---------------------------------------------------------------------------


def test_status_employee_rejected_403_byte_equal_for_existing_nonexistent_malformed_ids(
    client: TestClient, employee: User, incidents: dict[str, Incident]
) -> None:
    """INSTR-004/BR-022: an employee always gets the same byte-equal 403 body.

    The role check fires in the ``require_role("support")`` dependency BEFORE
    the handler performs any lookup and before the malformed path id reaches
    UUID validation — an existing id, a random nonexistent UUID, and a
    syntactically invalid id must be indistinguishable.
    """
    existing = client.patch(
        f"/api/incidents/{incidents['pending'].id}/status",
        json={"new_status": "in_progress"},
        headers=_auth_header(employee),
    )
    nonexistent = client.patch(
        f"/api/incidents/{uuid.uuid4()}/status",
        json={"new_status": "in_progress"},
        headers=_auth_header(employee),
    )
    malformed = client.patch(
        "/api/incidents/not-a-uuid/status",
        json={"new_status": "in_progress"},
        headers=_auth_header(employee),
    )

    assert existing.status_code == nonexistent.status_code == malformed.status_code == 403
    # Byte-equal bodies prove no id shape leaks information about existence.
    assert existing.content == nonexistent.content == malformed.content
    assert existing.json() == {"detail": _FORBIDDEN_DETAIL}


# ---------------------------------------------------------------------------
# Safe 404 for support callers (BR-010)
# ---------------------------------------------------------------------------


def test_status_support_nonexistent_uuid_returns_safe_404(
    client: TestClient, support_user: User
) -> None:
    """Support + random nonexistent UUID -> exactly the safe 404 body."""
    response = client.patch(
        f"/api/incidents/{uuid.uuid4()}/status",
        json={"new_status": "in_progress"},
        headers=_auth_header(support_user),
    )

    assert response.status_code == 404, response.text
    assert response.json() == {"detail": _NOT_FOUND_DETAIL}


# ---------------------------------------------------------------------------
# 422 structured shape (BR-017 absent/out-of-set; BR-012 extra fields)
# ---------------------------------------------------------------------------


def test_status_missing_new_status_returns_422_located_to_body_new_status(
    client: TestClient, support_user: User
) -> None:
    """An empty body is a structured 422 whose entry sits at ``["body", "new_status"]``."""
    response = client.patch(
        f"/api/incidents/{uuid.uuid4()}/status", json={}, headers=_auth_header(support_user)
    )

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    assert len(detail) == 1
    entry = detail[0]
    assert entry["loc"] == ["body", "new_status"]
    assert "msg" in entry
    assert "type" in entry


def test_status_out_of_set_new_status_returns_422_located_to_body_new_status(
    client: TestClient, support_user: User
) -> None:
    """``new_status: "closed"`` is outside the BR-017 fixed set -> structured 422."""
    response = client.patch(
        f"/api/incidents/{uuid.uuid4()}/status",
        json={"new_status": "closed"},
        headers=_auth_header(support_user),
    )

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    entry = detail[0]
    assert entry["loc"] == ["body", "new_status"]
    assert "msg" in entry
    assert "type" in entry


def test_status_extra_body_field_rejected_422(client: TestClient, support_user: User) -> None:
    """BR-012: ``extra="forbid"`` — a ``comment`` field can never ride along."""
    response = client.patch(
        f"/api/incidents/{uuid.uuid4()}/status",
        json={"new_status": "in_progress", "comment": "x"},
        headers=_auth_header(support_user),
    )

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    assert all(entry["loc"] != ["body", "new_status"] for entry in detail)


# ---------------------------------------------------------------------------
# CA-01/CA-02 — the legal journey and the exactly-once history invariant
# ---------------------------------------------------------------------------


def test_status_legal_journey_appends_exactly_two_history_rows_atomically(
    client: TestClient,
    db: Session,
    support_user: User,
    employee: User,
    incidents: dict[str, Incident],
) -> None:
    """CA-01/CA-02/INSTR-005: pending -> in_progress -> resolved, 2 rows appended.

    The seed wrote the creation baseline (previous NULL); the two accepted
    transitions must add EXACTLY two rows on top of it, with the response
    carrying exactly the success-fixture key sets, and the final ORM state
    must be ``resolved`` with an ``updated_at`` after the seed baseline.
    """
    incident = incidents["pending"]
    baseline = _history_count(db, incident.id)
    assert baseline == 1  # the creation entry the seed wrote

    first = client.patch(
        f"/api/incidents/{incident.id}/status",
        json={"new_status": "in_progress"},
        headers=_auth_header(support_user),
    )

    assert first.status_code == 200, first.text
    body = first.json()
    assert set(body) == {"incident", "latest_history"}
    assert set(body["incident"]) == _INCIDENT_KEYS
    assert body["incident"]["status"] == "in_progress"
    assert set(body["latest_history"]) == _HISTORY_KEYS
    assert body["latest_history"]["previous_status"] == "pending"
    assert body["latest_history"]["new_status"] == "in_progress"
    assert body["latest_history"]["changed_by"] == str(support_user.id)

    second = client.patch(
        f"/api/incidents/{incident.id}/status",
        json={"new_status": "resolved"},
        headers=_auth_header(support_user),
    )

    assert second.status_code == 200, second.text

    # Direct ORM query on the harness session: exactly 2 rows appended.
    db.expire_all()
    rows = (
        db.query(IncidentHistory)
        .filter(IncidentHistory.incident_id == incident.id)
        .order_by(IncidentHistory.changed_at.asc())
        .all()
    )
    assert len(rows) == baseline + 2 == 3
    first_row, second_row = rows[1], rows[2]
    assert first_row.previous_status == "pending"
    assert first_row.new_status == "in_progress"
    assert first_row.changed_by == support_user.id
    assert second_row.previous_status == "in_progress"
    assert second_row.new_status == "resolved"
    assert second_row.changed_by == support_user.id

    final = db.query(Incident).filter(Incident.id == incident.id).one()
    assert final.status == "resolved"
    assert final.updated_at > incident.created_at


# ---------------------------------------------------------------------------
# Illegal transitions (BR-023/024) — every non-linear cell is 409
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(("seed_status", "new_status"), _ILLEGAL_TRANSITIONS)
def test_status_illegal_transition_rejected_409(
    client: TestClient,
    support_user: User,
    incidents: dict[str, Incident],
    seed_status: str,
    new_status: str,
) -> None:
    """BR-023/BR-024: backwards/skipped/repeated/out-of-resolved are all 409."""
    response = client.patch(
        f"/api/incidents/{incidents[seed_status].id}/status",
        json={"new_status": new_status},
        headers=_auth_header(support_user),
    )

    assert response.status_code == 409, response.text
    assert response.json() == {"detail": _INVALID_TRANSITION_DETAIL}


def test_status_illegal_transition_leaves_status_and_history_untouched(
    client: TestClient, db: Session, support_user: User, incidents: dict[str, Incident]
) -> None:
    """INSTR-003: a rejected transition writes NOTHING — no status flip, no row."""
    incident = incidents["in_progress"]
    before = _history_count(db, incident.id)

    response = client.patch(
        f"/api/incidents/{incident.id}/status",
        json={"new_status": "pending"},
        headers=_auth_header(support_user),
    )

    assert response.status_code == 409, response.text
    db.expire_all()
    after = db.query(Incident).filter(Incident.id == incident.id).one()
    assert after.status == "in_progress"
    assert _history_count(db, incident.id) == before


# ---------------------------------------------------------------------------
# Repeat-call verification (backend.yaml verification bullet 1)
# ---------------------------------------------------------------------------


def test_status_identical_repeat_patch_rejected_409(
    client: TestClient, support_user: User, incidents: dict[str, Incident]
) -> None:
    """The same ``pending -> in_progress`` PATCH twice: 200 then 409."""
    incident = incidents["pending"]
    headers = _auth_header(support_user)
    payload = {"new_status": "in_progress"}

    first = client.patch(f"/api/incidents/{incident.id}/status", json=payload, headers=headers)
    second = client.patch(f"/api/incidents/{incident.id}/status", json=payload, headers=headers)

    assert first.status_code == 200, first.text
    assert second.status_code == 409
    assert second.json() == {"detail": _INVALID_TRANSITION_DETAIL}
