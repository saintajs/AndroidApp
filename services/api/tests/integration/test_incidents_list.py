"""Integration tests: GET /api/incidents — owner-scoped list + status filter (US-003).

Exercises the ``list-incidents`` endpoint of ``backend.yaml`` through the real
FastAPI app built by :func:`app.main.create_app`, on the same harness as
``test_auth_api.py`` (US-002): the database comes from ``TEST_DATABASE_URL``
(Alembic ``upgrade head`` per session, applied by ``tests/conftest.py``), every
test runs inside the per-test rolled-back transaction of the ``db`` fixture,
and ``get_db`` is overridden so the app's repositories share the exact session
the fixture users/incidents are seeded through (nothing is ever committed).

Bearer tokens are minted in-process with
:func:`app.core.security.create_access_token` — the ``get_settings_dep``
override carries the same JWT secret, so ``get_current_user`` decodes them.

Asserted behaviour (spec CA-01..CA-03, FUNC-002/003/004, BR-009/017/019/020):
- CA-01: A's list is a plain JSON array of exactly A's 3 incidents, ordered
  ``created_at`` DESC (distinct seeded timestamps make the ordering
  deterministic), with no pagination envelope.
- Owner isolation (INSTR-001, BR-009): asserted positively (exactly A's ids)
  and negatively (B's 2 ids never appear in A's list) — even though B owns the
  NEWEST incident overall.
- CA-03: ``?status=pending|in_progress|resolved`` narrows ON TOP of the owner
  scope; ``?status=all`` and the absent parameter are the unfiltered list.
- CA-02: every list item serializes exactly
  ``{id, title, category, priority, status, created_at, updated_at}`` — the
  stored ``description`` never leaks into a list item.
- CA-03b: ``?status=bogus`` is rejected 422 with an entry located at
  ``["query", "status"]``.
- 401: a missing Authorization header is the uniform ``No autenticado`` body.

Import note (same as ``test_auth_api.py``): ``app.main`` builds a module-level
app and calls ``Settings()`` at import time, so those imports are DEFERRED
into the fixtures below — this module must be collectable with the mandatory
environment variables unset (the env-free skip gate of the fast tier).
"""

import datetime
import uuid
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.core.security import create_access_token, hash_password
from app.domain.models import Incident, User

pytestmark = pytest.mark.integration

# The SAME 32+ byte secret the integration run environment exports as
# JWT_SECRET — tokens issued in-process must decode with the DI-provided value.
JWT_SECRET = "integration-harness-only-secret-please-32b"

_EMPLOYEE_A_EMAIL = "empleado-a@minisoporte.test"
_EMPLOYEE_B_EMAIL = "empleado-b@minisoporte.test"
_UNAUTHENTICATED_DETAIL = "No autenticado"

#: The exact key set every list item must serialize (CA-02) — and nothing else:
#: ``description`` is stored on the row but structurally omitted (FUNC-003).
_LIST_ITEM_KEYS = {"id", "title", "category", "priority", "status", "created_at", "updated_at"}

_UTC = datetime.timezone.utc


def _ts(*, days: int, hour: int, minute: int) -> datetime.datetime:
    """Build a deterministic tz-aware UTC timestamp."""
    return datetime.datetime(2026, 10, days, hour, minute, tzinfo=_UTC)


def _parse_iso(value: str) -> datetime.datetime:
    """Parse the API's ISO 8601 timestamp (``Z`` or ``+00:00`` suffix)."""
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


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

    ``created_at``/``updated_at`` are set explicitly so the ``created_at``
    DESC ordering assertion never depends on insertion order or clock
    resolution; the values satisfy the table's CHECK constraints.
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


@pytest.fixture
def employee_a(db: Session) -> User:
    """Employee A — owns exactly 3 incidents (one per status)."""
    return _create_user(db, email=_EMPLOYEE_A_EMAIL)


@pytest.fixture
def employee_b(db: Session) -> User:
    """Employee B — owns exactly 2 incidents, NEVER visible to A."""
    return _create_user(db, email=_EMPLOYEE_B_EMAIL)


@pytest.fixture
def owned_ids(db: Session, employee_a: User, employee_b: User) -> dict[str, list[uuid.UUID]]:
    """Seed A's 3 incidents (distinct ``created_at``, mixed statuses) and B's 2.

    B's first incident is the NEWEST row in the table — if the owner scope
    were ever missing, it would surface first in A's list.
    """
    a_pending = _create_incident(
        db,
        owner_id=employee_a.id,
        title="Pantalla tactil no responde",
        description="La pantalla tactil deja de responder a los minutos",
        category="equipment",
        priority="high",
        status="pending",
        created_at=_ts(days=6, hour=10, minute=15),
    )
    a_in_progress = _create_incident(
        db,
        owner_id=employee_a.id,
        title="Sin acceso al almacen",
        description="La tarjeta de acceso no abre la puerta del almacen",
        category="access",
        priority="medium",
        status="in_progress",
        created_at=_ts(days=5, hour=8, minute=30),
    )
    a_resolved = _create_incident(
        db,
        owner_id=employee_a.id,
        title="Impresora atascada",
        description="La impresora de planta primera se atasca con todo papel",
        category="equipment",
        priority="low",
        status="resolved",
        created_at=_ts(days=1, hour=12, minute=0),
    )
    b_newest = _create_incident(
        db,
        owner_id=employee_b.id,
        title="Correo sin conexion",
        description="El cliente de correo pierde la conexion cada pocos minutos",
        category="systems",
        priority="medium",
        status="pending",
        created_at=_ts(days=7, hour=11, minute=0),
    )
    b_old = _create_incident(
        db,
        owner_id=employee_b.id,
        title="Cadena rota en sala",
        description="La cadena de la sala de reuniones cuelga de un lado",
        category="other",
        priority="low",
        status="resolved",
        created_at=_ts(days=3, hour=8, minute=0),
    )
    db.flush()
    return {
        "a": [a_pending.id, a_in_progress.id, a_resolved.id],
        "b": [b_newest.id, b_old.id],
    }


def _auth_header(user: User) -> dict[str, str]:
    """Mint a valid HS256 bearer token for this user in-process."""
    token = create_access_token(sub=str(user.id), role=user.role, secret=JWT_SECRET)
    return {"Authorization": f"Bearer {token}"}


def _list_ids(response_body: list[dict[str, object]]) -> list[uuid.UUID]:
    """Extract the ``id`` values of a list response body."""
    return [uuid.UUID(str(item["id"])) for item in response_body]


# ---------------------------------------------------------------------------
# CA-01 — plain array of exactly the owner's incidents, newest first
# ---------------------------------------------------------------------------


def test_list_returns_exactly_owner_incidents_newest_first(
    client: TestClient, employee_a: User, owned_ids: dict[str, list[uuid.UUID]]
) -> None:
    """CA-01/BR-019/BR-020: plain array, exactly A's 3, ``created_at`` DESC."""
    response = client.get("/api/incidents", headers=_auth_header(employee_a))

    assert response.status_code == 200, response.text
    body = response.json()
    assert isinstance(body, list)  # a plain array, NOT a page/total envelope
    assert set(body[0]) <= _LIST_ITEM_KEYS  # sanity before extracting ids
    ids = _list_ids(body)
    assert len(ids) == 3
    assert set(ids) == set(owned_ids["a"])

    created_ats = [_parse_iso(str(item["created_at"])) for item in body]
    assert created_ats == sorted(created_ats, reverse=True)
    # Deterministic proof of the ordering (distinct seeded timestamps):
    assert [item["id"] for item in body] == [
        str(owned_ids["a"][0]),  # pending, 2026-10-06 — newest
        str(owned_ids["a"][1]),  # in_progress, 2026-10-05
        str(owned_ids["a"][2]),  # resolved, 2026-10-01 — oldest
    ]


def test_list_never_contains_another_employees_incidents(
    client: TestClient, employee_a: User, owned_ids: dict[str, list[uuid.UUID]]
) -> None:
    """Owner isolation, negative side: B's ids never appear in A's list.

    B owns the newest row in the table — a missing owner predicate would put
    it FIRST in A's response.
    """
    response = client.get("/api/incidents", headers=_auth_header(employee_a))

    assert response.status_code == 200, response.text
    body = response.json()
    assert isinstance(body, list)
    a_ids = set(_list_ids(body))
    assert a_ids.isdisjoint(set(owned_ids["b"]))
    assert a_ids == set(owned_ids["a"])


def test_each_owner_sees_only_their_own_list(
    client: TestClient, employee_b: User, owned_ids: dict[str, list[uuid.UUID]]
) -> None:
    """Positive isolation from B's side: exactly B's 2, newest first."""
    response = client.get("/api/incidents", headers=_auth_header(employee_b))

    assert response.status_code == 200, response.text
    body = response.json()
    assert isinstance(body, list)
    assert _list_ids(body) == owned_ids["b"]  # newest (10-07) first, oldest (10-03) last


def test_status_all_and_absent_query_are_the_unfiltered_list(
    client: TestClient, employee_a: User, owned_ids: dict[str, list[uuid.UUID]]
) -> None:
    """``?status=all`` and no query parameter both return the full owner list."""
    absent = client.get("/api/incidents", headers=_auth_header(employee_a))
    explicit_all = client.get(
        "/api/incidents", params={"status": "all"}, headers=_auth_header(employee_a)
    )

    assert absent.status_code == explicit_all.status_code == 200
    assert _list_ids(absent.json()) == _list_ids(explicit_all.json()) == owned_ids["a"]


# ---------------------------------------------------------------------------
# CA-03 — ?status narrows on TOP of the owner scope
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("status_filter", "expected_index"),
    [("pending", 0), ("in_progress", 1), ("resolved", 2)],
)
def test_status_filter_narrows_on_top_of_owner_scope(
    client: TestClient,
    employee_a: User,
    owned_ids: dict[str, list[uuid.UUID]],
    status_filter: str,
    expected_index: int,
) -> None:
    """CA-03/FUNC-004/BR-017: one owned incident per status, nothing foreign."""
    response = client.get(
        "/api/incidents", params={"status": status_filter}, headers=_auth_header(employee_a)
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert isinstance(body, list)
    ids = _list_ids(body)
    assert ids == [owned_ids["a"][expected_index]]
    assert all(item["status"] == status_filter for item in body)
    # Narrowing must never widen: no foreign id sneaks in through the filter.
    assert set(ids).isdisjoint(set(owned_ids["b"]))


def test_status_filter_result_stays_within_the_callers_own_scope(
    client: TestClient, employee_b: User, owned_ids: dict[str, list[uuid.UUID]]
) -> None:
    """The filter's result is the INTERSECTION of owner scope and status.

    B's ``?status=pending`` list is B's own pending row — never A's, even
    though A also owns a pending incident (the scopes never union).
    """
    response = client.get(
        "/api/incidents", params={"status": "pending"}, headers=_auth_header(employee_b)
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert isinstance(body, list)
    assert _list_ids(body) == [owned_ids["b"][0]]


# ---------------------------------------------------------------------------
# CA-02 — list items serialize exactly the public key set, no description
# ---------------------------------------------------------------------------


def test_list_items_exactly_the_public_key_set_without_description(
    client: TestClient, employee_a: User, owned_ids: dict[str, list[uuid.UUID]]
) -> None:
    """CA-02/FUNC-003: every item has EXACTLY the 7 public keys — never ``description``."""
    response = client.get("/api/incidents", headers=_auth_header(employee_a))

    assert response.status_code == 200, response.text
    body = response.json()
    assert isinstance(body, list)
    assert body  # the seeded incidents are there
    assert all(set(item) == _LIST_ITEM_KEYS for item in body)
    assert "description" not in response.text  # stored on the row, never serialized


# ---------------------------------------------------------------------------
# CA-03b — ?status=bogus -> 422 located at ["query", "status"]
# ---------------------------------------------------------------------------


def test_unknown_status_value_yields_422_located_to_query_status(
    client: TestClient, employee_a: User, owned_ids: dict[str, list[uuid.UUID]]
) -> None:
    """CA-03b/BR-017: ``bogus`` is a structured 422 keyed to the query param."""
    response = client.get(
        "/api/incidents", params={"status": "bogus"}, headers=_auth_header(employee_a)
    )

    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, list)
    assert any(entry["loc"] == ["query", "status"] for entry in detail)


# ---------------------------------------------------------------------------
# 401 — the uniform unauthenticated body without a bearer token
# ---------------------------------------------------------------------------


def test_list_without_authorization_header_returns_uniform_401(client: TestClient) -> None:
    """The list is behind the same bearer guard as every /api route."""
    response = client.get("/api/incidents")

    assert response.status_code == 401
    assert response.json() == {"detail": _UNAUTHENTICATED_DETAIL}
