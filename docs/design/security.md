## Security

Security baseline for MiniSoporte_copy, built to OWASP Top 10 and OWASP Mobile (MASVS-aligned) standards as mandated by the wizard and the extractions (INSTR-008, CONF-001..CONF-007). The design has no third-party identity provider; all authentication and authorization are first-party.

### Authentication flow

- **Mechanism:** Email + password sign-in against `POST /api/auth/login` (FUNC-001). The only credential transport is the HTTPS request body; credentials never appear in URLs, logs, or responses (INSTR-008).
- **Token:** Signed **JWT HS256 with 8-hour expiration and no refresh token** (binding Q-002); signed and validated with **PyJWT 2.15.1** using an explicit algorithms allow-list on decode — `algorithms=["HS256"]` is mandatory so algorithm-confusion attacks are impossible (Q-011). Tokens are issued only for valid credentials (CONF-001) and carry the user id, role, and `exp` claim.
- **Password verification:** bcrypt hashes compared with `bcrypt.checkpw` using the **bcrypt library directly — passlib is banned** because the passlib+bcrypt pairing is broken (binding Q-010, INSTR-008).
- **Email handling:** Addresses are normalized (trimmed, lowercased) before lookup and are unique in `users` (INSTR-006).
- **Failure posture:** Invalid credentials return a single generic message that never reveals whether the email exists; the rate limiter behaves uniformly regardless of account existence so throttling cannot be used for account enumeration (FUNC-001, CONF-003).
- **Sign-out:** Client-side deletion of the stored token; server-side invalidation is deliberately not implemented — the 8-hour TTL is the accepted mitigation (binding Q-008, FUNC-002).

### Session/token storage (mobile)

The session token is stored exclusively in **Android Keystore-backed encrypted storage** (`TokenStore`, CONF-002) — never plain text, never SharedPreferences-in-the-clear, never logged. Sign-out deletes the stored token so no protected screen remains reachable (FUNC-002). No token material is ever written to logcat.

### Authorization / RBAC model

Two fixed roles, enforced server-side on every route (CONF-011: access control per route is mandatory):

| Role | Capabilities |
|------|--------------|
| `employee` | `GET /api/me`; list own incidents (`GET /api/incidents`, optional status filter); create own incidents (`POST /api/incidents`); read details+history of **own** incidents only (`GET /api/incidents/{id}`). |
| `support` | Everything an authenticated token requires to traverse `GET /api/me`; plus `PATCH /api/incidents/{id}/status` — the status transition endpoint. No support mobile screen exists in v1; this capability is API-only (FUNC-007). |

- **Owner isolation (core confidentiality rule):** every incident query is scoped by `owner_id = current_user.id` at the repository layer (INSTR-001) — list, detail, and history alike. Requests for a missing or foreign incident return an indistinguishable safe response (404-shaped, no existence leak), so identifiers cannot be probed (FUNC-006).
- **Role enforcement:** the status endpoint is guarded by a `require_role("support")` dependency; an employee attempting it receives `403 Forbidden` (INSTR-004, proven by integration tests per INSTR-013).
- **Immutability:** employees have no endpoint that can modify owner or history; `incident_history` is append-only in the service layer (INSTR-005, FUNC-006).

### Input validation

- **Server-side (authoritative):** Pydantic v2 schemas validate every request body — title 5–100 chars, description 10–1,000 chars, category in {systems, equipment, access, other}, priority in {low, medium, high}, status values in the fixed set (FUNC-005, INSTR-011). Path/query parameters are typed and validated by FastAPI. Validation failures return field-level errors; the incident is never partially created.
- **Client-side (feedback):** the Compose form mirrors the same rules with live per-field validation and char counters so users get immediate errors next to the fields (INSTR-011).
- **Status transitions:** the linear transition matrix Pending → In progress → Resolved (Resolved terminal) is validated in the domain service before any write; backwards, skipped, and repeated transitions are rejected (INSTR-003).

### Injection defenses

- **SQL injection:** all database access goes through the SQLAlchemy 2.0.x ORM with bound parameters — no string-concatenated SQL anywhere (CONF-005, binding Q-003). Identifiers, emails, titles, and filters are never interpolated into queries.
- **XSS:** the API returns JSON only (no HTML rendering); kotlinx.serialization on the client treats API payloads as data, never markup. Compose renders text, not interpreted HTML.
- **Mass assignment:** creation endpoints accept explicit field sets only; owner and status are set by the server (owner = token subject, status = `pending`), never taken from client input (INSTR-002).

### Rate limiting and API hardening

- **Rate limiter:** in-process, in-memory counters **per-IP and per-email** on failed `POST /api/auth/login` attempts, implemented as FastAPI dependencies — no Redis/cache tier exists in the target plan (binding Q-004, CONF-003). Behavior is uniform for existing and nonexistent accounts. The single-process deployment makes in-memory state sufficient; a multi-process deployment would revisit this.
- **CORS:** the API serves a native Android client, not a browser; CORS is closed by default (no wildcard allow), with local development origins added only where a browser-based tool needs them.
- **Error hygiene:** unhandled exceptions return sanitized JSON errors — no stack traces, no SQL, no config (FUNC-008 applies to `/health`; the same discipline applies to error handlers globally).
- **HTTPS:** mandatory outside the local environment; cleartext permitted only for local development (CONF-004). The Android release configuration uses Android's default network security policy (cleartext blocked).
- **Android attack surface:** zero declared permissions, no push, no deep links, no offline cache, online only (CONF-007, CONF-009) — the smallest defensible surface for v1.

### Encryption

- **In transit:** HTTPS/TLS for all non-local client↔API traffic (CONF-004).
- **At rest:** passwords only as salted bcrypt hashes in `password_hash` (INSTR-008); JWTs are signed (integrity) rather than encrypted, and carry no sensitive claims beyond id/role; the mobile token at rest is protected by Android Keystore-backed encryption (CONF-002). No cardholder, health, or other regulated data exists in v1, so no field-level encryption scheme is required beyond the above.
- **Logging:** passwords and tokens are never logged, in either component (INSTR-008).

### Secrets handling

All secrets arrive via environment variables; `.env`, keystores, `key.properties`, and credentials are Git-excluded (CONF-006). Android release signing reads only `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` and **fails with a clear message when any is absent — a debug key is never substituted for release**. Debug and check builds require no production secrets. Details in `## Environment Strategy`.

### Test obligations (security regression net)

INSTR-013 requires pytest unit tests for business rules and integration tests specifically proving **ownership isolation** and **role protection of the status endpoint**, at ≥90% coverage of business logic — these tests are the executable proof of the rules above and gate the internal-test build.
