# US-003 — Incident REST API: owner-scoped list + status filter, create-as-Pending, detail with history

**Epic:** EPIC-001 — Incident Management Service
**Feature:** FEAT-003 — Incident API & Lifecycle
**Status:** locked (approved)
**Related Items:** FUNC-003, FUNC-004, FUNC-005, FUNC-006, INSTR-001, INSTR-002, INSTR-011, BR-009, BR-010, BR-011, BR-012, BR-013, BR-014, BR-015, BR-016, BR-017, BR-018, BR-019, BR-020, BR-021

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- api

**APIs**

- GET /api/incidents
- POST /api/incidents
- GET /api/incidents/{id}

**Data entities**

- incidents
- incident_history

**Flows**

- Incident creation flow
- Incident review flow (list, filter, details)

**Scope note:** This story implements the API half of the creation and review flows; the mobile halves are US-008 and US-009/US-007. No screens apply (reason: api component has no screens in the design).

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Endpoints

#### `GET /api/incidents`

**Request**

```
Query parameters: | status | string | No | One of `pending`, `in_progress`, `resolved`, `all`; absent or `all` = no filter (FUNC-004, BR-017) | default `all` (no filter). Any other `status` value -> 422 with a query-parameter loc entry ("loc": ["query", "status"]).
```

**Response**

```
200: a JSON array (no pagination envelope, Q-007) of list items, ordered created_at DESC. The list item omits `description` (the card shows only title, category, priority, status, and creation date; the full description is fetched per incident by GET /api/incidents/{id}). List item schema: | id | string (UUID) | No | Incident identifier | | title | string | No | 5–100 chars (BR-013) | | category | string | No | systems / equipment / access / other (BR-015) | | priority | string | No | low / medium / high (BR-016) | | status | string | No | pending / in_progress / resolved (BR-017) | | created_at | string (ISO 8601 UTC) | No | Creation instant; ordering key (BR-019) | | updated_at | string (ISO 8601 UTC) | No | Last status mutation instant |
```

**Validation:** Errors: 401 {"detail": "No autenticado"}; 422 for an unknown status value with loc ["query", "status"]. No 403/404 cases exist: the owner filter cannot fail, only shrink (owner scope applied in the repository from the token subject — no client parameter can widen it, INSTR-001/BR-009). Pagination: N/A — no pagination by binding Q-007 (BR-020): returns all owned incidents matching the filter, newest first, plain JSON array, no envelope, no page/page_size, no total.

#### `POST /api/incidents`

**Request**

```
Request body: | title | string | Yes | 5–100 characters inclusive (BR-013); char-level errors field-located (INSTR-011) | | description | string | Yes | 10–1000 characters inclusive (BR-014) | | category | string | Yes | One of systems, equipment, access, other (BR-015) | | priority | string | Yes | One of low, medium, high (BR-016) |. Mass-assignment defense (INSTR-002): the schema declares extra="forbid" and contains no status, owner_id, or id fields — a client-supplied {"status": "resolved", "owner_id": "..."} is rejected 422 (unknown field), and status is always server-set to pending (BR-011). Owner is always the token subject. An invalid body never creates a partial incident (INSTR-011).
```

**Response**

```
| 201 | Valid body | The created incident: {"id", "title", "description", "category", "priority", "status": "pending", "created_at", "updated_at"} — same field types as the list item plus description, with status always "pending" and created_at == updated_at | | 422 | Validation failure (BR-013–016, INSTR-011) or forbidden extra field | Structured 422 shape, one detail entry per offending field | | 401 | No/invalid token | {"detail": "No autenticado"} |
```

**Validation:** 422 rules: title 5–100 chars (BR-013), description 10–1000 chars (BR-014), category in systems/equipment/access/other (BR-015), priority in low/medium/high (BR-016), char-level errors field-located (INSTR-011), extra="forbid" rejects unknown fields; status never accepted from the client (BR-011); creation inserts one initial incident_history row with previous_status NULL (INSTR-005).

#### `GET /api/incidents/{id}`

**Request**

```
Path parameter `id`: UUID string; non-UUID -> 422.
```

**Response**

```
200: the incident object (fields as in the 201 body: id, title, description, category, priority, status, created_at, updated_at) plus a `history` array ordered ascending by `changed_at` (timeline order, S-04), assembled from incident_history (INSTR-005). History entry schema: | id | string (UUID) | No | History entry identifier | | previous_status | string | null | Yes | null on the initial-creation entry; otherwise prior status (INSTR-002, BR-025) | | new_status | string | No | Status after the change | | changed_by | string (UUID) | No | Actor's user id (the creator on the initial entry; the support agent on transitions, INSTR-005) | | changed_at | string (ISO 8601 UTC) | No | Transition instant, ascending sort key |
```

**Validation:** | 200 | Incident exists and owner_id = token subject | Detail object with history | | 401 | No/invalid token | {"detail": "No autenticado"} | | 404 | Incident missing or owned by another user — one indistinguishable safe response; no existence leak, identifiers cannot be probed (INSTR-001, FUNC-006, BR-010) | {"detail": "Incidencia no encontrada"} |

### Entities

#### incidents

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, NO null, default gen_random_uuid() (FUNC-006, INSTR-001) |
| owner_id | UUID | FK -> users.id, NO null, ON DELETE RESTRICT — the authenticated creator, never client input (INSTR-002); every query filters by it (INSTR-001, BR-009); index ix_incidents_owner_created (owner_id, created_at DESC) |
| title | TEXT | NO null, CHECK char_length BETWEEN 5 AND 100 (BR-013) |
| description | TEXT | NO null, CHECK char_length BETWEEN 10 AND 1000 (BR-014); omitted from list items, included in detail/201 |
| category | TEXT | NO null, CHECK IN ('systems','equipment','access','other') (BR-015) |
| priority | TEXT | NO null, CHECK IN ('low','medium','high') (BR-016) |
| status | TEXT | NO null, default 'pending', CHECK IN ('pending','in_progress','resolved') (INSTR-002, BR-011, BR-017) |
| created_at | TIMESTAMPTZ | NO null, default now() — ordering key created_at DESC (BR-019) |
| updated_at | TIMESTAMPTZ | NO null, default now(), onupdate — equals created_at at creation; set with the history insert on transitions (INSTR-003/005) |

#### incident_history

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, NO null, default gen_random_uuid() — exposed in the detail timeline payload (FUNC-006) |
| incident_id | UUID | FK -> incidents.id, NO null, ON DELETE RESTRICT — read WHERE incident_id = ? ORDER BY changed_at ASC; index ix_incident_history_incident_changed |
| previous_status | TEXT | YES null, default NULL, CHECK NULL or IN ('pending','in_progress','resolved') — NULL = initial creation entry (INSTR-002, BR-025) |
| new_status | TEXT | NO null, CHECK IN ('pending','in_progress','resolved') |
| changed_by | UUID | FK -> users.id, NO null, ON DELETE RESTRICT — the creator on the initial entry (INSTR-005) |
| changed_at | TIMESTAMPTZ | NO null, default now() — ascending timeline sort key |

### Conventions

- One route module per resource under `app/api/routes/` (`incidents.py`) — one HTTP operation per function; thin handlers only: declare `Annotated` dependencies, call one domain service, map domain exceptions to HTTP status codes (no business logic, no direct ORM queries).
- `app/db/repositories/incident_repository.py` and `history_repository.py` are the ONLY place SQLAlchemy queries are written; every incident query filters by `owner_id` there (INSTR-001); services never import `select` themselves; repositories commit nothing — the domain service orchestrates the unit of work.
- `app/domain/services/incident_service.py` holds all business rules: owner-scoped list/filter, create-as-Pending (INSTR-001/002); Pydantic v2 request schemas use Field(min_length=..., max_length=...) for title/description (BR-013/014) and Literal/Enum types for category/priority (BR-015/016); creation schema declares extra="forbid"; route handlers return typed response models so Pydantic serializes and filters the response.
- Domain exceptions (`IncidentNotFoundError`) map to `404` "Incidencia no encontrada" — same body for missing and foreign ids (BR-010); validation failures are never caught (Pydantic structured 422, INSTR-011).
- History rows are never updated or deleted by application code; the repository exposes append and read-only queries (INSTR-005, BR-012). Timestamps timezone-aware DateTime(timezone=True) with server_default=func.now().

### Flow Slice

Incident review flow — Step 1: On S-02 entry the app calls `GET /api/incidents` (no query parameter). The repository filters by `owner_id` = token subject — no client parameter can widen the scope (INSTR-001, BR-009) — and returns a plain JSON array ordered `created_at DESC` with no pagination (Q-007, BR-020); list items omit `description`. Step 2: The employee taps a filter chip; the app calls `GET /api/incidents?status=pending|in_progress|resolved` (or `all`); filtering applies on top of owner isolation (FUNC-004). Step 3: The employee taps a card; the app calls `GET /api/incidents/{id}` (owner only); the response carries the full incident (including `description`) plus the `history` array ordered ascending by `changed_at`. Outcome & errors: `422` — an unknown `status` value (`"loc": ["query", "status"]`); `404` {"detail": "Incidencia no encontrada"} — incident missing or owned by another user: one indistinguishable safe response (INSTR-001, BR-010); empty result set is a normal edge case. Incident creation flow — Steps 3-5: The app calls `POST /api/incidents` with `{"title", "description", "category", "priority"}`; the schema declares `extra="forbid"` with no `status`, `owner_id`, or `id` fields: a client-supplied status/owner_id is rejected `422` (mass-assignment defense, INSTR-002). The API service creates one `incidents` row: `owner_id` = token subject (INSTR-001), `status` = `"pending"` server-set and DB-defaulted (BR-011), `created_at == updated_at`; creation is all-or-nothing (INSTR-011). On `201` the new "Pendiente" incident appears at the top of the list (BR-019). `422` — validation failures: structured detail list with one entry per offending field; the incident is not created.

### Notes

List ordering uses index ix_incidents_owner_created (owner_id, created_at DESC); the status filter is a cheap residual predicate over that index. GET /api/incidents has no 403/404 cases — the owner filter can only shrink. The initial history entry (previous_status NULL, changed_by = creator) is written in the same transaction as the incident insert.

---

## Narrative

As an authenticated employee I can list only my own incidents (optionally filtered by status), create a new incident that always starts Pending, and open my incident's full detail with its chronological status history, so that I can report and follow my own reports while never seeing another employee's data. Trigger: GET /api/incidents on entering the list screen or tapping a chip, POST /api/incidents on submitting the form, GET /api/incidents/{id} on tapping a card.

---

## Functional Flow

1. GET /api/incidents (optional ?status=pending|in_progress|resolved|all) returns a plain JSON array of the token subject's incidents ordered created_at DESC, list items omitting description; no pagination (Q-007). 2. POST /api/incidents with {title, description, category, priority}: the schema declares extra="forbid" with no status/owner_id/id fields, so client-supplied status or owner is rejected 422 (INSTR-002); owner is the token subject, status server-set 'pending' (BR-011), created_at == updated_at, and one initial incident_history row with previous_status NULL is inserted. 3. GET /api/incidents/{id} returns the full incident (including description) plus the history array ascending by changed_at. Errors: invalid status query value -> 422 with loc ["query","status"]; field violations -> 422 field-located entries (BR-013..016, INSTR-011) and no partial incident is created; 401 {"detail": "No autenticado"} without a valid token; 404 {"detail": "Incidencia no encontrada"} indistinguishably for missing or foreign ids (BR-010).

---

## Acceptance Criteria

- **CA-01:** Given employee A owns 3 incidents and employee B owns 2, When A calls GET /api/incidents, Then exactly A's 3 incidents return as a plain JSON array ordered created_at DESC with no pagination envelope (BR-009, BR-019, BR-020).
- **CA-02:** Given a list response, When inspected, Then each item carries id/title/category/priority/status/created_at/updated_at and omits description (FUNC-003).
- **CA-03:** Given ?status=pending (or in_progress/resolved), When the list is fetched, Then only owned incidents with that status return; given ?status=bogus, Then the API returns 422 with loc ["query","status"] (FUNC-004, BR-017).
- **CA-04:** Given a valid create body, When POST /api/incidents is called, Then the response is 201 with status "pending", created_at == updated_at, owner = the token subject, and exactly one incident_history row exists with previous_status NULL and changed_by = the creator (INSTR-002, BR-011, INSTR-005).
- **CA-05:** Given a body with title of 4 chars, description of 1001 chars, or an unknown category/priority value, When submitted, Then the API returns 422 with one field-located detail entry per offending field and no incident row is created (BR-013..016, INSTR-011).
- **CA-06:** Given a body carrying extra fields {"status": "resolved", "owner_id": "..."}, When submitted, Then the API rejects it 422 (unknown field, extra="forbid") and status/owner remain server-controlled (INSTR-002).
- **CA-07:** Given the owner requests an own incident id, When GET /api/incidents/{id} is called, Then the 200 body carries the full incident including description plus history ascending by changed_at (BR-021).
- **CA-08:** Given a missing UUID or another employee's incident id, When GET /api/incidents/{id} is called, Then the response is the identical 404 {"detail": "Incidencia no encontrada"} in both cases, with no existence leak (BR-010).
- **CA-09:** Given integration tests, When run, Then owner isolation is proven across list, detail and history (A never sees B's data), and the coverage gate holds >=90% on the incident service/repository.

---

## Business Rules

- BR-009 — An employee can view only incidents they own; list, detail, and history queries are all owner-scoped in the repository.
- BR-010 — Requests for a missing or foreign incident return a safe response that does not leak the incident's existence or data (indistinguishable 404 shape).
- BR-011 — Every incident created starts in Pending status; status is never taken from client input (DB default + server-set).
- BR-012 — An incident belongs to exactly one owner (the authenticated creator); no endpoint exposes owner mutation.
- BR-013 — The incident title is required and must be 5–100 characters (client and server, field-level errors).
- BR-014 — The incident description is required and must be 10–1,000 characters (server authoritative).
- BR-015 — Category must be one of Systems, Equipment, Access, Other (stored systems/equipment/access/other).
- BR-016 — Priority must be Low, Medium, or High (stored low/medium/high).
- BR-017 — Statuses limited to the fixed set pending/in_progress/resolved (CHECK constraint + domain service).
- BR-018 — All form data is validated on client and backend; a failed validation never creates the incident; errors are field-located (INSTR-011).
- BR-019 — The list shows only the owner's incidents ordered newest first by creation date; list items show title, category, priority, status, creation date.
- BR-020 — No pagination in v1: all owned incidents matching the filter are returned (Q-007).
- BR-021 — The detail response carries the full incident plus the chronological status-history timeline (ascending changed_at).

---

## Dependencies

- US-001
- US-002

---

## Security Considerations

- Owner isolation is applied in the repository from the token subject — no client parameter can widen it (INSTR-001, BR-009).
- Safe non-leaking 404 for missing/foreign incidents prevents UUID probing (BR-010, INSTR-001).
- Mass-assignment defense: extra="forbid" creation schema without status/owner_id/id fields (INSTR-002).
- Opaque UUID identifiers in URLs prevent sequential enumeration (INSTR-001, data-model rationale).

---

## Non-Functional Requirements

- List responses target the <2 s budget (CONF-009): list items omit description to avoid bloating every refresh; index ix_incidents_owner_created serves owner+ordering without a sort step.
- No pagination by design in v1 (Q-007); revisit only if volume grows.
- Creation is transactional and all-or-nothing (INSTR-011).

---

## Activity Diagram

@startuml
start
:GET /api/incidents with Bearer JWT;
if (Token valid? (BR-004)) then (no)
  :401 "No autenticado" - expired-token recovery on the client;
  stop
else (yes)
endif
:Repository filters owner_id = token subject (INSTR-001, BR-009), ORDER BY created_at DESC (BR-019, BR-020);
if (status query parameter valid? ) then (invalid value)
  :422 with loc ["query","status"];
  stop
else (yes)
endif
:200 plain JSON array, list items omit description;
:Employee taps a card -> GET /api/incidents/{id};
if (Incident exists and owner_id = token subject? (BR-010)) then (no - missing or foreign)
  :404 "Incidencia no encontrada" - indistinguishable safe response;
  stop
else (yes)
  :200 incident incl. description + history ascending by changed_at (BR-021);
  stop
endif
@enduml
