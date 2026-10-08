"""Shared security core: bcrypt password hashing and HS256 JWT issue/verify.

Passwords are hashed with the ``bcrypt`` library directly (never passlib, Q-010).
Access tokens are JWTs signed with HS256 (PyJWT); decoding pins an explicit
``algorithms=["HS256"]`` allow-list so ``none``/RS256-family tokens are rejected
(BR-004, Q-011). The signing key is always passed in by the caller from the
DI-provided :class:`~app.core.config.Settings` ``JWT_SECRET`` — this module never
reads the environment itself (design.md Decision 2).
"""

import time
from dataclasses import dataclass

import bcrypt
import jwt

from app.domain.exceptions import DomainError

TOKEN_TTL_SECONDS = 28800
"""Access-token lifetime in seconds (8 hours, no refresh)."""


class TokenDecodeError(DomainError):
    """Base for all token-rejection cases (expired, malformed, bad signature)."""


class ExpiredTokenError(TokenDecodeError):
    """The token's ``exp`` claim is in the past."""


class InvalidTokenError(TokenDecodeError):
    """The token is malformed, badly signed, non-HS256 or missing claims."""


@dataclass(frozen=True)
class TokenPayload:
    """Validated claims carried by an access token."""

    sub: str
    role: str
    exp: int


def hash_password(plain: str) -> str:
    """Hash a plaintext password with bcrypt; returns the hash as text."""
    hashed = bcrypt.hashpw(plain.encode("utf-8"), bcrypt.gensalt())
    return hashed.decode("utf-8")


def verify_password(plain: str, password_hash: str) -> bool:
    """Check a plaintext password against a stored bcrypt hash.

    Never raises on garbage/corrupt stored hashes — any bcrypt failure is
    reported as ``False`` so the uniform sign-in failure path never 500s.
    """
    try:
        return bcrypt.checkpw(plain.encode("utf-8"), password_hash.encode("utf-8"))
    except (ValueError, TypeError):
        return False


def create_access_token(*, sub: str, role: str, secret: str) -> str:
    """Issue an HS256 access token for ``sub`` with role and TTL claims."""
    now = int(time.time())
    claims: dict[str, object] = {
        "sub": sub,
        "role": role,
        "iat": now,
        "exp": now + TOKEN_TTL_SECONDS,
    }
    return jwt.encode(claims, secret, algorithm="HS256")


def decode_access_token(token: str, *, secret: str) -> TokenPayload:
    """Decode and validate an access token, pinning the HS256 allow-list.

    Raises :class:`ExpiredTokenError` when ``exp`` has passed and
    :class:`InvalidTokenError` for every other rejection (malformed token,
    wrong signature, wrong algorithm, missing claims). No raw PyJWT error
    ever escapes.
    """
    try:
        payload = jwt.decode(token, secret, algorithms=["HS256"])
    except jwt.ExpiredSignatureError as exc:
        raise ExpiredTokenError("Token expired") from exc
    except jwt.PyJWTError as exc:
        raise InvalidTokenError("Invalid token") from exc

    sub = payload.get("sub")
    role = payload.get("role")
    exp = payload.get("exp")
    if not isinstance(sub, str) or not isinstance(role, str) or not isinstance(exp, int):
        raise InvalidTokenError("Invalid token claims")
    return TokenPayload(sub=sub, role=role, exp=exp)
