"""Authentication request/response schemas (backend.yaml `endpoints.post-auth-login`).

``LoginRequest`` validates the POST /api/auth/login body: missing or blank fields
must fail with a 422 carrying a field-level entry per offending field (BR-001).
``TokenResponse`` is the typed shape of a successful sign-in: an HS256 session JWT
with ``token_type="bearer"`` and the fixed 28800 s TTL.
"""

import typing

from email_validator import EmailSyntaxError, validate_email
from pydantic import AfterValidator, BaseModel, BeforeValidator, Field, field_validator


def _strip_whitespace(value: str) -> str:
    """Trim leading/trailing whitespace so padded submissions parse (CA-05)."""
    return value.strip()


def _validate_email_format(value: str) -> str:
    """Validate email syntax, accepting the reserved ``.test`` demo TLD.

    Preserves the ``EmailStr`` behavioral contract — a format-validated email or
    a 422 field-level entry with the default English msg/type — while accepting
    the spec-mandated reserved ``@minisoporte.test`` demo domain (email-validator
    2.x refuses special-use TLDs unless ``test_environment=True``) and
    whitespace-padded submissions (CA-05). Only the syntax is checked; the value
    is returned with its ORIGINAL casing untouched — normalization (trim+lower)
    is the service layer's job (INSTR-006), never the schema's.
    """
    try:
        validate_email(value, check_deliverability=False, test_environment=True)
    except EmailSyntaxError as exc:
        raise ValueError(f"value is not a valid email address: {exc}") from exc
    return value


LoginEmail = typing.Annotated[
    str,
    BeforeValidator(_strip_whitespace),
    AfterValidator(_validate_email_format),
]
"""Whitespace-tolerant, format-validated email (see ``_validate_email_format``)."""


class LoginRequest(BaseModel):
    """Sign-in request body — email plus a non-blank password.

    ``password`` carries no format constraints (INSTR-008) beyond being non-blank:
    an empty or whitespace-only value must reject with a 422 field-level entry
    keyed to ``password`` (BR-001). ``email`` uses ``LoginEmail`` (not ``EmailStr``)
    because email-validator 2.x rejects the RFC-2606 reserved ``.test`` TLD used by
    the demo/seed accounts; the email is normalized (trimmed, lowercased) by the
    service layer before lookup (INSTR-006), not here.
    """

    email: LoginEmail
    password: str = Field(min_length=1)

    @field_validator("password")
    @classmethod
    def reject_whitespace_only(cls, value: str) -> str:
        """Reject whitespace-only passwords the same as an empty one."""
        if not value.strip():
            raise ValueError("String should have at least 1 character")
        return value


class TokenResponse(BaseModel):
    """Successful sign-in payload — the session JWT and its fixed TTL."""

    access_token: str
    token_type: typing.Literal["bearer"] = "bearer"  # noqa: S105 - OAuth2 scheme name, not a secret
    expires_in_seconds: int = 28800
