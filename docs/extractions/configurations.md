# Configuration & Infrastructure

## CONF-001 — Signed token sessions with expiration and role-based authorization

 *(Category: security)*

Authentication must use signed tokens with expiration and signature validation, and the backend must enforce role-based authorization (employee versus support) on protected endpoints. Successful authentication returns this token-based session, and a token is issued only for valid credentials. Without signed, expiring tokens the API would be open to forged or never-expiring sessions, undermining owner isolation and the role protection of the status endpoint.

**Technical detail:** Signed tokens with expiration and signature validation; token issued only for valid credentials; roles: employee, support

## CONF-002 — Android Keystore-backed token storage

 *(Category: security)*

The Android application must store the session token using Android Keystore-backed storage, never as plain text. This protects the session token from extraction by other apps or from a compromised device filesystem, keeping the session valid only for the legitimate holder of the device. Without it, a plain-text token file would let any local attacker hijack the employee's session; signing out must delete this stored token.

**Technical detail:** Token stored via Android Keystore-backed storage; deleted on sign out; never plain text

## CONF-003 — Rate limiting on sign-in attempts

 *(Category: security)*

The sign-in endpoint must enforce rate limiting on failed authentication attempts to slow down brute-force and credential-stuffing attacks against employee accounts. The limiter applies to POST /api/auth/login and must not leak whether an email exists through different throttling behavior. Without it, attackers could try unlimited password guesses against known employee email addresses.

**Technical detail:** Rate limiting applied to POST /api/auth/login sign-in attempts

## CONF-004 — HTTPS required outside the local environment

 *(Category: security)*

All client-server communication outside the local development environment must use HTTPS so that credentials, tokens, and incident data are encrypted in transit. Local development may run plain HTTP for convenience, but the deployed internal-test setup must reject cleartext traffic. Without enforced HTTPS, tokens and passwords would be exposed to network interception.

**Technical detail:** HTTPS mandatory outside local environment; cleartext allowed only locally

## CONF-005 — Parameterized queries or ORM to prevent injection

 *(Category: security)*

All database access must use parameterized queries or an ORM so that user-supplied values (emails, titles, filters, identifiers) are never concatenated into SQL strings. This prevents SQL injection against the PostgreSQL database that stores users, incidents, and history. Without it, a crafted input field could read or modify any user's data, directly defeating owner isolation.

**Technical detail:** Parameterized queries or ORM for all database access; no string-concatenated SQL

## CONF-006 — Secrets supplied via environment variables and excluded from Git

 *(Category: security)*

All application secrets must be supplied through environment variables, and keystores, .env files, key.properties, and credentials must be excluded from source control. Release builds are prepared for externally supplied signing secrets without containing any real secrets, and a release build without signing variables must fail clearly rather than substitute a debug signing key. Violation would leak credentials into the repository and could produce an insecurely signed release APK.

**Technical detail:** Secrets via environment variables; Git excludes keystores, .env, key.properties, credentials; release build fails clearly without signing variables (AC-11)

## CONF-007 — No Android permissions and no push notifications

 *(Category: configuration)*

The Android application must declare no Android permissions in this version and must not include push notifications, deep links, or offline operation. Only explicitly selected Android permissions may be enabled, and this version requires none, which minimizes the attack surface and simplifies the internal-test approval. Violation would add device capabilities the customer explicitly excluded and could fail the build review.

**Technical detail:** Android permissions: none; push notifications disabled; deep links out of scope; offline operation disabled

## CONF-008 — Reproducible database migrations on PostgreSQL

 *(Category: infrastructure)*

The database layer must use reproducible migrations so that any environment can rebuild an identical schema from version control. The data model comprises User (UUID id, unique normalized email, password_hash, role, active, created_at), Incident (UUID id, owner_id reference to User, title, description, category, priority, status, created_at, updated_at), and IncidentHistory (UUID id, incident_id, optional previous_status, new_status, changed_by, changed_at). Per the Target system selections the engine is PostgreSQL 16.15 with foreign keys, constraints, and indexes. Without reproducible migrations, environments would drift and seeds or tests would fail unpredictably.

**Technical detail:** PostgreSQL 16.15; tables User, Incident, IncidentHistory with UUID ids, foreign keys (owner_id, incident_id, changed_by), unique email, status values pending/in_progress/resolved, category systems/equipment/access/other, priority low/medium/high

## CONF-009 — Android phone in portrait, Spanish UI, online only

 *(Category: configuration)*

The mobile client targets Android phones in portrait orientation only, with the user interface language in Spanish, and operates online only; no offline synchronization is required or provided. Network errors must not crash the application and must always provide a retry path, and the target API response time is under two seconds under normal conditions. Violation would break the declared form factor, confuse Spanish-speaking employees, or produce a fragile app on flaky networks.

**Technical detail:** Form factor: phone portrait; UI language: Spanish; online only; no crash on network errors with retry; API response under 2 seconds

## CONF-010 — Internal-testing Android build and run.apps contract

 *(Category: deployment)*

The project must deliver an installable debug APK for internal validation built from the selected mobile toolchain (Kotlin/Jetpack Compose, application id com.nexti.minisoporte), plus a run.apps contract containing a build-only mobile application and an HTTP backend, and a release configuration prepared for externally supplied signing secrets without real ones. Running the declared tests, lint checks, and Android build command must produce the APK without requiring production secrets. Distribution is internal testing only; Google Play publication is out of scope. Without this packaging the reviewers cannot install or run the product.

**Technical detail:** Internal distribution; debug APK; run.apps contract (build-only mobile app + HTTP backend); release config for external signing secrets; application id com.nexti.minisoporte; Kotlin Android

## CONF-011 — REST API surface built on FastAPI

 *(Category: infrastructure)*

The backend exposes the proposed REST surface: public POST /api/auth/login to authenticate, authenticated GET /api/me to return the current user, employee-accessible GET /api/incidents (list with optional status filter) and POST /api/incidents (create), owner-restricted GET /api/incidents/{id} (details and history), support-only PATCH /api/incidents/{id}/status, and operational GET /health. Per the Target system selections the framework is FastAPI 0.123.10 on Python 3.10 with a layered, clean-code structure using dependency injection and strict typing. Target system is the only source of truth for implementation technologies; this document must not be used to add technologies the user did not select. Access control per route is mandatory; a wrong access level on any route would break owner isolation or role protection.

**Technical detail:** FastAPI 0.123.10, Python 3.10 (CPython), layered architecture, dependency injection, strict typing; routes: POST /api/auth/login (public), GET /api/me (authenticated), GET/POST /api/incidents (employee), GET /api/incidents/{id} (owner), PATCH /api/incidents/{id}/status (support), GET /health (operational)
