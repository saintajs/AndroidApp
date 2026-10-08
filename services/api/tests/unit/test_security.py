"""Unit tests for app/core/security.py (US-002).

Two groups:

- bcrypt password hashing round-trip (CA-01): correct password verifies True,
  a wrong password verifies False, and garbage/empty stored hashes report
  False without raising (the uniform sign-in failure path never 500s).
- HS256 access-token issue/decode (BR-004, Q-011): the happy round-trip
  returns the validated claims with the full 8-hour TTL, while expired,
  tampered-signature, non-HS256 (HS512 / ``none``), wrong-secret and
  malformed tokens are all rejected with the module's own
  ``TokenDecodeError`` subclasses — no raw PyJWT error ever escapes.

All values (secrets, passwords) are throwaway test fixtures; real bcrypt and
PyJWT are exercised, nothing is mocked.
"""

import time

import jwt
import pytest

from app.core import security
from app.core.security import (
    ExpiredTokenError,
    InvalidTokenError,
    TokenDecodeError,
    create_access_token,
    decode_access_token,
    hash_password,
    verify_password,
)

TEST_SECRET = "unit-test-jwt-secret-0123456789abcdef"
OTHER_SECRET = "another-unit-test-jwt-secret-9876543210"


# ---------------------------------------------------------------------------
# bcrypt password hashing (CA-01)
# ---------------------------------------------------------------------------


def test_hash_password_verify_password_round_trip_is_true() -> None:
    """CA-01: hashing then verifying the same plaintext succeeds."""
    stored = hash_password("s3cret-Demo-Password")

    assert stored != "s3cret-Demo-Password"  # never stored in plaintext
    assert verify_password("s3cret-Demo-Password", stored) is True


def test_verify_password_wrong_plaintext_is_false() -> None:
    """CA-01 / BR-002: a different plaintext must not verify."""
    stored = hash_password("s3cret-Demo-Password")

    assert verify_password("wrong-password", stored) is False


@pytest.mark.parametrize("stored_hash", ["", "not-a-bcrypt-hash", "$2b$12$short"])
def test_verify_password_garbage_stored_hash_is_false_without_raising(
    stored_hash: str,
) -> None:
    """CA-01: corrupt/empty stored hashes report False — never an exception,
    so the uniform sign-in failure path cannot turn into a 500."""
    assert verify_password("s3cret-Demo-Password", stored_hash) is False


def test_hash_password_uses_bcrypt_format() -> None:
    """Sanity: the stored hash is a bcrypt modular-crypt string."""
    stored = hash_password("s3cret-Demo-Password")

    assert stored.startswith("$2b$")


# ---------------------------------------------------------------------------
# HS256 token issue/decode round-trip (BR-004)
# ---------------------------------------------------------------------------


def test_token_ttl_constant_is_8_hours() -> None:
    """The access-token TTL is 28800 seconds (8 hours, no refresh)."""
    assert security.TOKEN_TTL_SECONDS == 28800


def test_create_then_decode_token_round_trip_returns_claims() -> None:
    """BR-004: a freshly issued token decodes back to its sub/role claims with
    exp ≈ issued-at + TOKEN_TTL_SECONDS."""
    before = int(time.time())
    token = create_access_token(sub="0198d0aa-0001", role="admin", secret=TEST_SECRET)
    after = int(time.time())

    payload = decode_access_token(token, secret=TEST_SECRET)

    assert isinstance(payload, security.TokenPayload)
    assert payload.sub == "0198d0aa-0001"
    assert payload.role == "admin"
    assert before + security.TOKEN_TTL_SECONDS <= payload.exp
    assert payload.exp <= after + security.TOKEN_TTL_SECONDS


# ---------------------------------------------------------------------------
# decode rejection matrix (BR-004 / Q-011)
# ---------------------------------------------------------------------------


def _encode_claims(claims: dict[str, object], algorithm: str, secret: str | None) -> str:
    """Craft an ad-hoc JWT outside create_access_token (test-only helper).

    PyJWT requires ``key=None`` when signing with ``alg: none``.
    """
    key = None if algorithm == "none" else secret
    return jwt.encode(claims, key, algorithm=algorithm)


def test_expired_token_raises_expired_token_error() -> None:
    """BR-004: a token whose exp is in the past raises the dedicated
    ExpiredTokenError (not the generic invalid-token error)."""
    now = int(time.time())
    token = _encode_claims(
        {"sub": "u1", "role": "admin", "iat": now - 10, "exp": now - 1},
        "HS256",
        TEST_SECRET,
    )

    with pytest.raises(ExpiredTokenError) as excinfo:
        decode_access_token(token, secret=TEST_SECRET)

    assert isinstance(excinfo.value, TokenDecodeError)


def test_tampered_signature_raises_invalid_token_error() -> None:
    """BR-004: flipping a character in the signature segment invalidates the
    token — rejection is InvalidTokenError."""
    token = create_access_token(sub="u1", role="admin", secret=TEST_SECRET)
    head, body, signature = token.split(".")
    tampered_signature = "A" if signature[-1] != "A" else "B"
    tampered = f"{head}.{body}.{signature[:-1]}{tampered_signature}"

    assert tampered != token
    with pytest.raises(InvalidTokenError) as excinfo:
        decode_access_token(tampered, secret=TEST_SECRET)

    assert isinstance(excinfo.value, TokenDecodeError)


def test_hs512_signed_token_rejected_because_decode_pins_hs256() -> None:
    """Q-011: an HS512-signed token is rejected — decode pins an explicit
    ``algorithms=["HS256"]`` allow-list regardless of the header's alg."""
    now = int(time.time())
    hs512_secret = "h" * 64  # 64 bytes: RFC 7518 §3.2 recommends ≥ digest size
    token = _encode_claims(
        {"sub": "u1", "role": "admin", "iat": now, "exp": now + 100},
        "HS512",
        hs512_secret,
    )

    with pytest.raises(InvalidTokenError):
        decode_access_token(token, secret=TEST_SECRET)


def test_alg_none_token_rejected_because_decode_pins_hs256() -> None:
    """Q-011: the classic ``alg: none`` forgery is rejected — an unsigned
    token can never satisfy the HS256 allow-list."""
    now = int(time.time())
    token = _encode_claims(
        {"sub": "u1", "role": "admin", "iat": now, "exp": now + 100},
        "none",
        TEST_SECRET,
    )

    with pytest.raises(InvalidTokenError):
        decode_access_token(token, secret=TEST_SECRET)


def test_token_signed_with_different_secret_rejected() -> None:
    """BR-004: a token issued under one secret fails verification against
    another (signature mismatch → InvalidTokenError, never a raw PyJWT error)."""
    token = create_access_token(sub="u1", role="admin", secret=TEST_SECRET)

    with pytest.raises(InvalidTokenError):
        decode_access_token(token, secret=OTHER_SECRET)


@pytest.mark.parametrize("garbage", ["", "not-a-jwt", "a.b.c", "eyJhbGciOiJIUzI1NiJ9.."])
def test_garbage_token_string_rejected(garbage: str) -> None:
    """BR-004: malformed token strings raise InvalidTokenError without raising
    anything else (the 401 mapping in deps.py relies on this)."""
    with pytest.raises(InvalidTokenError):
        decode_access_token(garbage, secret=TEST_SECRET)


@pytest.mark.parametrize(
    ("claims", "missing"),
    [
        ({"role": "admin", "exp": 9999999999}, "sub"),
        ({"sub": "u1", "exp": 9999999999}, "role"),
        ({"sub": "u1", "role": "admin"}, "exp"),
        ({"sub": 42, "role": "admin", "exp": 9999999999}, "non-string sub"),
        ({"sub": "u1", "role": "admin", "exp": "soon"}, "non-int exp"),
    ],
)
def test_payload_with_missing_or_mistyped_claims_rejected(
    claims: dict[str, object], missing: str
) -> None:
    """BR-004: a syntactically valid signature is not enough — payloads
    missing sub/role/exp (or with mistyped claims) raise InvalidTokenError."""
    token = _encode_claims(claims, "HS256", TEST_SECRET)

    with pytest.raises(InvalidTokenError):
        decode_access_token(token, secret=TEST_SECRET)

    assert missing  # documents which claim each parametrized case abuses
