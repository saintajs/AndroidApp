"""Sign-in use case (US-002): credential verification and session-token issuance.

``AuthService`` is the only place the login rules live: the email is normalized
(trimmed, lowercased) before lookup (INSTR-006, BR-008), bcrypt verifies the
password, and every failure — absent email, deactivated account, wrong password —
raises the SAME :class:`~app.domain.exceptions.InvalidCredentialsError`. On the
absent-email path a deterministic dummy-hash verify is still run so the timing
matches the wrong-password path (BR-002). Failures are logged with the client
IP only — never the attempted email, password, token, or outcome pairing
(INSTR-008). The rate limiter is NOT part of this class: recording failures is
the route's job, and a successful login never touches any counter.
"""

import logging
from functools import lru_cache

from app.core.config import Settings
from app.core.security import (
    TOKEN_TTL_SECONDS,
    create_access_token,
    hash_password,
    verify_password,
)
from app.db.repositories.user_repository import UserRepository
from app.domain.exceptions import InvalidCredentialsError
from app.domain.models import User
from app.domain.schemas.auth import TokenResponse

logger = logging.getLogger(__name__)


@lru_cache(maxsize=1)
def _dummy_hash() -> str:
    """Return the process-wide dummy bcrypt hash, computed exactly once.

    Deterministic per process (BR-002): verifying an absent email against this
    hash burns the same bcrypt cost as a real verify, keeping the timing of the
    wrong-password and absent-email failures uniform.
    """
    return hash_password("mini-soporte-timing-dummy-verify")


class AuthService:
    """Sign-in use case: verify credentials, issue the session JWT."""

    def __init__(self, user_repository: UserRepository, settings: Settings) -> None:
        """Bind the use case to the users port and the DI-provided settings."""
        self._user_repository = user_repository
        self._settings = settings

    def login(self, email: str, password: str, client_ip: str) -> TokenResponse:
        """Verify the credentials and return the session token.

        Every failure mode raises the same :class:`InvalidCredentialsError` and
        logs one line carrying only ``client_ip``.
        """
        normalized = email.strip().lower()
        user = self._user_repository.get_by_email(normalized)
        if user is None:
            # Absent email: burn the same bcrypt cost as a real verify (BR-002).
            verify_password(password, _dummy_hash())
        elif not user.active or not verify_password(password, user.password_hash):
            user = None
        if user is None:
            logger.warning("Authentication failed from %s", client_ip)
            raise InvalidCredentialsError("Invalid credentials")
        return self.issue_token(user)

    def issue_token(self, user: User) -> TokenResponse:
        """Issue an HS256 access token for ``user`` with the fixed 8 h TTL."""
        token = create_access_token(
            sub=str(user.id), role=user.role, secret=self._settings.JWT_SECRET
        )
        return TokenResponse(
            access_token=token,
            token_type="bearer",  # noqa: S105, S106 - OAuth2 scheme name, not a secret
            expires_in_seconds=TOKEN_TTL_SECONDS,
        )
