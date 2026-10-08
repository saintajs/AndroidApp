# US-010 — Idempotent demo seed with env-only passwords and the >=90% business-logic coverage gate

**Epic:** EPIC-003 — Operations, Seed & Internal Release
**Feature:** FEAT-008 — Demo Seed, Environment & Quality Gate
**Status:** locked (approved)
**Related Items:** FUNC-009, INSTR-010, INSTR-013, CONF-006, BR-029, BR-033, BR-034

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- api

**Data entities**

- users
- incidents
- incident_history

**Flows**

- Development environment initialization

**Scope note:** Seed inserts rows only (schema came with US-001) — the entities category names the tables it populates; apis [] because INSTR-010 explicitly states the seed has no endpoint.

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Entities

#### users

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, default gen_random_uuid() — seed rows: one employee (empleado@minisoporte.test) and one support agent (email from DEMO_SUPPORT_EMAIL) |
| email | TEXT | UQ, NO null — normalized (trimmed, lowercased) at seed time as at login (INSTR-006, BR-008); idempotency keys on the fixed emails |
| password_hash | TEXT | NO null — bcrypt hash of the env-supplied DEMO_EMPLOYEE_PASSWORD / DEMO_SUPPORT_PASSWORD; no default password ever embedded (BR-033) |
| role | TEXT | employee for the demo employee; support for the seeded agent (CHECK-constrained) |
| active | BOOLEAN | default TRUE |
| created_at | TIMESTAMPTZ | default now() |

#### incidents

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK — three seeded incidents, all owned by the demo employee (owner_id = employee id) |
| status | TEXT | pending / in_progress / resolved — one seeded incident per status (FUNC-009) |
| title/description/category/priority | TEXT | demo values within the CHECK-constrained sets (BR-013..016) |
| created_at / updated_at | TIMESTAMPTZ | consistent with the seeded history entries |

#### incident_history

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK — consistent history per seeded incident |
| previous_status | TEXT | NULL on each seeded creation entry; prior statuses on transition entries (INSTR-002, INSTR-005) |
| new_status / changed_by / changed_at | TEXT / UUID / TIMESTAMPTZ | consistent with each incident's status and timeline (FUNC-009) |

### Conventions

- Seed module `app/seed/demo_seed.py` (package `seed`); reads configuration ONLY through the DI-provided settings object (CONF-006) — seed-only variables DEMO_EMPLOYEE_PASSWORD, DEMO_SUPPORT_PASSWORD, DEMO_SUPPORT_EMAIL; missing mandatory variables fail with a clear message (no os.getenv elsewhere).
- The seed inserts rows only — it never alters schema (schema changes ship as Alembic migrations, CONF-008); it is idempotent (running twice leaves the same record count).
- Emails normalized (trimmed, lowercased) at seed time (INSTR-006); passwords hashed with bcrypt directly (BR-026).
- Negative-config tests verify startup/seed failures: missing JWT_SECRET, missing DEMO_EMPLOYEE_PASSWORD/DEMO_SUPPORT_PASSWORD fail with clear messages and no default password (BR-033); seed idempotency proven by running it twice and asserting the same record count.
- pytest + pytest-cov gate: `pytest --cov=app --cov-report=term-missing --cov-fail-under=90` scoped to `app/domain`, `app/core/security.py`, `app/core/ratelimit.py`, `app/api/deps.py` (INSTR-013).

### Flow Slice

Development environment initialization — Trigger: A developer or reviewer prepares the local internal-test environment. Steps: 1. Configure the environment variables — all secrets via env vars, never committed (CONF-006, BR-029): DEMO_EMPLOYEE_PASSWORD and DEMO_SUPPORT_PASSWORD for the seed, and TEST_DATABASE_URL for the pytest integration suite (BR-034). 3. Run the idempotent demo seed — module `app/seed/demo_seed.py`, no endpoint exists for it (FUNC-009, INSTR-010). It reads passwords exclusively from DEMO_EMPLOYEE_PASSWORD / DEMO_SUPPORT_PASSWORD and creates: one employee `empleado@minisoporte.test`, one support agent with the configured email, and three owned `incidents` covering `pending`, `in_progress`, and `resolved`, with consistent `incident_history` entries (including `previous_status: null` creation entries). It fails with a clear message if either password variable is missing, embeds no default password, and never runs automatically in production (BR-033). 5. Only then exercise the client: sign in as `empleado@minisoporte.test` on S-01 and run the authentication, review, and creation flows against the seeded data. Outcome & errors: Missing DEMO_EMPLOYEE_PASSWORD or DEMO_SUPPORT_PASSWORD — the seed aborts with a clear message and creates nothing (no default password exists in source, BR-033).

### Notes

Per Q-001 the internal-test environment runs this seed manually with configured emails (DEMO_SUPPORT_EMAIL), since v1 has no registration or user-management flow. This story closes the coverage gate across the modules delivered by US-001..US-004; its own dependencies list reflects that. The seed never runs automatically in production (ENVIRONMENT gate).

---

## Narrative

As a developer or internal-test operator I can run an idempotent demo seed that provisions one employee (empleado@minisoporte.test), one support agent (email from configuration) and three incidents covering Pending/In progress/Resolved with consistent history — only outside production and only with passwords supplied via DEMO_EMPLOYEE_PASSWORD/DEMO_SUPPORT_PASSWORD — so that the internal-test environment is reproducible and the mobile demo flows have realistic data; the same change finalizes the pytest-cov gate at >=90% business-logic coverage so the quality bar is mechanical, not aspirational. Trigger: manual seed invocation during environment preparation (Q-001), and every backend test run.

---

## Functional Flow

1. The operator exports DEMO_EMPLOYEE_PASSWORD, DEMO_SUPPORT_PASSWORD, DEMO_SUPPORT_EMAIL and TEST_DATABASE_URL (CONF-006, BR-029). 2. The operator runs app/seed/demo_seed.py manually (no endpoint exists for it, INSTR-010). 3. If either password variable is missing, the seed aborts with a clear message and creates nothing — no default password is embedded (BR-033). 4. Otherwise the seed inserts (rows only, never schema): the employee, the support agent, and three owned incidents covering pending/in_progress/resolved with consistent incident_history entries including previous_status NULL creation entries (FUNC-009). 5. The operator verifies with GET /health (200 {"status": "ok"}) and a demo sign-in as empleado@minisoporte.test. 6. The operator/engineer runs `pytest --cov=app --cov-report=term-missing --cov-fail-under=90`; the gate must pass on business-logic packages before the internal-test build is shared (INSTR-013, BR-034). Errors: seed refuses to run with ENVIRONMENT=production; re-running the seed twice leaves the same record count (idempotency).

---

## Acceptance Criteria

- **CA-01:** Given both DEMO password variables (and DEMO_SUPPORT_EMAIL), When the seed runs once, Then users contains empleado@minisoporte.test (role employee) and the configured support agent (role support), and incidents contains three owned incidents with statuses pending, in_progress and resolved.
- **CA-02:** Given the seeded data, When the history is inspected, Then each incident has consistent incident_history entries including a creation entry with previous_status NULL, and each transition entry matches the incident's current status timeline.
- **CA-03:** Given DEMO_EMPLOYEE_PASSWORD or DEMO_SUPPORT_PASSWORD unset, When the seed runs, Then it aborts with a clear message naming the missing variable, creates nothing, and no default password exists anywhere in source (BR-033).
- **CA-04:** Given ENVIRONMENT=production, When the seed is invoked, Then it refuses to run (INSTR-010, BR-033).
- **CA-05:** Given the seed has already run, When it runs a second time, Then the record counts for users, incidents and incident_history are unchanged (idempotent).
- **CA-06:** Given the full backend suite, When `pytest --cov=app --cov-report=term-missing --cov-fail-under=90` runs against TEST_DATABASE_URL, Then the gate passes on app/domain, app/core/security.py, app/core/ratelimit.py and app/api/deps.py (INSTR-013, BR-034).
- **CA-07:** Given no TEST_DATABASE_URL, When the suite runs on a Docker-equipped dev machine, Then Testcontainers may serve as an optional fallback, but no test requires Docker to pass in the sandbox.
- **CA-08:** Given negative-config tests, When run, Then missing JWT_SECRET and missing demo password variables are proven to fail with clear messages (no defaults).

---

## Business Rules

- BR-033 — Demo seed passwords come exclusively from DEMO_EMPLOYEE_PASSWORD and DEMO_SUPPORT_PASSWORD; a missing variable fails the seed with a clear message, no default password is embedded, the seed is idempotent, and it never runs automatically in production (INSTR-010).
- BR-034 — Backend unit and integration tests cover authentication, validation, ownership isolation, role protection of the status endpoint, and API error handling, targeting >=90% coverage of business logic (INSTR-013).
- BR-029 — All application secrets are supplied through environment variables; secret files are Git-excluded.

---

## Dependencies

- US-002
- US-003
- US-004

---

## Security Considerations

- No default passwords anywhere in source; seed passwords only from environment variables (BR-033, CONF-006).
- The seed refuses production environments, preventing demo credentials in real deployments (INSTR-010).
- Bcrypt hashing at seed time matches login-time verification (BR-026).

---

## Non-Functional Requirements

- Idempotency makes the seed safe to re-run during environment preparation (Q-001 manual run in internal-test).
- The coverage gate is mechanical: the build review cannot pass below 90% business-logic coverage (INSTR-013, wizard thresholds 90).
- Test determinism: fake clocks, no sleeps, transaction-rollback isolation (backend test strategy).

---

## Activity Diagram

@startuml
start
:Operator sets DEMO_EMPLOYEE_PASSWORD, DEMO_SUPPORT_PASSWORD, DEMO_SUPPORT_EMAIL, TEST_DATABASE_URL (BR-029);
:Run app/seed/demo_seed.py manually;
if (Both demo password env vars set? (BR-033)) then (no)
  :Seed aborts with clear message - nothing created;
  stop
else (yes)
endif
if (ENVIRONMENT is production?) then (yes)
  :Seed refuses to run;
  stop
else (no)
endif
:Idempotent insert - one empleado@minisoporte.test, one support agent, three incidents (pending/in_progress/resolved) with consistent history (FUNC-009);
:Verify GET /health 200 {"status": "ok"} + demo sign-in;
:Run pytest --cov=app --cov-fail-under=90 (INSTR-013, BR-034);
stop
@enduml
