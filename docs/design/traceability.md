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
