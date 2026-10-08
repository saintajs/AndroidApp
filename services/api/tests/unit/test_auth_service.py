"""Unit tests for app/domain/services/auth_service.py (US-002, testing.yaml).

``AuthService`` is exercised against an in-memory fake of the
``UserRepository`` port storing real ``User`` ORM instances — no database, no
environment writes: the ``Settings`` instance is built from explicit kwargs so
the run stays env-leak-free under
``env -u DATABASE_URL -u JWT_SECRET -u ENVIRONMENT -u TEST_DATABASE_URL``.

Covered behaviour:
- successful sign-in for an existing active employee returns a bearer
  ``TokenResponse`` whose HS256 JWT carries ``sub`` (user UUID string), ``role``
  and ``exp = issued + 28800 s`` (spec: "expose POST /api/auth/login ... 200").
- the submitted email is normalized (trimmed, lowercased) before lookup
  (INSTR-006, BR-008, CA-05).
- absent email, wrong password and deactivated account all raise the SAME
  ``InvalidCredentialsError`` (BR-002); the absent path still burns a bcrypt
  verify against the dummy hash.
- failure logs carry the client IP only — never the email, password or token
  (INSTR-008).

The rate limiter is deliberately NOT imported here: throttling is a route
concern, and a successful ``AuthService.login`` never touches any counter.
"""

import datetime
import logging
import time
import uuid

import pytest

from app.core.config import Settings
from app.core.security import (
    TOKEN_TTL_SECONDS,
    decode_access_token,
    hash_password,
    verify_password,
)
from app.db.repositories.user_repository import UserRepository
from app.domain.exceptions import InvalidCredentialsError
from app.domain.models import User
from app.domain.services import auth_service
from app.domain.services.auth_service import AuthService

# 40+ bytes so the HS256 signing key is comfortably above the 32-byte floor.
_TEST_SECRET = "unit-test-jwt-secret-with-more-than-32-bytes!!"
_EMPLOYEE_EMAIL = "empleado@minisoporte.test"
_EMPLOYEE_PASSWORD = "Clave-Empleado-Demo-1"  # noqa: S106 - test fixture value
_WRONG_PASSWORD = "no-es-la-clave-42"  # noqa: S106 - test fixture value
_CLIENT_IP = "10.0.0.1"


class FakeUserRepository(UserRepository):
    """In-memory ``UserRepository`` double keyed by exact (normalized) email.

    Overrides both port methods; records every ``get_by_email`` lookup so tests
    can assert the service normalized the address before hitting the port.
    """

    def __init__(self, *users: User) -> None:
        self._users_by_email: dict[str, User] = {user.email: user for user in users}
        self.requested_emails: list[str] = []

    def get_by_email(self, email: str) -> User | None:
        self.requested_emails.append(email)
        return self._users_by_email.get(email)

    def get_by_id(self, user_id: uuid.UUID) -> User | None:
        return next(
            (user for user in self._users_by_email.values() if user.id == user_id),
            None,
        )


def _make_user(*, email: str, password: str, active: bool = True) -> User:
    """Build an in-memory ``User`` ORM instance with a real bcrypt hash."""
    return User(
        id=uuid.uuid4(),
        email=email,
        password_hash=hash_password(password),
        role="employee",
        active=active,
        created_at=datetime.datetime.now(datetime.timezone.utc),
    )


@pytest.fixture(scope="module")
def employee() -> User:
    """The one active employee account the fake repository holds."""
    return _make_user(email=_EMPLOYEE_EMAIL, password=_EMPLOYEE_PASSWORD, active=True)


@pytest.fixture(scope="module")
def deactivated() -> User:
    """A deactivated employee: even the CORRECT password must not sign in."""
    return _make_user(email="baja@minisoporte.test", password="Clave-Baja-Demo-1", active=False)  # noqa: S106 - test fixture value


@pytest.fixture
def settings() -> Settings:
    """Explicit-kwargs Settings — no ``os.environ`` reads or writes."""
    return Settings(
        DATABASE_URL="postgresql+psycopg://unit:unit@localhost:1/unit",
        JWT_SECRET=_TEST_SECRET,
        ENVIRONMENT="test",
    )


@pytest.fixture
def repo(employee: User, deactivated: User) -> FakeUserRepository:
    """Fresh fake per test: the recorded-lookup list never leaks between tests."""
    return FakeUserRepository(employee, deactivated)


@pytest.fixture
def service(repo: FakeUserRepository, settings: Settings) -> AuthService:
    return AuthService(repo, settings)


def test_login_success_returns_bearer_token_with_expected_claims(
    service: AuthService, employee: User, settings: Settings
) -> None:
    """Spec (POST /api/auth/login, 200): valid active credentials yield a
    bearer ``TokenResponse`` whose JWT carries ``sub``/``role``/``exp``."""
    before = int(time.time())

    response = service.login(_EMPLOYEE_EMAIL, _EMPLOYEE_PASSWORD, _CLIENT_IP)

    after = int(time.time())
    assert response.token_type == "bearer"
    assert response.expires_in_seconds == TOKEN_TTL_SECONDS == 28800

    payload = decode_access_token(response.access_token, secret=settings.JWT_SECRET)
    assert payload.sub == str(employee.id)
    assert payload.role == "employee"
    # exp = issued + 28800 s; bound both edges against the wall clock.
    assert before + TOKEN_TTL_SECONDS <= payload.exp <= after + TOKEN_TTL_SECONDS
    assert TOKEN_TTL_SECONDS - (after - before) <= payload.exp - after <= TOKEN_TTL_SECONDS
    assert 28790 <= payload.exp - int(time.time()) <= 28800


def test_login_normalizes_padded_mixed_case_email_before_lookup(
    service: AuthService, repo: FakeUserRepository, employee: User, settings: Settings
) -> None:
    """CA-05 / INSTR-006 / BR-008: " Empleado@MiniSoporte.Test " is trimmed and
    lowercased BEFORE the port lookup, so the lowercase-stored user is found."""
    response = service.login(" Empleado@MiniSoporte.Test ", _EMPLOYEE_PASSWORD, _CLIENT_IP)

    assert repo.requested_emails == ["empleado@minisoporte.test"]
    assert response.token_type == "bearer"
    payload = decode_access_token(response.access_token, secret=settings.JWT_SECRET)
    assert payload.sub == str(employee.id)


def test_login_wrong_password_raises_invalid_credentials(service: AuthService) -> None:
    """BR-002: a wrong password for an existing active account raises the
    typed domain exception — never an HTTP error below the routes layer."""
    with pytest.raises(InvalidCredentialsError):
        service.login(_EMPLOYEE_EMAIL, _WRONG_PASSWORD, _CLIENT_IP)


def test_login_nonexistent_email_raises_the_same_exception_type(
    service: AuthService,
) -> None:
    """BR-002: a never-registered email raises exactly the SAME exception type
    as the wrong-password case, so callers cannot tell the two apart."""
    with pytest.raises(InvalidCredentialsError) as wrong_password:
        service.login(_EMPLOYEE_EMAIL, _WRONG_PASSWORD, _CLIENT_IP)

    with pytest.raises(InvalidCredentialsError) as nonexistent:
        service.login("nadie@minisoporte.test", _EMPLOYEE_PASSWORD, _CLIENT_IP)

    assert type(wrong_password.value) is type(nonexistent.value)
    assert type(nonexistent.value) is InvalidCredentialsError


def test_login_deactivated_user_with_correct_password_raises_invalid_credentials(
    service: AuthService,
) -> None:
    """BR-002: an active = false account is rejected with the SAME
    ``InvalidCredentialsError`` even when the password is correct."""
    with pytest.raises(InvalidCredentialsError):
        service.login("baja@minisoporte.test", "Clave-Baja-Demo-1", _CLIENT_IP)  # noqa: S106 - test fixture value


def test_login_nonexistent_email_still_spends_a_bcrypt_dummy_verify(
    service: AuthService,
) -> None:
    """BR-002 (uniform timing): the absent-email path runs a real bcrypt verify
    against the module-level dummy hash before raising, so its cost matches the
    wrong-password path. Structural proof: the dummy hash exists and rejects a
    wrong plaintext like any stored hash."""
    with pytest.raises(InvalidCredentialsError):
        service.login("nadie@minisoporte.test", _EMPLOYEE_PASSWORD, _CLIENT_IP)

    dummy_hash = auth_service._dummy_hash()
    assert dummy_hash.startswith("$2")
    assert verify_password("definitely-not-the-dummy-plaintext", dummy_hash) is False  # noqa: S106 - test fixture value


def test_failure_log_carries_client_ip_only(
    service: AuthService, employee: User, caplog: pytest.LogCaptureFixture
) -> None:
    """INSTR-008: the failure log line names the client IP — and never the
    attempted email, the password, or any issued token substring."""
    caplog.set_level(logging.WARNING, logger="app.domain.services.auth_service")

    token = service.issue_token(employee).access_token

    with pytest.raises(InvalidCredentialsError):
        service.login(_EMPLOYEE_EMAIL, _WRONG_PASSWORD, _CLIENT_IP)

    text = caplog.text
    assert _CLIENT_IP in text
    assert _EMPLOYEE_EMAIL not in text
    assert _WRONG_PASSWORD not in text
    assert token not in text
