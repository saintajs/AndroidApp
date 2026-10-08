"""Reusable API-layer dependencies (US-002 — backend.yaml ``services.deps``).

This module sits in the API layer alongside the route modules, so raising
``HTTPException`` here does NOT violate the "never raise HTTPException below
routes" rule — that rule guards the domain and persistence layers only.
``get_current_user`` is the shared bearer-token guard that protected routes
declare via :data:`CurrentUserDep` (CONF-011); US-003/US-004 consume the
signatures unchanged.

Every token rejection (missing header, non-bearer scheme, expired, malformed,
badly signed, non-HS256, unknown subject) collapses to the SAME
``{"detail": "No autenticado"}`` 401 body — the cases are indistinguishable
from the outside (spec CA-07, ``endpoints.get-me``). Only a valid token whose
subject is deactivated yields ``403 Cuenta inactiva`` (mid-session
deactivation). Tokens are never logged (INSTR-008, BR-026).
"""

import uuid
from collections.abc import Callable
from typing import Annotated

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.core.security import (
    TokenDecodeError,
    decode_access_token,
)
from app.db.repositories.user_repository import UserRepository
from app.db.session import get_db
from app.domain.models import User
from app.domain.services.auth_service import AuthService

#: Bearer extractor with ``auto_error=False``: a MISSING Authorization header
#: reaches us as ``None`` so the dependency itself can emit the uniform Spanish
#: 401 body, instead of HTTPBearer's default English "Not authenticated".
_HTTP_BEARER = HTTPBearer(auto_error=False)


def get_settings_dep() -> Settings:
    """Thin ``Depends`` wrapper over :func:`app.core.config.get_settings`."""
    return get_settings()


def get_user_repository(
    session: Annotated[Session, Depends(get_db)],
) -> UserRepository:
    """Bind the users port to the request-scoped session."""
    return UserRepository(session)


def get_auth_service(
    repository: Annotated[UserRepository, Depends(get_user_repository)],
    settings: Annotated[Settings, Depends(get_settings_dep)],
) -> AuthService:
    """Build the sign-in use case from its users port and the DI settings."""
    return AuthService(user_repository=repository, settings=settings)


def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_HTTP_BEARER)],
    repository: Annotated[UserRepository, Depends(get_user_repository)],
    settings: Annotated[Settings, Depends(get_settings_dep)],
) -> User:
    """Decode the bearer token, load its subject, and enforce activation.

    Decode pins the HS256 allow-list via
    :func:`app.core.security.decode_access_token`; the user is loaded by the
    ``sub`` claim (a UUID string) and must be active. Returns the ORM
    :class:`~app.domain.models.User`.
    """
    if credentials is None:
        # Missing Authorization header or non-bearer scheme.
        raise HTTPException(status_code=401, detail="No autenticado")
    try:
        payload = decode_access_token(credentials.credentials, secret=settings.JWT_SECRET)
    except TokenDecodeError as exc:
        # Expired, malformed, badly signed or non-HS256 — one uniform 401 that
        # never reveals which case occurred (spec CA-07, endpoints.get-me).
        raise HTTPException(status_code=401, detail="No autenticado") from exc
    try:
        user_id = uuid.UUID(payload.sub)
    except ValueError as exc:
        # Malformed ``sub`` is just another invalid credential.
        raise HTTPException(status_code=401, detail="No autenticado") from exc
    user = repository.get_by_id(user_id)
    if user is None:
        raise HTTPException(status_code=401, detail="No autenticado")
    if not user.active:
        # Token valid, but the subject was deactivated mid-session.
        raise HTTPException(status_code=403, detail="Cuenta inactiva")
    return user


#: Auth guard alias every protected route declares on its signature (CONF-011).
CurrentUserDep = Annotated[User, Depends(get_current_user)]


def require_role(role: str) -> Callable[[User], User]:
    """Build a role-gating dependency that wraps :func:`get_current_user`.

    US-004 (backend.yaml ``services.require_role``): the returned function is a
    FastAPI dependency whose signature declares ``CurrentUserDep``, so the
    shared bearer-token guard runs FIRST inside the dependency chain — a
    missing/invalid token yields 401 before any role logic, and only a valid,
    active subject reaches the role comparison. A subject whose role differs
    from ``role`` is rejected with the uniform
    ``403 {"detail": "No autorizado"}``; the real :class:`~app.domain.models.User`
    is returned otherwise.

    Because the guard sits in the dependency chain, the 403 fires BEFORE the
    route handler body performs any incident lookup — mechanically
    guaranteeing role-before-lookup so an employee probing a foreign or
    nonexistent id gets the same 403 and nothing leaks (INSTR-004, BR-022).
    Usage on a route signature (CONF-011):

    ``current_user: Annotated[User, Depends(require_role("support"))]``
    """

    def dependency(user: CurrentUserDep) -> User:
        """Reject ``user`` unless their role is exactly ``role``."""
        if user.role != role:
            # Uniform, leak-free: identical body for any non-matching role,
            # with no hint about whether the target id exists (BR-022).
            raise HTTPException(status_code=403, detail="No autorizado")
        return user

    return dependency
