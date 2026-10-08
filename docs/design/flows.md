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
