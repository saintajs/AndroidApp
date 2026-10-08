# US-002 — Sign-in, session JWT and profile API with rate-limited generic failures

**Epic:** EPIC-001 — Incident Management Service
**Feature:** FEAT-002 — Authentication & Session API
**Status:** locked (approved)
**Related Items:** FUNC-001, INSTR-006, INSTR-007, INSTR-008, CONF-001, CONF-003, BR-001, BR-002, BR-004, BR-007, BR-008, BR-026

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- api

**APIs**

- POST /api/auth/login
- GET /api/me

**Data entities**

- users

**Flows**

- Employee authentication flow
- Session lifecycle (sign in to sign out)

**Scope note:** Backend-only story: it implements the login/profile half of FLOW-001/FLOW-005; the mobile side of those flows is US-006/US-007. No screens apply (reason: api component has no screens in the design).

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Endpoints

#### `POST /api/auth/login`

**Request**

```
Request body (application/json): | Field | Type | Required | Validation | Example | — `email` | string | Yes | Valid email format (Pydantic `EmailStr`); normalized by the service (trimmed, lowercased) before lookup (INSTR-006) | "empleado@minisoporte.test" — `password` | string | Yes | Non-blank; no format constraints (never logged, INSTR-008) | "S3cret!pass". Missing or blank fields -> 422 with field-level `detail` entries for each offending field (BR-001).
```

**Response**

```
| 200 | Valid credentials, active account | {"access_token": "<JWT>", "token_type": "bearer", "expires_in_seconds": 28800} | | 401 | Invalid credentials or nonexistent email — one uniform body, never reveals account existence (BR-002) | {"detail": "Credenciales inválidas"} | | 422 | Missing/blank/malformed fields (BR-001) | Structured 422 shape (detail list of {loc, msg, type}) | | 429 | IP or email failure budget exhausted (CONF-003) | {"detail": "Demasiados intentos. Inténtelo de nuevo más tarde."} + Retry-After header | Field precision for 200: access_token — string, signed JWT HS256, claims sub/role/exp; token_type — string literal "bearer"; expires_in_seconds — integer, always 28800 (8 h, Q-002).
```

**Validation:** 422: missing/blank/malformed fields; validation msg/type strings keep the FastAPI default English text (client maps to its own Spanish per-field copy, INSTR-011). 401: one uniform body for invalid credentials or nonexistent email (BR-002). 429: applies to failed attempts only — successful authentications do not consume budget (CONF-003); two independent in-process in-memory counters per attempt — per client IP and per submitted email (Q-004, BR-007); behavior uniform regardless of account existence; thresholds env-configurable via LOGIN_RATELIMIT_IP_MAX, LOGIN_RATELIMIT_EMAIL_MAX, LOGIN_RATELIMIT_WINDOW_SECONDS; design defaults 5 failures per window per IP, 5 per email, window 900 s; tripped response carries a Retry-After header (seconds until the counter resets).

#### `GET /api/me`

**Request**

```
Requires a valid bearer token (Authorization: Bearer <JWT>). No body, no parameters.
```

**Response**

```
200 fields: | id | string (UUID) | No | Token subject (CONF-008) | — | email | string | No | Normalized stored email (INSTR-006) | — | role | string | No | employee or support (CONF-001) | — | active | boolean | No | Account enabled flag | — | created_at | string (ISO 8601 UTC) | No | Account creation instant |. password_hash is never part of any response (INSTR-008, BR-026).
```

**Validation:** 401 {"detail": "No autenticado"} (missing/expired/invalid token); 403 {"detail": "Cuenta inactiva"} (token valid but active = false). Token auth: JWT HS256, claims sub (user UUID string), role, exp (issued + 28800 s); decode pins algorithms=["HS256"] (CONF-001, Q-002, Q-011, BR-004).

### Entities

#### users

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, NO null, default gen_random_uuid() — sub claim of the JWT (CONF-001, CONF-008) |
| email | TEXT | UQ (uq_users_email), NO null — normalized (trimmed, lowercased) by the service layer before storage/lookup (INSTR-006, BR-008); uniqueness also the DB guard against duplicate accounts |
| password_hash | TEXT | NO null — salted bcrypt hash (60-char $2b$… string), bcrypt library used directly, never passlib, never logged or returned (INSTR-008, Q-010, BR-026) |
| role | TEXT | NO null, CHECK role IN ('employee','support') — role JWT claim drives authorization (CONF-001, INSTR-004) |
| active | BOOLEAN | NO null, default TRUE — FALSE blocks sign-in and API access (token subject inactive -> 403 Cuenta inactiva) |
| created_at | TIMESTAMPTZ | NO null, default now() — returned by GET /api/me (CONF-011) |

### Conventions

- `app/core/security.py` — bcrypt hash/verify used directly, never passlib (Q-010, BR-026); PyJWT encode/decode pinning algorithms=["HS256"] (Q-011, BR-004); constants like TOKEN_TTL_SECONDS = 28800 in UPPER_SNAKE_CASE.
- `app/core/ratelimit.py` — in-memory per-IP/per-email counters exposed as FastAPI dependencies (Q-004, BR-007); the limiter's counters live in a module-level singleton injected via `Depends` so tests can reset/inject fake clocks.
- Domain services raise typed domain exceptions defined in `app/domain/` (e.g. `InvalidCredentialsError`, `RateLimitExceededError`) — never raise HTTPException below the routes layer; route handlers translate each exception to exactly the status code + Spanish detail string fixed in api-contract.md (`401` "Credenciales inválidas" uniform per BR-002, `403` "Cuenta inactiva", `429` "Demasiados intentos. Inténtelo de nuevo más tarde." + Retry-After).
- Auth guards are reusable dependencies in `app/api/deps.py`: `get_current_user` (validates JWT with the HS256 allow-list, loads the active user), reusable as `CurrentUserDep = Annotated[User, Depends(get_current_user)]`; access level per route is declared on the route signature itself — mandatory per CONF-011.
- All path operations, dependencies, and services use plain `def` (sync stack); strict typing everywhere; Pydantic v2 schemas in `app/domain/schemas/auth.py`/`user.py` with typed response models so Pydantic serializes and filters the response.
- Logging: log authentication failures with the request client IP only — never the attempted email in a way that pairs with a success/failure outcome that could aid enumeration; bcrypt verification results and JWT claims are not logged; NEVER log passwords, tokens, or Authorization headers (INSTR-008, BR-026).

### Flow Slice

Employee authentication flow — Trigger: The employee opens the app without a stored valid token and lands on S-01. Steps: 2. The app calls `POST /api/auth/login` (public, rate-limited on failures) with `{"email", "password"}`. The API service normalizes the email (trimmed, lowercased) before lookup against `users` (INSTR-006); the bcrypt `password_hash` is verified — the password is never logged or returned (INSTR-008). 3. On success the API issues the session token: `200 {"access_token": "<JWT HS256>", "token_type": "bearer", "expires_in_seconds": 28800}` with claims `sub`/`role`/`exp` (CONF-001). 4. Optionally the app may call `GET /api/me` to display the signed-in profile; the returned `users` fields are `id`, `email`, `role`, `active`, `created_at` — never `password_hash`. Outcome & errors: `422` — missing/blank/malformed fields (BR-001): structured Pydantic field errors; `401` {"detail": "Credenciales inválidas"} — wrong password or nonexistent email: one uniform body, never reveals account existence (BR-002); `429` {"detail": "Demasiados intentos. Inténtelo de nuevo más tarde."} + `Retry-After` — per-IP or per-email failure budget exhausted (defaults: 5 failures / 900 s window; CONF-003). Session lifecycle (sign in to sign out) — step 1: sign-in issues the HS256 JWT (`exp` = issued + 28800 s, claims `sub`/`role`/`exp`); step 2: every protected call attaches `Authorization: Bearer <JWT>`; the API decodes with the pinned `algorithms=["HS256"]` allow-list (BR-004); `403` {"detail": "Cuenta inactiva"} — mid-session deactivation rejects a token whose `sub` resolves to an `active = false` user.

### Notes

This story also establishes the shared JWT/bcrypt core (`app/core/security.py`) and the auth dependencies in `app/api/deps.py` that every later protected route reuses. The 3x3 login behavior is server-side; the demo credentials consumed by this flow are created by the seed (US-010).

---

## Narrative

As an employee (or support agent) I can sign in with my email and password and receive a session JWT so that I can call protected endpoints for 8 hours; the API returns one generic failure message for wrong passwords and nonexistent emails alike and throttles repeated failures, so attackers cannot enumerate accounts. Trigger: POST /api/auth/login with credentials after the app opens without a valid token (or GET /api/me with an existing token).

---

## Functional Flow

1. The client posts {"email", "password"} to /api/auth/login (public, rate-limited on failures). Missing/blank fields are rejected 422 with field-level entries (BR-001). 2. The service normalizes the email (trimmed, lowercased) before lookup (INSTR-006) and verifies the bcrypt password_hash — the password is never logged or returned (INSTR-008). 3. On success: 200 {"access_token": JWT HS256, "token_type": "bearer", "expires_in_seconds": 28800} with claims sub/role/exp (CONF-001). 4. The client may call GET /api/me with the bearer token to render the profile (email as S-02 subtitle); response carries id/email/role/active/created_at, never password_hash. Errors: 401 {"detail": "Credenciales inválidas"} uniformly for wrong password or nonexistent email (BR-002, INSTR-007); 429 {"detail": "Demasiados intentos. Inténtelo de nuevo más tarde."} + Retry-After when the per-IP or per-email failure budget is exhausted (uniform whether or not the email exists, BR-007); 401 {"detail": "No autenticado"} for missing/expired/invalid tokens on /api/me; 403 {"detail": "Cuenta inactiva"} when a token's subject has active=false.

---

## Acceptance Criteria

- **CA-01:** Given valid credentials for an active account, When POST /api/auth/login is called, Then the response is 200 with access_token (JWT HS256), token_type "bearer" and expires_in_seconds 28800, with claims sub/role/exp.
- **CA-02:** Given a wrong password, and separately a never-registered email, When each is submitted, Then both return the identical 401 {"detail": "Credenciales inválidas"} with no difference in body, timing budget, or rate-limit behavior (BR-002).
- **CA-03:** Given a request missing or blanking email or password, When submitted, Then the API returns 422 with a structured detail list carrying one field-level entry per offending field (BR-001).
- **CA-04:** Given LOGIN_RATELIMIT_* at design defaults, When 5 failed attempts occur for the same email (or same IP) inside the 900 s window, Then the next attempt returns 429 with {"detail": "Demasiados intentos. Inténtelo de nuevo más tarde."} and a Retry-After header; a successful login does not consume budget.
- **CA-05:** Given a stored user email empleado@minisoporte.test, When login is attempted with " Empleado@MiniSoporte.Test ", Then the service normalizes it (trimmed, lowercased) and authentication succeeds against the stored row (INSTR-006, BR-008).
- **CA-06:** Given a valid token, When GET /api/me is called, Then the response carries id, email, role, active, created_at and never password_hash (BR-026).
- **CA-07:** Given an expired, malformed, invalidly signed, or non-HS256 token, When a protected route is called, Then the response is 401 {"detail": "No autenticado"}; given a token whose sub resolves to an active=false user, Then the response is 403 {"detail": "Cuenta inactiva"}.
- **CA-08:** Given the auth code paths, When the coverage gate runs, Then app/domain auth service, app/core/security.py, app/core/ratelimit.py and app/api/deps.py hold >=90% coverage including wrong-algorithm/expired-token rejection and limiter windows (no sleeping; fake clock injected).

---

## Business Rules

- BR-001 — Both email and password are required for sign-in; a request missing either is rejected with field-level validation feedback.
- BR-002 — A failed authentication attempt returns a single generic message that never reveals whether the email exists.
- BR-004 — Sessions are signed JWTs (HS256) with 8-hour expiration and no refresh token, validated with an explicit algorithms allow-list (PyJWT 2.15.1).
- BR-007 — Failed sign-in attempts are rate-limited per IP and per email with behavior uniform regardless of account existence (in-process in-memory counters).
- BR-008 — Email addresses are normalized (trimmed and lowercased) before authentication and must be unique across users.
- BR-026 — Passwords are stored only as salted bcrypt hashes (bcrypt directly, no passlib) and never logged, returned, or stored in plain text.

---

## Dependencies

- US-001

---

## Security Considerations

- Account-enumeration defense: uniform 401 body and uniform rate-limit behavior for existing and nonexistent accounts (BR-002, INSTR-007).
- Algorithm-confusion defense: decode pins algorithms=["HS256"] (Q-011, BR-004).
- Credential hygiene: EmailStr validation, bcrypt verification only, password never logged/returned; credentials never appear in URLs (INSTR-008).
- No refresh endpoint exists; the 8-hour TTL is the accepted server-side mitigation for sign-out (Q-008).

---

## Non-Functional Requirements

- In-process in-memory rate limiting is sufficient for the single-process internal deployment; a multi-process deployment would revisit it (Q-004).
- All handlers plain `def` on the threadpool; API response time target under two seconds under normal conditions (CONF-009).
- Rate-limit thresholds env-configurable with documented design defaults (5/5/900 s).

---

## Activity Diagram

@startuml
start
:Client posts {email, password} to POST /api/auth/login;
if (Both fields present and well-formed? (BR-001)) then (no)
  :422 structured field-level detail list;
  stop
else (yes)
endif
if (Failure budget available per IP and per email? (BR-007)) then (no)
  :429 "Demasiados intentos. Inténtelo de nuevo más tarde." + Retry-After;
  stop
else (yes)
endif
:Normalize email trimmed lowercased (INSTR-006); verify bcrypt password_hash (INSTR-008);
if (Credentials valid and account active? (BR-002)) then (no)
  :401 "Credenciales inválidas" — uniform, no account-existence leak;
  stop
else (yes)
  :200 {access_token, token_type "bearer", expires_in_seconds 28800} (CONF-001);
  :Client stores token Keystore-backed (BR-006); optional GET /api/me renders profile;
  stop
endif
@enduml
