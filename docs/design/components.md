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
