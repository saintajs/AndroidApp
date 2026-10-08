"""Integration tests: POST /api/incidents — create-as-Pending + validation (US-003).

Exercises the ``create-incident`` endpoint of ``backend.yaml`` through the real
FastAPI app built by :func:`app.main.create_app`, on the same harness as
``test_incidents_list.py`` (task 7): the database comes from
``TEST_DATABASE_URL`` (Alembic ``upgrade head`` per session, applied by
``tests/conftest.py``), every test runs inside the per-test rolled-back
transaction of the ``db`` fixture (the service's commit releases a savepoint —
nothing persists beyond the test), and ``get_db`` is overridden so the app's
repositories and ``IncidentService`` share the exact session the fixture users
are seeded through.

Bearer tokens are minted in-process with
:func:`app.core.security.create_access_token` — the ``get_settings_dep``
override carries the same JWT secret, so ``get_current_user`` decodes them.

Asserted behaviour (spec CA-04..CA-06, INSTR-002/005/011, BR-011/013..018):
- CA-04: a valid body yields 201 with ``status "pending"``,
  ``created_at == updated_at`` (exact string equality of the ISO fields), the
  owner = token subject (verified on the row), and EXACTLY ONE
  ``incident_history`` row with ``previous_status NULL``, ``new_status
  "pending"`` and ``changed_by`` = creator — the 201 ``history`` timeline
  mirrors the database.
- CA-05: a 4-char title / 1001-char description / unknown category AND
  priority in ONE body are rejected 422 with one field-located entry per
  offending field (``string_too_short`` / ``string_too_long`` / plain
  ``Literal`` errors located at ``["body", "<field>"]``) and ZERO rows
  persisted — an invalid body never creates a partial incident (INSTR-011,
  BR-018). Note (design.md Decision 3): category/priority are plain
  ``Literal`` types, so pydantic reports type ``literal_error`` there — the
  assertions pin loc + input semantics, not the ``"enum"`` type the fixture
  sketch sketched.
- CA-06: extra ``{"status", "owner_id"}`` fields are rejected 422
  ``extra_forbidden`` (mass-assignment defense, INSTR-002) — status/owner are
  never client-controlled — with nothing persisted.
- 401: a POST without an Authorization header is the uniform
  ``No autenticado`` body (CA-04 of US-002), no rows.

Import note (same as ``test_auth_api.py`` / ``test_incidents_list.py``):
``app.main`` builds a module-level app and calls ``Settings()`` at import
time, so those imports are DEFERRED into the fixtures below — this module
must be collectable with the mandatory environment variables unset (the
env-free skip gate of the fast tier).
"""

import uuid
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.core.security import create_access_token, hash_password
from app.domain.models import Incident, IncidentHistory, User

pytestmark = pytest.mark.integration

# The SAME 32+ byte secret the integration run environment exports as
# JWT_SECRET — tokens issued in-process must decode with the DI-provided value.
JWT_SECRET = "integration-harness-only-secret-please-32b"

_EMPLOYEE_EMAIL = "creadora@minisoporte.test"
_UNAUTHENTICATED_DETAIL = "No autenticado"

_CREATE_PATH = "/api/incidents"

#: A body satisfying every IncidentCreate constraint (BR-013..016).
_VALID_PAYLOAD: dict[str, str] = {
    "title": "Pantalla rota",
    "description": "La pantalla no enciende al arrancar",
    "category": "equipment",
    "priority": "high",
}


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
def employee(db: Session) -> User:
    """The employee whose token creates incidents in this module."""
    return _create_user(db, email=_EMPLOYEE_EMAIL)


def _auth_header(user: User) -> dict[str, str]:
    """Mint a valid HS256 bearer token for this user in-process."""
    token = create_access_token(sub=str(user.id), role=user.role, secret=JWT_SECRET)
    return {"Authorization": f"Bearer {token}"}


def _row_counts(db: Session) -> dict[str, int]:
    """Total rows in ``incidents`` and ``incident_history`` right now."""
    return {
        "incidents": db.scalar(select(func.count()).select_from(Incident)) or 0,
        "incident_history": db.scalar(select(func.count()).select_from(IncidentHistory)) or 0,
    }


def _locate(detail: list[dict[str, object]], loc: list[str]) -> dict[str, object]:
    """Return the single 422 entry located at ``loc`` (fails otherwise)."""
    entries = [entry for entry in detail if entry["loc"] == loc]
    assert len(entries) == 1, f"expected exactly one entry at {loc}, got {detail}"
    return entries[0]


# ---------------------------------------------------------------------------
# CA-04 — valid body: 201 pending, equal timestamps, one atomic history row
# ---------------------------------------------------------------------------


def test_create_returns_201_pending_with_single_initial_history_row(
    client: TestClient, db: Session, employee: User
) -> None:
    """CA-04/BR-011/BR-013..016/INSTR-005: the full happy path, API + database."""
    response = client.post(_CREATE_PATH, json=_VALID_PAYLOAD, headers=_auth_header(employee))

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["status"] == "pending"  # BR-011: server-set, never client-owned
    assert body["title"] == _VALID_PAYLOAD["title"]
    assert body["description"] == _VALID_PAYLOAD["description"]
    assert body["category"] == _VALID_PAYLOAD["category"]
    assert body["priority"] == _VALID_PAYLOAD["priority"]
    # CA-04: exact string equality of the serialized ISO fields at creation.
    assert body["created_at"] == body["updated_at"]
    created_id = uuid.UUID(str(body["id"]))  # a well-formed server-generated UUID

    # The 201 timeline mirrors exactly one initial entry (INSTR-005).
    assert len(body["history"]) == 1
    entry = body["history"][0]
    assert entry["previous_status"] is None  # BR-025: NULL = creation entry
    assert entry["new_status"] == "pending"
    assert entry["changed_by"] == str(employee.id)

    # Database truth: the row belongs to the token subject (INSTR-002).
    incident = db.get(Incident, created_id)
    assert incident is not None
    assert incident.owner_id == employee.id
    assert incident.status == "pending"

    # Database truth: EXACTLY ONE history row, matching the timeline entry.
    history_rows = db.scalars(
        select(IncidentHistory).where(IncidentHistory.incident_id == created_id)
    ).all()
    assert len(history_rows) == 1
    db_row = history_rows[0]
    assert db_row.previous_status is None
    assert db_row.new_status == "pending"
    assert db_row.changed_by == employee.id


# ---------------------------------------------------------------------------
# CA-05 — invalid fields: field-located 422, zero rows persisted
# ---------------------------------------------------------------------------


def test_short_title_yields_422_string_too_short_and_zero_rows(
    client: TestClient, db: Session, employee: User
) -> None:
    """CA-05a/BR-013/INSTR-011: a 4-char title never reaches the database."""
    before = _row_counts(db)
    payload = {**_VALID_PAYLOAD, "title": "Abcd"}  # 4 chars < min_length 5

    response = client.post(_CREATE_PATH, json=payload, headers=_auth_header(employee))

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    assert len(detail) == 1  # exactly ONE offending field
    entry = _locate(detail, ["body", "title"])
    assert entry["type"] == "string_too_short"
    assert entry["input"] == "Abcd"
    assert _row_counts(db) == before == {"incidents": 0, "incident_history": 0}


def test_long_description_yields_422_string_too_long_and_zero_rows(
    client: TestClient, db: Session, employee: User
) -> None:
    """CA-05b/BR-014/INSTR-011: a 1001-char description never reaches the database."""
    before = _row_counts(db)
    payload = {**_VALID_PAYLOAD, "description": "x" * 1001}  # > max_length 1000

    response = client.post(_CREATE_PATH, json=payload, headers=_auth_header(employee))

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    assert len(detail) == 1
    entry = _locate(detail, ["body", "description"])
    assert entry["type"] == "string_too_long"
    assert entry["input"] == "x" * 1001
    assert _row_counts(db) == before == {"incidents": 0, "incident_history": 0}


def test_unknown_category_and_priority_yield_422_per_field_and_zero_rows(
    client: TestClient, db: Session, employee: User
) -> None:
    """CA-05c/BR-015/BR-016/INSTR-011: ONE body, TWO field-located entries.

    Plain ``Literal`` types (design.md Decision 3) reject each offending value
    at its own ``["body", <field>]`` location — asserted on loc + input
    semantics; nothing is persisted.
    """
    before = _row_counts(db)
    payload = {**_VALID_PAYLOAD, "category": "unknown", "priority": "urgent"}

    response = client.post(_CREATE_PATH, json=payload, headers=_auth_header(employee))

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    assert len(detail) == 2  # one field-located entry per offending field
    category_entry = _locate(detail, ["body", "category"])
    assert category_entry["input"] == "unknown"
    priority_entry = _locate(detail, ["body", "priority"])
    assert priority_entry["input"] == "urgent"
    assert _row_counts(db) == before == {"incidents": 0, "incident_history": 0}


# ---------------------------------------------------------------------------
# CA-06 — extra status/owner_id: extra_forbidden, never client-controlled
# ---------------------------------------------------------------------------


def test_extra_status_and_owner_id_rejected_extra_forbidden(
    client: TestClient, db: Session, employee: User
) -> None:
    """CA-06/INSTR-002: extra=forbid rejects the mass-assignment attempt.

    Both ``status`` and ``owner_id`` get their own ``extra_forbidden`` entry —
    a client can never set the lifecycle state or pick the owner — and no
    incident (with a forged owner or otherwise) is persisted.
    """
    before = _row_counts(db)
    payload = {
        **_VALID_PAYLOAD,
        "status": "resolved",  # BR-011: server-set — never accepted from a client
        "owner_id": str(uuid.uuid4()),  # INSTR-002: owner is always the token subject
    }

    response = client.post(_CREATE_PATH, json=payload, headers=_auth_header(employee))

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    status_entry = _locate(detail, ["body", "status"])
    assert status_entry["type"] == "extra_forbidden"
    assert status_entry["input"] == "resolved"
    owner_entry = _locate(detail, ["body", "owner_id"])
    assert owner_entry["type"] == "extra_forbidden"
    assert _row_counts(db) == before == {"incidents": 0, "incident_history": 0}


# ---------------------------------------------------------------------------
# 401 — unauthenticated create is the uniform body, no rows
# ---------------------------------------------------------------------------


def test_create_without_authorization_header_returns_uniform_401(
    client: TestClient, db: Session
) -> None:
    """CA-04b: the create endpoint sits behind the same bearer guard."""
    before = _row_counts(db)

    response = client.post(_CREATE_PATH, json=_VALID_PAYLOAD)

    assert response.status_code == 401
    assert response.json() == {"detail": _UNAUTHENTICATED_DETAIL}
    assert _row_counts(db) == before
