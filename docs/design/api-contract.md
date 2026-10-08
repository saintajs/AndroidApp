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
