# EPICS — Story Registry

## EPIC-001 — Incident Management Service  _(Status: locked (approved))_

The backend domain of MiniSoporte: the FastAPI service and PostgreSQL 16.15 data platform on which AndersonMobile employees' incident reports live. It matters because every employee-visible capability (sign-in, reporting, review) is delivered by this service, and its owner-isolation and role rules are the product's confidentiality promise.

- Delivers the layered API foundation, schema and health probe (CONF-005/008/011).
- Delivers first-party authentication with JWT sessions and rate limiting.
- Delivers the owner-scoped incident REST surface (list, filter, create, detail+history).
- Delivers the support-only status lifecycle with an immutable audit trail.

### FEAT-001 — API Foundation & Data Model  _(Status: locked (approved))_

Establishes the greenfield backend in one change: the uv workspace, the layered FastAPI skeleton (api -> domain -> db with a core leaf), env-only configuration, the SQLAlchemy ORM models and the Alembic 0001 migration creating users, incidents and incident_history, the sanitized error handler, and the public GET /health probe.

- Reproducible schema in any environment from migrations only (CONF-008).
- Secrets read exclusively from environment variables (CONF-006).
- Sanitized errors and a safe liveness endpoint (FUNC-008, INSTR-009).

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-001 | Backend foundation: workspace, layered FastAPI skeleton, PostgreSQL schema and /health | locked (approved) | api, PostgreSQL 16.15 · GET /health · users, incidents… · Development environment initial… |

### FEAT-002 — Authentication & Session API  _(Status: locked (approved))_

First-party email+password authentication issuing HS256 8-hour JWTs, with a profile endpoint and in-memory per-IP/per-email rate limiting on failed sign-ins. It is the gate to every employee capability and the enforcement point for no-account-enumeration and password-hash rules.

- POST /api/auth/login with normalized email, bcrypt verification and generic failure (FUNC-001).
- GET /api/me profile for the signed-in user (CONF-011).
- Uniform rate limiting that cannot probe for accounts (CONF-003, INSTR-007).

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-002 | Sign-in, session JWT and profile API with rate-limited generic failures | locked (approved) | api · POST /api/auth/login, GET /api/me · users · Employee authentication flow, Session l… |

### FEAT-003 — Incident API & Lifecycle  _(Status: locked (approved))_

The incident REST surface: owner-scoped listing with status filter, creation that always starts Pending, owner-only detail with the chronological history, and the support-only status transition endpoint that records an immutable history entry per accepted change. Together these realize FUNC-003..007 and the product's core confidentiality and lifecycle rules.

- Owner isolation enforced in the repository on every query (INSTR-001).
- Client+server validation with field-level errors; mass-assignment defense (INSTR-002, INSTR-011).
- Linear status transitions with Resolved terminal and append-only history (INSTR-003..005).

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-003 | Incident REST API: owner-scoped list + status filter, create-as-Pending, detail with history | locked (approved) | api · GET /api/incidents, POST /api/incidents… · incidents, incident_history · Incident c… |
| US-004 | Support-only status transition API with linear matrix and immutable history | locked (approved) | api · PATCH /api/incidents/{id}/status · incidents, incident_history · Support status upd… |

## EPIC-002 — Employee Mobile Experience  _(Status: locked (approved))_

The native Android product surface for AndersonMobile employees, delivered mock-first: a Kotlin + Jetpack Compose app (com.nexti.minisoporte, minSdk 26, Spanish, portrait) that runs the complete employee journey — sign in, report incidents, review only their own reports, sign out — entirely on in-memory fake repositories with deterministic seeded data, fake admin/admin authentication and an in-memory TokenStore. The mock MVP declares ZERO permissions, makes no network calls and requires no backend; this is the only user-facing component of MiniSoporte v1 and it renders the approved Pre-Design screens without any server dependency. Real-backend integration — Retrofit/OkHttp, DTOs, mappers, AuthInterceptor, the INTERNET permission, JWT sessions, Keystore-backed token storage and every real FastAPI endpoint — is the single deferred non-blocking follow-up story US-012, on which no mock story depends.

### FEAT-004 — Mobile App Foundation & Theming  _(Status: locked (approved))_

Builds the Android project shell in one mock-first change: Gradle Kotlin DSL with the pinned version catalog, single-Activity Compose host, typed navigation routes S-01..S-04, the Material 3 theme mapping the approved design tokens, the IncidentRepository seam with the in-memory FakeIncidentRepository seeded with mock users/incidents/status history, fake authentication accepting username admin / password admin, and the TokenStore interface with its in-memory fake. The manifest declares ZERO permissions. Real REST integration (Retrofit/OkHttp, DTOs, mappers, AuthInterceptor), the android.permission.INTERNET declaration and the Android Keystore-backed TokenStore implementation are delivered as the non-blocking follow-up story US-012.

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-005 | Android mock-first foundation: Gradle/Compose project, Material 3 theme, navigation S-01..S-04, repository seam with in-memory fakes, fake admin/admin authentication and seeded mock data | locked (approved) | mobile-app |
| US-012 | Android real-backend integration: Retrofit/OkHttp client, DTOs, mappers, AuthInterceptor, INTERNET permission, real authentication and Keystore-backed TokenStore behind the repository seam | locked (approved) | mobile-app · POST /api/auth/login, GET /api/me… · Employee authentication flow, Session l… |

### FEAT-005 — Sign In & Session Handling  _(Status: locked (approved))_

The S-01 entry screen and the client-side session machinery, implemented mock-first: required-field validation with Spanish per-field errors, the demo hint box showing admin/admin, generic failure messaging with a submit lock, and sign-out (delivered with S-02). Authentication runs exclusively against the in-memory FakeAuthService from US-005 accepting exactly admin/admin, and the fake session token lives only in the in-memory TokenStore fake — no POST /api/auth/login, no HTTP 401/422/429 handling, no JWT, no Android Keystore and no network. Real authentication (login endpoint, rate limits, expired-token recovery, Keystore-backed persistence) is deferred to US-012. - Spanish form with required-field errors (BR-001) validated client-side only in the mock MVP. - Generic error that never hints whether an account exists, even against the fake (INSTR-007, BR-002). - No 401 handling exists in the mock MVP — the expired-token recovery path arrives with US-012.

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-006 | S-01 Iniciar sesión: validated mock sign-in with demo credentials (admin/admin), submit lock and generic failure | locked (approved) | mobile-app · S-01 — Iniciar sesión · Employee authentication flow, Session lifecycle (sig… |

### FEAT-006 — My Incidents: List, Filter & Sign Out  _(Status: locked (approved))_

The S-02 home screen after sign-in, implemented mock-first on the in-memory FakeIncidentRepository seeded by US-005: the signed-in mock user's own incidents newest-first as compact cards, status filter chips with live counts, loading/empty/error+retry states, the accent FAB to create, and the Salir sign-out that clears only the in-memory mock session and returns to S-01. No GET /api/incidents, no HTTP responses, no JWT and no Android Keystore — the real REST list contract is delivered by US-012. - Owner-scoped rendering enforced by the fake repository under every filter (FUNC-003, INSTR-001). - Filter chips Todas/Pendiente/En progreso/Resuelta with live count badges computed over the seeded list (FUNC-004). - Sign-out clears the in-memory mock session token; protected screens unreachable afterwards (FUNC-002, BR-005).

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-007 | S-02 Mis incidencias: owned list with status filter chips, states and sign-out | locked (approved) | mobile-app · S-02 — Mis incidencias · Incident review flow (list, filter, details), Sessi… |

### FEAT-007 — Create & Review Incidents  _(Status: locked (approved))_

The two drill-down screens of the review experience, implemented mock-first against the deterministic in-memory FakeIncidentRepository: S-03 Nueva incidencia with live per-field validation, character counters and a duplicate-submission lock creating incidents in memory (always Pendiente, with the initial history entry), and S-04 Detalle de incidencia with the full incident card plus the newest-first status-history timeline and a safe no-access state for foreign identifiers. No POST /api/incidents, no GET /api/incidents/{id}, no HTTP responses, no JWT and no Android Keystore — the real API integration is deferred to US-012. - Creation always yields a Pendiente mock incident with local field-level validation and deterministic results (FUNC-005, INSTR-002). - Detail shows identifier, full data and the read-only newest-first history timeline from the seeded fake (FUNC-006, BR-021). - Missing/foreign ids render the safe no-access state — the UI never leaks another mock user's incident (BR-010).

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-008 | S-03 Nueva incidencia: validated creation form with counters and duplicate-submission lock | locked (approved) | mobile-app · S-03 — Nueva incidencia · Incident creation flow |
| US-009 | S-04 Detalle de incidencia: detail card and newest-first mock status-history timeline with safe no-access state | locked (approved) | mobile-app · S-04 — Detalle de incidencia · Incident review flow (list, filter, details) |

## EPIC-003 — Operations, Seed & Internal Release  _(Status: locked (approved))_

The operational backbone that makes the product reproducible and deliverable for internal testing: the idempotent demo seed provisioning demo accounts and incidents outside production, the pytest quality gate proving authentication, ownership isolation and role protection at >=90% business-logic coverage, and the mock-first Android delivery contract — an installable, unsigned debug APK (app/build/outputs/apk/debug/app-debug.apk) built from apps/mobile with ./gradlew check :app:assembleDebug and copied manually to reviewer devices. The current MVP requires no release signing: no keystore files, no key.properties, no ANDROID_KEY* environment variables and no Google Play publication — release signing and store submission remain deliberate external actions outside the mock MVP.

### FEAT-008 — Demo Seed, Environment & Quality Gate  _(Status: locked (approved))_

Implements the development environment initialization flow: the idempotent demo seed module (one employee, one support agent, three incidents spanning every status with consistent history) that reads passwords only from environment variables and never runs automatically in production, plus the pytest-cov gate that mechanically enforces the >=90% business-logic coverage threshold across the backend.

- Seed is idempotent, env-only, non-production, and inserts rows only (FUNC-009, INSTR-010).
- Negative-config tests prove missing-variable failures with no default passwords (BR-033).
- Coverage gate: pytest --cov=app --cov-fail-under=90 on business-logic packages (INSTR-013).

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-010 | Idempotent demo seed with env-only passwords and the >=90% business-logic coverage gate | locked (approved) | api · users, incidents… · Development environment initialization |

### FEAT-009 — Installable Internal-Test Debug APK (No Release Signing)  _(Status: locked (approved))_

Completes the mock-first distribution story for internal testing: the build contract that produces the installable app/build/outputs/apk/debug/app-debug.apk from apps/mobile via ./gradlew check :app:assembleDebug with zero signing secrets, a monotonic versionCode with a human-readable versionName, and a build-only run entry. There is no release signing configuration in the mock MVP — no keystore files, no key.properties, no ANDROID_KEY* variables and no signed release APK — and no publication of any kind. - Installable app-debug.apk for manual internal distribution from a clean machine with zero secrets (CONF-010, BR-037). - check/assembleDebug remain the only required build tasks; no production secrets are ever needed. - No publication: internal testing only; store submission and release signing stay deliberate external actions (BR-038).

| Story | Title | Status | Implements |
|-------|-------|--------|------------|
| US-011 | Installable debug APK for internal testing: mock-first build contract without signing secrets | locked (approved) | mobile-app |
