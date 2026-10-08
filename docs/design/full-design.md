## Overview

MiniSoporte_copy is a greenfield internal incident-reporting product for AndersonMobile, built as a native Android client (Kotlin + Jetpack Compose, application id `com.nexti.minisoporte`, package `apps/mobile`) backed by a FastAPI 0.123.10 / Python 3.10 REST API and a PostgreSQL 16.15 database. Employees sign in with email and password, report internal incidents (systems, equipment, access, or other), and see ONLY the incidents they own — filterable by status — each with a detail view and an immutable chronological status-history timeline. Support agents never touch the mobile app in v1: they advance incident status exclusively through a role-protected API endpoint (`PATCH /api/incidents/{id}/status`), and every accepted transition is recorded as a history entry. Distribution is internal testing only: no cloud infrastructure, no CI/CD pipeline, no Google Play publication.

### Actors and Goals

| Actor | Role / Access | Goals |
|-------|---------------|-------|
| **Employee** | Android app user, `employee` role | Sign in securely (JWT session stored in Android Keystore-backed storage); create a new incident with validated title, description, category, and priority (always starting in Pending); view and filter ONLY their own incidents (All / Pending / In progress / Resolved, newest first); open incident details with a status-history timeline; sign out from the list screen. |
| **Support agent** | Backend role, `support` role — **no mobile screen in v1** | Update incident status through the support-only API endpoint, following the linear lifecycle Pending → In progress → Resolved (Resolved is terminal in v1); each change records previous status, new status, date, and actor. Employees attempting the same endpoint receive a forbidden response. |
| **Operations tooling** | Unauthenticated HTTP client | Verify the backend is up via the public `GET /health` endpoint, which reports health without exposing stack traces, configuration, or internal details; used before pointing the Android client at the service. |
| **Development tooling** | Maintain engineers / internal-test setup | Run an idempotent demo seed that creates one employee (`empleado@minisoporte.test`), one support agent (email from configuration), and three incidents covering all three statuses with consistent history — only in non-production environments, with passwords read exclusively from `DEMO_EMPLOYEE_PASSWORD` / `DEMO_SUPPORT_PASSWORD` (missing variables fail the seed; no default password is ever embedded in source). Per clarification Q-001, the internal-test environment also runs this seed manually with configured emails, since v1 has no registration or user-management flow. |

### Business Context

- **Customer and use:** AndersonMobile employees use MiniSoporte_copy to report internal problems with systems, equipment, and access, and to follow each report's progress through its status history. This is a first version (v1) of a greenfield product.
- **Distribution:** Internal testing only. The deliverable is an installable debug APK (plus a release configuration prepared for externally supplied signing secrets); no Google Play submission, no store listing, no cloud infrastructure.
- **Connectivity:** Online only. There is no offline mode, no synchronization, and no caching layer; every screen talks to the REST API. Network errors must never crash the app and must always offer a retry path; the target API response time is under two seconds under normal conditions.
- **Form factor and language:** Android phones in portrait orientation only; the entire UI is in Spanish (sign-in "Iniciar sesión", list "Mis incidencias", form "Nueva incidencia", detail "Detalle"). Per INSTR-012, statuses are differentiated with text and color — never color alone — on the palette of dark green `#1E3A2A`, yellow accent `#EFC64E`, and light background `#F5F7F5`, with 8-pixel corners, spacing in multiples of eight, compact list cards, and no side navigation.
- **Security posture:** OWASP Mobile and OWASP Top 10 standards apply; JWT (HS256, 8-hour expiration, no refresh) sessions, bcrypt-hashed passwords, HTTPS outside local development, and owner isolation on every incident query are non-negotiable foundations of the product's confidentiality promise.

### In Scope

1. **Employee sign in / sign out** — email + password against `POST /api/auth/login`; signed 8-hour JWT stored in Keystore-backed storage; generic failure message that never reveals whether an email exists; sign-out deletes the local token (client-side deletion plus short TTL accepted per Q-008).
2. **My incidents list** — only the authenticated employee's own incidents, newest first, each showing title, category, priority, status badge, and creation date; loading, empty, error (with retry), and content states; Sign out and New-incident actions.
3. **Status filter** — chips for All / Pending / In progress / Resolved applied on top of owner isolation; fixed status set `pending`, `in_progress`, `resolved`; no pagination in v1 (Q-007).
4. **Create incident** — Spanish form S-03 with title (5–100 chars), description (10–1,000 chars), category (Systems, Equipment, Access, Other), priority (Low, Medium, High); validation on both client and server with field-level errors; duplicate submission prevented; every new incident starts Pending.
5. **Incident details + status history** — owner-restricted `GET /api/incidents/{id}`; detail card plus a chronological status-history timeline; safe (non-leaking) response for missing or foreign incidents; history is immutable for employees.
6. **Support status updates (API-only)** — `PATCH /api/incidents/{id}/status`, support role only, linear transitions with Resolved terminal; every accepted change writes an immutable history entry; no support mobile screen; covered by API tests.
7. **Session security and API hardening** — JWT HS256 8-hour tokens (PyJWT 2.15.1, per Q-011), bcrypt password hashing used directly (Q-010), in-process in-memory rate limiting on failed sign-ins (Q-004), SQLAlchemy 2.0.x ORM + Alembic migrations (Q-003), HTTPS outside local environment, no Android permissions, no push notifications, no deep links.
8. **Operational and development tooling** — public `GET /health`; idempotent, non-production demo seed driven by environment secrets; debug APK build (`./gradlew check :app:assembleDebug`) and release configuration that fails clearly without signing variables.
9. **Testing** — pytest unit and integration tests covering authentication, validation, ownership isolation, role protection, and API error handling, targeting ≥90% coverage of business logic (INSTR-013).

### Out of Scope

The following are explicitly excluded from v1 (per the extractions and wizard constraints):

- **Camera access** and **file attachments** on incidents.
- **Location** services of any kind.
- **Biometric** authentication.
- **Push notifications** and deep links.
- **Offline operation** — the app is online only; no cache-first or sync behavior.
- **Mobile administration panel** — no admin UI on Android.
- **Support-agent mobile screen** — status updates are API-only in v1.
- **Google Play publication** — internal testing distribution only; store submission is a deliberate external action not part of this project.
- **Pagination** on the incident list (Q-007: all owned incidents are returned, newest first).
- **Server-side token invalidation** on sign-out (Q-008: local deletion + short TTL is the accepted mitigation).
- **Registration / in-app user-management flows** — accounts are provisioned by the seed run manually in the internal-test environment (Q-001); there is no sign-up screen or user admin UI.
- **Cloud infrastructure, CI/CD pipelines, and external resources** — forbidden by project constraints; builds and verification are local only.

### Approved UI Starting Point

The user-approved Pre-Design draft (`docs/design/pre_design_mobile.html`, phone mode, Spanish, all four screens wired per `pre_design_dynamism.json`) is the approved starting point for the UI design: **S-01 Iniciar sesión** (sign-in form with generic error and submit lock), **S-02 Mis incidencias** (owned-incident list with status filter chips, counts, FAB, and "Salir" sign-out), **S-03 Nueva incidencia** (live per-field validation, category/priority choice groups, duplicate-submission lock), and **S-04 Detalle** (incident card plus status-history timeline with a safe no-access state for foreign identifiers). Its screens, palette, spacing system, and interaction behaviors are the baseline the frontend design extends — not suggestions to be re-derived.

---

## Components

The component survey (`docs/design/manifest.json`) marks this project as a greenfield `mobile_backend` system with exactly three present component classes — one frontend, one backend, one database — and **no CI/CD and no cloud components** (internal testing only; no pipeline or external infrastructure was requested, and clarifications resolved all infrastructure questions to in-process or local mechanisms). The sections below enumerate only the classes the manifest marks present.

The UI description reflects the user-approved Pre-Design mobile draft (`docs/design/pre_design_mobile.html`, one phone-framed mode) and its interactive-component manifest (`docs/design/pre_design_dynamism.json`), which implements screens S-01–S-04 with Spanish UI copy, status filter chips with live count badges, loading/empty/error states, and list→detail drill-down — treated as the approved starting point for the app's screens.

### Frontends

#### mobile-app (scope: `All project`)

Native Android application for Android phones, portrait orientation only, Spanish UI, online only (CONF-009). Kotlin + Jetpack Compose, application id `com.nexti.minisoporte`, `minSdk 26` (Q-006). Located at `apps/mobile`. Declares **zero Android permissions**, no push notifications, no deep links, no offline mode (CONF-007).

Networking stack: Retrofit 3.0.0 + OkHttp 5.x + kotlinx.serialization (Q-012), with a bearer-token interceptor attaching the session token. The session token is stored exclusively via Android Keystore-backed storage, never plain text, and is deleted on sign-out (CONF-002). Every network failure path renders a recoverable error state with a retry action — never a crash (CONF-009).

Per Q-005, all data access goes through a repository interface (`IncidentRepository`) whose v1 implementation is an in-memory `FakeIncidentRepository` with mock users, incidents, and status history. The fake drives debug builds, Compose previews, automated tests, and the App Builder viewer, so the demo flow never requires a running backend; the Retrofit-backed implementation is defined behind the same interface for future REST integration.

Primary screens (from the approved Pre-Design draft):

| Screen | Name | Purpose |
|--------|------|---------|
| S-01 | Iniciar sesión | Email + password sign-in; required-field validation, generic failure message that never reveals account existence, submit disabled while the request runs (FUNC-001). |
| S-02 | Mis incidencias | Owned-incident list newest-first (title, category, priority, status badge, creation date), status filter chips (Todas/Pendiente/En progreso/Resuelta) with live counts, FAB to create, header "Salir" sign-out, loading/empty/error+retry states (FUNC-002, FUNC-003, FUNC-004). |
| S-03 | Nueva incidencia | Create form with live per-field validation (title 5–100 chars with counter, description 10–1000 chars with counter, category and priority choice groups), duplicate-submission lock, success returns to list with confirmation (FUNC-005, INSTR-002). |
| S-04 | Detalle de incidencia | Incident identifier, title, description, category, priority, status text badge, creation date, plus a chronological status-history timeline; safe no-access state for missing/foreign ids; back navigation (FUNC-006). |

### Backends / services

#### api (scope: `All project`)

Layered FastAPI REST API on Python 3.10 (CPython), FastAPI 0.123.10 (Q-009), with dependency injection, strict typing, and clean-code layering (CONF-011). Located at `services/api`. JWT HS256 session tokens with 8-hour expiration and no refresh token (Q-002, Q-011); failed sign-in attempts are throttled by an in-process in-memory limiter (per-IP and per-email counters) built as FastAPI dependencies, with behavior uniform enough not to leak account existence (CONF-003, Q-004). All database access goes through the SQLAlchemy 2.0.x ORM (parameterized, no string-concatenated SQL, CONF-005, Q-003); schema changes ship as Alembic migrations (CONF-008). Passwords are hashed with bcrypt used directly, no passlib (Q-010, INSTR-008).

Routes and access control (access level per route is mandatory, CONF-011):

| Route | Access | Responsibility |
|-------|--------|----------------|
| `POST /api/auth/login` | Public | Authenticate with normalized email + password; issue JWT HS256 8 h token; rate-limited; generic failure response (FUNC-001). |
| `GET /api/me` | Authenticated | Return the current user's profile from the token (CONF-011). |
| `GET /api/incidents` | Employee (owner-scoped) | List only the authenticated employee's incidents, optional status filter, newest first, no pagination in v1 (FUNC-003, FUNC-004, Q-007). |
| `POST /api/incidents` | Employee | Create an incident owned by the authenticated employee, always starting in Pending status, server-side field validation (FUNC-005, INSTR-002, INSTR-011). |
| `GET /api/incidents/{id}` | Owner | Incident details plus chronological status history; missing or foreign ids return a safe result that does not leak information (FUNC-006, INSTR-001). |
| `PATCH /api/incidents/{id}/status` | Support role only | Validate the linear transition Pending → In progress → Resolved (Resolved is terminal) and record an immutable history entry with previous status, new status, date, and actor (FUNC-007, INSTR-003–005). |
| `GET /health` | Public (operational) | Liveness report without authentication; exposes no stack traces or sensitive configuration (FUNC-008). |

Supporting modules: an idempotent demo seed (one demo employee `empleado@minisoporte.test`, one support agent with configured email, three incidents covering Pending / In progress / Resolved with consistent history) that runs only outside production and reads passwords exclusively from `DEMO_EMPLOYEE_PASSWORD` / `DEMO_SUPPORT_PASSWORD`, failing clearly when either is missing (FUNC-009, INSTR-010, Q-001). Tests are pytest, with unit tests for business rules and integration tests for ownership isolation and role protection, targeting ≥90% coverage of business logic (INSTR-013); integration tests take their PostgreSQL from the `TEST_DATABASE_URL` environment variable.

### Database / data stores

#### PostgreSQL 16.15 (engine: PostgreSQL)

Single relational database holding all persistent state, rebuilt identically in any environment from Alembic migrations, with foreign keys, constraints, and indexes (CONF-008). Three table groups:

| Table group | Contents | Key integrity rules |
|-------------|----------|---------------------|
| `users` | Employees and support agents: UUID id, unique normalized email, bcrypt `password_hash`, role (`employee` / `support`), active flag, `created_at`. | Unique email; email normalized (trimmed, lowercased) before authentication (INSTR-006); role drives endpoint authorization (CONF-001). |
| `incidents` | Employee-reported incidents: UUID id, `owner_id` FK to users, title, description, category (`systems`/`equipment`/`access`/`other`), priority (`low`/`medium`/`high`), status (`pending`/`in_progress`/`resolved`), `created_at`, `updated_at`. | `owner_id` FK enforces single-owner model; CHECK constraints pin status/category/priority to the fixed sets; status defaults to `pending` at the database level as well as the service layer (INSTR-001, INSTR-002); index on `owner_id` (+ status for the filter) supports owner-scoped, newest-first list queries. |
| `incident_history` | Append-only status timeline: UUID id, `incident_id` FK, nullable `previous_status`, `new_status`, `changed_by` FK to users, `changed_at`. | Every accepted transition inserts one row; employees cannot edit owner or history (INSTR-005); nullable `previous_status` represents initial creation; index on `incident_id` supports the S-04 timeline read. |

### Component count summary

| Class | Count | Names |
|-------|-------|-------|
| Frontends | 1 | mobile-app |
| Backends / services | 1 | api |
| Database / data stores | 1 | PostgreSQL 16.15 |
| CI/CD | 0 | — (absent per manifest: internal testing only, local builds) |
| Cloud services | 0 | — (absent per manifest: no external resources permitted) |

---

## Tech Stack

All versions below honor the wizard stack and the binding clarification answers verbatim (each pinned row cites its source). Versions verified live against a package registry during this design are marked **(verified)**; versions pinned from current stable knowledge without a clarification answer are marked **†** and must be re-confirmed at first build — no version was invented arbitrarily.

### Frontend (Android)

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | "2.3.21" | Application language; latest stable 2.x line (verified against Maven Central `kotlin-gradle-plugin` metadata; compatible with the pinned BOM generation). |
| Android Gradle Plugin | "8.13.2" | Android build tooling; last stable release of the mature 8.x line (verified against Google Maven metadata; 9.x exists but 8.13 is the conservative, toolchain-compatible choice). |
| Gradle wrapper | "8.14" † | Build orchestration; minimum required by AGP 8.13.x, Kotlin DSL only. |
| Jetpack Compose BOM | "2026.09.00" | Compose runtime/UI/material/foundation version alignment via a pinned lockfile-style version catalog (binding: Q-013). |
| Compose compiler | via Kotlin plugin "2.3.21" | `org.jetbrains.kotlin.plugin.compose` ships the compiler with Kotlin 2.x — no separate compiler pin needed. |
| androidx.activity:activity-compose | "1.11.0" † | Activity integration for Compose (`ComponentActivity`, back handling). |
| androidx.lifecycle:lifecycle-viewmodel-compose | "2.9.0" † | Screen-level state holders wired into Compose. |
| androidx.navigation:navigation-compose | "2.9.0" † | S-01↔S-02↔S-03↔S-04 navigation graph with typed routes. |
| kotlinx-coroutines-android | "1.10.2" † | Async data loading and submission locks on the main-safety boundary. |
| Retrofit | "3.0.0" | Type-safe REST client (binding: Q-012). |
| Retrofit converter | "3.0.0" (`converter-kotlinx-serialization`) | kotlinx.serialization-based request/response conversion, matched to the Retrofit release line. |
| OkHttp | "5.5.0" | HTTP client, logging interceptor, timeouts (binding line: Q-012 "OkHttp 5.x"; concrete 5.5.0 verified against Maven Central as latest stable 5.x). |
| kotlinx-serialization-json | "1.11.0" | JSON (de)serialization of DTOs (verified against Maven Central; 1.12.0 is only an RC and was rejected). |
| compileSdk / targetSdk | "36" † | Compilation and target API level compatible with the pinned BOM and AGP 8.13.x. |
| minSdk | "26" | Android 8.0 baseline (binding: Q-006); recorded in `app/build.gradle.kts`. |
| Android build-tools | "36.0.0" † | Pinned installed build-tools version, per the Android Construction Contract. |
| Java / Kotlin JVM target | "17" | `sourceCompatibility` = `targetCompatibility` = `jvmTarget` (Construction Contract alignment rule). |

AndroidX is enabled explicitly (`android.useAndroidX=true` in `gradle.properties`), all pins live in `gradle/libs.versions.toml`, and the manifest declares zero permissions (CONF-007).

### Backend

| Technology | Version | Purpose |
|------------|---------|---------|
| Python (CPython) | "3.10" | Backend runtime (binding: target plan / CONF-011). |
| FastAPI | "0.123.10" | REST framework; kept exactly as the target plan mandates (binding: Q-009), layered architecture with dependency injection and strict typing. |
| Starlette / Pydantic | as required by FastAPI "0.123.10" (Pydantic v2 line, ">= 2.11") | Request validation and response models; Pydantic v2 is a hard requirement of the pinned FastAPI line. |
| SQLAlchemy | "2.0.54" | ORM; maintenance 2.0.x series, all access parameterized (binding: Q-003; CONF-005). |
| Alembic | "1.20.0" | Reproducible schema migrations (verified against PyPI; requires Python ≥3.10 — compatible; binding tool choice: Q-003, CONF-008). |
| psycopg (psycopg[binary]) | ">= 3.2" | PostgreSQL 3.x driver for SQLAlchemy (minimum stated; exact patch resolved at install). |
| bcrypt | "4.3.0" (4.x line) | Password hashing used directly — no passlib (binding: Q-010; the passlib+bcrypt pairing is broken). |
| PyJWT | "2.15.1" | JWT HS256 signing/validation with an explicit algorithms allow-list on decode (binding: Q-011). |
| uvicorn | "0.54.0" | ASGI server for local/production-preview serving (verified against PyPI). |

### Database

| Technology | Version | Purpose |
|------------|---------|---------|
| PostgreSQL | "16.15" | Relational store for `users`, `incidents`, `incident_history` with FKs, CHECK constraints, and indexes (binding: target plan / CONF-008). |
| Alembic migrations | "1.20.0" | Schema is rebuilt identically from version control in any environment; migrations — not ad-hoc SQL — are the authoritative schema source (CONF-008). |

### Testing

| Technology | Version | Purpose |
|------------|---------|---------|
| pytest | "8.3.3" | Backend unit and integration tests (binding: target plan). Target ≥90% coverage of business logic (INSTR-013). |
| pytest-cov | ">= 6.0" † | Coverage measurement enforcing the 90% business-logic threshold. |
| httpx | ">= 0.28" † | ASGI in-process test client (`TestClient`) used by FastAPI integration tests. |
| TEST_DATABASE_URL (env var) | — | Integration tests take their PostgreSQL connection from this environment variable (sandbox has real `pg-sandbox` servers and NO Docker; tests never depend on containers). |
| Testcontainers | optional fallback only | Used ONLY when `TEST_DATABASE_URL` is unset (a developer machine with Docker); never required to pass the suite. |
| JUnit4 + compose-ui-test (via BOM "2026.09.00") | BOM-managed | Android unit tests in `app/src/test` and Compose UI tests against the `FakeIncidentRepository` (Q-005). |

### Infrastructure / DevOps (local-only — no CI/CD, no cloud)

| Technology | Version | Purpose |
|------------|---------|---------|
| uv | ">= 0.8" † | Python package/dependency management and the workspace root (`[tool.uv.workspace] members = ["services/*"]` in the root `pyproject.toml`). |
| Gradle wrapper (Kotlin DSL) | "8.14" † | Local Android validation/build: `./gradlew check :app:assembleDebug` (debug APK for internal testing, CONF-010) and `./gradlew check :app:assembleRelease` (release APK signed from `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` — fails clearly when absent, never substitutes a debug key). |
| docker-compose.yml (infra/) | n/a | LOCAL development PostgreSQL 16.15 convenience only. Nothing in verification depends on running it: the sandbox has no Docker, and integration tests use `TEST_DATABASE_URL`. |
| .env.example | n/a | Template of every required environment variable (`DATABASE_URL`, `JWT_SECRET`, `DEMO_EMPLOYEE_PASSWORD`, `DEMO_SUPPORT_PASSWORD`, `ENVIRONMENT`, …); real `.env`, keystores, and `key.properties` are Git-excluded (CONF-006). |
| Android Keystore (release signing) | n/a | Externally supplied signing secrets, read only from the four named environment variables at release-build time; no keystore or secret is ever committed. |

### Shared

No shared package exists. The only contract between the two runtimes is REST/JSON over HTTP, owned independently by the Pydantic schemas (`services/api/app/domain/schemas/`) and the kotlinx.serialization DTOs (`apps/mobile/.../data/remote/`); no code is genuinely used by 2+ components, so no `packages/shared` node is created.

---

## Archetype

No reference archetype document exists (`docs/extractions/reference/` is absent), so the layout below was derived directly from CONF-011's layered clean-code / dependency-injection requirement (driving `services/api`) and the AGENTS.md Android Construction Contract (driving `apps/mobile` with `app/src/main`, `app/src/test`, and Gradle Kotlin DSL). No `packages/shared` is included: the only cross-component contract is REST/JSON, and no code is genuinely shared by two components.

```
/workspace
├── pyproject.toml                                  # uv workspace ROOT: [tool.uv.workspace] members = ["services/*"]
├── .env.example                                    # template: DATABASE_URL, JWT_SECRET, ENVIRONMENT,
│                                                   #   DEMO_EMPLOYEE_PASSWORD, DEMO_SUPPORT_PASSWORD (CONF-006)
├── .gitignore                                      # excludes .env, key.properties, keystores, build outputs
├── README.md                                       # local run + test instructions (backend + Android)
│
├── apps/
│   └── mobile/                                     # ANDROID APP — Gradle-managed (own settings.gradle.kts)
│       ├── settings.gradle.kts                     # root project name, repositories (google/mavenCentral)
│       ├── build.gradle.kts                        # plugin declarations via version catalog
│       ├── gradle.properties                       # android.useAndroidX=true
│       ├── gradle/
│       │   ├── libs.versions.toml                  # pinned lockfile-style version catalog (Q-013)
│       │   └── wrapper/gradle-wrapper.properties   # pinned Gradle distribution
│       ├── gradlew                                 # wrapper entrypoint
│       └── app/
│           ├── build.gradle.kts                    # applicationId com.nexti.minisoporte, minSdk 26,
│           │                                       #   compileSdk 36, jvmTarget 17, buildToolsVersion,
│           │                                       #   release signing from the 4 keystore env vars (fails clearly)
│           ├── proguard-rules.pro
│           └── src/
│               ├── main/
│               │   ├── AndroidManifest.xml         # ZERO permissions, portrait-only, no deep links (CONF-007)
│               │   └── java/com/nexti/minisoporte/
│               │       ├── MiniSoporteApp.kt                       # Application class
│               │       ├── MainActivity.kt                         # single-Activity Compose host
│               │       ├── ui/
│               │       │   ├── theme/                              # palette #1E3A2A / #EFC64E / #F5F7F5,
│               │       │   │                                       #   8dp corners, Type.kt (INSTR-012)
│               │       │   ├── navigation/
│               │       │   │   ├── NavGraph.kt                     # S-01↔S-02↔S-03↔S-04 typed routes
│               │       │   │   └── AppNavHost.kt
│               │       │   ├── signin/
│               │       │   │   ├── SignInScreen.kt                 # S-01 Iniciar sesión
│               │       │   │   └── SignInViewModel.kt              # generic error, submit lock (FUNC-001)
│               │       │   ├── incidents/
│               │       │   │   ├── IncidentListScreen.kt           # S-02 Mis incidencias
│               │       │   │   ├── StatusFilterChips.kt            # Todas/Pendiente/En progreso/Resuelta + counts
│               │       │   │   └── IncidentListViewModel.kt        # loading/empty/error+retry states (FUNC-003/004)
│               │       │   ├── newincident/
│               │       │   │   ├── NewIncidentScreen.kt            # S-03 Nueva incidencia
│               │       │   │   └── NewIncidentViewModel.kt         # field-level validation, dup lock (FUNC-005)
│               │       │   ├── detail/
│               │       │   │   ├── IncidentDetailScreen.kt         # S-04 Detalle + history timeline
│               │       │   │   └── IncidentDetailViewModel.kt      # safe no-access state (FUNC-006)
│               │       │   └── components/
│               │       │       ├── StatusBadge.kt                  # text + color, never color alone (INSTR-012)
│               │       │       ├── IncidentCard.kt
│               │       │       └── ErrorRetryState.kt              # network error + retry (CONF-009)
│               │       ├── data/
│               │       │   ├── model/                              # Incident, IncidentHistoryEntry, User,
│               │       │   │   └── Enums.kt                        #   Status/Category/Priority enums
│               │       │   ├── repository/
│               │       │   │   └── IncidentRepository.kt           # INTERFACE (Q-005 seam)
│               │       │   ├── fake/
│               │       │   │   └── FakeIncidentRepository.kt       # in-memory mock data — debug/previews/tests (Q-005)
│               │       │   └── remote/
│               │       │       ├── IncidentApi.kt                  # Retrofit interface (Q-012)
│               │       │       ├── dto/                            # kotlinx.serialization DTOs
│               │       │       ├── mappers/                        # DTO ↔ domain model
│               │       │       └── RetrofitIncidentRepository.kt   # future REST implementation
│               │       ├── network/
│               │       │   ├── ApiClient.kt                        # Retrofit 3.0.0 + OkHttp 5.5.0 builder, timeouts
│               │       │   └── AuthInterceptor.kt                  # bearer token header from TokenStore
│               │       └── security/
│               │           └── TokenStore.kt                       # Android Keystore-backed token storage (CONF-002)
│               └── test/java/com/nexti/minisoporte/                # JUnit unit tests mirroring main/
│                   ├── ui/                                         # ViewModel + validation logic tests
│                   ├── data/                                       # FakeIncidentRepository behavior tests
│                   └── security/                                   # TokenStore contract tests
│
├── services/
│   └── api/                                        # FASTAPI LAYERED REST API — Python 3.10
│       ├── pyproject.toml                          # project deps (uv-managed, workspace member)
│       ├── .env.example                            # backend-specific env template
│       ├── alembic.ini
│       ├── app/
│       │   ├── main.py                             # app factory, /health, router registration, CORS
│       │   ├── api/
│       │   │   ├── deps.py                         # get_db, CurrentUser, require_role guards,
│       │   │   │                                   #   login-rate-limit dependencies (Q-004)
│       │   │   └── routes/
│       │   │       ├── auth.py                     # POST /api/auth/login (public, rate-limited)
│       │   │       ├── users.py                    # GET /api/me (authenticated)
│       │   │       ├── incidents.py                # GET/POST /api/incidents, GET /api/incidents/{id} (owner)
│       │   │       └── statuses.py                 # PATCH /api/incidents/{id}/status (support only)
│       │   ├── core/
│       │   │   ├── config.py                       # pydantic-settings: env-driven config (CONF-006)
│       │   │   ├── security.py                     # bcrypt hash/verify (Q-010), PyJWT HS256 encode/decode (Q-011)
│       │   │   └── ratelimit.py                    # in-memory per-IP/per-email limiter (CONF-003, Q-004)
│       │   ├── domain/
│       │   │   ├── enums.py                        # Status/Category/Priority/Role enums
│       │   │   ├── models.py                       # SQLAlchemy 2.0 ORM: User, Incident, IncidentHistory (CONF-008)
│       │   │   ├── schemas/                        # Pydantic v2 request/response models
│       │   │   │   ├── auth.py
│       │   │   │   ├── user.py
│       │   │   │   └── incident.py
│       │   │   └── services/
│       │   │       ├── auth_service.py             # email normalization, generic-failure login (INSTR-006, FUNC-001)
│       │   │       ├── incident_service.py         # owner isolation, list/filter, create-as-Pending (INSTR-001/002)
│       │   │       └── status_service.py           # linear transitions + immutable history write (INSTR-003/004/005)
│       │   ├── db/
│       │   │   ├── session.py                      # engine + SessionLocal + get_db (ORM-only access, CONF-005)
│       │   │   └── repositories/
│       │   │       ├── user_repository.py
│       │   │       ├── incident_repository.py      # owner-scoped queries, newest-first, status filter
│       │   │       └── history_repository.py
│       │   └── seed/
│       │       └── demo_seed.py                    # idempotent demo data, env-only passwords (FUNC-009, INSTR-010)
│       ├── migrations/
│       │   ├── env.py                              # Alembic environment bound to SQLAlchemy metadata
│       │   ├── script.py.mako
│       │   └── versions/
│       │       └── 0001_initial_schema.py          # users, incidents, incident_history + FKs/constraints/indexes
│       └── tests/
│           ├── conftest.py                         # app + DB fixtures from TEST_DATABASE_URL
│           ├── unit/                               # services, transition matrix, limiter, JWT, bcrypt
│           │   ├── test_auth_service.py
│           │   ├── test_status_service.py
│           │   ├── test_ratelimit.py
│           │   └── test_schemas.py
│           └── integration/                        # authorization + ownership + API error handling (INSTR-013)
│               ├── test_login_flow.py
│               ├── test_owner_isolation.py
│               ├── test_role_protection.py
│               └── test_health.py
│
└── infra/
    ├── docker-compose.yml                          # LOCAL DEV ONLY: PostgreSQL 16.15 (pg-sandbox equivalent);
    │                                               #   verification sandbox has NO Docker — tests use TEST_DATABASE_URL
    └── init/
        └── 01-create-database.sql                  # optional bootstrap (create db/user); schema itself comes
                                                    #   from Alembic migrations, never from this file
```

### Top-level annotations

- `pyproject.toml` (root) — the uv workspace root artifact: `[tool.uv.workspace] members = ["services/*"]` with one Python member; all backend deps resolve through one root lockfile.
- `apps/mobile` — the Android application, Gradle-managed with its own `settings.gradle.kts` (Android is not a uv member); feature-organized Compose UI over a repository seam (interface → in-memory fake for v1, Retrofit remote for REST integration), Keystore token storage, and mirrored unit tests.
- `services/api` — the FastAPI backend as a layered uv-workspace member: `api` (routes + DI deps) → `domain` (ORM models, Pydantic schemas, business services) → `db` (session + repositories), with `core` (config/security/rate-limit), `seed`, Alembic `migrations`, and `tests/unit` + `tests/integration` mirroring the source layout.
- `infra/` — local development support files only: a dev-Postgres docker-compose (not usable in the verification sandbox) and a bootstrap SQL stub; the authoritative schema always comes from Alembic migrations.
- `.env.example` / `.gitignore` — environment/secrets stubs enforcing CONF-006: secrets only via environment variables, `.env`, keystores and `key.properties` never committed.

No CI/CD pipelines, cloud resources, or additional tooling are included — the project is built and verified locally per the wizard's constraints (internal testing only).

---

## Data Model

The data model for the single backend component **`api`** (repo scope: `All project`, per `docs/design/manifest.json`) — one flat entity list, no per-component grouping. It comprises exactly the three entities mandated by CONF-008 (`users`, `incidents`, `incident_history`) on PostgreSQL 16.15. All three are confirmed by the requirements; **none is inferred**. The schema is created and evolved exclusively through Alembic migrations (`migrations/versions/0001_initial_schema.py`, CONF-008), and every access path goes through the SQLAlchemy 2.0.54 ORM — parameterized, never string-concatenated (CONF-005, Q-003).

### Naming conventions

- SQL identifiers are `snake_case`, plural table names (`users`, `incidents`, `incident_history`).
- ORM models live in `services/api/app/domain/models.py` (SQLAlchemy 2.0 `Mapped[...]` / `mapped_column` declarative style, per `docs/design/archetype.md`): classes `User`, `Incident`, `IncidentHistory` map to the tables below.
- Primary keys are `UUID` with `gen_random_uuid()` defaults — identifiers are opaque because they appear in client URLs (`/api/incidents/{id}`); sequential integers would allow enumeration probing (INSTR-001, FUNC-006).
- All timestamps are `TIMESTAMPTZ` (UTC) — never naive `timestamp`.
- Fixed value sets (role, status, category, priority) are `TEXT NOT NULL` + `CHECK (... IN (...))` constraints — deliberately **not** PostgreSQL `ENUM` types, so adding a value is a plain migration, not a type alteration.
- Enumerated values are stored exactly as the API transports them (`pending`/`in_progress`/`resolved`, `systems`/`equipment`/`access`/`other`, `low`/`medium`/`high`, `employee`/`support`) so no translation layer exists between ORM, API, and Kotlin DTOs.

### Entity: `users`

One row per employee or support agent. Accounts are **never hard-deleted in v1** — deactivation uses the `active` flag — so sign-in and authorization can reject without cascading deletes (INSTR-001, CONF-001).

| Field | Type | Key | Nullable | Default | Description |
|-------|------|-----|----------|---------|-------------|
| `id` | `UUID` | PK | NO | `gen_random_uuid()` | Opaque user identifier; `sub` claim of the JWT and `changed_by` target (CONF-001, CONF-008). |
| `email` | `TEXT` | UQ | NO | — | Login identifier; normalized (trimmed, lowercased) **by the service layer before storage/lookup** (INSTR-006); uniqueness enforced by `uq_users_email` (BR-008). |
| `password_hash` | `TEXT` | — | NO | — | Salted bcrypt hash (60-char `$2b$…` string), written via the bcrypt library directly — never passlib, never plain text, never logged or returned by any endpoint (INSTR-008, Q-010, BR-026). |
| `role` | `TEXT` | — | NO | — | Fixed set: `employee` / `support`; drives per-route authorization and the `role` JWT claim (CONF-001, INSTR-004). CHECK: `role IN ('employee','support')`. |
| `active` | `BOOLEAN` | — | NO | `TRUE` | Soft-enable flag. `FALSE` blocks sign-in and API access without deleting the row or its incidents (INSTR-001 rationale). |
| `created_at` | `TIMESTAMPTZ` | — | NO | `now()` | Row creation instant; returned by `GET /api/me` (CONF-011). |

**Traces:** CONF-008 (verbatim field list), CONF-001 (roles), INSTR-006 (normalized unique email), INSTR-008 (bcrypt `password_hash`), BR-008, BR-026, FUNC-009 (seed rows).

### Entity: `incidents`

One row per employee-reported incident. Exactly one owner; owner and status lifecycle are server-controlled (INSTR-001, INSTR-002).

| Field | Type | Key | Nullable | Default | Description |
|-------|------|-----|----------|---------|-------------|
| `id` | `UUID` | PK | NO | `gen_random_uuid()` | Opaque incident identifier used in `/api/incidents/{id}` paths (FUNC-006, INSTR-001). |
| `owner_id` | `UUID` | FK → `users.id` | NO | — | The authenticated creator; **never client input** (mass-assignment defense, INSTR-002). Every list/detail/history query filters by it (INSTR-001, BR-009). ON DELETE `RESTRICT`. |
| `title` | `TEXT` | — | NO | — | Incident title, 5–100 characters (FUNC-005, BR-013). CHECK: `char_length(title) BETWEEN 5 AND 100` (belt-and-braces; the Pydantic schema is the primary gate, INSTR-011). |
| `description` | `TEXT` | — | NO | — | Incident description, 10–1000 characters (FUNC-005, BR-014). CHECK: `char_length(description) BETWEEN 10 AND 1000`. Omitted from list-item responses, included in detail (see `api-contract.md`). |
| `category` | `TEXT` | — | NO | — | Fixed set: `systems` / `equipment` / `access` / `other` (FUNC-005, BR-015). CHECK: `category IN ('systems','equipment','access','other')`. |
| `priority` | `TEXT` | — | NO | — | Fixed set: `low` / `medium` / `high` (FUNC-005, BR-016). CHECK: `priority IN ('low','medium','high')`. |
| `status` | `TEXT` | — | NO | `'pending'` | Fixed set: `pending` / `in_progress` / `resolved` (FUNC-004, BR-017). Defaults to `pending` **at the DB level as well as the service layer** (INSTR-002, BR-011). CHECK: `status IN ('pending','in_progress','resolved')`. |
| `created_at` | `TIMESTAMPTZ` | — | NO | `now()` | Creation instant; list ordering is `created_at DESC`, newest first (FUNC-003, BR-019). |
| `updated_at` | `TIMESTAMPTZ` | — | NO | `now()` | Last mutation instant (accepted status transitions); set by the service layer inside the same transaction as the history insert (INSTR-003, INSTR-005). |

**Traces:** CONF-008 (verbatim field list), FUNC-003/004/005/006, INSTR-001 (owner), INSTR-002 (status default), BR-009..BR-019.

### Entity: `incident_history`

Append-only audit trail: one row per accepted status transition (INSTR-005). No endpoint creates, updates, or deletes history rows directly; only the status-transition service inserts, and employees cannot alter history (FUNC-006, BR-012, BR-025).

| Field | Type | Key | Nullable | Default | Description |
|-------|------|-----|----------|---------|-------------|
| `id` | `UUID` | PK | NO | `gen_random_uuid()` | History entry identifier; exposed in the S-04 timeline payload (FUNC-006). |
| `incident_id` | `UUID` | FK → `incidents.id` | NO | — | The transitioned incident; the timeline read is `WHERE incident_id = ? ORDER BY changed_at ASC` (FUNC-006, S-04). ON DELETE `RESTRICT`. |
| `previous_status` | `TEXT` | — | YES | `NULL` | Status before the change. `NULL` represents the **initial creation entry** (INSTR-002, INSTR-005, BR-025). When present, CHECK: `previous_status IN ('pending','in_progress','resolved')`. |
| `new_status` | `TEXT` | — | NO | — | Status after the change. CHECK: `new_status IN ('pending','in_progress','resolved')`. |
| `changed_by` | `UUID` | FK → `users.id` | NO | — | Actor who performed the transition (the support agent's token subject); rendered in the timeline (FUNC-007, INSTR-005). ON DELETE `RESTRICT`. |
| `changed_at` | `TIMESTAMPTZ` | — | NO | `now()` | Transition instant; timeline is ordered ascending by it (FUNC-006). |

**Traces:** CONF-008 (verbatim field list), FUNC-006/007, INSTR-005 (immutable entries), INSTR-002 (nullable `previous_status` on creation), INSTR-003 (one row per accepted transition), BR-024, BR-025.

### Entity relationships

| Relationship | Cardinality | FK side | ON DELETE / ON UPDATE | Reason |
|--------------|-------------|---------|----------------------|--------|
| `users` → `incidents` | 1 : N (one owner owns many incidents; an incident has exactly one owner) | `incidents.owner_id` → `users.id` | `ON DELETE RESTRICT` / `ON UPDATE NO ACTION` | Users are **never hard-deleted in v1** — deactivation is the `active` flag (INSTR-001). `RESTRICT` makes any accidental user deletion fail loudly instead of silently cascading away employees' incident reports. UUID PKs never change, so `ON UPDATE NO ACTION` is moot by construction. |
| `incidents` → `incident_history` | 1 : N (one incident accumulates one history row per accepted transition; creation inserts a row with `previous_status = NULL`) | `incident_history.incident_id` → `incidents.id` | `ON DELETE RESTRICT` / `ON UPDATE NO ACTION` | **Incident deletion is not a feature of v1** (no endpoint exposes it, FUNC-006). Even if a future version adds retention tooling, the audit trail must not vanish with the incident: `RESTRICT` forces an explicit, reviewed decision before history rows are destroyed (INSTR-005 — the timeline must stay trustworthy). `CASCADE` was rejected for exactly this reason. |
| `users` → `incident_history` | 1 : N (one actor performs many transitions) | `incident_history.changed_by` → `users.id` | `ON DELETE RESTRICT` / `ON UPDATE NO ACTION` | Same no-hard-delete policy as above; the actor attribution of an audit entry must remain resolvable (INSTR-005). |

No M:N relationships exist, so **no junction tables** are required.

### ER diagram

```
┌────────────────────────┐          ┌──────────────────────────────┐
│         users          │          │          incidents           │
├────────────────────────┤          ├──────────────────────────────┤
│ id          UUID   PK  │◄───────┐ │ id            UUID       PK  │
│ email       TEXT   UQ  │        │ │ owner_id      UUID   FK ─────┼──► users.id   (RESTRICT)
│ password_hash TEXT     │        └─┼─title         TEXT           │
│ role        TEXT  CHECK│          │ description   TEXT           │
│ active      BOOLEAN    │          │ category      TEXT  CHECK    │
│ created_at  TIMESTAMPTZ│          │ priority      TEXT  CHECK    │
└────────────────────────┘          │ status        TEXT  CHECK    │
          ▲                         │ created_at    TIMESTAMPTZ    │
          │                         │ updated_at    TIMESTAMPTZ    │
          │ changed_by (RESTRICT)   └──────────────▲───────────────┘
          │                                        │ incident_id (RESTRICT)
┌─────────┴──────────────────────────┐            │
│         incident_history           │            │
├────────────────────────────────────┤  1 : N     │
│ id               UUID          PK  │────────────┘
│ incident_id      UUID    FK ──────┼──► incidents.id
│ previous_status  TEXT  NULL CHECK │   (NULL = initial creation entry)
│ new_status       TEXT      CHECK  │
│ changed_by       UUID    FK ──────┼──► users.id
│ changed_at       TIMESTAMPTZ      │
└────────────────────────────────────┘
```

### Notable indexes and constraints

PostgreSQL does **not** auto-index FK columns, so the two hot read paths get explicit indexes:

| Name | Definition | Query pattern it serves |
|------|-----------|------------------------|
| `uq_users_email` | `UNIQUE (email)` | Sign-in lookup by normalized email (FUNC-001, INSTR-006); also the DB-level guard against duplicate accounts (BR-008). |
| `ix_incidents_owner_created` | `(owner_id, created_at DESC)` | `GET /api/incidents`: owner-scoped newest-first list — index satisfies both the `owner_id` equality and the `created_at DESC` ordering without a sort step (FUNC-003, BR-019). The `status` filter (FUNC-004) is applied as a cheap residual predicate over this index while each owner's row count is small; if volumes grow, a supplementary `(owner_id, status, created_at DESC)` index is a single migration. |
| `ix_incident_history_incident_changed` | `(incident_id, changed_at)` | `GET /api/incidents/{id}`: chronological ascending timeline read for S-04 — index scan directly yields the ordered history (FUNC-006, BR-021). |
| `ck_users_role` | `CHECK (role IN ('employee','support'))` | Pins roles to the two authorized values (CONF-001, INSTR-004). |
| `ck_incidents_category` | `CHECK (category IN ('systems','equipment','access','other'))` | Fixed category set (FUNC-005, BR-015). |
| `ck_incidents_priority` | `CHECK (priority IN ('low','medium','high'))` | Fixed priority set (FUNC-005, BR-016). |
| `ck_incidents_status` | `CHECK (status IN ('pending','in_progress','resolved'))` | Fixed status set (FUNC-004, BR-017); together with `DEFAULT 'pending'` it makes an invalid or non-Pending initial status impossible even bypassing the ORM (INSTR-002, BR-011). |
| `ck_incidents_title_len` | `CHECK (char_length(title) BETWEEN 5 AND 100)` | Belt-and-braces under the authoritative Pydantic validation (FUNC-005, INSTR-011, BR-013). |
| `ck_incidents_description_len` | `CHECK (char_length(description) BETWEEN 10 AND 1000)` | Same rationale (FUNC-005, INSTR-011, BR-014). |
| `ck_incident_history_prev_status` | `CHECK (previous_status IS NULL OR previous_status IN ('pending','in_progress','resolved'))` | `NULL` is legal only as the initial-creation marker (INSTR-002, INSTR-005, BR-025). |
| `ck_incident_history_new_status` | `CHECK (new_status IN ('pending','in_progress','resolved'))` | Fixed status set on every entry (INSTR-005). |

Transition legality itself (Pending → In progress → Resolved, Resolved terminal) is **service-layer logic**, not a DB constraint: it requires reading the current status and rejecting before write (INSTR-003, BR-023, BR-024), and is covered by the mandatory unit/integration tests (INSTR-013).

### Migration ownership

The schema above is authored once as `migrations/versions/0001_initial_schema.py` (Alembic 1.20.0) creating all three tables, foreign keys, CHECK constraints, unique constraint, and indexes in dependency order (`users` → `incidents` → `incident_history`); no ad-hoc SQL ever defines schema in any environment (CONF-008). The demo seed (FUNC-009, INSTR-010, Q-001) inserts rows only — it never alters schema.

---

## API Contract

REST/JSON contract for the single backend component **`api`** (repo scope: `All project`) — one flat endpoint table, one base URL. Implemented with FastAPI 0.123.10 on Python 3.10 (CONF-011, Q-009); every request/response body below is a Pydantic v2 schema in `services/api/app/domain/schemas/`, and the Kotlin DTOs mirror these names verbatim via `@SerialName`.

### Field naming decision

All JSON field names are **snake_case** (`access_token`, `expires_in_seconds`, `new_status`, `previous_status`, `changed_by`, `changed_at`, …). This matches the Pydantic models exactly as declared (no aliasing layer) and the Android DTOs via kotlinx.serialization `@SerialName`. No camelCase mapping exists anywhere in the contract.

### Base URL and versioning

- **Base URL:** `http(s)://<host>[:port]/` with **no `/v1` prefix** — the routes below already carry the `/api` segment (CONF-011). Decision: the surface is fixed for v1; the mobile client ships together with the API and is versioned with it (app version ↔ API version move in lockstep during internal testing), so a URL-version segment would add no capability. Breaking changes in a future version would introduce `/api/v2` routes rather than mutating these.
- **Content type:** `application/json` for all requests and responses. Timestamps are ISO 8601 UTC with offset (e.g. `2026-10-06T14:03:00Z`), serialized from `TIMESTAMPTZ` columns.
- **Identifiers:** UUID strings, opaque by design (see `data-model.md`).

### Authentication

- **Header format:** `Authorization: Bearer <JWT>` on every protected request.
- **Token:** JWT signed **HS256**, claims `sub` (user UUID string), `role` (`employee` | `support`), `exp` (issued + 8 hours = `28800` seconds). Signed and validated with PyJWT 2.15.1; decode pins `algorithms=["HS256"]` (CONF-001, Q-002, Q-011, BR-004).
- **Lifecycle:** issued only by `POST /api/auth/login` on valid credentials. **No refresh endpoint exists**; on expiry the API returns `401` and the client re-authenticates. Sign-out is client-side deletion of the stored token (Android Keystore-backed storage); the 8-hour TTL is the accepted server-side mitigation (Q-008, FUNC-002, BR-005).
- **Failure:** missing, malformed, expired, or invalidly signed tokens on protected routes → `401` with the uniform body `{"detail": "No autenticado"}`. A token whose `sub` resolves to an `active = false` user → `403` with `{"detail": "Cuenta inactiva"}` (deactivated accounts cannot ride an unexpired token; INSTR-001 rationale).

### Endpoint summary (access level per route — mandatory, CONF-011)

| Method | Path | Access | Traces |
|--------|------|--------|--------|
| `POST` | `/api/auth/login` | Public (rate-limited, CONF-003) | FUNC-001, BR-001/002/007/008 |
| `GET` | `/api/me` | Authenticated | CONF-011, CONF-001 |
| `GET` | `/api/incidents` | Employee (owner-scoped) | FUNC-003, FUNC-004, INSTR-001, Q-007, BR-009/019/020 |
| `POST` | `/api/incidents` | Employee (any authenticated user may create) | FUNC-005, INSTR-002, INSTR-011, BR-011/013–018 |
| `GET` | `/api/incidents/{id}` | Owner only (safe 404 otherwise) | FUNC-006, INSTR-001, BR-010/021 |
| `PATCH` | `/api/incidents/{id}/status` | Support role only | FUNC-007, INSTR-003/004/005, BR-022–025 |
| `GET` | `/health` | Public (operational, no auth) | FUNC-008, BR-032 |

### Standard error response shape

One shape everywhere — the FastAPI default envelope:

```json
{"detail": <string | list>}
```

- **Validation failures (`422`):** `detail` is a **list** of Pydantic field-level error objects, each `{"loc": [...], "msg": "...", "type": "..."}` — kept structured so the Android form can render errors next to the corresponding fields (INSTR-011, BR-018). Example:

```json
{
  "detail": [
    {"loc": ["body", "title"], "msg": "String should have at least 5 characters", "type": "string_too_short"}
  ]
}
```

- **All other errors:** `detail` is a single **Spanish** string — the client UI language is Spanish (CONF-009), so user-decidable errors (auth, ownership, transitions) are Spanish: `"Credenciales inválidas"`, `"No autenticado"`, `"Incidencia no encontrada"`, `"No autorizado"`, `"Transición de estado no válida"`, `"Demasiados intentos. Inténtelo de nuevo más tarde."`.
- **Decision on `422` messages:** validation `msg`/`type` strings keep the **FastAPI default English** text. Rationale: they are machine-shaped field descriptors the client maps to its own Spanish per-field copy (INSTR-011 requires field-local errors, not translated server prose); mixing generated Spanish translations into Pydantic's default handler would add fragile custom machinery for no user-visible gain.
- **Unhandled exceptions:** a global exception handler returns sanitized `500 {"detail": "Error interno del servidor"}` — no stack traces, SQL, or configuration ever reach the response (FUNC-008 discipline applied globally, BR-032).

### Pagination strategy

**N/A — no pagination by binding clarification Q-007** (BR-020): `GET /api/incidents` returns **all** owned incidents matching the filter, newest first, as a plain JSON array with no envelope, no `page`/`page_size` parameters, and no `total` count. Revisit only if incident volume makes the full list untenable in a future version.

### Rate limiting

- **Applies to:** `POST /api/auth/login` **failed attempts only** — successful authentications do not consume budget (CONF-003).
- **Counters:** two independent in-process, in-memory counters per attempt — **per client IP** and **per submitted email** (Q-004, BR-007). Behavior is **uniform regardless of account existence**: a nonexistent email consumes the same per-email budget and produces the same responses, so throttling cannot probe for accounts (FUNC-001, BR-002).
- **Implementation:** FastAPI dependencies (`app/core/ratelimit.py`); no Redis/cache tier exists in the target plan, and the single-process internal deployment makes memory sufficient (Q-004).
- **Thresholds:** environment-configurable via `LOGIN_RATELIMIT_IP_MAX`, `LOGIN_RATELIMIT_EMAIL_MAX` (max failures), and `LOGIN_RATELIMIT_WINDOW_SECONDS` (sliding window). Design defaults, not secrets: 5 failures per window per IP, 5 per email, window 900 s.
- **Response when tripped:** `429` with the same uniform body for every blocked caller and a `Retry-After` header (seconds until the counter resets):

```
HTTP/1.1 429 Too Many Requests
Retry-After: 537

{"detail": "Demasiados intentos. Inténtelo de nuevo más tarde."}
```

### WebSocket / SSE endpoints

**N/A — no real-time features; the client polls on screen load.** The Android app is online-only and fetches data when a screen opens (CONF-009); the history timeline appears via `GET /api/incidents/{id}` and updates on re-entry, with no push channel (CONF-007 disables notifications).

---

### `POST /api/auth/login`

Authenticates with email + password and issues the session JWT (FUNC-001). Public; rate-limited (CONF-003).

**Request body** (`application/json`):

| Field | Type | Required | Validation | Example |
|-------|------|----------|------------|---------|
| `email` | string | Yes | Valid email format (Pydantic `EmailStr`); normalized by the service (trimmed, lowercased) before lookup (INSTR-006) | `"empleado@minisoporte.test"` |
| `password` | string | Yes | Non-blank; no format constraints (never logged, INSTR-008) | `"S3cret!pass"` |

Missing or blank fields → `422` with field-level `detail` entries for each offending field (BR-001).

**Responses:**

| Status | Condition | Body |
|--------|-----------|------|
| `200` | Valid credentials, active account | `{"access_token": "<JWT>", "token_type": "bearer", "expires_in_seconds": 28800}` |
| `401` | Invalid credentials **or nonexistent email** — one uniform body, never reveals account existence (BR-002) | `{"detail": "Credenciales inválidas"}` |
| `422` | Missing/blank/malformed fields (BR-001) | Structured `422` shape (see Standard error response shape) |
| `429` | IP or email failure budget exhausted (CONF-003) | `{"detail": "Demasiados intentos. Inténtelo de nuevo más tarde."}` + `Retry-After` header |

**Field precision for `200`:** `access_token` — string, signed JWT HS256, claims `sub`/`role`/`exp`; `token_type` — string literal `"bearer"`; `expires_in_seconds` — integer, always `28800` (8 h, Q-002).

Example `200`:

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.<payload>.<sig>",
  "token_type": "bearer",
  "expires_in_seconds": 28800
}
```

### `GET /api/me`

Returns the current user's profile from the token (CONF-011). Requires a valid bearer token.

**Request:** no body, no parameters.

**Response `200`:**

| Field | Type | Nullable | Description | Example |
|-------|------|----------|-------------|---------|
| `id` | string (UUID) | No | Token subject (CONF-008) | `"3fa85f64-5717-4562-b3fc-2c963f66afa6"` |
| `email` | string | No | Normalized stored email (INSTR-006) | `"empleado@minisoporte.test"` |
| `role` | string | No | `employee` or `support` (CONF-001) | `"employee"` |
| `active` | boolean | No | Account enabled flag | `true` |
| `created_at` | string (ISO 8601 UTC) | No | Account creation instant | `"2026-10-01T09:00:00Z"` |

**Errors:** `401` `{"detail": "No autenticado"}` (missing/expired/invalid token); `403` `{"detail": "Cuenta inactiva"}` (token valid but `active = false`). `password_hash` is **never** part of any response (INSTR-008, BR-026).

### `GET /api/incidents`

Lists **only** the authenticated employee's incidents, newest first (INSTR-001, FUNC-003). Owner scope is applied in the repository from the token subject — no client parameter can widen it.

**Query parameters:**

| Name | Type | Required | Validation | Default |
|------|------|----------|------------|---------|
| `status` | string | No | One of `pending`, `in_progress`, `resolved`, `all`; absent or `all` = no filter (FUNC-004, BR-017) | `all` (no filter) |

Any other `status` value → `422` with a query-parameter `loc` entry (`"loc": ["query", "status"]`).

**Response `200`:** a **JSON array** (no pagination envelope, Q-007) of list items, ordered `created_at DESC`. The list item **omits `description`** — FUNC-003 specifies the card shows only title, category, priority, status, and creation date, so carrying a ≤1000-char description per row would bloat every list refresh (CONF-009 targets <2 s responses) for data the card never renders; the full description is fetched per incident by `GET /api/incidents/{id}`.

**List item schema:**

| Field | Type | Nullable | Description | Example |
|-------|------|----------|-------------|---------|
| `id` | string (UUID) | No | Incident identifier | `"7c9e6679-7425-40de-944b-e07fc1f90ae7"` |
| `title` | string | No | 5–100 chars (BR-013) | `"Impresora no responde"` |
| `category` | string | No | `systems` / `equipment` / `access` / `other` (BR-015) | `"equipment"` |
| `priority` | string | No | `low` / `medium` / `high` (BR-016) | `"high"` |
| `status` | string | No | `pending` / `in_progress` / `resolved` (BR-017) | `"pending"` |
| `created_at` | string (ISO 8601 UTC) | No | Creation instant; ordering key (BR-019) | `"2026-10-05T16:20:00Z"` |
| `updated_at` | string (ISO 8601 UTC) | No | Last status mutation instant | `"2026-10-05T16:20:00Z"` |

**Errors:** `401` `{"detail": "No autenticado"}`. No `403`/`404` cases exist: the owner filter cannot fail, only shrink.

### `POST /api/incidents`

Creates an incident owned by the authenticated user (FUNC-005). Access: any authenticated user (employee in practice; CONF-011 labels the route employee-accessible).

**Request body:**

| Field | Type | Required | Validation | Example |
|-------|------|----------|------------|---------|
| `title` | string | Yes | 5–100 characters inclusive (BR-013); char-level errors field-located (INSTR-011) | `"Impresora no responde"` |
| `description` | string | Yes | 10–1000 characters inclusive (BR-014) | `"La impresora de recepción no responde desde ayer por la tarde."` |
| `category` | string | Yes | One of `systems`, `equipment`, `access`, `other` (BR-015) | `"equipment"` |
| `priority` | string | Yes | One of `low`, `medium`, `high` (BR-016) | `"high"` |

- **Mass-assignment defense (INSTR-002):** the schema declares `extra="forbid"` and contains **no** `status`, `owner_id`, or `id` fields — a client-supplied `{"status": "resolved", "owner_id": "..."}` is rejected `422` (unknown field), and `status` is always server-set to `pending` (BR-011). Owner is always the token subject.
- An invalid body never creates a partial incident (INSTR-011).

**Responses:**

| Status | Condition | Body |
|--------|-----------|------|
| `201` | Valid body | The created incident: `{"id", "title", "description", "category", "priority", "status": "pending", "created_at", "updated_at"}` — same field types as the list item plus `description`, with `status` always `"pending"` and `created_at == updated_at` |
| `422` | Validation failure (BR-013–016, INSTR-011) or forbidden extra field | Structured `422` shape, one `detail` entry per offending field |
| `401` | No/invalid token | `{"detail": "No autenticado"}` |

Example `201`:

```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "title": "Impresora no responde",
  "description": "La impresora de recepción no responde desde ayer por la tarde.",
  "category": "equipment",
  "priority": "high",
  "status": "pending",
  "created_at": "2026-10-05T16:20:00Z",
  "updated_at": "2026-10-05T16:20:00Z"
}
```

### `GET /api/incidents/{id}`

Incident detail plus chronological status history for the **owner** (FUNC-006). Path parameter `id`: UUID string; non-UUID → `422`.

**Response `200`:** the incident object (fields as in the `201` body above) plus a `history` array ordered **ascending by `changed_at`** (timeline order, S-04), assembled from `incident_history` (INSTR-005).

**History entry schema:**

| Field | Type | Nullable | Description | Example |
|-------|------|----------|-------------|---------|
| `id` | string (UUID) | No | History entry identifier | `"b1e2c3d4-1111-4aaa-9bbb-2c963f66afa6"` |
| `previous_status` | string \| null | Yes | `null` on the initial-creation entry; otherwise prior status (INSTR-002, BR-025) | `null` |
| `new_status` | string | No | Status after the change | `"pending"` |
| `changed_by` | string (UUID) | No | Actor's user id (the creator on the initial entry; the support agent on transitions, INSTR-005) | `"3fa85f64-5717-4562-b3fc-2c963f66afa6"` |
| `changed_at` | string (ISO 8601 UTC) | No | Transition instant, ascending sort key | `"2026-10-05T16:20:00Z"` |

**Errors:**

| Status | Condition | Body |
|--------|-----------|------|
| `200` | Incident exists **and** `owner_id` = token subject | Detail object with `history` |
| `401` | No/invalid token | `{"detail": "No autenticado"}` |
| `404` | Incident **missing or owned by another user** — one indistinguishable safe response; no existence leak, identifiers cannot be probed (INSTR-001, FUNC-006, BR-010) | `{"detail": "Incidencia no encontrada"}` |

### `PATCH /api/incidents/{id}/status`

Support-only status transition (FUNC-007, INSTR-004). Path parameter `id`: UUID string.

**Request body:**

| Field | Type | Required | Validation | Example |
|-------|------|----------|------------|---------|
| `new_status` | string | Yes | One of `pending`, `in_progress`, `resolved` (BR-017); anything else → `422`. Whether the value is a **legal transition** is decided by the service (→ `409`, INSTR-003) | `"in_progress"` |

This is the body's only field — no comment, owner, or timestamp fields are accepted (immutable history, BR-012/025).

**Transition matrix (validated before any write; INSTR-003, BR-023/024):**

| From \ To | `pending` | `in_progress` | `resolved` |
|-----------|-----------|---------------|------------|
| `pending` | ✗ `409` | ✓ | ✗ `409` (skip) |
| `in_progress` | ✗ `409` (backwards) | ✗ `409` (repeat) | ✓ |
| `resolved` | ✗ `409` | ✗ `409` | ✗ `409` (terminal) |

Every accepted transition atomically updates `incidents.status` + `updated_at` and inserts one immutable `incident_history` row (previous status, new status, actor = token subject, timestamp) in one transaction (INSTR-005, BR-025).

**Response `200`:** the updated incident object (as in the `201` body above) **plus** the latest history entry:

```json
{
  "incident": {
    "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "title": "Impresora no responde",
    "description": "La impresora de recepción no responde desde ayer por la tarde.",
    "category": "equipment",
    "priority": "high",
    "status": "in_progress",
    "created_at": "2026-10-05T16:20:00Z",
    "updated_at": "2026-10-06T09:12:00Z"
  },
  "latest_history": {
    "id": "c2d3e4f5-2222-4bbb-8ccc-2c963f66afa6",
    "previous_status": "pending",
    "new_status": "in_progress",
    "changed_by": "9f8e7d6c-3333-4ccc-8ddd-2c963f66afa6",
    "changed_at": "2026-10-06T09:12:00Z"
  }
}
```

**Errors (checked in this order):**

| Status | Condition | Body |
|--------|-----------|------|
| `401` | No/invalid token | `{"detail": "No autenticado"}` |
| `403` | Caller role is `employee` — role is checked **before** any incident lookup, so the forbidden response is uniform and leaks nothing (INSTR-004, BR-022) | `{"detail": "No autorizado"}` |
| `404` | Caller is support but the id is missing or malformed-UUID-not-found — same safe 404 shape as the owner route, no existence leak (INSTR-001 discipline, BR-010) | `{"detail": "Incidencia no encontrada"}` |
| `409` | Value is in the fixed set but the transition is backwards, skipped, repeated, or out of `resolved` (INSTR-003, BR-023/024) | `{"detail": "Transición de estado no válida"}` |
| `422` | `new_status` absent or outside the fixed set | Structured `422` shape |

Support is **not** owner-scoped on this route: the support role exists precisely to advance any employee's incident, and owner isolation remains intact because employees can never call it (INSTR-004; no support mobile screen exists in v1 — API-only, FUNC-007).

### `GET /health`

Operational liveness probe (FUNC-008). Public, no auth, no rate limit.

**Response `200`:** `{"status": "ok"}` — a single sanitized literal; no stack traces, configuration, database internals, or version details (BR-032). A failing deployment surfaces errors through process logs and the sanitized `500` handler, never through this body.

### Traceability summary

| Requirement | Covered by |
|-------------|-----------|
| FUNC-001 sign-in | `POST /api/auth/login` |
| FUNC-002 sign-out | Token lifecycle (client-side deletion; no server endpoint, Q-008) |
| FUNC-003 list | `GET /api/incidents` |
| FUNC-004 filter | `GET /api/incidents?status=…` |
| FUNC-005 create | `POST /api/incidents` |
| FUNC-006 detail + history | `GET /api/incidents/{id}` |
| FUNC-007 status update | `PATCH /api/incidents/{id}/status` |
| FUNC-008 health | `GET /health` |
| FUNC-009 seed | Out of API scope — module `app/seed/demo_seed.py`, no endpoint (INSTR-010) |
| CONF-001/003, Q-002/004/007/008, Q-011 | Auth, rate limiting, pagination, and lifecycle sections above |

---

## Backend Code Conventions

Concrete conventions for the `api` component (`services/api`): Python 3.10 (CPython), FastAPI 0.123.10, Pydantic v2, SQLAlchemy 2.0.54, Alembic 1.20.0, PyJWT 2.15.1, bcrypt 4.3.0 (CONF-011, Q-003, Q-009, Q-010, Q-011). Module layout follows `docs/design/archetype.md` exactly; every rule below cites the requirement or design decision it serves.

### File and folder naming

- Modules and packages: `snake_case` (`auth_service.py`, `incident_repository.py`, `ratelimit.py`); package names are single lowercase words (`api`, `core`, `domain`, `db`, `seed`).
- One route module per resource under `app/api/routes/` (`auth.py`, `users.py`, `incidents.py`, `statuses.py`) — one HTTP operation per function, never two methods on one handler (FastAPI best practice).
- Pydantic schemas live in `app/domain/schemas/` split by resource (`auth.py`, `user.py`, `incident.py`); ORM models in the single `app/domain/models.py` (three models, small enough to stay together); Alembic migrations in `migrations/versions/` with sequential numeric prefixes (`0001_initial_schema.py`).
- Test files mirror the source layout under `tests/unit/` and `tests/integration/`, named `test_<module>.py` (INSTR-013 structure from `archetype.md`).

### Casing rules

| Element | Convention | Example |
|---------|-----------|---------|
| Files / modules / packages | `snake_case` | `incident_service.py` |
| Classes (ORM models, Pydantic schemas, services, exceptions) | `PascalCase` | `IncidentHistory`, `LoginRequest`, `InvalidTransitionError` |
| Functions / methods / variables / parameters | `snake_case` | `list_owned_incidents`, `normalize_email` |
| Constants | `UPPER_SNAKE_CASE` | `TOKEN_TTL_SECONDS = 28800` |
| Type aliases for DI | `PascalCase` + `Dep`/`Depends` suffix | `CurrentUserDep`, `DbSession` |
| DB tables / columns | `snake_case`, plural table names | `users`, `incident_history`, `password_hash` |
| JSON field names | `snake_case`, no aliasing layer | `access_token`, `new_status` (per `api-contract.md`) |
| Environment variables | `UPPER_SNAKE_CASE` | `DATABASE_URL`, `JWT_SECRET` |
| Enum members (Python and DB values) | lowercase `snake_case` values | `Status.IN_PROGRESS = "in_progress"` |

### Module layout and layering

Strict one-way dependency direction — `api` → `domain` → `db`, with `core` leaf-used by all (CONF-011 layered clean code):

- `app/api/routes/*` — thin handlers only: declare `Annotated` dependencies, call one domain service, map domain exceptions to HTTP status codes. No business logic, no direct ORM queries in routes.
- `app/domain/services/*` — all business rules: email normalization + generic-failure login (`auth_service.py`, INSTR-006, BR-002), owner-scoped list/detail/create-as-Pending (`incident_service.py`, INSTR-001/002), linear transition matrix + immutable history write (`status_service.py`, INSTR-003/004/005). Services are plain classes/functions receiving a `Session` via DI — testable without HTTP.
- `app/db/repositories/*` — the ONLY place SQLAlchemy queries are written; every incident query filters by `owner_id` here (INSTR-001). Services never import `select` themselves.
- `app/core/security.py` — bcrypt hash/verify used directly, never passlib (Q-010, BR-026); PyJWT encode/decode pinning `algorithms=["HS256"]` (Q-011, BR-004).
- `app/core/ratelimit.py` — in-memory per-IP/per-email counters exposed as FastAPI dependencies (Q-004, BR-007).
- `app/core/config.py` — the only module that touches environment variables.

### Typing style

- Strict typing everywhere (CONF-011 `strict_typing`): every function has full parameter and return annotations; `X | None` (PEP 604) instead of `Optional[X]`; modern collections generics (`list[str]`, `dict[str, Any]`).
- Dependencies declared with `Annotated` type aliases reused across routes (FastAPI best practice):

  ```python
  CurrentUserDep = Annotated[User, Depends(get_current_user)]
  DbSession = Annotated[Session, Depends(get_db)]
  ```

- Pydantic v2: no `...` defaults for required fields, no `RootModel` (banned); request bodies use `Field(min_length=..., max_length=...)` for title/description (BR-013/014) and `Literal`/`Enum` types for category/priority/status (BR-015/016/017); route handlers return typed response models so Pydantic serializes and filters the response (never returning raw ORM objects untyped).

### Sync vs async

All path operations, dependencies, and services use plain `def` (not `async def`): the stack is sync SQLAlchemy 2.0 + psycopg 3 sync, and `def` handlers run in the threadpool so blocking DB calls never stall the event loop (FastAPI rule). No `asyncio`/`Asyncer` mixing is needed in v1.

### Error-handling pattern

- Domain services raise typed domain exceptions defined in `app/domain/` (e.g. `InvalidCredentialsError`, `IncidentNotFoundError`, `ForbiddenRoleError`, `InvalidTransitionError`, `RateLimitExceededError`) — never raise `HTTPException` below the routes layer.
- Route handlers (or small mapper helpers) translate each domain exception to exactly the status code + Spanish `detail` string fixed in `api-contract.md`: `401` `"Credenciales inválidas"` (uniform, BR-002), `403` `"No autorizado"` / `"Cuenta inactiva"`, `404` `"Incidencia no encontrada"` (same body for missing and foreign ids — BR-010), `409` `"Transición de estado no válida"`, `429` `"Demasiados intentos. Inténtelo de nuevo más tarde."` + `Retry-After` header (CONF-003).
- A global exception handler catches unhandled `Exception` and returns sanitized `500 {"detail": "Error interno del servidor"}` — no stack traces, SQL, or config in any response (FUNC-008 discipline applied globally).
- Validation failures are never caught: Pydantic v2 returns the standard structured `422` field-level body (INSTR-011); the client maps descriptors to its own Spanish per-field copy.
- Transaction discipline: repositories commit nothing; the domain service orchestrates the unit of work (transition validation → status update + history insert in one transaction, INSTR-005), and `get_db` rolls back on unhandled exceptions.

### Logging pattern

- Standard library `logging`: one module-level logger per module, `logger = logging.getLogger(__name__)`; configured once in `app/main.py` (level from `ENVIRONMENT`).
- NEVER log passwords, tokens, or `Authorization` headers (INSTR-008, BR-026). Log authentication failures with the request client IP only — never the attempted email in a way that pairs with a success/failure outcome that could aid enumeration; bcrypt verification results and JWT claims are not logged.
- Unexpected exceptions are logged server-side with `logger.exception(...)` (full trace in server logs only) while the client still receives the sanitized 500 body.
- uvicorn access logs remain enabled; no custom request-id middleware in v1.

### Configuration access

- `app/core/config.py` defines one pydantic-settings `Settings` class; every module reads config ONLY through the DI-provided settings object — `os.environ` / `os.getenv` appear nowhere else (CONF-006).
- Required variables: `DATABASE_URL`, `JWT_SECRET`, `ENVIRONMENT`; seed-only: `DEMO_EMPLOYEE_PASSWORD`, `DEMO_SUPPORT_PASSWORD` (no default password ever embedded — BR-033); rate-limit knobs with design defaults: `LOGIN_RATELIMIT_IP_MAX=5`, `LOGIN_RATELIMIT_EMAIL_MAX=5`, `LOGIN_RATELIMIT_WINDOW_SECONDS=900` (Q-004).
- `.env` is Git-excluded; `.env.example` documents every variable with placeholder values (BR-029/039). Missing mandatory variables fail startup with a clear message.

### Dependency injection style

- Framework-native FastAPI DI only — no third-party DI container.
- `get_db` is a `yield` dependency that opens a session per request and closes/rolls it back on exit; repositories and services are instantiated inside route dependencies, receiving the session (composition root in `app/api/deps.py`).
- Auth guards are reusable dependencies: `get_current_user` (validates JWT with the HS256 allow-list, loads the active user), `require_role("support")` wrapping it for the status route (BR-022). Access level per route is declared on the route signature itself — mandatory per CONF-011.
- The rate limiter's counters live in a module-level singleton injected via `Depends` so tests can reset/inject fake clocks.

### Lint, format, and static checks

| Tool | Role | Key rules |
|------|------|-----------|
| Ruff (lint + format) | Format and lint in one tool; single `ruff check . && ruff format --check .` gate | Rules: `E,W` (pycodestyle), `F` (pyflakes), `I` (import order), `B` (bugbear), `UP` (pyupgrade), `S` (bandit — flags hardcoded secrets/SQL strings), `SIM`; line length 100; uv-managed |
| mypy | Strict typing gate (CONF-011 `strict_typing`) | `strict = true`, plugins: `pydantic.mypy`, `sqlalchemy.plugin` if available for the pinned line |
| pytest + pytest-cov | Test and coverage gate (see `## Backend Test Strategy`) | `--cov=app --cov-fail-under=90` on business-logic packages (INSTR-013) |

- Bandit-class rules (`S`) are treated as blocking: any flagged SQL string concatenation or hardcoded secret fails lint (enforces CONF-005/CONF-006 mechanically).
- Alembic migration files are excluded from Ruff format where autogenerated; migration review checks that every schema change ships as a migration, never ad-hoc SQL (CONF-008).

### Database access conventions

- SQLAlchemy 2.0 style only: `select()` constructs with typed `Mapped[...]`/`mapped_column(...)` declarations; no legacy `Query` API; no raw SQL strings anywhere — the parameterization guarantee is structural (CONF-005, BR-027).
- ORM model names mirror table names singular (`User`, `Incident`, `IncidentHistory`); enum columns store the lowercase string values from `data-model.md` via a Python `enum.Enum` shared with Pydantic (`app/domain/enums.py`), so API values, DB values, and transitions use one source of truth.
- Timestamps are timezone-aware `DateTime(timezone=True)` mapped to `TIMESTAMPTZ`, set with `server_default=func.now()`; `updated_at` uses `onupdate=func.now()` (data-model.md).
- History rows are never updated or deleted by application code; the repository exposes append and read-only queries (INSTR-005, BR-012).

---

## Backend Test Strategy

Test strategy for the `api` component (`services/api`), satisfying INSTR-013 / BR-034: pytest 8.3.3 unit tests for business rules and integration tests for authorization (ownership isolation, role protection of the status endpoint), **coverage target ≥ 90% of business logic** — the exact wizard QA threshold (`thresholds: 90` in `AGENTS.md`), enforced mechanically with pytest-cov.

### Test types and what each covers

| Type | Framework | Scope | Requirement coverage |
|------|-----------|-------|----------------------|
| Unit | pytest 8.3.3, no DB, no HTTP | Domain services (`auth_service`, `incident_service`, `status_service`), the 3×3 transition matrix incl. Resolved-terminal (INSTR-003), rate-limiter counters with an injected fake clock (Q-004), JWT encode/decode incl. wrong-algorithm and expired-token rejection (Q-011), bcrypt hash/verify round-trip (Q-010), Pydantic schema validation boundaries (title 5–100, description 10–1000, enums — BR-013..017), email normalization (INSTR-006) | INSTR-003/005/006, BR-002/004/007/011/023/024/026 |
| Integration | pytest + httpx `TestClient` (in-process ASGI) against real PostgreSQL 16.15 from `TEST_DATABASE_URL` | Full request→response paths: login flow (success, generic 401, 429 + `Retry-After`, BR-001/002/007), **owner isolation** — employee A never sees employee B's incidents on list, detail, or history; foreign/missing id both return the identical 404 shape (INSTR-001, FUNC-003/006, AC-03), **role protection** — employee `403` on `PATCH /api/incidents/{id}/status`, support transitions succeed and append exactly one history row per accepted change (INSTR-004/005, AC-07), create-always-Pending (INSTR-002), 422 field-level validation errors (INSTR-011), safe `/health` (FUNC-008), inactive-account `403`, sanitized 500 handler | INSTR-001/002/004/011/013, CONF-003, FUNC-001..009 |
| Contract | FastAPI's OpenAPI schema, asserted in one integration test | Every route, access annotation, and response model in `api-contract.md` stays in sync: schemas validate, snake_case field names, error bodies match the documented shapes | CONF-011 |

### Test framework and tooling

- **pytest 8.3.3** (wizard-pinned) + **pytest-cov ≥ 6.0** for the 90% gate + **httpx ≥ 0.28** for the ASGI test client + **freezegun** for token-expiry and rate-limit-window determinism (time is never slept in tests).
- Coverage command (the gate): `pytest --cov=app --cov-report=term-missing --cov-fail-under=90` scoped to business-logic packages `app/domain`, `app/core/security.py`, `app/core/ratelimit.py`, `app/api/deps.py` — the modules where INSTR-013's "business logic" lives. Route wiring is covered transitively by integration tests.
- Test naming: `test_<unit>_<scenario>_<expected_outcome>` (e.g. `test_get_incident_of_other_user_returns_safe_404`); AAA structure (Arrange–Act–Assert) per test; one behavior per test.
- Markers: `@pytest.mark.integration` for DB-dependent tests so `pytest -m "not integration"` runs the fast unit tier without PostgreSQL.

### Test data and fixtures strategy (`tests/conftest.py`)

- **Database provisioning:** the engine is built from `TEST_DATABASE_URL` (PostgreSQL — the sandbox's real `pg-sandbox` server). Alembic runs `upgrade head` once per session against the test database, so the schema under test is always the migration-defined schema (CONF-008), never a parallel hand-written DDL.
- **Isolation:** each test runs inside a transaction that is rolled back at teardown (session-per-test with nested savepoints), so tests are order-independent and share no state.
- **User fixtures:** `employee_a`, `employee_b`, `support_agent` — created with bcrypt-hashed passwords from fixture constants (test-only values, never the demo seed's env passwords); an `inactive_employee` for the deactivation path.
- **Auth helpers:** `auth_headers(user)` issuing a real token through `core/security.py` so every integration test exercises the actual JWT path (HS256 allow-list, 8 h expiry).
- **Incident fixtures:** per-status owned incidents (pending / in_progress / resolved) with consistent history rows, mirroring the demo seed's shape (FUNC-009) but self-contained — tests never depend on the seed having run.
- **Rate-limit tests** inject a fake clock and reset the in-memory counter singleton between tests (deterministic, no sleeping).
- **Negative-config tests** verify startup/seed failures: missing `JWT_SECRET`, missing `DEMO_EMPLOYEE_PASSWORD`/`DEMO_SUPPORT_PASSWORD` fail with clear messages and no default password (BR-033); seed idempotency is proven by running it twice and asserting the same record count.

### How backend behavior is exercised end-to-end

- Each FUNC flow is tested at the HTTP boundary as a sequence of real calls: sign in → list own incidents → filter by status → create incident (asserts `pending` + owner) → fetch detail with history → support transition (asserts history append) → repeat transition rejected `409` → employee transition attempt rejected `403`.
- **Playwright browser end-to-end coverage: N/A — no browser UI exists.** The only client is the native Android app (Kotlin/Compose), which Playwright cannot drive; per binding Q-005 the app's demo/test flows run against the in-memory `FakeIncidentRepository` with the identical API contract, and the Android side is covered by JUnit + Compose UI tests in `apps/mobile/app/src/test` (mirrored ViewModel/validation tests). The API-backed flows are therefore covered end-to-end by the httpx integration tier above, which is the complete e2e surface for this architecture.
- Response-time budget (CONF-009 < 2 s) is respected structurally: the whole suite runs against the local sandbox Postgres, and integration tests assert correctness, not latency; no artificial sleeps exist in production code paths.

### Database sourcing rule (binding)

Integration tests take their PostgreSQL **exclusively from the `TEST_DATABASE_URL` environment variable** — the build sandbox provides real PostgreSQL servers and has **no Docker**, so no test may depend on Docker, docker-compose, or Testcontainers to pass. Testcontainers exists only as an optional developer-machine fallback used when `TEST_DATABASE_URL` is unset, and the suite never requires it. `infra/docker-compose.yml` is a local-dev convenience only and is never on any test's critical path.

---

## Visual Design System

This design system defines the visual language for MiniSoporte (native Android, Kotlin + Jetpack Compose, portrait phone, Spanish-language UI, package `com.nexti.minisoporte`). All values below are extracted verbatim from the user-approved Pre-Design draft `docs/design/pre_design_mobile.html` (mobile mode, 390×844 baseline). Production screens implement these tokens natively in Compose (Material 3 theming driven by `Color.kt` / `Type.kt` / `Shape.kt` equivalents of the values below).

### Color Palette

| Token | Hex | Usage |
|---|---|---|
| primary | #1E3A2A | App bar / hero band background, primary buttons, selected chips & choice options, active states |
| primary-hover | #2A4E39 | Pressed/hover state of primary buttons and selected controls |
| secondary (accent) | #EFC64E | FAB background, brand accent, demo-hint border highlights |
| accent-soft | #FBF1D4 | Demo hint box background, pending status badge background |
| background | #F5F7F5 | Screen background behind cards and bars |
| surface | #FFFFFF | Cards, bottom navigation bar, form fields, sheet backgrounds |
| text-primary | #1C2B22 | Primary text: titles, body, field values |
| text-muted | #5C6B62 | Secondary text: subtitles, metadata, hints, counters |
| border | #DDE4DF | Card borders, field outlines, dividers, bottom-nav top border |
| danger / error | #B3261E | Field error borders/text, destructive semantics |
| danger-soft | #FBEAE9 | Error field background |
| status-pending-bg | #FBF1D4 | "Pendiente" status badge background |
| status-pending-fg | #7A5B10 | "Pendiente" status badge text |
| status-in-progress-bg | #E1EAF6 | "En progreso" status badge background |
| status-in-progress-fg | #2A4E79 | "En progreso" status badge text |
| status-resolved-bg | #DFEFE3 | "Resuelta" status badge background |
| status-resolved-fg | #1E5B36 | "Resuelta" status badge text |
| text-on-primary | #FFFFFF | Text and icons on primary surfaces (app bar, primary buttons) |
| priority-high-border | #C4463E | "Alta" priority pill border |
| priority-high-text | #A32E27 | "Alta" priority pill text |
| priority-medium-border | #B98A1E | "Media" priority pill border |
| priority-medium-text | #7A5B10 | "Media" priority pill text |
| priority-low-border | #6B8F7A | "Baja" priority pill border |
| priority-low-text | #446B54 | "Baja" priority pill text |

**Status and priority are always encoded as color + text (INSTR-012):** every status badge shows its label ("Pendiente" / "En progreso" / "Resuelta") on its paired background; priority pills show their label inside a bordered pill — never color alone.

**Source:** values extracted verbatim from the user-approved Pre-Design draft `docs/design/pre_design_mobile.html`.

### Button Styles

| Variant | Background | Color | Border | Border-radius | Padding |
|---|---|---|---|---|---|
| Primary (filled) | #1E3A2A | #FFFFFF | none | 8px | 14px 16px, min-height 48px |
| Secondary / outline (retry) | #FFFFFF | #1E3A2A | 1px solid #1E3A2A | 8px | 10px 24px, min-height 44px |
| Text-link / app-bar button ("Salir", "Volver") | rgba(255,255,255,0.12) on the #1E3A2A bar | #FFFFFF | 1px solid rgba(255,255,255,0.25) | 8px | 8px 14px, min-height 40px |
| Danger | #B3261E (where a destructive action is used; the draft uses danger mainly as field-error styling: 1px solid #B3261E border on #FBEAE9 background) | #FFFFFF | none | 8px | 14px 16px, min-height 48px |
| Filter chip (pill) | unselected: #FFFFFF; selected: #1E3A2A with #FFFFFF text | #1C2B22 unselected / #FFFFFF selected | 1px solid #DDE4DF unselected; 1px solid #1E3A2A selected | 999px (pill) | 8px 14px, min-height 36px |
| Choice group option (Categoría / Prioridad) | unselected: #FFFFFF; selected: #1E3A2A with #FFFFFF text | #1C2B22 unselected / #FFFFFF selected | 1px solid #DDE4DF unselected; 1px solid #1E3A2A selected | 8px | 10px 14px, min-height 40px |
| FAB (centered, bottom nav) | #EFC64E | icon/text #1E3A2A | 3px solid #F5F7F5 | 50% (circle) | 52×52dp, raised shadow 0 4px 16px rgba(30,58,42,0.18) |

All buttons: disabled state at reduced opacity (~0.5) with the submit label switching to a progress/locked state (e.g. "Guardando…") while a submission is in flight (duplicate-submission lock). Minimum touch target 48dp.

### Typography

Font family for every scale: system sans stack — `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, system-ui, sans-serif` (on Android this maps to **Roboto**, so the Compose implementation uses the Roboto `FontFamily.Default` throughout). Sizes are given in px as extracted from the draft with the rem conversion (1rem = 16px).

| Scale | Font-family | Size | Weight | Line-height |
|---|---|---|---|---|
| heading-xl | system sans (Roboto on Android) | 26px / 1.625rem | 800 | 1.2 |
| heading-lg | system sans (Roboto on Android) | 18px / 1.125rem | 700 | 1.3 |
| heading-md | system sans (Roboto on Android) | 15px / 0.9375rem | 700 | 1.4 |
| body | system sans (Roboto on Android) | 15px / 0.9375rem | 400 | 1.5 |
| body-small | system sans (Roboto on Android) | 14px / 0.875rem | 400 | 1.45 |
| caption | system sans (Roboto on Android) | 12.5px / 0.78rem | 400 | 1.4 |
| label | system sans (Roboto on Android) | 13px / 0.8125rem | 600 | 1.35 |
| button | system sans (Roboto on Android) | 15px / 0.9375rem | 700 | 1.2 |
| badge | system sans (Roboto on Android) | 12px / 0.75rem | 700 | 1.2 |
| chip | system sans (Roboto on Android) | 13.5px / 0.84rem | 600 | 1.2 |
| bottom-nav | system sans (Roboto on Android) | 11.5px / 0.72rem | 600 | 1.2 |

Usage: heading-xl = login hero title; heading-lg = screen titles; heading-md = card and section titles; label = field labels; caption = meta text (creation dates, timestamps, counters); badge = status badges; chip = filter chips; bottom-nav = navigation item labels.

### Spacing & Layout Tokens

- **Base grid:** 8px unit. Steps: space-1 = 8px, space-2 = 16px, space-3 = 24px, space-4 = 32px.
- **Card style:** surface #FFFFFF, border 1px solid #DDE4DF, radius 8px, shadow `0 1px 2px rgba(30,58,42,0.08), 0 2px 8px rgba(30,58,42,0.06)`, internal padding 16px.
- **Phone-portrait layout:** full-width single column, content padding 16px, portrait-only (390×844 baseline; scales to other phone widths as a single column). No container max-width beyond the phone frame — the only cap is the login card at 480px.
- **App bar:** background #1E3A2A, white text (#FFFFFF), brand mark left, optional app-bar button right (rgba(255,255,255,0.12) background with rgba(255,255,255,0.25) border).
- **Bottom navigation bar** (list screen): surface #FFFFFF with top border 1px solid #DDE4DF, centered accent FAB (#EFC64E, 52dp circle, 3px #F5F7F5 border).
- **Sign-in screen:** full-width primary hero band (#1E3A2A) with brand mark and title/subtitle, then the centered white login card on #F5F7F5.
- **Corners:** 8px radius everywhere except pills/chips/badges (999px pill) and the FAB (circle).
- **Focus ring:** 3px rgba(30,58,42,0.15) around focused fields and controls (maps to Compose focus/indication styling for keyboard and TalkBack navigation).

### Icon Set

The Pre-Design draft uses inline Material-style line SVG icons (2px stroke, 24px viewBox) plus a text "M" brand mark and emoji glyphs in state placeholders. For the native app the icon set is **Material Icons (outlined style, bundled via `androidx.compose.material:material-icons-extended`, version BOM-managed)** — no external icon library and no custom icon fonts. The "M" brand mark is rendered as text in the app-bar/hero, matching the draft.

---

## Mockups

The single visual source for all screens is the user-approved Pre-Design draft (`docs/design/pre_design_mobile.html`, mobile mode). Its verified full-page screenshot (viewport 390×844) is embedded below — it captures the S-01 sign-in screen, which is the initially active screen of the draft. All UI copy is **Spanish**, layouts are **portrait phone** on a 390×844 baseline, and production screens are built natively in **Kotlin + Jetpack Compose** following `docs/design/design-system.md`. The draft implements screens S-01–S-04 with real interactive states (validation, filtering, navigation) described per screen below.

### S-01 — Iniciar sesión (Sign in)

![MiniSoporte mobile — Pre-Design draft](screenshots/pre_design_mobile.png)

**Layout & key components.** Full-width dark-green hero band (#1E3A2A) containing the brand mark "M", the "MiniSoporte" title (heading-xl, 26px/1.625rem, weight 800) and a subtitle. Below the hero, on the #F5F7F5 background, a centered white login card (surface #FFFFFF, 1px #DDE4DF border, 8px radius, capped at 480px) with:
- a demo hint box (accent-soft #FBF1D4 background, 1px #EFC64E border) showing the demo credentials;
- an inline generic error alert (shown after a failed attempt);
- "Correo electrónico" and "Contraseña" fields, each with a required asterisk (*), field label (13px/0.8125rem, weight 600) and field-level error text in #B3261E on #FBEAE9 when validation fails;
- a full-width primary button (#1E3A2A, white text, 8px radius, 14px 16px padding, min-height 48px).

**Interaction states** (as implemented in the approved draft, FUNC-001): the form validates that both credential fields are filled (field-level required errors otherwise), disables the button while a simulated 600ms request runs (pending-submission lock), accepts exactly `admin`/`admin` to enter the app, and shows a generic inline error for anything else without revealing whether the account exists. Managed state: `authed` flag, required-field validation state, pending-submission lock.

### S-02 — Mis incidencias (My incidents list)

![MiniSoporte mobile — Pre-Design draft](screenshots/pre_design_mobile.png)

**Layout & key components.** Dark app bar (#1E3A2A) with the brand mark, the title "Mis incidencias" and the signed-in user's email as subtitle, plus a "Salir" (sign out) app-bar button (rgba(255,255,255,0.12) background, rgba(255,255,255,0.25) border, white text). Below the bar, on #F5F7F5 with 16px content padding:
- a horizontally scrollable row of filter chips — Todas / Pendiente / En progreso / Resuelta — each with a live count badge; the active chip is filled dark green (#1E3A2A, white text), inactive chips are white with #DDE4DF border; chips are 999px pills;
- incident cards (surface #FFFFFF, 1px #DDE4DF border, 8px radius): top row with the incident title (heading-md, 15px/0.9375rem, weight 700) and its status badge on the right, then a meta row with a category pill, a priority pill (bordered pill: Alta #C4463E border / #A32E27 text, Media #B98A1E / #7A5B10, Baja #6B8F7A / #446B54) and "Creada el <date>" caption text. Tapping a card opens S-04;
- bottom navigation bar (white surface, 1px top border #DDE4DF) with the list item and a centered accent FAB (52dp #EFC64E circle, "+" glyph #1E3A2A, 3px #F5F7F5 border) that opens S-03;
- **empty state:** dashed-border box with an icon, "Sin incidencias" title and hint text; **error state:** the same box plus a retry button (secondary/outline: white bg, #1E3A2A text and border).

**Interaction states** (as implemented in the approved draft): status filter chips — tapping Todas/Pendiente/En progreso/Resuelta filters the in-page incident array by status and updates each chip's live count badge (FUNC-004); incident list — renders only the owned incidents newest-first (title, category, priority, status badge, creation date) and shows the empty state when the active filter matches nothing (FUNC-003); sign out — the header "Salir" button resets the authenticated state and returns to the sign-in screen with a confirmation toast (FUNC-002).

### S-03 — Nueva incidencia (New incident form)

![MiniSoporte mobile — Pre-Design draft](screenshots/pre_design_mobile.png)

**Layout & key components.** Dark app bar (#1E3A2A) with a back button ("Volver") and the title "Nueva incidencia". Below, a form card (surface #FFFFFF, 1px #DDE4DF border, 8px radius) on #F5F7F5 with 16px padding containing:
- "Título" text input with a live "n / 100" character counter (caption style, #5C6B62) and field-level errors in #B3261E;
- "Categoría" choice group (Sistemas / Equipos / Acceso / Otro) — 8px-radius options, selected option filled #1E3A2A with white text;
- "Prioridad" choice group (Baja / Media / Alta) with the same selected style;
- "Descripción" textarea with a live "n / 1000" character counter;
- a hint noting the incident will be created in "Pendiente" state (INSTR-002: initial status is always Pending);
- the "Guardar incidencia" primary button (#1E3A2A, full width, min-height 48px) with duplicate-submission lock — the label switches to "Guardando…" while saving.

**Interaction states** (as implemented in the approved draft, FUNC-005 / INSTR-002): live per-field validation — title 5–100 chars, description 10–1000 chars with char counters, required category and priority choice groups; the submit button is disabled while saving; on success the form prepends a new "Pendiente" incident with an initial history entry and returns to the list with a confirmation toast.

### S-04 — Detalle de incidencia (Incident detail + status history)

![MiniSoporte mobile — Pre-Design draft](screenshots/pre_design_mobile.png)

**Layout & key components.** Dark app bar (#1E3A2A) with a back button ("Mis incidencias") and the title "Detalle". Below, on #F5F7F5 with 16px padding:
- a detail card (surface #FFFFFF, 1px #DDE4DF border, 8px radius) showing "ID <uuid>" (caption), the incident title (heading-md) with its status badge (color + label, INSTR-012), the description block (body, line-height 1.5), and label/value rows for Categoría, Prioridad, Estado and Fecha de creación (label 13px weight 600, value 15px);
- a "Historial de estados" section with a vertical timeline: each entry shows a "Pendiente → En progreso" style status line ("Creación — Pendiente" for the first entry), a timestamp and the actor email (caption style), ordered newest-first, with the latest entry highlighted via a filled dot on the timeline;
- a **safe no-access state** for a missing or foreign id: dashed-border box with a lock icon, "Incidencia no disponible" title and "No se encontró la incidencia o no tienes acceso a ella." hint — owner-based authorization is enforced server-side, the UI never leaks another user's incident.

**Interaction states** (as implemented in the approved draft, FUNC-006): tapping a list card in S-02 opens the detail view showing identifier, title, description, category, priority, status badge, creation date, and the newest-first chronological status-history timeline; a missing id renders the safe no-access state described above. Navigation between all four screens — bottom navigation bar plus drill-down (list to detail, FAB and back buttons) — switches views client-side in the draft (in production, Compose navigation) and scrolls each view back to top.

---

## Frontend Code Conventions

Conventions for the single frontend component `mobile-app` (Kotlin 2.3.21 + Jetpack Compose, BOM 2026.09.00, `apps/mobile`, package `com.nexti.minisoporte`, minSdk 26 / compileSdk 36 / jvmTarget 17). These are architect defaults aligned with the pinned stack in `tech-stack.md`, the package layout in `archetype.md`, and the user-approved Pre-Design draft.

### Project & file organization

- Feature-first packages under `ui/`: `ui/theme`, `ui/navigation`, `ui/signin` (S-01), `ui/incidents` (S-02), `ui/newincident` (S-03), `ui/detail` (S-04), `ui/components` (shared composables), per `archetype.md`.
- One top-level composable per screen file, named after the screen: `SignInScreen.kt`, `IncidentListScreen.kt`, `NewIncidentScreen.kt`, `IncidentDetailScreen.kt`; its state holder beside it (`SignInViewModel.kt`, …). Shared pieces live in `ui/components/` (`StatusBadge.kt`, `IncidentCard.kt`, `ErrorRetryState.kt`).
- Data layer: `data/model/` (domain models + `Enums.kt`), `data/repository/IncidentRepository.kt` (interface — the Q-005 seam), `data/fake/FakeIncidentRepository.kt` (v1 in-memory implementation used by debug builds, previews and tests), `data/remote/` (Retrofit interface, kotlinx.serialization DTOs, mappers, future `RetrofitIncidentRepository`). Networking in `network/` (`ApiClient.kt`, `AuthInterceptor.kt`); token storage in `security/TokenStore.kt`.
- Single-Activity host: `MainActivity.kt` hosts the entire Compose tree; no Fragments. All version pins live in `gradle/libs.versions.toml` (Q-013); `gradle.properties` sets `android.useAndroidX=true`.

### Naming & casing

- Composables, classes, data classes: `PascalCase` (`IncidentCard`, `StatusBadge`). Functions and properties: `camelCase`. Constants: `SCREAMING_SNAKE_CASE`. Packages: lowercase, no underscores (`com.nexti.minisoporte.ui.newincident`).
- Files named after the single top-level declaration they contain (`StatusBadge.kt` contains `StatusBadge`).
- State classes: `<Feature>UiState` (`IncidentListUiState`, `SignInUiState`); events as sealed interfaces or plain lambdas passed down.
- Compose test tags: `Modifier.testTag("s02_filter_chip_pendiente")` style — `s<screen><element>` snake-case suffix, mirroring the e2e selector convention in `frontend-test-strategy.md`.

### Component structure

- Stateless composables first: screen composables take a `UiState` value + event lambdas; state hoisting is mandatory (no business logic inside composables). `Modifier` is the first optional parameter of every composable; slot APIs for reusable scaffolds.
- Screens compose `Scaffold` with a top app bar and (on S-02) the bottom navigation bar; content goes in `LazyColumn` for lists with stable `key = { it.id }` items.
- Recomposition hygiene: no side effects during composition — data loads happen in `LaunchedEffect(key)`; derived values use `derivedStateOf`; state that must survive configuration changes uses `rememberSaveable` only for trivial UI state (form field text), never for session/data state (that lives in the ViewModel).
- Validation logic (title 5–100, description 10–1000, required category/priority) lives in the ViewModel, unit-testable without Compose; the UI only renders the resulting field-error state and char counters.

### State management

- Unidirectional data flow: `UiState` flows down, events flow up. Each ViewModel exposes `val uiState: StateFlow<Out>` via `MutableStateFlow` + `asStateFlow()`, updated with `update { it.copy(...) }`; screens collect with `collectAsStateWithLifecycle()`.
- `ViewModel` is the only owner of mutable screen state and coroutine launches (`viewModelScope`); composables never hold repository references.
- One-shot events (toast "Sesión cerrada", navigation on success) are emitted as consumed events from the ViewModel, not stored in state.

### Data fetching

- All data access goes through `IncidentRepository` (interface). v1 wires `FakeIncidentRepository`; the Retrofit implementation (`RetrofitIncidentRepository` + `IncidentApi` + DTO mappers) is swapped in behind the same interface — screens and ViewModels never know which is active (Q-005).
- Coroutines on the repository boundary; suspending functions for every read/write. Network failure paths always resolve to an error `UiState` with a retry event — never an unhandled exception or crash (CONF-009).
- The bearer token is attached by `AuthInterceptor` reading `TokenStore` (Android Keystore-backed); token material never appears in logs, DTOs, or `UiState` (CONF-002, INSTR-008).
- Serialization: kotlinx.serialization DTOs in `data/remote/dto/` mirroring the Pydantic response schemas; domain models never leak into the network layer (mappers in `data/remote/mappers/`).

### Styling

- Material 3 with the project theme in `ui/theme/` (`Color.kt`, `Type.kt`, `Theme.kt`) mapping the design tokens from `design-system.md` verbatim: primary #1E3A2A, secondary #EFC64E, background #F5F7F5, surface #FFFFFF, status badge pairs, 8dp corner radius, 8dp spacing grid.
- Only theme tokens are used in composables (`MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*`) — no hard-coded hex/DP in feature code. Cards: 8dp radius, 1dp border #DDE4DF equivalent, card shadow per design system. Chips/badges are pill-shaped (50% radius).
- Status is always rendered as text + color together (`StatusBadge`), never color alone (INSTR-012). Light theme only (the approved draft defines no dark palette).
- Portrait-only, phone form factor: no landscape or tablet layout variants; `AndroidManifest.xml` locks orientation and declares zero permissions (CONF-007).

### Accessibility baseline (WCAG AA)

- Minimum touch target 48dp for all interactive elements (matches the draft's field/button min-heights); visible focus handling via Material default focus rings.
- All icons carry `contentDescription` (or `null` when purely decorative, as with status-badge dots that are paired with text labels); toasts and errors use semantics so TalkBack announces them (`role="alert"`/`role="status"` equivalents via `liveRegion`).
- Contrast: text/background pairs from the palette meet AA (dark text #1C2B22 on #F5F7F5/#FFFFFF; white on #1E3A2A; status fg/bg pairs verified in the draft).
- Form fields use `OutlinedTextField`-style labels bound with semantics so errors are announced per field, mirroring the draft's per-field error pattern.

### Lint & format tooling

- **ktlint** (standard ruleset) for Kotlin formatting — 4-space indent, no wildcard imports, trailing commas per Kotlin style; enforced in the Gradle `check` task so `./gradlew check` gates formatting.
- **Android Lint** (bundled with AGP 8.13.2) with default severity plus errors on `ContentDescription`, `UnusedResources`, and hardcoded-text checks; no extra third-party static analysis tooling is introduced.
- Compose compiler metrics not enabled by default; `@Preview` composables are provided per screen against `FakeIncidentRepository` data and kept compiling (they are part of `check`).

---

## Frontend Test Strategy

Test strategy for `mobile-app` (Kotlin + Jetpack Compose at `apps/mobile`). **Coverage target: ≥90%** (wizard QA threshold from `AGENTS.md`; for the frontend this applies to critical UI logic — ViewModels, validation, and the repository seam — mirroring INSTR-013's backend gate). Enforcement: `./gradlew check` runs the full JVM-side suite; coverage of `ui/` ViewModels, `data/` and `security/` packages measured with JaCoCo/Compose-tooling counts toward the 90% gate on those packages.

### Test types

- **Unit (JVM, JUnit4)** — `app/src/test/java/com/nexti/minisoporte/`:
  - `ui/` ViewModel tests: sign-in validation and generic-failure behavior with submit lock (FUNC-001), list/filter state machine including loading/empty/error+retry (FUNC-003, FUNC-004), new-incident field validation (title 5–100, description 10–1000, required category/priority), duplicate-submission lock and initial `Pendiente` status (FUNC-005, INSTR-002), detail safe no-access state for missing/foreign ids (FUNC-006).
  - `data/` tests: `FakeIncidentRepository` behavior — owner scoping, newest-first ordering, status filter, history consistency (INSTR-001).
  - `security/` tests: `TokenStore` contract — save/read/delete; deletion on sign-out leaves no readable token (FUNC-002, CONF-002). No token value is ever asserted into logs.
- **Component (Compose UI tests)** — `androidx.compose.ui.test` JUnit4 rules (BOM 2026.09.00) driving single screens against `FakeIncidentRepository`: chip filter narrowing + count badges, card → detail drill-down, form error rendering next to fields with live counters, FAB/back navigation, button-disabled-while-submitting states. Test tags per the `s<screen>_<element>` convention.
- **Visual** — no pixel-diff tooling is introduced; visual fidelity is anchored on the approved Pre-Design screenshot (`screenshots/pre_design_mobile.png` in `mockups.md`) reviewed at PR time plus per-screen `@Preview`s kept compiling in `check`. (No screenshot-testing framework requested; nothing added beyond the wizard stack.)

### Mandatory Playwright end-to-end

E2E is REQUIRED and is exercised on the user-approved Pre-Design draft (`docs/design/pre_design_mobile.html`), which implements the full S-01→S-04 interaction model with in-page state — the same journeys the native app must reproduce against `FakeIncidentRepository`:

- **Journeys covered:** (1) sign-in happy path admin/admin → My incidents renders; sign-in failure shows the generic error without account-existence hints; submit button disabled during the request (FUNC-001); (2) filter chips Todas/Pendiente/En progreso/Resuelta narrow the list and update count badges (FUNC-004); (3) create incident via FAB → form validation errors → valid submit → list shows new Pendiente entry + confirmation toast (FUNC-005); (4) card → detail with full status-history timeline → back (FUNC-006); (5) sign-out via "Salir" → token-less state returns to S-01 (FUNC-002).
- **Selectors/test-id convention:** Playwright locators target the draft's stable structure (`#login-email`, `#login-submit`, `.chip[data-filter="pendiente"]`, `.incident-card[data-id]`, `#fab-new`, `#new-submit`, `#toast`, `.timeline li`); the native mirrors of these elements carry `Modifier.testTag("s01_email_input", …)`-style tags so the same journey can be re-expressed as Compose UI tests.
- **Environments:** local only — Playwright runs headless Chromium at the mobile viewport (390x844) against the draft opened via `file://`; no backend, no deployed environment, no CI pipeline (project is internal-testing only). Native end-to-end on device/emulator is covered by instrumented Compose tests driving the same five journeys against `FakeIncidentRepository` (Q-005 — no running backend required).

### Gating

- `./gradlew check :app:assembleDebug` (the contract build command) compiles and runs unit + Compose component tests; it must pass before any internal-test APK is shared.
- The five e2e journeys above are the acceptance mirror of FUNC-001/002/003/004/005/006; a failing journey blocks the build review even when unit tests pass.

---

## External Integrations

**This system has no external third-party integrations.** Every connection in the architecture is internal to the product itself: the Android client talks to the project's own FastAPI backend, and the backend talks to the project's own PostgreSQL database. This is a deliberate consequence of the project constraints — AGENTS.md forbids cloud infrastructure and external resources, the deployment target is internal testing only, and the extractions name no third-party service (no email, no push service, no storage provider, no identity provider, no payment or analytics provider). FUNC-008's `GET /health` and CONF-007's zero-permission, no-deep-link posture were designed precisely to keep the attack and integration surface at zero.

The only cross-boundary interaction worth designing for is the first-party client ↔ API link, documented here for completeness:

### Android app ↔ REST API (first-party, internal)

| Aspect | Design |
|--------|--------|
| Direction | Outbound from `apps/mobile` to `services/api`. |
| Protocol | HTTPS REST/JSON (Retrofit 3.0.0 + OkHttp 5.x + kotlinx.serialization, per Q-012). Plain HTTP is permitted only against a local development backend (CONF-004); the Android release/network security configuration rejects cleartext everywhere else. |
| Auth method | `Authorization: Bearer <JWT>` header on every protected call; the token is the HS256 8-hour JWT issued by `POST /api/auth/login` (Q-002, Q-011). No refresh flow (Q-008): on `401` the app clears the local token and routes back to S-01. |
| Data exchanged | Login credentials → token; incident list/create/detail/status-filter payloads; the current user profile (`GET /api/me`); nothing else. |
| Failure/retry handling | Online only (CONF-009): network errors never crash the app — every screen renders a recoverable error state with an explicit retry action; timeouts on OkHttp; the UI language reports the failure generically. Sign-in additionally throttles repeated failures server-side (CONF-003). |

### Boundary notes

- **No outbound calls from the backend.** `services/api` performs no webhook delivery, no SMTP, no external API calls; its only dependency is the local PostgreSQL instance.
- **No inbound callers besides the Android client and operations tooling.** `GET /health` serves local run/deployment validation only and discloses no internal details (FUNC-008).
- **Supply-chain note (not an integration):** Gradle/Maven and PyPI dependencies are resolved at build time from public registries; versions are pinned in `gradle/libs.versions.toml` and the uv lockfile so builds are reproducible and no runtime registry access is required.

---

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

---

## Environment Strategy

The project is internal-testing only (no production deployment, no cloud infrastructure, no CI/CD per the wizard constraints). Configuration is therefore simple: **two local-facing environments** — `development` and `internal-test` (staging-equivalent) — plus a `test` mode for the pytest suite. All secrets live in environment variables; nothing sensitive is ever committed (CONF-006).

### Environments

| Environment | Purpose | Database | Secrets | Transport |
|-------------|---------|----------|---------|-----------|
| `development` | Local backend + Android emulator/device work; demo seed allowed (FUNC-009) | Local PostgreSQL 16.15 (`infra/docker-compose.yml` convenience, or the sandbox `pg-sandbox` via `DATABASE_URL`) | Developer-owned `.env` (Git-excluded), values supplied per machine | Plain HTTP allowed locally (CONF-004) |
| `internal-test` | The deliverable environment: backend reachable from reviewer devices; seeded manually per Q-001 | PostgreSQL 16.15 instance reachable from the test network, schema via Alembic | Supplied by the operator as real environment variables (never a committed file); demo seed run manually with configured emails/passwords | **HTTPS required** (CONF-004) |
| `test` (pytest) | Automated verification | PostgreSQL from **`TEST_DATABASE_URL`** (real server, no Docker in the verification sandbox); Testcontainers only as an optional fallback on a Docker-equipped dev machine | Throwaway values injected by `conftest.py`; demo passwords from test-scoped env vars | In-process ASGI client (httpx) |

`production` is explicitly out of scope: the seed never runs automatically outside development/demo contexts (INSTR-010), and no production deploy, store publication, or cloud resource exists (wizard constraints).

### Configuration sources (build-time vs runtime)

- **Runtime (backend):** `services/api` reads configuration exclusively from environment variables via pydantic-settings (`app/core/config.py`) — no config is baked into images or source. Required variables:
  - `DATABASE_URL` — PostgreSQL connection string (Alembic's `env.py` reads the same value).
  - `JWT_SECRET` — HS256 signing key; must be a long random value unique per environment; compromise ⇒ rotate (all tokens invalidated by rotation, acceptable given the 8-hour TTL).
  - `ENVIRONMENT` — `development` | `internal-test` (gates the demo seed).
  - `DEMO_EMPLOYEE_PASSWORD`, `DEMO_SUPPORT_PASSWORD` — seed-only; the seed **fails with a clear message if either is missing and no default is embedded** (FUNC-009, INSTR-010).
  - `DEMO_SUPPORT_EMAIL` — support account email for the seed, per Q-001 (the demo employee is fixed at `empleado@minisoporte.test`).
- **Runtime (mobile):** the Android app takes its backend base URL per build type — debug builds point at the local/emulator backend (or run fully on the `FakeIncidentRepository`, per Q-005: debug builds, previews and tests need no running backend); release/internal-test builds point at the HTTPS internal-test host. No secrets are embedded in the APK — the app holds no credentials beyond the user's own session token in Keystore-backed storage.
- **Build-time (Android release signing only):** `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` are read **only** when `assembleRelease`/`bundleRelease` is explicitly requested; `check` and `assembleDebug` run with no production secrets. A release build with any variable absent **fails with a clear message and never substitutes a debug key** (CONF-006, Construction Contract).
- **Template, not truth:** `.env.example` (root and per-component) lists every variable with no real values; real `.env` files are Git-excluded.

### Secrets management and rotation

- **Where secrets live:** environment variables only — a local `.env` file for development, operator-supplied process environment for internal-test. No cloud secret manager exists (no cloud is permitted).
- **Git exclusion:** `.gitignore` blocks `.env`, `*.keystore`/`*.jks`, `key.properties`, and build outputs (CONF-006). Keystores live outside the repository at the path named by `ANDROID_KEYSTORE_PATH`.
- **Rotation:** `JWT_SECRET` rotation invalidates outstanding sessions (bounded by the 8-hour TTL, so rotation is low-impact); demo passwords are re-set by re-running the idempotent seed with new values; the Android keystore is replaced by the operator out-of-band if compromised, producing a new signing identity for subsequent internal-test builds.
- **Version control hygiene:** no secrets, keystores, or `.env` files are ever committed (wizard constraint); the release artifact itself contains no secrets (CONF-010).

### How each component reads its configuration

| Component | Reads | At what time |
|-----------|-------|--------------|
| `services/api` app | `ENVIRONMENT`, `DATABASE_URL`, `JWT_SECRET` | Process start (pydantic-settings); fail fast on missing required values |
| Demo seed (`app/seed/demo_seed.py`) | `ENVIRONMENT`, `DATABASE_URL`, `DEMO_EMPLOYEE_PASSWORD`, `DEMO_SUPPORT_PASSWORD`, `DEMO_SUPPORT_EMAIL` | Manual invocation only; refuses to run in production and without the password variables |
| Alembic (`migrations/env.py`) | `DATABASE_URL` | Migration run time |
| pytest (`tests/conftest.py`) | `TEST_DATABASE_URL` (falls back to Testcontainers only when unset) | Test session start |
| Android `app/build.gradle.kts` | The four `ANDROID_KEY*` variables | Release-build invocation only |
| Android runtime | Backend base URL (BuildConfig per build type); token from Keystore-backed `TokenStore` | App start / first API call |

---

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

---

## Flows

End-to-end flows for MiniSoporte, elaborating the six seed flows in `docs/extractions/flows.md` plus one implied flow (expired-token recovery) that follows from the API contract's token lifecycle (Q-008: no refresh endpoint). Every endpoint, entity, screen, and error body below is taken verbatim from `api-contract.md`, `data-model.md`, `mockups.md`, and `external-integrations.md`. The system has **no external third-party integrations** — the only cross-boundary link is the first-party Android app (`apps/mobile`) ↔ FastAPI `api` (`services/api`) connection over the local PostgreSQL instance.

> **v1 demo note (Q-005):** in debug builds, Compose previews, and automated tests the Android app's data access goes through the in-memory `FakeIncidentRepository` behind the `IncidentRepository` interface, so the user flows below run end-to-end without a live backend. The Retrofit-backed implementation targets the exact same contract; the endpoint calls described here apply to the Retrofit path (and to any REST client exercising the API directly, e.g. support tooling and the pytest integration suite).

### Employee authentication flow

Elaborates the seed flow *Employee authentication flow* (FUNC-009 → FUNC-001 → INSTR-006 → INSTR-007 → INSTR-008 → CONF-001 → CONF-003).

**Trigger** — The employee opens the app without a stored valid token (fresh install, after sign-out, or after token expiry) and lands on **S-01 — Iniciar sesión**. (The demo accounts this flow typically authenticates against are created by the Development environment initialization flow below.)

**Steps**

1. On S-01 the employee enters "Correo electrónico" and "Contraseña". The Compose form enforces required-field validation client-side (BR-001); the primary button is disabled while a request is in flight (BR-003 duplicate-submission lock).
2. The app calls `POST /api/auth/login` (public, rate-limited on failures) with `{"email", "password"}`. The API service normalizes the email (trimmed, lowercased) before lookup against `users` (INSTR-006); the bcrypt `password_hash` is verified — the password is never logged or returned (INSTR-008).
3. On success the API issues the session token: `200 {"access_token": "<JWT HS256>", "token_type": "bearer", "expires_in_seconds": 28800}` with claims `sub`/`role`/`exp` (CONF-001, Q-002). The app stores the token exclusively in Android Keystore-backed storage — never plain text (CONF-002).
4. The app routes to **S-02 — Mis incidencias**, which triggers the incident review flow's initial load. Optionally the app may call `GET /api/me` to display the signed-in profile (email as S-02 app-bar subtitle); the returned `users` fields are `id`, `email`, `role`, `active`, `created_at` — never `password_hash`.

**Screen ↔ API map**

| Screen | Action | Endpoint | Notes |
|--------|--------|----------|-------|
| S-01 Iniciar sesión | Submit credentials | `POST /api/auth/login` | Button locked while in flight (BR-003); demo hint box shows the seeded demo credentials |
| S-01 Iniciar sesión → S-02 Mis incidencias | (Optional) load profile after sign-in | `GET /api/me` | Renders email/role for the S-02 app-bar subtitle |

**Outcome & errors**

- **Success:** token persisted in Keystore-backed storage, S-02 opens, session valid 8 hours (28800 s).
- `422` — missing/blank/malformed fields (BR-001): structured Pydantic field errors (`{"loc": [...], "msg": ..., "type": ...}`); the client maps them to its own Spanish per-field copy next to each field (INSTR-011).
- `401` `{"detail": "Credenciales inválidas"}` — wrong password **or nonexistent email**: one uniform body, never reveals account existence (BR-002); S-01 shows the generic inline error alert.
- `429` `{"detail": "Demasiados intentos. Inténtelo de nuevo más tarde."}` + `Retry-After` header — per-IP or per-email failure budget exhausted (defaults: 5 failures / 900 s window; CONF-003, Q-004). Behavior is uniform whether or not the email exists.
- `403` `{"detail": "Cuenta inactiva"}` cannot occur at login itself (inactive accounts fail with the uniform `401`), but a token issued before deactivation is rejected on subsequent protected calls (see Session lifecycle).

### Incident creation flow

Elaborates the seed flow *Incident creation flow* (FUNC-005 → INSTR-002 → INSTR-011 → INSTR-001).

**Trigger** — On **S-02 — Mis incidencias**, the employee taps the accent FAB ("+") in the bottom navigation bar, opening **S-03 — Nueva incidencia**.

**Steps**

1. On S-03 the employee fills "Título" (live "n / 100" counter), "Categoría" choice group (Sistemas / Equipos / Acceso / Otro), "Prioridad" choice group (Baja / Media / Alta), and "Descripción" (live "n / 1000" counter). Live per-field validation mirrors the server rules (title 5–100 chars, description 10–1000 chars, category and priority required — BR-013..016). A hint states the incident will be created in "Pendiente" state (INSTR-002).
2. The employee taps "Guardar incidencia"; the button locks with the label "Guardando…" (duplicate-submission lock, FUNC-005).
3. The app calls `POST /api/incidents` with `{"title", "description", "category", "priority"}`. The request schema declares `extra="forbid"` and has no `status`, `owner_id`, or `id` fields: a client-supplied `{"status": "resolved", "owner_id": "..."}` is rejected `422` (mass-assignment defense, INSTR-002).
4. The API service creates one `incidents` row: `owner_id` = token subject (never client input — INSTR-001), `status` = `"pending"` server-set and DB-defaulted (BR-011), `created_at == updated_at`. Creation is all-or-nothing — an invalid body never leaves a partial incident (INSTR-011).
5. On `201` the app returns to S-02 and shows a confirmation; the new "Pendiente" incident appears at the top of the list (newest-first ordering, BR-019). In the FakeIncidentRepository path (Q-005) the same shape is simulated in memory, including the initial history entry, without a backend.

**Screen ↔ API map**

| Screen | Action | Endpoint | Notes |
|--------|--------|----------|-------|
| S-02 Mis incidencias | Tap FAB → open form | — | Client-side navigation only |
| S-03 Nueva incidencia | Submit form ("Guardar incidencia") | `POST /api/incidents` | `extra="forbid"`; status always `"pending"`; owner = token subject |
| S-03 → S-02 | Return with confirmation | — | New pending card first in list |

**Outcome & errors**

- **Success:** `201` with the created incident `{"id", "title", "description", "category", "priority", "status": "pending", "created_at", "updated_at"}`; exactly one Pending incident appears in S-02.
- `422` — validation failures (BR-013–016, INSTR-011) or forbidden extra fields: structured `detail` list with one entry per offending field; S-03 renders each error next to its field and the incident is not created.
- `401` `{"detail": "No autenticado"}` — missing/expired/invalid token; triggers the expired-token recovery flow.
- Network failure — S-03 keeps the form data and offers retry; the app never crashes (CONF-009).

### Incident review flow (list, filter, details)

Elaborates the seed flow *Incident review flow* (FUNC-003 → FUNC-004 → FUNC-006 → INSTR-001 → INSTR-009).

**Trigger** — The employee lands on **S-02 — Mis incidencias** after sign-in, re-opens the screen, taps a filter chip, or taps an incident card.

**Steps**

1. On S-02 entry the app calls `GET /api/incidents` (no query parameter). The repository filters by `owner_id` = token subject — no client parameter can widen the scope (INSTR-001, BR-009) — and returns a plain JSON array ordered `created_at DESC` with **no pagination** (Q-007, BR-020); list items omit `description`. S-02 renders each card with title, category, priority, status badge, and "Creada el <date>", plus loading / empty ("Sin incidencias") / error+retry states.
2. The employee taps a filter chip (Todas / Pendiente / En progreso / Resuelta). The app calls `GET /api/incidents?status=pending|in_progress|resolved` (or `all` / omits the parameter to clear the filter). Filtering applies on top of owner isolation — a filter never exposes another employee's incidents (FUNC-004). Chip count badges update live.
3. The employee taps a card; the app calls `GET /api/incidents/{id}` (owner only) and opens **S-04 — Detalle de incidencia**. The response carries the full incident (including `description`) plus the `history` array from `incident_history`, ordered **ascending by `changed_at`** — S-04 renders it as the "Historial de estados" timeline, with `"Creación — Pendiente"` for the initial entry (`previous_status: null`) and actor attribution via `changed_by` (INSTR-005, BR-021).
4. The employee taps "Mis incidencias" to navigate back to S-02; the timeline refreshes on each re-entry to S-04 (client polls on screen load; no push channel — CONF-007/009).

**Screen ↔ API map**

| Screen | Action | Endpoint | Notes |
|--------|--------|----------|-------|
| S-02 Mis incidencias | Load list on entry / retry | `GET /api/incidents` | Owner-scoped, newest first, plain array, no pagination |
| S-02 Mis incidencias | Tap filter chip | `GET /api/incidents?status={pending\|in_progress\|resolved\|all}` | Filter narrows owned incidents only |
| S-02 Mis incidencias | Tap incident card → S-04 | `GET /api/incidents/{id}` | Detail + `history` ascending by `changed_at` |
| S-04 Detalle de incidencia | Back to list | — | Client-side navigation only |

**Outcome & errors**

- **Success:** S-02 shows only the employee's incidents in the chosen filter; S-04 shows full detail plus the chronological status timeline.
- `422` — an unknown `status` value on the query parameter (`"loc": ["query", "status"]`); the chip UI cannot produce it in practice.
- `401` `{"detail": "No autenticado"}` — expired/invalid token; triggers the expired-token recovery flow. S-02's error state is only for network failures; a token failure routes to S-01.
- `404` `{"detail": "Incidencia no encontrada"}` — incident missing **or owned by another user**: one indistinguishable safe response (INSTR-001, BR-010). S-04 renders the safe no-access state: "Incidencia no disponible" with the hint "No se encontró la incidencia o no tienes acceso a ella."
- Empty result set — S-02 shows the "Sin incidencias" empty state (normal edge case for a new account or a filter with no matches).

### Support status update flow

Elaborates the seed flow *Support status update flow* (FUNC-007 → INSTR-003 → INSTR-004 → INSTR-005 → FUNC-006).

**Trigger** — A support agent (role `support`) issues an API call to advance an employee's incident. **This flow is API-only**: no support mobile screen exists in v1 (FUNC-007), so there is no Screen ↔ API map. The support agent authenticates through the same `POST /api/auth/login` endpoint and receives the same JWT shape with `role: "support"`.

**Steps**

1. The support agent authenticates via `POST /api/auth/login` (as in the authentication flow); the issued token carries the `role: "support"` claim (CONF-001).
2. The support agent calls `PATCH /api/incidents/{id}/status` with `{"new_status": "in_progress"}` or `{"new_status": "resolved"}`. This body's only field is `new_status` — no comment, owner, or timestamp fields are accepted (immutable history, BR-012/025).
3. The API checks the caller's role **before any incident lookup** (INSTR-004): an `employee` token gets `403 {"detail": "No autorizado"}` uniformly, whether or not the incident exists (BR-022).
4. For a support caller, the service loads the `incidents` row (missing/malformed id → safe `404 {"detail": "Incidencia no encontrada"}`) and validates the linear transition matrix **before any write** (INSTR-003): `pending → in_progress` ✓, `in_progress → resolved` ✓; backwards, skipped, repeated, and any transition out of `resolved` are rejected (Resolved is terminal — BR-023/024).
5. For an accepted transition, one atomic transaction updates `incidents.status` + `updated_at` and inserts one immutable `incident_history` row (`previous_status`, `new_status`, `changed_by` = the support agent's token subject, `changed_at`) (INSTR-005, BR-025).
6. The employee observes the change through the incident review flow: the next `GET /api/incidents` refresh shows the new status badge, and `GET /api/incidents/{id}` extends the S-04 "Historial de estados" timeline with the new entry (the flow's closure back to FUNC-006).

**Outcome & errors**

- **Success:** `200` with `{"incident": {...}, "latest_history": {...}}` — the updated incident plus the newest `incident_history` entry; the employee's S-04 timeline gains one entry.
- `401` `{"detail": "No autenticado"}` — missing/expired/invalid token.
- `403` `{"detail": "No autorizado"}` — caller role is `employee` (checked first, leaks nothing — INSTR-004/BR-022); proven by mandatory integration tests (INSTR-013).
- `404` `{"detail": "Incidencia no encontrada"}` — support caller, but the id is missing or not found (safe shape, no existence leak — BR-010).
- `409` `{"detail": "Transición de estado no válida"}` — value in the fixed set but backwards, skipped, repeated, or out of terminal `resolved` (INSTR-003).
- `422` — `new_status` absent or outside `pending`/`in_progress`/`resolved`: structured field errors.

### Session lifecycle (sign in to sign out)

Elaborates the seed flow *Session lifecycle* (FUNC-001 → FUNC-002 → CONF-001 → CONF-002).

**Trigger** — Spans the whole session: begins at sign-in on S-01, ends when the employee taps "Salir" on S-02 or the token expires.

**Steps**

1. **Sign in** — the employee completes the authentication flow (above): `POST /api/auth/login` returns the HS256 JWT (`exp` = issued + 28800 s, claims `sub`/`role`/`exp`), stored in Android Keystore-backed storage (CONF-002, BR-006), never plain text or logs.
2. **Authenticated use** — every protected call (`GET /api/me`, `GET /api/incidents`, `POST /api/incidents`, `GET /api/incidents/{id}`) attaches the token as `Authorization: Bearer <JWT>` via the OkHttp interceptor; the API decodes with the pinned `algorithms=["HS256"]` allow-list (BR-004).
3. **Sign out** — the employee taps the "Salir" app-bar button on S-02. There is **no server-side sign-out endpoint and no refresh endpoint** (Q-008): the client deletes the stored token from Keystore-backed storage (FUNC-002, BR-005), shows a confirmation toast, and returns to S-01 with no protected screen reachable afterward. The accepted server-side mitigation for an already-issued token is the 8-hour TTL.
4. **Expiry without sign-out** — if the session outlives 8 hours, the next protected call receives `401 {"detail": "No autenticado"}` and the expired-token recovery flow takes over.

**Screen ↔ API map**

| Screen | Action | Endpoint | Notes |
|--------|--------|----------|-------|
| S-01 Iniciar sesión | Sign in | `POST /api/auth/login` | Issues the 8 h JWT; token stored Keystore-backed |
| S-02 Mis incidencias | Authenticated use | `GET /api/incidents` (and other protected calls) | Bearer header attached by interceptor |
| S-02 Mis incidencias | "Salir" sign-out | — (no endpoint) | Client-side token deletion only (Q-008); routes to S-01 |

**Outcome & errors**

- **Success:** a bounded session — sign-in to sign-out or natural 8-hour expiry; after "Salir", navigation to any protected screen requires a new sign-in.
- `403` `{"detail": "Cuenta inactiva"}` — mid-session deactivation: a token whose `sub` resolves to a user with `active = false` is rejected on protected routes even before expiry (INSTR-001 rationale); the client clears the token and routes to S-01.
- `401` `{"detail": "No autenticado"}` — expired or invalid token on any protected route (see the recovery flow).
- Edge: a signed-out device's token remains cryptographically valid until `exp` if copied — accepted risk per Q-008 (short TTL, internal-test distribution).

### Expired-token recovery (401 → re-authenticate)

Implied flow — not among the six seeds. It follows from the API contract's authentication section and `external-integrations.md`: **no refresh endpoint exists**, so every `401 {"detail": "No autenticado"}` on a protected route ends the session client-side.

**Trigger** — Any protected call (`GET /api/incidents`, `POST /api/incidents`, `GET /api/incidents/{id}`, `GET /api/me`) returns `401` because the token is expired (past `exp`), malformed, or invalidly signed.

**Steps**

1. The OkHttp interceptor receives `401 {"detail": "No autenticado"}` while S-02, S-03, or S-04 is waiting on a protected endpoint.
2. The app clears the token from Android Keystore-backed storage (same deletion path as sign-out, CONF-002/BR-005). No silent retry is attempted — there is no refresh mechanism to retry with (Q-008).
3. The app routes to **S-01 — Iniciar sesión**; the interrupted action is not auto-resumed — after a successful re-authentication the employee restarts at S-02 (fresh list load), which restores the context.
4. The employee signs in again via the authentication flow; a new 8-hour JWT is issued and stored.

**Screen ↔ API map**

| Screen | Action | Endpoint | Notes |
|--------|--------|----------|-------|
| S-02/S-03/S-04 (any) | Protected call fails | `GET /api/incidents` / `POST /api/incidents` / `GET /api/incidents/{id}` / `GET /api/me` | `401` body is uniform: `{"detail": "No autenticado"}` |
| (any) → S-01 Iniciar sesión | Clear token, re-authenticate | `POST /api/auth/login` | New JWT; session restarts at S-02 |

**Outcome & errors**

- **Success:** token cleared, employee re-authenticates, S-02 reloads with the fresh list — a new bounded session begins.
- Edge: if the account was deactivated mid-session, the re-authentication attempt itself fails with the uniform `401 {"detail": "Credenciales inválidas"}`, and any still-valid old token gets `403 {"detail": "Cuenta inactiva"}` on protected routes.
- Edge: transient network errors must **not** trigger this flow — only an actual `401` status ends the session; network failures keep the recoverable error+retry state on the current screen (CONF-009).

### Development environment initialization

Elaborates the seed flow *Development environment initialization* (FUNC-009 → INSTR-010 → CONF-006 → CONF-008 → FUNC-008). **Backend-only flow** — manual developer/operations steps, no mobile screen involved; the screen table is omitted.

**Trigger** — A developer or reviewer prepares the local internal-test environment: fresh checkout, before running the API or pointing the Android client at it.

**Steps**

1. Configure the environment variables — all secrets via env vars, never committed (CONF-006, BR-029): `DEMO_EMPLOYEE_PASSWORD` and `DEMO_SUPPORT_PASSWORD` for the seed, and `TEST_DATABASE_URL` pointing at a disposable PostgreSQL database for the pytest integration suite (BR-034). Optional rate-limit tuning: `LOGIN_RATELIMIT_IP_MAX`, `LOGIN_RATELIMIT_EMAIL_MAX`, `LOGIN_RATELIMIT_WINDOW_SECONDS`.
2. Apply the schema: run the Alembic migration `migrations/versions/0001_initial_schema.py`, creating `users`, `incidents`, and `incident_history` with their foreign keys, CHECK constraints, unique email constraint, and indexes (CONF-008). No ad-hoc SQL ever defines schema.
3. Run the idempotent demo seed — module `app/seed/demo_seed.py`, **no endpoint exists for it** (FUNC-009, INSTR-010). It reads passwords exclusively from `DEMO_EMPLOYEE_PASSWORD` / `DEMO_SUPPORT_PASSWORD` and creates: one employee `empleado@minisoporte.test`, one support agent with the configured email, and three owned `incidents` covering `pending`, `in_progress`, and `resolved`, with consistent `incident_history` entries (including `previous_status: null` creation entries). It fails with a clear message if either password variable is missing, embeds no default password, and never runs automatically in production (BR-033).
4. Verify liveness: call `GET /health` (public, no auth, no rate limit). A `200 {"status": "ok"}` confirms the API started correctly and is safe to exercise with the Android client; the body exposes no stack traces or configuration (FUNC-008, BR-032).
5. Only then exercise the client: sign in as `empleado@minisoporte.test` on S-01 and run the authentication, review, and creation flows against the seeded data.

**Outcome & errors**

- **Success:** a reproducible local environment — migrated schema, three demo incidents spanning every status with trustworthy history, healthy API answering `{"status": "ok"}`, and a working demo sign-in.
- Missing `DEMO_EMPLOYEE_PASSWORD` or `DEMO_SUPPORT_PASSWORD` — the seed aborts with a clear message and creates nothing (no default password exists in source, BR-033).
- Migration failure or unreachable PostgreSQL — the API fails to serve protected routes; `GET /health` still reflects process liveness only, with failures surfaced through process logs and the sanitized `500 {"detail": "Error interno del servidor"}` handler, never through response bodies (BR-032).

---

## Traceability

Requirement-to-design map for MiniSoporte. Every design artifact class defined in `docs/design/` (components, screens, endpoints, entities, flows, business rules) is mapped to the extraction ids it realizes. Extraction sources: `docs/extractions/functionalities.md` (FUNC-001..009), `docs/extractions/instructions.md` (INSTR-001..013, as consolidated — note the two seed-flow chains quote INSTR-007 and INSTR-009, which exist only in `docs/extractions/_raw.json` and are mapped here), `docs/extractions/configurations.md` (CONF-001..011), and the flow ids FLOW-001..FLOW-006 from `docs/extractions/_raw.json` (`docs/extractions/flows.md` names them without ids). `docs/extractions/visualization.md` extracted **no** VIS- items, so no VIS- id exists for any screen.

### Components

| Design artifact | Type | Realizes |
|-----------------|------|----------|
| `mobile-app` (Android, Kotlin + Jetpack Compose, `com.nexti.minisoporte`, minSdk 26, Spanish, portrait, online only, zero permissions, Keystore-backed token storage, Retrofit/OkHttp/kotlinx.serialization, `IncidentRepository` + `FakeIncidentRepository`) | Frontend | FUNC-001, FUNC-002, FUNC-003, FUNC-004, FUNC-005, FUNC-006; CONF-002, CONF-004, CONF-007, CONF-009, CONF-010; INSTR-012; BR-003, BR-005, BR-006, BR-031, BR-035, BR-036, BR-037, BR-040 |
| `api` (layered FastAPI 0.123.10 / Python 3.10; JWT HS256 8 h auth; in-memory per-IP/per-email login rate limiter; SQLAlchemy 2.0.x ORM; bcrypt; sanitized error handling; pytest suite ≥90 % business-logic coverage; demo seed module `app/seed/demo_seed.py`) | Backend | FUNC-001, FUNC-002, FUNC-003, FUNC-004, FUNC-005, FUNC-006, FUNC-007, FUNC-008, FUNC-009; CONF-001, CONF-003, CONF-004, CONF-005, CONF-006, CONF-011; INSTR-001..006, INSTR-007, INSTR-008, INSTR-009, INSTR-010, INSTR-011, INSTR-013; BR-001, BR-002, BR-004, BR-007, BR-008, BR-009, BR-010, BR-011, BR-012, BR-013..BR-018, BR-019, BR-020, BR-022..BR-027, BR-032, BR-033, BR-034 |
| PostgreSQL 16.15 (tables `users`, `incidents`, `incident_history`; FKs, CHECK constraints, unique email, indexes; Alembic migration `0001_initial_schema.py`) | Database | CONF-005, CONF-008; INSTR-001, INSTR-002, INSTR-006, INSTR-011; BR-008, BR-009, BR-011, BR-012, BR-015, BR-016, BR-017, BR-025, BR-027 |

### Screens

| Design artifact | Type | Realizes |
|-----------------|------|----------|
| S-01 — Iniciar sesión | Screen | FUNC-001; INSTR-007; BR-001, BR-002, BR-003, BR-006. Mockup source: Pre-Design draft (`pre_design_mobile.png`) — **no VIS- id extracted** |
| S-02 — Mis incidencias | Screen | FUNC-002, FUNC-003, FUNC-004; INSTR-001, INSTR-012; BR-005, BR-009, BR-017, BR-019, BR-035, BR-036. Mockup source: Pre-Design draft — **no VIS- id extracted** |
| S-03 — Nueva incidencia | Screen | FUNC-005; INSTR-002, INSTR-011, INSTR-012; BR-003, BR-011, BR-013..BR-016, BR-018, BR-036. Mockup source: Pre-Design draft — **no VIS- id extracted** |
| S-04 — Detalle de incidencia | Screen | FUNC-006; INSTR-001, INSTR-005, INSTR-012; BR-009, BR-010, BR-012, BR-021, BR-025, BR-036. Mockup source: Pre-Design draft — **no VIS- id extracted** |

### Endpoints

| Design artifact | Type | Realizes |
|-----------------|------|----------|
| `POST /api/auth/login` | Endpoint | FUNC-001; INSTR-006, INSTR-007, INSTR-008; CONF-001, CONF-003; BR-001, BR-002, BR-004, BR-007, BR-008 |
| `GET /api/me` | Endpoint | CONF-001, CONF-011; INSTR-006, INSTR-008; BR-026 |
| `GET /api/incidents` | Endpoint | FUNC-003, FUNC-004; INSTR-001; BR-009, BR-017, BR-019, BR-020 |
| `POST /api/incidents` | Endpoint | FUNC-005; INSTR-001, INSTR-002, INSTR-011; BR-011, BR-012, BR-013..BR-018 |
| `GET /api/incidents/{id}` | Endpoint | FUNC-006; INSTR-001, INSTR-005, INSTR-009; BR-009, BR-010, BR-021 |
| `PATCH /api/incidents/{id}/status` | Endpoint | FUNC-007; INSTR-001, INSTR-003, INSTR-004, INSTR-005; BR-010, BR-012, BR-022, BR-023, BR-024, BR-025 |
| `GET /health` | Endpoint | FUNC-008; INSTR-009; BR-032 |

### Entities

| Design artifact | Type | Realizes |
|-----------------|------|----------|
| `users` | Entity | FUNC-001, FUNC-002, FUNC-009; CONF-001, CONF-008; INSTR-006, INSTR-008; BR-004, BR-008, BR-026 |
| `incidents` | Entity | FUNC-003, FUNC-004, FUNC-005, FUNC-006, FUNC-007, FUNC-009; CONF-008; INSTR-001, INSTR-002, INSTR-011; BR-009, BR-011, BR-012, BR-013..BR-017, BR-019 |
| `incident_history` | Entity | FUNC-006, FUNC-007, FUNC-009; CONF-008; INSTR-002, INSTR-003, INSTR-005; BR-021, BR-023, BR-025 |

### Flows

| Design artifact | Type | Realizes |
|-----------------|------|----------|
| Employee authentication flow (`flows.md`) | Flow | Elaborates **FLOW-001**; FUNC-001, FUNC-009; INSTR-006, INSTR-007, INSTR-008; CONF-001, CONF-002, CONF-003; BR-001, BR-002, BR-003, BR-004, BR-006, BR-007, BR-008 |
| Incident creation flow (`flows.md`) | Flow | Elaborates **FLOW-002**; FUNC-005; INSTR-001, INSTR-002, INSTR-011; BR-003, BR-011, BR-013..BR-018 |
| Incident review flow (list, filter, details) (`flows.md`) | Flow | Elaborates **FLOW-003**; FUNC-003, FUNC-004, FUNC-006; INSTR-001, INSTR-005; BR-009, BR-010, BR-017, BR-019, BR-020, BR-021 |
| Support status update flow (`flows.md`) | Flow | Elaborates **FLOW-004**; FUNC-006, FUNC-007; INSTR-003, INSTR-004, INSTR-005; BR-022, BR-023, BR-024, BR-025 |
| Session lifecycle (sign in to sign out) (`flows.md`) | Flow | Elaborates **FLOW-005**; FUNC-001, FUNC-002; CONF-001, CONF-002; BR-004, BR-005, BR-006 |
| Expired-token recovery (401 → re-authenticate) (`flows.md`) | Flow | **Implied flow — no FLOW- id** (follows from the no-refresh token lifecycle, Q-008); FUNC-001, FUNC-002; CONF-001, CONF-002; BR-004, BR-005, BR-006 |
| Development environment initialization (`flows.md`) | Flow | Elaborates **FLOW-006**; FUNC-008, FUNC-009; INSTR-009, INSTR-010, INSTR-013; CONF-006, CONF-008; BR-029, BR-032, BR-033, BR-034 |

### Business rules

| Design artifact | Type | Realizes |
|-----------------|------|----------|
| BR-001 — Email and password both required at sign-in | Business rule | FUNC-001 |
| BR-002 — Generic sign-in failure, no account-existence leak | Business rule | FUNC-001, INSTR-007 |
| BR-003 — Sign-in button locked while request in flight | Business rule | FUNC-001 |
| BR-004 — JWT HS256, 8 h, no refresh, allow-listed decode | Business rule | CONF-001 |
| BR-005 — Sign-out deletes local token; protected screens locked | Business rule | FUNC-002 |
| BR-006 — Token stored only in Keystore-backed storage | Business rule | CONF-002 |
| BR-007 — Rate-limit failed sign-ins per IP and per email, uniformly | Business rule | CONF-003, INSTR-007 |
| BR-008 — Email normalized and unique | Business rule | INSTR-006 |
| BR-009 — Owner-only visibility on list, detail, history | Business rule | INSTR-001, FUNC-003 |
| BR-010 — Safe non-leaking response for missing/foreign incidents | Business rule | FUNC-006, INSTR-001 |
| BR-011 — New incidents always start Pending | Business rule | INSTR-002, FUNC-005 |
| BR-012 — Single owner; owner and history immutable for employees | Business rule | INSTR-001, FUNC-006 |
| BR-013 — Title required, 5–100 chars | Business rule | FUNC-005 |
| BR-014 — Description required, 10–1,000 chars | Business rule | FUNC-005 |
| BR-015 — Category in fixed 4-value set | Business rule | FUNC-005 |
| BR-016 — Priority in fixed 3-value set | Business rule | FUNC-005 |
| BR-017 — Statuses limited to pending/in_progress/resolved | Business rule | FUNC-004 |
| BR-018 — Client+server validation with field-level errors | Business rule | INSTR-011 |
| BR-019 — Own incidents, newest first, five visible fields | Business rule | FUNC-003 |
| BR-020 — No pagination in v1 | Business rule | FUNC-003 (binding clarification Q-007) |
| BR-021 — Detail shows full data + history timeline | Business rule | FUNC-006 |
| BR-022 — Only support role updates status | Business rule | INSTR-004, FUNC-007 |
| BR-023 — Linear transitions only (no skip/back/repeat) | Business rule | INSTR-003, FUNC-007 |
| BR-024 — Resolved is terminal in v1 | Business rule | INSTR-003, FUNC-007 |
| BR-025 — Immutable history entry per accepted transition | Business rule | INSTR-005, FUNC-007 |
| BR-026 — Passwords only as salted bcrypt hashes, never logged | Business rule | INSTR-008 |
| BR-027 — Parameterized/ORM access only | Business rule | CONF-005 |
| BR-028 — HTTPS outside local environment | Business rule | CONF-004 |
| BR-029 — Secrets via env vars; secret files Git-excluded | Business rule | CONF-006 |
| BR-030 — Release build fails clearly without signing variables | Business rule | CONF-006 (wizard Constraints, AC-11) |
| BR-031 — Zero Android permissions; no push/deep links/offline | Business rule | CONF-007 |
| BR-032 — Public safe `/health` endpoint | Business rule | FUNC-008, INSTR-009 |
| BR-033 — Seed env-only passwords, idempotent, non-production | Business rule | INSTR-010, FUNC-009 |
| BR-034 — ≥90 % business-logic test coverage incl. authz | Business rule | INSTR-013 (wizard thresholds: 90) |
| BR-035 — Online-only Spanish portrait app; retry on network errors | Business rule | CONF-009 |
| BR-036 — Contrast, labels, touch targets; status text+color, defined palette | Business rule | INSTR-012 |
| BR-037 — Native Kotlin/Compose, AndroidX, aligned JVM targets, monotonic versionCode | Business rule | Wizard (Constraints) — Android Construction Contract; CONF-010 |
| BR-038 — Internal testing only; no production/cloud/Play publication | Business rule | Wizard (Constraints) — deployment notes |
| BR-039 — Never commit secrets/keystores/.env | Business rule | Wizard (Constraints) — deployment notes; CONF-006 |
| BR-040 — Kotlin/Compose fixed; mobile run entry is build-only | Business rule | Wizard (Constraints) — Android Construction Contract; CONF-010 |

### Coverage check

Every extraction id appears at least once above:

- **FUNC-001..FUNC-009** — all nine in Components, Endpoints, Entities, Screens, and Flows.
- **INSTR-001..INSTR-013** — all eleven consolidated ids in instructions.md (001, 002, 003, 004, 005, 006, 008, 010, 011, 012, 013) plus the two raw-only ids INSTR-007 and INSTR-009, which the seed flow chains in `docs/extractions/flows.md` reference and which exist in `docs/extractions/_raw.json` (INSTR-007 "Generic authentication failure messages"; INSTR-009 "No sensitive data in logs or error responses").
- **CONF-001..CONF-011** — all eleven in Components, Endpoints, Entities, and Flows.
- **FLOW-001..FLOW-006** — all six elaborated one-to-one in Flows; the seventh flow (expired-token recovery) is design-implied and marked as such.
- **VIS-** — none exist; see Not covered.

### Not covered

| Design artifact | Type | Realizes | Reason |
|-----------------|------|----------|--------|
| — (VIS- ids) | Visualization | — | `docs/extractions/visualization.md` extracted no visual items; the screen designs derive from the user-approved Pre-Design draft (`pre_design_mobile.html`) instead, as recorded in `mockups.md` and `components.md`. |
| CI/CD components | Component class | — | Absent per `docs/design/manifest.json`: internal testing only, local builds; no pipeline was requested (CONF-010 covers the run/build contract). |
| Cloud components | Component class | — | Absent per `docs/design/manifest.json`: the project forbids cloud infrastructure and external resources; no external integrations exist (`external-integrations.md`). |