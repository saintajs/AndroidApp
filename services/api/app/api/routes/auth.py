"""Authentication & session routes (backend.yaml `endpoints`).

``POST /api/auth/login`` verifies credentials through the
:class:`~app.domain.services.auth_service.AuthService` use case and returns the
session JWT; ``GET /api/me`` returns the bearer-identified profile. Both are
thin plain-``def`` handlers (sync stack): every domain failure is translated
here to exactly one status + Spanish detail string per the backend contract —
``InvalidCredentialsError`` -> 401 ``Credenciales inválidas``,
``RateLimitExceededError`` -> 429 ``Demasiados intentos. Inténtelo de nuevo
más tarde.`` + ``Retry-After`` — while 422 validation stays FastAPI-default.

The limiter check runs inside the handler BEFORE the credential check (a
tripped budget never touches bcrypt). Failed attempts are recorded by the
route only
after :class:`~app.domain.exceptions.InvalidCredentialsError` so a successful
login consumes no budget (CONF-003). No logging happens in this module: the
service already logs failures with the client IP only, and passwords, tokens,
Authorization headers and outcome-paired emails are never logged (INSTR-008,
BR-026).
"""

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request

from app.api.deps import CurrentUserDep, get_auth_service
from app.core.ratelimit import login_rate_limiter
from app.domain.exceptions import InvalidCredentialsError, RateLimitExceededError
from app.domain.schemas.auth import LoginRequest, TokenResponse
from app.domain.schemas.user import UserResponse
from app.domain.services.auth_service import AuthService

router = APIRouter(prefix="/api", tags=["auth"])

_CREDENTIALS_DETAIL = "Credenciales inválidas"
_RATE_LIMIT_DETAIL = "Demasiados intentos. Inténtelo de nuevo más tarde."


def _client_ip(request: Request) -> str:
    """Best-effort client IP for the limiter keys (test client included)."""
    return request.client.host if request.client else "unknown"


def _email_key(email: str) -> str:
    """Normalize the limiter's email key so case variants share one budget."""
    return email.strip().lower()


def _enforce_login_rate_limit(payload: LoginRequest, request: Request) -> None:
    """Reject the attempt when the per-IP or per-email budget is exhausted.

    Called directly by the ``login`` handler (NOT via ``Depends``): declaring
    the same ``LoginRequest`` body in both the endpoint and a dependency makes
    FastAPI validate the body twice and duplicate every 422 field-level entry
    (BR-001). Raises
    :class:`~app.domain.exceptions.RateLimitExceededError` from
    :data:`~app.core.ratelimit.login_rate_limiter` and translates it HERE (the
    one consistent place) to the fixed 429 body plus the ``Retry-After``
    header carried by the exception. The key normalizes the email so
    ``Empleado@MiniSoporte.Test`` and the lowercase form draw the same budget,
    uniformly regardless of account existence (BR-007).
    """
    try:
        login_rate_limiter.check_or_raise(
            client_ip=_client_ip(request), email=_email_key(payload.email)
        )
    except RateLimitExceededError as exc:
        raise HTTPException(
            status_code=429,
            detail=_RATE_LIMIT_DETAIL,
            headers={"Retry-After": str(exc.retry_after_seconds)},
        ) from exc


@router.post("/auth/login", response_model=TokenResponse)
def login(
    payload: LoginRequest,
    request: Request,
    service: Annotated[AuthService, Depends(get_auth_service)],
) -> TokenResponse:
    """Sign in: verify credentials and return the session JWT (CA-01).

    The limiter check above has already vetted the budgets before any
    credential work; on :class:`~app.domain.exceptions.InvalidCredentialsError`
    the failure is recorded FIRST (so it counts for the NEXT attempt) and the
    route raises the uniform Spanish 401 — the service log line is the only
    failure log (client IP only).
    """
    client_ip = _client_ip(request)
    _enforce_login_rate_limit(payload, request)
    try:
        return service.login(payload.email, payload.password, client_ip)
    except InvalidCredentialsError as exc:
        login_rate_limiter.record_failure(client_ip=client_ip, email=_email_key(payload.email))
        raise HTTPException(status_code=401, detail=_CREDENTIALS_DETAIL) from exc


@router.get("/me", response_model=UserResponse)
def me(current_user: CurrentUserDep) -> UserResponse:
    """Return the bearer-identified profile (CA-06).

    ``response_model=UserResponse`` is what structurally keeps
    ``password_hash`` out of the payload (BR-026); the 401/403 token
    translations live in :mod:`app.api.deps` and are not duplicated here.
    """
    return UserResponse.model_validate(current_user)
