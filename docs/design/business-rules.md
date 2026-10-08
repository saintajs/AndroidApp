## Business Rules

Every rule carries a unique ID, a one-sentence statement, its extraction source (or `wizard (Constraints)` where it comes only from AGENTS.md), the enforcing layer, and notes. `docs/extractions/reference/rules.md` does not exist, so `docs/extractions/instructions.md` and the embedded rules in `functionalities.md`/`configurations.md` are the authoritative sources; wizard-level constraints from AGENTS.md are added per the design contract.

### Authentication & session

- **BR-001** — Both email and password are required for sign-in; a request missing either is rejected with field-level validation feedback.
  **Source:** FUNC-001. **Layer:** API validation (+ UI guard). **Notes:** Pydantic required-field validation; the Compose form mirrors it for immediate feedback.
- **BR-002** — A failed authentication attempt returns a single generic message that never reveals whether the email exists.
  **Source:** FUNC-001. **Layer:** domain service. **Notes:** Uniform response and uniform rate-limit behavior prevent account enumeration.
- **BR-003** — The sign-in button stays disabled while the authentication request is in flight, preventing duplicate submissions.
  **Source:** FUNC-001. **Layer:** UI guard. **Notes:** ViewModel submission lock (Pre-Design behavior).
- **BR-004** — Sessions are signed JWTs (HS256) with 8-hour expiration and no refresh token; tokens are issued only for valid credentials and validated with an explicit algorithms allow-list.
  **Source:** CONF-001; Q-002, Q-011. **Layer:** domain service. **Notes:** PyJWT 2.15.1; decode pins `algorithms=["HS256"]`.
- **BR-005** — Signing out deletes the locally stored token, and no protected screen remains accessible afterward.
  **Source:** FUNC-002; Q-008. **Layer:** UI guard (client). **Notes:** Client-side deletion + short TTL is the accepted mitigation; no server-side invalidation in v1.
- **BR-006** — The session token is stored only in Android Keystore-backed storage, never as plain text.
  **Source:** CONF-002. **Layer:** client security module. **Notes:** `TokenStore`; token never logged.
- **BR-007** — Failed sign-in attempts are rate-limited per IP and per email with behavior uniform regardless of account existence.
  **Source:** CONF-003; Q-004. **Layer:** API validation (FastAPI dependency). **Notes:** In-process in-memory counters; no Redis tier exists in the target plan.
- **BR-008** — Email addresses are normalized (trimmed and lowercased) before authentication and must be unique across users.
  **Source:** INSTR-006. **Layer:** DB constraint (unique) + domain service (normalization). **Notes:** Normalization applies at both seed and login.

### Incident ownership & lifecycle

- **BR-009** — An employee can view only incidents they own; list, detail, and history queries are all owner-scoped.
  **Source:** INSTR-001, FUNC-003. **Layer:** domain service (repository always filters by `owner_id`). **Notes:** The core confidentiality rule; acceptance criteria AC-03.
- **BR-010** — Requests for a missing or foreign incident return a safe response that does not leak the incident's existence or data.
  **Source:** FUNC-006, INSTR-001. **Layer:** domain service + API validation. **Notes:** Indistinguishable 404-shaped response for both cases.
- **BR-011** — Every incident created by an employee starts in Pending status; no other initial status is permitted.
  **Source:** INSTR-002, FUNC-005. **Layer:** DB constraint (default/CHECK) + domain service. **Notes:** Status is never taken from client input.
- **BR-012** — An incident belongs to exactly one owner (the authenticated creator), and employees cannot change the owner or alter history.
  **Source:** INSTR-001, FUNC-006. **Layer:** DB constraint (FK, server-set owner) + domain service. **Notes:** No endpoint exposes owner mutation.

### Incident field validation

- **BR-013** — The incident title is required and must be 5–100 characters.
  **Source:** FUNC-005. **Layer:** API validation (+ UI guard). **Notes:** Enforced on both client and server; field-level errors.
- **BR-014** — The incident description is required and must be 10–1,000 characters.
  **Source:** FUNC-005. **Layer:** API validation (+ UI guard). **Notes:** Char counter in the form; server-side authoritative.
- **BR-015** — The incident category must be one of Systems, Equipment, Access, Other.
  **Source:** FUNC-005. **Layer:** DB constraint (CHECK) + API validation. **Notes:** Stored as `systems`/`equipment`/`access`/`other`.
- **BR-016** — The incident priority must be Low, Medium, or High.
  **Source:** FUNC-005. **Layer:** DB constraint (CHECK) + API validation. **Notes:** Stored as `low`/`medium`/`high`.
- **BR-017** — Incident statuses are limited to the fixed set Pending, In progress, Resolved.
  **Source:** FUNC-004. **Layer:** DB constraint (CHECK) + domain service. **Notes:** Filter options are All, Pending, In progress, Resolved.
- **BR-018** — All form data is validated on both the Android client and the backend, and a failed validation never creates the incident; errors appear next to the corresponding fields.
  **Source:** INSTR-011. **Layer:** API validation (+ UI guard). **Notes:** Server is authoritative; client gives immediate feedback.

### Incident listing & detail

- **BR-019** — The incident list shows only the authenticated employee's incidents, ordered newest first by creation date, with each item showing title, category, priority, status, and creation date.
  **Source:** FUNC-003. **Layer:** domain service (query order) + UI guard (rendering). **Notes:** Status filter applies on top of owner isolation.
- **BR-020** — The list returns all owned incidents matching the filter with no pagination in v1.
  **Source:** Q-007 (binding clarification), FUNC-003. **Layer:** domain service. **Notes:** Deliberate v1 API shape; revisit if volume grows.
- **BR-021** — The incident detail screen shows the identifier, title, description, category, priority, status badge, creation date, and a chronological status-history timeline.
  **Source:** FUNC-006. **Layer:** UI guard (rendering over owner-scoped API). **Notes:** History timeline is read-only for employees.

### Status transitions (support)

- **BR-022** — Only users with the support role can update an incident's status; an employee attempting the endpoint receives a forbidden response.
  **Source:** INSTR-004, FUNC-007. **Layer:** API validation (`require_role` dependency). **Notes:** Proven by mandatory integration tests (INSTR-013).
- **BR-023** — The only valid status transitions are Pending → In progress and In progress → Resolved; backwards, skipped, and repeated transitions are rejected.
  **Source:** INSTR-003, FUNC-007. **Layer:** domain service (transition matrix) + API validation. **Notes:** Validated before any write.
- **BR-024** — A Resolved incident cannot return to a previous status in v1.
  **Source:** INSTR-003, FUNC-007. **Layer:** domain service (Resolved is terminal). **Notes:** Deliberate v1 lifecycle; reopening would be a future version.
- **BR-025** — Every accepted status change records an immutable history entry with previous status, new status, date, and actor; initial creation may leave previous status empty.
  **Source:** INSTR-005, FUNC-007. **Layer:** domain service + DB constraint (append-only usage of `incident_history`). **Notes:** AC-07; timeline is a trustworthy audit trail.

### Security & data protection

- **BR-026** — Passwords are stored only as resistant salted hashes (`password_hash`) and never logged, returned, or stored in plain text.
  **Source:** INSTR-008. **Layer:** domain service (bcrypt, direct — no passlib per Q-010). **Notes:** OWASP Top 10 / OWASP Mobile obligation.
- **BR-027** — All database access uses parameterized queries or the ORM; user-supplied values are never concatenated into SQL strings.
  **Source:** CONF-005. **Layer:** domain service (SQLAlchemy 2.0.x ORM everywhere). **Notes:** SQL-injection defense.
- **BR-028** — All client-server communication outside the local environment uses HTTPS; cleartext is permitted only locally.
  **Source:** CONF-004. **Layer:** API/network configuration (Android cleartext policy + deployment). **Notes:** Protects credentials and tokens in transit.
- **BR-029** — All application secrets are supplied through environment variables, and keystores, `.env` files, `key.properties`, and credentials are excluded from source control.
  **Source:** CONF-006. **Layer:** environment strategy + `.gitignore` (process constraint). **Notes:** See `## Environment Strategy`.
- **BR-030** — A release Android build without all four signing variables (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) fails with a clear message and never substitutes a debug signing key.
  **Source:** CONF-006 (AC-11); wizard (Constraints). **Layer:** build configuration (Gradle release signing). **Notes:** Debug/check builds need no production secrets.
- **BR-031** — The Android app declares no permissions and includes no push notifications, deep links, or offline operation.
  **Source:** CONF-007. **Layer:** AndroidManifest (build configuration). **Notes:** Minimal attack surface; v1 scope boundary.

### Health, seed & quality

- **BR-032** — The public `GET /health` endpoint reports service liveness without authentication and without exposing stack traces or sensitive information.
  **Source:** FUNC-008. **Layer:** API validation (sanitized handler). **Notes:** Used by run tooling before exercising the client.
- **BR-033** — Demo seed passwords come exclusively from `DEMO_EMPLOYEE_PASSWORD` and `DEMO_SUPPORT_PASSWORD`; a missing variable fails the seed with a clear message, no default password is embedded in source, the seed is idempotent, and it never runs automatically in production.
  **Source:** INSTR-010, FUNC-009. **Layer:** domain service (seed module) + environment strategy. **Notes:** Per Q-001 the internal-test environment runs the seed manually with configured emails.
- **BR-034** — Backend unit and integration tests cover authentication, validation, ownership isolation, role protection of the status endpoint, and API error handling, targeting ≥90% coverage of business logic.
  **Source:** INSTR-013; wizard (thresholds: 90). **Layer:** process constraint (pytest gate). **Notes:** Integration tests take PostgreSQL from `TEST_DATABASE_URL`.
- **BR-035** — The Android app is online only, in Spanish, portrait phone-only; network errors never crash the app and always provide a retry path, with a target API response time under two seconds.
  **Source:** CONF-009. **Layer:** UI guard + API (performance budget). **Notes:** Loading/empty/error states on every screen.

### Presentation & build constraints (wizard)

- **BR-036** — The UI provides readable labels, sufficient contrast, adequate touch targets, and statuses differentiated with both text and color (never color alone), using the defined palette (dark green `#1E3A2A`, yellow `#EFC64E`, light background `#F5F7F5`), 8-pixel corners, spacing in multiples of eight, compact list cards, and no side navigation.
  **Source:** INSTR-012. **Layer:** UI guard (design system + `StatusBadge`). **Notes:** WCAG-AA-aligned per wizard `accessibility`; Pre-Design draft implements it.
- **BR-037** — The Android build uses native Kotlin + Jetpack Compose with application id `com.nexti.minisoporte`, `android.useAndroidX=true`, a pinned installed build-tools version, aligned Java/Kotlin JVM targets, and a monotonically increasing `versionCode` with a human-readable `versionName`.
  **Source:** wizard (Constraints) — Android Construction Contract. **Layer:** build configuration (`app/build.gradle.kts`, `gradle.properties`, version catalog). **Notes:** minSdk 26 per Q-006.
- **BR-038** — The product is distributed for internal testing only: no deployment to production, no publication to Google Play, no cloud infrastructure or external resources, and no store upload is ever triggered by a build.
  **Source:** wizard (Constraints) — deployment notes. **Layer:** process constraint. **Notes:** Store submission remains a deliberate external action; AAB building must not upload.
- **BR-039** — Never commit secrets, credentials, keystores, or `.env` files to source control.
  **Source:** wizard (Constraints) — deployment notes; CONF-006. **Layer:** process constraint (`.gitignore` + review). **Notes:** Overlaps BR-029; restated as a wizard-level invariant.
- **BR-040** — The Android app must not be replaced with another mobile framework, and the run contract declares the mobile app as build-only (no serve/port/health) alongside the HTTP backend entry.
  **Source:** wizard (Constraints) — Android Construction Contract; CONF-010. **Layer:** build configuration + run contract. **Notes:** Local validation via `./gradlew check :app:assembleDebug` producing `app/build/outputs/apk/debug/app-debug.apk`; release via `assembleRelease`.

### Summary

| ID | Category | Statement | Layer |
|----|----------|-----------|-------|
| BR-001 | Validation | Email and password both required at sign-in | API validation + UI guard |
| BR-002 | Security | Generic sign-in failure, no account-existence leak | Domain service |
| BR-003 | Process | Sign-in button locked while request in flight | UI guard |
| BR-004 | Session | JWT HS256, 8 h, no refresh, allow-listed decode | Domain service |
| BR-005 | Session | Sign-out deletes local token; protected screens locked | UI guard |
| BR-006 | Security | Token stored only in Keystore-backed storage | Client security module |
| BR-007 | Security | Rate-limit failed sign-ins per IP and per email, uniformly | API dependency |
| BR-008 | Data integrity | Email normalized and unique | DB constraint + domain service |
| BR-009 | Authorization | Owner-only visibility on list, detail, history | Domain service |
| BR-010 | Authorization | Safe non-leaking response for missing/foreign incidents | Domain service + API validation |
| BR-011 | Lifecycle | New incidents always start Pending | DB constraint + domain service |
| BR-012 | Data integrity | Single owner; owner and history immutable for employees | DB constraint + domain service |
| BR-013 | Validation | Title required, 5–100 chars | API validation + UI guard |
| BR-014 | Validation | Description required, 10–1,000 chars | API validation + UI guard |
| BR-015 | Validation | Category in fixed 4-value set | DB constraint + API validation |
| BR-016 | Validation | Priority in fixed 3-value set | DB constraint + API validation |
| BR-017 | Validation | Statuses limited to pending/in_progress/resolved | DB constraint + domain service |
| BR-018 | Validation | Client+server validation with field-level errors | API validation + UI guard |
| BR-019 | Presentation | Own incidents, newest first, five visible fields | Domain service + UI guard |
| BR-020 | API shape | No pagination in v1 | Domain service |
| BR-021 | Presentation | Detail shows full data + history timeline | UI guard |
| BR-022 | Authorization | Only support role updates status | API dependency |
| BR-023 | Lifecycle | Linear transitions only (no skip/back/repeat) | Domain service |
| BR-024 | Lifecycle | Resolved is terminal in v1 | Domain service |
| BR-025 | Audit | Immutable history entry per accepted transition | Domain service + DB |
| BR-026 | Compliance | Passwords only as salted bcrypt hashes, never logged | Domain service |
| BR-027 | Security | Parameterized/ORM access only | Domain service |
| BR-028 | Security | HTTPS outside local environment | Network configuration |
| BR-029 | Security | Secrets via env vars; secret files Git-excluded | Environment strategy |
| BR-030 | Build | Release build fails clearly without signing variables | Build configuration |
| BR-031 | Configuration | Zero Android permissions; no push/deep links/offline | AndroidManifest |
| BR-032 | Operations | Public safe `/health` endpoint | API validation |
| BR-033 | Compliance | Seed env-only passwords, idempotent, non-production | Domain service (seed) |
| BR-034 | Quality | ≥90% business-logic test coverage incl. authz | Process constraint (pytest) |
| BR-035 | UX/operations | Online-only Spanish portrait app; retry on network errors | UI guard + API budget |
| BR-036 | Accessibility | Contrast, labels, touch targets; status text+color, defined palette | UI guard (design system) |
| BR-037 | Build | Native Kotlin/Compose, AndroidX, aligned JVM targets, monotonic versionCode | Build configuration |
| BR-038 | Deployment | Internal testing only; no production/cloud/Play publication | Process constraint |
| BR-039 | Security | Never commit secrets/keystores/.env | Process constraint |
| BR-040 | Build | Kotlin/Compose fixed; mobile run entry is build-only | Build configuration |
