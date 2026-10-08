"""Integration tests: POST /api/auth/login + GET /api/me end-to-end (US-002).

Exercises CA-01..CA-07 from ``specs/main/spec.md`` through the real FastAPI
app built by :func:`app.main.create_app`, on the US-001 harness: the database
comes from ``TEST_DATABASE_URL`` (Alembic ``upgrade head`` per session), every
test runs inside the per-test rolled-back transaction of the ``db`` fixture,
and the login rate-limiter singleton is reset around each test.

``get_db`` is overridden so the repositories used by the app share the exact
session the fixture users are seeded through (nothing is ever committed);
``get_settings_dep`` is overridden to a :class:`~app.core.config.Settings`
carrying the same JWT secret the run environment exports, so tokens issued by
``/api/auth/login`` verify with ``decode_access_token``.

Import note: ``app.main`` builds a module-level app and ``app.core.ratelimit``
instantiates its singleton — both call ``Settings()`` at import time. That
import is therefore DEFERRED into the fixtures/tests below so this module can
be collected (and the whole integration tier skipped cleanly) with the
mandatory environment variables unset.
"""

import time
import uuid
from collections.abc import Iterator

import jwt
import pytest
from fastapi.testclient import TestClient
from httpx import Response
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.core.security import (
    TOKEN_TTL_SECONDS,
    create_access_token,
    decode_access_token,
    hash_password,
)
from app.domain.models import User

pytestmark = pytest.mark.integration

# The SAME 32+ byte secret the integration run environment exports as
# JWT_SECRET — issued tokens must decode with the DI-provided settings value.
JWT_SECRET = "integration-harness-only-secret-please-32b"

EMPLOYEE_EMAIL = "empleado@minisoporte.test"
SUPPORT_EMAIL = "soporte@minisoporte.test"
INACTIVE_EMAIL = "inactivo@minisoporte.test"
EMPLOYEE_PASSWORD = "empleado-integration-pass-1"
SUPPORT_PASSWORD = "soporte-integration-pass-1"
INACTIVE_PASSWORD = "inactivo-integration-pass-1"

_UNIFORM_CREDENTIALS_DETAIL = "Credenciales inválidas"
_RATE_LIMIT_DETAIL = "Demasiados intentos. Inténtelo de nuevo más tarde."
_UNAUTHENTICATED_DETAIL = "No autenticado"
_INACTIVE_DETAIL = "Cuenta inactiva"


@pytest.fixture(autouse=True)
def _reset_login_rate_limiter() -> Iterator[None]:
    """Reset the limiter singleton before AND after every test.

    The per-IP counter keys on the TestClient host ("testclient") and is shared
    by every request of every test, so leaked state would poison neighbours.
    """
    from app.core.ratelimit import login_rate_limiter

    login_rate_limiter.reset()
    yield
    login_rate_limiter.reset()


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


def _create_user(
    db: Session,
    *,
    email: str,
    password: str,
    role: str = "employee",
    active: bool = True,
) -> User:
    """Insert one fixture user inside the per-test transaction."""
    user = User(
        id=uuid.uuid4(),
        email=email,
        password_hash=hash_password(password),
        role=role,
        active=active,
    )
    db.add(user)
    db.flush()
    return user


@pytest.fixture
def employee_user(db: Session) -> User:
    return _create_user(db, email=EMPLOYEE_EMAIL, password=EMPLOYEE_PASSWORD)


@pytest.fixture
def support_user(db: Session) -> User:
    return _create_user(db, email=SUPPORT_EMAIL, password=SUPPORT_PASSWORD, role="support")


@pytest.fixture
def deactivated_user(db: Session) -> User:
    return _create_user(db, email=INACTIVE_EMAIL, password=INACTIVE_PASSWORD, active=False)


def _login(client: TestClient, email: str, password: str) -> Response:
    return client.post("/api/auth/login", json={"email": email, "password": password})


def _auth_header(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _login_token(client: TestClient, email: str, password: str) -> str:
    response = _login(client, email, password)
    assert response.status_code == 200, response.text
    token: str = response.json()["access_token"]
    return token


# ---------------------------------------------------------------------------
# CA-01 — successful sign-in issues a decodable HS256 bearer token
# ---------------------------------------------------------------------------


def test_login_returns_bearer_token_with_expected_claims(
    client: TestClient, employee_user: User
) -> None:
    """CA-01: 200 with exactly {access_token, token_type, expires_in_seconds}."""
    response = _login(client, EMPLOYEE_EMAIL, EMPLOYEE_PASSWORD)

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"access_token", "token_type", "expires_in_seconds"}
    assert body["token_type"] == "bearer"
    assert body["expires_in_seconds"] == TOKEN_TTL_SECONDS == 28800

    payload = decode_access_token(body["access_token"], secret=JWT_SECRET)
    assert payload.sub == str(employee_user.id)
    assert payload.role == "employee"
    assert abs(payload.exp - (time.time() + TOKEN_TTL_SECONDS)) < 60


# ---------------------------------------------------------------------------
# CA-02 — wrong password and absent email are byte-identical uniform 401s
# ---------------------------------------------------------------------------


def test_wrong_password_and_absent_email_are_byte_identical(
    client: TestClient, employee_user: User
) -> None:
    """CA-02: one uniform 401 body, and no differential rate accounting."""
    wrong_password = _login(client, EMPLOYEE_EMAIL, "definitely-not-the-password")
    absent_email = _login(client, "ghost@minisoporte.test", EMPLOYEE_PASSWORD)

    assert wrong_password.status_code == absent_email.status_code == 401
    assert wrong_password.content == absent_email.content
    assert wrong_password.json() == {"detail": _UNIFORM_CREDENTIALS_DETAIL}

    # No differential accounting: BOTH failures consumed exactly one per-IP
    # slot. Three more failures (fresh emails, so no per-email budget moves)
    # exhaust the per-IP budget of 5 — the next attempt is 429 even for the
    # valid employee credentials.
    for _ in range(3):
        fresh_email = f"probe-{uuid.uuid4().hex}@example.com"
        assert _login(client, fresh_email, "whatever-1").status_code == 401
    assert _login(client, EMPLOYEE_EMAIL, EMPLOYEE_PASSWORD).status_code == 429


# ---------------------------------------------------------------------------
# CA-03 — 422 with one field-level entry per offending field
# ---------------------------------------------------------------------------


def test_missing_fields_yield_one_422_entry_per_field(client: TestClient) -> None:
    """CA-03/BR-001: EXACTLY one 422 detail entry per offending field.

    Exact counts, not a set comparison: a set collapses duplicates, and the
    duplicate-body-validation defect this guards against emitted each entry
    twice (len(detail) == 4 for an empty body).
    """
    response = client.post("/api/auth/login", json={})

    assert response.status_code == 422
    detail = response.json()["detail"]
    assert len(detail) == 2
    assert sorted(tuple(entry["loc"]) for entry in detail) == [
        ("body", "email"),
        ("body", "password"),
    ]
    assert all(entry["type"] == "missing" for entry in detail)
    assert len({(tuple(entry["loc"]), entry["msg"], entry["type"]) for entry in detail}) == 2

    # Single missing field -> exactly one entry for that field.
    partial = client.post("/api/auth/login", json={"password": "x"})
    assert partial.status_code == 422
    partial_detail = partial.json()["detail"]
    assert len(partial_detail) == 1
    assert partial_detail[0]["loc"] == ["body", "email"]
    assert partial_detail[0]["type"] == "missing"

    # Two invalid (non-missing) values -> exactly two entries, one per field.
    blank = client.post("/api/auth/login", json={"email": "", "password": ""})
    assert blank.status_code == 422
    blank_detail = blank.json()["detail"]
    assert len(blank_detail) == 2
    assert sorted(tuple(entry["loc"]) for entry in blank_detail) == [
        ("body", "email"),
        ("body", "password"),
    ]


def test_blank_password_yields_422_keyed_to_password(
    client: TestClient, employee_user: User
) -> None:
    """CA-03: a blank password is rejected with an entry keyed to password."""
    response = client.post("/api/auth/login", json={"email": EMPLOYEE_EMAIL, "password": ""})

    assert response.status_code == 422
    locations = {tuple(entry["loc"]) for entry in response.json()["detail"]}
    assert ("body", "password") in locations


# ---------------------------------------------------------------------------
# CA-04 — 429 + Retry-After after 5 failures; success consumes no budget
# ---------------------------------------------------------------------------


def test_rate_limit_after_five_failures_and_success_consumes_no_budget(
    client: TestClient, employee_user: User
) -> None:
    """CA-04: 6th attempt 429 + Retry-After; reset; success stays uncounted."""
    for _ in range(5):
        assert _login(client, EMPLOYEE_EMAIL, "definitely-not-the-password").status_code == 401

    exhausted = _login(client, EMPLOYEE_EMAIL, EMPLOYEE_PASSWORD)
    assert exhausted.status_code == 429
    assert exhausted.json() == {"detail": _RATE_LIMIT_DETAIL}
    assert int(exhausted.headers["Retry-After"]) >= 1

    # Reset the singleton so the second half of the test starts from a clean
    # budget (the autouse fixture also resets around the next test).
    from app.core.ratelimit import login_rate_limiter

    login_rate_limiter.reset()

    assert _login(client, EMPLOYEE_EMAIL, "definitely-not-the-password").status_code == 401
    assert _login(client, EMPLOYEE_EMAIL, "definitely-not-the-password").status_code == 401

    # The successful login itself returns 200…
    success = _login(client, EMPLOYEE_EMAIL, EMPLOYEE_PASSWORD)
    assert success.status_code == 200

    # …and consumed NO budget: one more failure is still below the limit (3 < 5).
    assert _login(client, EMPLOYEE_EMAIL, "definitely-not-the-password").status_code == 401


# ---------------------------------------------------------------------------
# CA-05 — email normalization on sign-in
# ---------------------------------------------------------------------------


def test_login_email_is_normalized_and_profile_returns_canonical(
    client: TestClient, employee_user: User
) -> None:
    """CA-05: " Empleado@MiniSoporte.Test " signs in and /api/me is canonical."""
    response = _login(client, "  Empleado@MiniSoporte.Test  ", EMPLOYEE_PASSWORD)
    assert response.status_code == 200

    me = client.get("/api/me", headers=_auth_header(response.json()["access_token"]))
    assert me.status_code == 200
    assert me.json()["email"] == EMPLOYEE_EMAIL


# ---------------------------------------------------------------------------
# CA-06 — GET /api/me returns exactly the public field set
# ---------------------------------------------------------------------------


def test_me_returns_exactly_the_public_field_set(client: TestClient, employee_user: User) -> None:
    """CA-06: {id, email, role, active, created_at} — never password_hash."""
    token = _login_token(client, EMPLOYEE_EMAIL, EMPLOYEE_PASSWORD)

    response = client.get("/api/me", headers=_auth_header(token))

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"id", "email", "role", "active", "created_at"}
    assert "password_hash" not in response.text
    assert body["id"] == str(employee_user.id)
    assert body["email"] == EMPLOYEE_EMAIL
    assert body["role"] == "employee"
    assert body["active"] is True
    assert body["created_at"]


def test_me_supports_the_support_role(client: TestClient, support_user: User) -> None:
    """CA-06: the same profile contract for a support-role user."""
    token = _login_token(client, SUPPORT_EMAIL, SUPPORT_PASSWORD)

    response = client.get("/api/me", headers=_auth_header(token))

    assert response.status_code == 200
    body = response.json()
    assert body["id"] == str(support_user.id)
    assert body["email"] == SUPPORT_EMAIL
    assert body["role"] == "support"


# ---------------------------------------------------------------------------
# CA-07 — every invalid token collapses to 401; deactivated subject -> 403
# ---------------------------------------------------------------------------


def _crafted_token(kind: str) -> str:
    """Build the ad-hoc tokens CA-07 needs (expired / HS512 / absent subject)."""
    if kind == "expired":
        return jwt.encode(
            {
                "sub": str(uuid.uuid4()),
                "role": "employee",
                "exp": int(time.time()) - 60,
            },
            JWT_SECRET,
            algorithm="HS256",
        )
    if kind == "hs512":
        return jwt.encode(
            {
                "sub": str(uuid.uuid4()),
                "role": "employee",
                "exp": int(time.time()) + TOKEN_TTL_SECONDS,
            },
            JWT_SECRET,
            algorithm="HS512",
        )
    if kind == "absent-subject":
        return create_access_token(sub=str(uuid.uuid4()), role="employee", secret=JWT_SECRET)
    raise AssertionError(f"unexpected token kind: {kind}")


@pytest.mark.parametrize("token_kind", ["garbage", "expired", "hs512", "absent-subject"])
def test_me_rejects_invalid_tokens_with_uniform_401(client: TestClient, token_kind: str) -> None:
    """CA-07: garbage/expired/non-HS256/absent-subject tokens all 401 alike."""
    token = "not-a-jwt" if token_kind == "garbage" else _crafted_token(token_kind)

    response = client.get("/api/me", headers=_auth_header(token))

    assert response.status_code == 401, token_kind
    assert response.json() == {"detail": _UNAUTHENTICATED_DETAIL}


def test_me_without_authorization_header_returns_uniform_401(client: TestClient) -> None:
    """CA-07: a missing Authorization header is the same uniform 401."""
    response = client.get("/api/me")

    assert response.status_code == 401
    assert response.json() == {"detail": _UNAUTHENTICATED_DETAIL}


def test_me_returns_403_for_deactivated_subject(client: TestClient, deactivated_user: User) -> None:
    """CA-07: a valid token for a deactivated account is 403 Cuenta inactiva."""
    token = create_access_token(sub=str(deactivated_user.id), role="employee", secret=JWT_SECRET)

    response = client.get("/api/me", headers=_auth_header(token))

    assert response.status_code == 403
    assert response.json() == {"detail": _INACTIVE_DETAIL}
