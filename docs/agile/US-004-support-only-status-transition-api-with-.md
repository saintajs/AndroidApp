# US-004 — Support-only status transition API with linear matrix and immutable history

**Epic:** EPIC-001 — Incident Management Service
**Feature:** FEAT-003 — Incident API & Lifecycle
**Status:** locked (approved)
**Related Items:** FUNC-007, INSTR-003, INSTR-004, INSTR-005, BR-010, BR-012, BR-022, BR-023, BR-024, BR-025

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- api

**APIs**

- PATCH /api/incidents/{id}/status

**Data entities**

- incidents
- incident_history

**Flows**

- Support status update flow

**Scope note:** API-only story by design (FUNC-007): no support mobile screen exists in v1, so screens is [] for a reason and no frontend work belongs to this story.

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Endpoints

#### `PATCH /api/incidents/{id}/status`

**Request**

```
Request body: | new_status | string | Yes | One of pending, in_progress, resolved (BR-017); anything else -> 422. Whether the value is a legal transition is decided by the service (-> 409, INSTR-003) | example "in_progress". This is the body's only field — no comment, owner, or timestamp fields are accepted (immutable history, BR-012/025). Transition matrix (validated before any write; INSTR-003, BR-023/024): pending->pending 409, pending->in_progress OK, pending->resolved 409 (skip); in_progress->pending 409 (backwards), in_progress->in_progress 409 (repeat), in_progress->resolved OK; resolved->any 409 (terminal). Every accepted transition atomically updates incidents.status + updated_at and inserts one immutable incident_history row (previous status, new status, actor = token subject, timestamp) in one transaction (INSTR-005, BR-025).
```

**Response**

```
200: the updated incident object (id, title, description, category, priority, status, created_at, updated_at) plus the latest history entry: {"incident": {...}, "latest_history": {"id", "previous_status", "new_status", "changed_by", "changed_at"}} — e.g. latest_history {"previous_status": "pending", "new_status": "in_progress", "changed_by": "<support agent uuid>", "changed_at": "2026-10-06T09:12:00Z"}.
```

**Validation:** Errors (checked in this order): 401 {"detail": "No autenticado"} (no/invalid token); 403 {"detail": "No autorizado"} (caller role is employee — role is checked BEFORE any incident lookup, so the forbidden response is uniform and leaks nothing, INSTR-004/BR-022); 404 {"detail": "Incidencia no encontrada"} (caller is support but the id is missing or malformed-UUID-not-found — same safe 404 shape as the owner route, no existence leak, BR-010); 409 {"detail": "Transición de estado no válida"} (value in the fixed set but backwards, skipped, repeated, or out of resolved, INSTR-003/BR-023/024); 422 structured shape (new_status absent or outside the fixed set). Support is NOT owner-scoped on this route: the support role exists to advance any employee's incident; owner isolation remains intact because employees can never call it (INSTR-004).

### Entities

#### incidents

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK — path parameter; missing/malformed-not-found -> safe 404 for support callers (BR-010) |
| status | TEXT | NO null, CHECK IN ('pending','in_progress','resolved') — updated only by this endpoint through the service-layer matrix (BR-017, BR-023) |
| updated_at | TIMESTAMPTZ | NO null, onupdate — set inside the same transaction as the history insert (INSTR-003, INSTR-005) |

#### incident_history

| Field | Type | Constraints |
|-------|------|-------------|
| id | UUID | PK, default gen_random_uuid() |
| incident_id | UUID | FK -> incidents.id, NO null, ON DELETE RESTRICT — one row per accepted transition |
| previous_status | TEXT | YES null, CHECK NULL or IN ('pending','in_progress','resolved') — prior status; NULL only for the creation entry (BR-025) |
| new_status | TEXT | NO null, CHECK IN ('pending','in_progress','resolved') |
| changed_by | UUID | FK -> users.id, NO null, ON DELETE RESTRICT — the support agent's token subject (INSTR-005) |
| changed_at | TIMESTAMPTZ | NO null, default now() |

### Conventions

- Route module `app/api/routes/statuses.py` — thin handler; access level declared on the route signature (support only) via the reusable dependency `require_role("support")` wrapping `get_current_user` (BR-022, CONF-011).
- `app/domain/services/status_service.py` holds the linear transition matrix + immutable history write (INSTR-003/004/005); typed domain exceptions `ForbiddenRoleError`, `IncidentNotFoundError`, `InvalidTransitionError` map to 403/404/409 with the exact Spanish detail strings; the service orchestrates the unit of work (validate -> update + history insert in one transaction), repositories commit nothing.
- History rows are never updated or deleted by application code; the repository exposes append and read-only queries (INSTR-005, BR-012).
- Integration tests mark DB-dependent cases `@pytest.mark.integration`; AAA structure; naming `test_<unit>_<scenario>_<expected_outcome>`.

### Flow Slice

Support status update flow — Trigger: A support agent (role support) issues an API call to advance an employee's incident. API-only: no support mobile screen exists in v1. Steps: 1. The support agent authenticates via POST /api/auth/login; the token carries role: "support" (CONF-001). 2. PATCH /api/incidents/{id}/status with {"new_status": "in_progress"} or {"new_status": "resolved"}; the body's only field is new_status (BR-012/025). 3. The API checks the caller's role before any incident lookup (INSTR-004): an employee token gets 403 {"detail": "No autorizado"} uniformly (BR-022). 4. For a support caller, the service loads the incidents row (missing/malformed id -> safe 404 {"detail": "Incidencia no encontrada"}) and validates the linear transition matrix before any write (INSTR-003): pending->in_progress OK, in_progress->resolved OK; backwards, skipped, repeated, and any transition out of resolved are rejected (BR-023/024). 5. For an accepted transition, one atomic transaction updates incidents.status + updated_at and inserts one immutable incident_history row (INSTR-005, BR-025). 6. The employee observes the change through the incident review flow: the next GET /api/incidents refresh shows the new status badge, and GET /api/incidents/{id} extends the S-04 timeline (FUNC-006). Outcome & errors: Success 200 {"incident", "latest_history"}; 401/403/404/409/422 as in the errors table above.

### Notes

Role check strictly precedes the incident lookup so the forbidden response is uniform and leaks nothing. Resolved is terminal in v1 — reopening is a deliberate future-version decision, not a story. The transition matrix is service-layer logic, not a DB constraint (data-model.md).

---

## Narrative

As a support agent I can advance any employee's incident through the linear lifecycle Pending -> In progress -> Resolved via PATCH /api/incidents/{id}/status, with every accepted transition recorded as one immutable history entry, so that employees see trustworthy progress while Resolved stays terminal and employees are locked out of the operation. Trigger: a support-role token calls the endpoint with {"new_status": ...}.

---

## Functional Flow

1. The support agent authenticates via POST /api/auth/login; the token carries role: "support" (CONF-001). 2. PATCH /api/incidents/{id}/status with {"new_status": "in_progress"|"resolved"} — the body's only field (immutable history, BR-012/025). 3. The API checks the caller's role BEFORE any incident lookup: an employee token gets 403 {"detail": "No autorizado"} uniformly, whether or not the incident exists (INSTR-004, BR-022). 4. For support, the service loads the incident (missing id -> safe 404) and validates the linear transition matrix before any write: pending->in_progress OK, in_progress->resolved OK; backwards, skipped, repeated and any move out of resolved are rejected (INSTR-003, BR-023/024). 5. For an accepted transition, one atomic transaction updates incidents.status + updated_at and inserts one immutable incident_history row (previous_status, new_status, changed_by = token subject, changed_at) (INSTR-005, BR-025). 6. The employee observes the change via the next list/detail refresh. Errors: 422 for new_status absent or outside the fixed set; 409 {"detail": "Transición de estado no válida"} for illegal transitions; 401 for invalid tokens.

---

## Acceptance Criteria

- **CA-01:** Given a support token and a pending incident, When PATCH new_status=in_progress is called, Then the response is 200 with the updated incident (status in_progress, refreshed updated_at) plus latest_history carrying previous_status pending, new_status in_progress, changed_by = the support agent's id.
- **CA-02:** Given a support token and an in_progress incident, When new_status=resolved is called, Then the transition is accepted and exactly one new incident_history row is appended (INSTR-005, BR-025).
- **CA-03:** Given a resolved incident, When any new_status is submitted, Then the API returns 409 {"detail": "Transición de estado no válida"} (Resolved terminal, BR-024); likewise pending->resolved (skip), in_progress->pending (backwards) and repeat transitions return 409 (BR-023).
- **CA-04:** Given an employee token, When PATCH /api/incidents/{id}/status is called (existing or nonexistent id), Then the response is uniformly 403 {"detail": "No autorizado"} and no incident lookup result is distinguishable (INSTR-004, BR-022).
- **CA-05:** Given a support token and a missing id, When the transition is requested, Then the response is the safe 404 {"detail": "Incidencia no encontrada"} (BR-010).
- **CA-06:** Given new_status absent or outside the fixed set, When submitted, Then the API returns the structured 422 shape; given a body carrying fields beyond new_status, Then they are not accepted (BR-012).
- **CA-07:** Given an accepted transition, When the transaction commits, Then incidents.status and updated_at change and exactly one immutable incident_history row is inserted in the same transaction; history rows are never updated or deleted by any code path (INSTR-005).
- **CA-08:** Given the unit suite, When run, Then the full 3x3 transition matrix is covered including Resolved-terminal, and the integration suite proves employee-403 role protection and the exactly-one-history-row invariant at >=90% coverage (INSTR-013).

---

## Business Rules

- BR-022 — Only users with the support role can update an incident's status; an employee attempting the endpoint receives a forbidden response (role checked before any lookup).
- BR-023 — The only valid transitions are Pending -> In progress and In progress -> Resolved; backwards, skipped, and repeated transitions are rejected (validated before any write).
- BR-024 — A Resolved incident cannot return to a previous status in v1 (Resolved is terminal).
- BR-025 — Every accepted status change records an immutable history entry with previous status, new status, date, and actor; initial creation may leave previous status empty.
- BR-010 — Support callers receive the same safe non-leaking 404 for missing ids.
- BR-012 — The body accepts only new_status: no comment, owner, or timestamp fields are accepted (immutable history).

---

## Dependencies

- US-003

---

## Security Considerations

- Role check before any lookup: uniform 403 leaks nothing about incident existence (INSTR-004, BR-022).
- Safe 404 for support callers on missing ids (BR-010); support is intentionally not owner-scoped, employees can never call the route.
- Only new_status is accepted — no client-supplied actor, comment, or timestamp can enter the audit trail (BR-012/025).

---

## Non-Functional Requirements

- Transition validation happens before any write; the write is one atomic transaction (INSTR-003/005).
- Append-only history preserves timeline trustworthiness (INSTR-005).
- No support UI exists; the endpoint is exercised by API tests and support tooling only (FUNC-007).

---

## Activity Diagram

@startuml
start
:Support agent authenticated via POST /api/auth/login (token carries role support);
:PATCH /api/incidents/{id}/status with {new_status};
if (Caller role is support? (BR-022)) then (no - employee)
  :403 "No autorizado" - checked before any lookup, leaks nothing;
  stop
else (yes)
endif
if (Incident exists? (BR-010)) then (no)
  :404 "Incidencia no encontrada" - safe shape;
  stop
else (yes)
endif
if (Linear transition legal? (BR-023/024)) then (no - backwards/skipped/repeated/out of resolved)
  :409 "Transición de estado no válida";
  stop
else (yes)
endif
:One transaction - UPDATE incidents.status + updated_at AND INSERT immutable incident_history (BR-025);
:200 {"incident": ..., "latest_history": ...};
stop
@enduml
