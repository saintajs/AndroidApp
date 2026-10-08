# US-001 — Backend foundation: workspace, layered FastAPI skeleton, PostgreSQL schema and /health

**Epic:** EPIC-001 — Incident Management Service
**Feature:** FEAT-001 — API Foundation & Data Model
**Status:** locked (approved)
**Related Items:** CONF-005, CONF-006, CONF-008, CONF-011, FUNC-008, INSTR-009, BR-027, BR-029, BR-032, BR-039

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- api
- PostgreSQL 16.15

**APIs**

- GET /health

**Data entities**

- users
- incidents
- incident_history

**Flows**

- Development environment initialization

**Scope note:** This story owns the service skeleton, all three table schemas via migration 0001, and the health endpoint; no [] category applies except screens (the api component has no mobile screens in the design).

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Endpoints

#### `GET /health`

**Request**

```
Operational liveness probe (FUNC-008). Public, no auth, no rate limit.
```

**Response**

```
200 {"status": "ok"} — a single sanitized literal; no stack traces, configuration, database internals, or version details (BR-032). A failing deployment surfaces errors through process logs and the sanitized 500 handler, never through this body.
```

**Validation:** Errors: sanitized 500 {"detail": "Error interno del servidor"} global handler; /health itself exposes no stack traces or sensitive configuration (BR-032).

### Entities

#### users

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, NO null, default gen_random_uuid() — opaque user identifier; sub claim of the JWT and changed_by target (CONF-001, CONF-008) |
| email | TEXT | UQ (uq_users_email), NO null — normalized (trimmed, lowercased) by the service layer before storage/lookup (INSTR-006, BR-008) |
| password_hash | TEXT | NO null — salted bcrypt hash (60-char $2b$… string), written via bcrypt directly, never passlib, never logged or returned (INSTR-008, Q-010, BR-026) |
| role | TEXT | NO null, CHECK role IN ('employee','support') — drives per-route authorization and the role JWT claim (CONF-001, INSTR-004) |
| active | BOOLEAN | NO null, default TRUE — soft-enable flag; FALSE blocks sign-in and API access without deleting the row (INSTR-001) |
| created_at | TIMESTAMPTZ | NO null, default now() — row creation instant; returned by GET /api/me (CONF-011) |

#### incidents

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, NO null, default gen_random_uuid() — opaque identifier used in /api/incidents/{id} paths (FUNC-006, INSTR-001) |
| owner_id | UUID | FK -> users.id, NO null, ON DELETE RESTRICT — the authenticated creator, never client input (mass-assignment defense, INSTR-002); every list/detail/history query filters by it (INSTR-001, BR-009) |
| title | TEXT | NO null, CHECK char_length(title) BETWEEN 5 AND 100 (BR-013; Pydantic schema is the primary gate, INSTR-011) |
| description | TEXT | NO null, CHECK char_length(description) BETWEEN 10 AND 1000 (BR-014); omitted from list-item responses, included in detail |
| category | TEXT | NO null, CHECK category IN ('systems','equipment','access','other') (BR-015) |
| priority | TEXT | NO null, CHECK priority IN ('low','medium','high') (BR-016) |
| status | TEXT | NO null, default 'pending', CHECK status IN ('pending','in_progress','resolved') — defaults to pending at DB level as well as service layer (INSTR-002, BR-011) |
| created_at | TIMESTAMPTZ | NO null, default now() — list ordering created_at DESC (BR-019) |
| updated_at | TIMESTAMPTZ | NO null, default now(), onupdate — set by the service layer inside the same transaction as the history insert (INSTR-003, INSTR-005) |

#### incident_history

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, NO null, default gen_random_uuid() — exposed in the S-04 timeline payload (FUNC-006) |
| incident_id | UUID | FK -> incidents.id, NO null, ON DELETE RESTRICT — timeline read WHERE incident_id = ? ORDER BY changed_at ASC (FUNC-006); index ix_incident_history_incident_changed (incident_id, changed_at) |
| previous_status | TEXT | YES null, default NULL, CHECK previous_status IS NULL OR previous_status IN ('pending','in_progress','resolved') — NULL represents the initial creation entry (INSTR-002, INSTR-005, BR-025) |
| new_status | TEXT | NO null, CHECK new_status IN ('pending','in_progress','resolved') (INSTR-005) |
| changed_by | UUID | FK -> users.id, NO null, ON DELETE RESTRICT — actor who performed the transition (FUNC-007, INSTR-005) |
| changed_at | TIMESTAMPTZ | NO null, default now() — transition instant; timeline ordered ascending by it (FUNC-006) |

### Conventions

- Configuration access: `app/core/config.py` defines one pydantic-settings `Settings` class; every module reads config ONLY through the DI-provided settings object — `os.environ` / `os.getenv` appear nowhere else (CONF-006); `.env` is Git-excluded; `.env.example` documents every variable with placeholder values (BR-029/039); missing mandatory variables fail startup with a clear message.
- ORM models live in the single `app/domain/models.py` (SQLAlchemy 2.0 `Mapped[...]` / `mapped_column` declarative style); Alembic migrations in `migrations/versions/` with sequential numeric prefixes (`0001_initial_schema.py`); every schema change ships as a migration, never ad-hoc SQL (CONF-008).
- SQLAlchemy 2.0 style only: `select()` constructs with typed declarations; no legacy `Query` API; no raw SQL strings anywhere — the parameterization guarantee is structural (CONF-005, BR-027); enum columns store lowercase string values via a Python `enum.Enum` shared with Pydantic (`app/domain/enums.py`); timestamps are timezone-aware `DateTime(timezone=True)` with `server_default=func.now()`; history rows are never updated or deleted by application code (INSTR-005, BR-012).
- Strict one-way dependency direction — `api` -> `domain` -> `db`, with `core` leaf-used by all (CONF-011): routes are thin handlers only (declare `Annotated` dependencies, call one domain service, map domain exceptions to HTTP status codes — no business logic, no direct ORM queries); `app/db/repositories/*` is the ONLY place SQLAlchemy queries are written (INSTR-001); services never import `select` themselves.
- A global exception handler catches unhandled `Exception` and returns sanitized `500 {"detail": "Error interno del servidor"}` — no stack traces, SQL, or config in any response; validation failures are never caught (Pydantic v2 returns the standard structured 422 field-level body, INSTR-011); `get_db` is a `yield` dependency that opens a session per request and closes/rolls it back on exit.
- All path operations, dependencies, and services use plain `def` (not `async def`): the stack is sync SQLAlchemy 2.0 + psycopg 3 sync, and `def` handlers run in the threadpool (FastAPI rule). Strict typing everywhere — full annotations, `X | None` (PEP 604), modern collections generics.
- Module/package naming: `snake_case` modules, single lowercase package names (`api`, `core`, `domain`, `db`, `seed`); classes `PascalCase`; constants `UPPER_SNAKE_CASE`; JSON field names `snake_case`, no aliasing layer; test files mirror source layout under `tests/unit/` and `tests/integration/` named `test_<module>.py`.
- Logging: standard library `logging`, one module-level logger per module configured once in `app/main.py`; NEVER log passwords, tokens, or `Authorization` headers (INSTR-008, BR-026); unexpected exceptions logged with `logger.exception(...)` while clients receive the sanitized 500 body.
- Ruff (lint + format): single `ruff check . && ruff format --check .` gate with rules `E,W,F,I,B,UP,S,SIM`, line length 100, uv-managed; Bandit-class rules (`S`) are blocking; mypy strict typing gate: `strict = true`, plugin `pydantic.mypy`.

### Flow Slice

Development environment initialization — Trigger: A developer or reviewer prepares the local internal-test environment: fresh checkout, before running the API or pointing the Android client at it. Steps (this story's slice): 1. Configure the environment variables — all secrets via env vars, never committed (CONF-006, BR-029). 2. Apply the schema: run the Alembic migration `migrations/versions/0001_initial_schema.py`, creating `users`, `incidents`, and `incident_history` with their foreign keys, CHECK constraints, unique email constraint, and indexes (CONF-008). No ad-hoc SQL ever defines schema. 4. Verify liveness: call `GET /health` (public, no auth, no rate limit). A `200 {"status": "ok"}` confirms the API started correctly and is safe to exercise with the Android client; the body exposes no stack traces or configuration (FUNC-008, BR-032). Outcome & errors: Migration failure or unreachable PostgreSQL — the API fails to serve protected routes; `GET /health` still reflects process liveness only, with failures surfaced through process logs and the sanitized `500 {"detail": "Error interno del servidor"}` handler, never through response bodies (BR-032).

### Notes

Schema creation happens here once; US-010 later adds the seed rows. Environment variables required at this stage: DATABASE_URL, JWT_SECRET, ENVIRONMENT (seed-only DEMO_* and rate-limit knobs come with their stories). Also create the root `.env.example`, `.gitignore` (excludes .env, keystores, key.properties, build outputs) and infra/ local-dev Postgres convenience files per archetype.md — schema always from Alembic, never from the bootstrap SQL.

---

## Narrative

As a member of the development team I need the greenfield backend foundation established in a single change so that every later API story builds on one architecture: uv workspace root, layered FastAPI app (api -> domain -> db, core used by all), pydantic-settings configuration reading only environment variables, SQLAlchemy ORM models plus the Alembic 0001_initial_schema migration creating the three tables with their constraints and indexes, the global sanitized error handler, and the public GET /health liveness probe. Trigger: fresh checkout by a developer or reviewer preparing the local internal-test environment.

---

## Functional Flow

1. The developer sets DATABASE_URL, JWT_SECRET and ENVIRONMENT via environment variables (never committed; .env.example documents placeholders). 2. The uv workspace resolves; the app factory starts and registers routers. 3. `alembic upgrade head` applies migrations/versions/0001_initial_schema.py, creating users, incidents and incident_history with FKs (ON DELETE RESTRICT), CHECK constraints, uq_users_email and the three indexes. 4. The developer calls GET /health (public, no auth, no rate limit) and receives 200 {"status": "ok"}. Error branches: a missing mandatory variable fails startup with a clear message naming it; an unhandled exception anywhere returns the sanitized 500 {"detail": "Error interno del servidor"} with no stack trace, SQL or configuration; a failing deployment surfaces detail only in process logs.

---

## Acceptance Criteria

- **CA-01:** Given the required env vars (DATABASE_URL, JWT_SECRET, ENVIRONMENT), When the API starts, Then the app factory registers all routers and GET /health returns 200 {"status": "ok"} without authentication or rate limiting.
- **CA-02:** Given a fresh PostgreSQL database, When `alembic upgrade head` runs, Then users, incidents and incident_history exist with UUID PK defaults, FKs ON DELETE RESTRICT, all CHECK constraints, uq_users_email, and indexes ix_incidents_owner_created and ix_incident_history_incident_changed.
- **CA-03:** Given JWT_SECRET or DATABASE_URL is unset, When the application starts, Then startup fails with a clear message naming the missing variable and no default value is substituted.
- **CA-04:** Given an unhandled exception inside any route, When the response is produced, Then the client receives 500 {"detail": "Error interno del servidor"} with no stack trace, SQL, or configuration, while the full trace appears in server logs.
- **CA-05:** Given the codebase, When searched, Then os.environ/os.getenv appear only inside app/core/config.py and all schema exists solely as the Alembic migration (no ad-hoc SQL in any environment).
- **CA-06:** Given the lint/type gates, When `ruff check . && ruff format --check .` and mypy strict run, Then both pass with Bandit-class (S) rules blocking.
- **CA-07:** Given the pytest suite, When run with TEST_DATABASE_URL set, Then integration tests exercise the migration-defined schema (Alembic upgrade head per session) with per-test transaction rollback, and `pytest -m "not integration"` runs the fast tier without PostgreSQL.

---

## Business Rules

- BR-027 — All database access uses parameterized queries or the ORM; user-supplied values are never concatenated into SQL strings (SQLAlchemy 2.0.x ORM everywhere).
- BR-029 — All application secrets are supplied through environment variables; .env, keystores, key.properties and credentials are Git-excluded.
- BR-032 — The public GET /health endpoint reports liveness without authentication and without exposing stack traces or sensitive information.
- BR-039 — Never commit secrets, credentials, keystores, or .env files to source control.

---

## Dependencies

_No dependencies._

---

## Security Considerations

- Secrets only via environment variables (CONF-006); JWT_SECRET must be a long random value unique per environment.
- Global sanitized error handler prevents stack-trace/SQL/config disclosure (FUNC-008 discipline applied globally).
- SQL-injection defense is structural: ORM-only access, no string-concatenated SQL (CONF-005, BR-027).

---

## Non-Functional Requirements

- Reproducible schema in any environment from Alembic migrations (CONF-008).
- Sync `def` handlers on the threadpool so blocking DB calls never stall the event loop.
- Local-only infrastructure: no CI/CD, no cloud; infra/docker-compose.yml is a dev convenience never on any test's critical path.

---

## Activity Diagram

_No activity diagram defined._
