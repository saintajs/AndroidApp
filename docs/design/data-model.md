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
