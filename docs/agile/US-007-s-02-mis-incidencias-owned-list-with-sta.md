# US-007 — S-02 Mis incidencias: owned list with status filter chips, states and sign-out

**Epic:** EPIC-002 — Employee Mobile Experience
**Feature:** FEAT-006 — My Incidents: List, Filter & Sign Out
**Status:** locked (approved)
**Related Items:** FUNC-002, FUNC-003, FUNC-004, INSTR-001, INSTR-012, CONF-009, BR-005, BR-009, BR-017, BR-019, BR-020, BR-036

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- mobile-app

**Screens**

- S-02 — Mis incidencias

**Flows**

- Incident review flow (list, filter, details)
- Session lifecycle (sign in to sign out)

**Scope note:** Screen story for the list half of the review flow plus sign-out from the session lifecycle flow, rendered against the seeded in-memory FakeIncidentRepository. APIs are [] because the real GET /api/incidents contract is realized by US-012 — the mock story only represents the flow against the fake. Entities [] because the client renders fake data without persisting it.

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Conventions

- Feature package ui/incidents: IncidentListScreen.kt, StatusFilterChips.kt, IncidentListViewModel.kt; shared ui/components/IncidentCard.kt, StatusBadge.kt (text + color, never color alone — INSTR-012), ErrorRetryState.kt.
- Screens compose Scaffold with a top app bar and (on S-02) the bottom navigation bar; list content in LazyColumn with stable key = { it.id } items; stateless composables with UiState + event lambdas; state hoisting mandatory.
- IncidentListUiState covers loading/empty/error+retry/content; exposed via MutableStateFlow + asStateFlow(); collected with collectAsStateWithLifecycle(); data loads in LaunchedEffect(key); one-shot events (toast 'Sesión cerrada', navigation) emitted as consumed events.
- All data access goes through the IncidentRepository seam wired to the in-memory FakeIncidentRepository from US-005 with deterministic seeded data; reads are suspending and resolved from memory — no Retrofit, OkHttp, DTO, JWT or network code is reachable from this screen; the fake's simulated-failure capability exercises the error+retry state (CONF-009 convention established by US-005), never a network error.
- Sign-out deletes the fake session token from the in-memory TokenStore fake only — no Keystore, no server call — and emits the navigation event to S-01.
- Test tags s02_* (e.g. s02_filter_chip_pendiente); 48dp minimum touch targets; chips/badges pill-shaped (50% radius).

### Test Strategy

["ViewModel unit tests over the fake: loading -> content for the seeded owned list, empty state for a filter with no matches, chip counts derived client-side from the seeded array, error+retry driven by the fake's simulated failure.", 'Owner-scoping test: the fake returns only incidents owned by the signed-in mock user under every filter.', 'Sign-out test: Salir clears the in-memory TokenStore fake, emits the toast event and routes to S-01; no protected screen reachable afterward.', 'Compose component tests with s02_* tags: cards render title/category/priority/status/date, chips filter and show live counts, status badge text + color.', './gradlew check passes with no backend, no network, no secrets.']

### Flow Slice

Incident review flow (mock-first) — Step 1: On S-02 entry the ViewModel loads the list through the repository seam exclusively from the in-memory FakeIncidentRepository; the fake returns only incidents owned by the signed-in mock user (INSTR-001, BR-009), newest-first with no pagination (BR-020); list items omit description. S-02 renders each card with title, category, priority, status badge, and 'Creada el <date>', plus loading / empty ('Sin incidencias') / error+retry states. Step 2: The employee taps a filter chip (Todas / Pendiente / En progreso / Resuelta); the ViewModel re-queries the fake with the status filter (FUNC-004) and count badges update live. Outcome & errors: an empty result set shows the 'Sin incidencias' state (normal edge case); the error+retry state is exercised via the fake's simulated failure (CONF-009) — no network failure path exists in the mock MVP. Session lifecycle — Step 3: The employee taps 'Salir' on S-02; sign-out clears the fake session token from the in-memory TokenStore fake only (FUNC-002, mock-phase BR-005), shows the confirmation toast 'Sesión cerrada', and returns to S-01 with no protected screen reachable afterward. No JWT, no Keystore and no HTTP request are involved anywhere; the real transport behavior is deferred to US-012.

### Notes

The FAB's navigation to S-03 is wired here; the create screen itself is US-008, and the card -> S-04 drill-down is completed by US-009. Chip counts derive from the currently loaded owned list (client-side counts over the fake's array, refreshed on load).

---

## Narrative

Implements the S-02 home screen exclusively on the mock foundation: the signed-in mock user's own seeded incidents newest-first as compact cards (title, category pill, priority pill, status badge, creation date), status filter chips with live counts, loading/empty/error+retry states, the accent FAB to S-03, and the Salir sign-out that clears only the in-memory mock session. All data comes from the deterministic in-memory FakeIncidentRepository — no GET /api/incidents, no HTTP responses, no JWT, no Android Keystore and no INTERNET permission; the real REST list contract is delivered by US-012.

---

## Functional Flow

1. On S-02 entry the ViewModel loads the incident list through the repository seam exclusively from the in-memory FakeIncidentRepository (no GET /api/incidents, no HTTP) — owner-scoped to the signed-in mock user, newest first, no pagination. 2. The list renders as cards (title + status badge, category pill, priority pill, 'Creada el <date>'); loading, empty ('Sin incidencias') and error+retry states cover every load outcome; the retry path is exercised via the fake's simulated failure capability (CONF-009 convention established by US-005), not by network errors. 3. Tapping a filter chip (Todas/Pendiente/En progreso/Resuelta) re-queries the fake with the status filter and updates every chip's live count badge; filtering never exposes another mock user's incidents. 4. Tapping a card navigates to S-04. 5. Tapping the FAB opens S-03. 6. Tapping 'Salir' clears the in-memory mock session (deletes the fake token from the in-memory TokenStore fake — no server endpoint, no Keystore, mock-phase BR-005), shows the confirmation toast 'Sesión cerrada' and routes to S-01; no protected screen remains accessible afterward (BR-005). Out of scope (US-012): the real GET /api/incidents contract, HTTP responses, expired-token recovery and any network behavior — no such logic exists in the mock MVP.

---

## Acceptance Criteria

- **CA-01:** CA-01: Given the signed-in mock user owns 3 seeded incidents (mixed statuses), When S-02 loads from the in-memory FakeIncidentRepository, Then exactly those 3 cards render newest-first, each with title, category pill, priority pill, status badge and 'Creada el <date>' (BR-019, BR-009).
- **CA-02:** CA-02: Given the load is in flight, When S-02 is entered, Then the loading state renders; given the owned list for the active filter is empty, Then the 'Sin incidencias' empty state renders (FUNC-003).
- **CA-03:** CA-03: Given the fake's simulated failure mode (CONF-009 convention from US-005), When the load fails, Then the error state renders with a retry button and tapping retry reloads from the fake without crashing — no network error path exists in this story.
- **CA-04:** CA-04: Given the chips Todas/Pendiente/En progreso/Resuelta, When a chip is tapped, Then the list narrows to that status, the active chip is filled dark green, and every chip's live count badge updates from the seeded list (FUNC-004, BR-017).
- **CA-05:** CA-05: Given seeded incidents for more than one mock user, When the signed-in mock user filters or reloads, Then no card, count, or detail path ever exposes another mock user's data (BR-009, INSTR-001).
- **CA-06:** CA-06: Given 'Salir' is tapped, When sign-out completes, Then the fake session token is deleted from the in-memory TokenStore fake (nothing readable remains), the toast 'Sesión cerrada' shows, and the app routes to S-01 with no protected screen reachable — no Keystore, no JWT and no network call involved (FUNC-002, mock-phase BR-005).
- **CA-07:** CA-07: Given a status badge, When rendered, Then it always shows its Spanish label together with its paired background color — never color alone (INSTR-012, BR-036).
- **CA-08:** CA-08: Given the test suites, When ./gradlew check runs, Then the list/filter ViewModel state-machine tests over the fake and s02_* Compose component tests pass fully offline, and journeys 2 and 5 of the five mandatory mock journeys hold.
- **CA-09:** CA-09: Given the story scope, When inspected, Then no real API contract logic exists — no GET /api/incidents, no HTTP responses, no JWT, no Android Keystore, no INTERNET permission — these are explicitly deferred to US-012, and this story depends only on US-005 and US-006.

---

## Business Rules

- BR-005 (mock-phase) — Signing out clears the in-memory mock session (the fake token is deleted from the in-memory TokenStore fake), and no protected screen remains accessible afterward; no server endpoint, JWT or Keystore exists in the mock MVP (FUNC-002, Q-008).
- BR-009 — The list shows only incidents the signed-in mock user owns; the fake enforces owner isolation under every filter (INSTR-001).
- BR-017 — Statuses limited to the fixed set pending/in_progress/resolved; filter options are All, Pending, In progress, Resolved.
- BR-019 — Own incidents, newest first, each card showing title, category, priority, status, and creation date.
- BR-020 — No pagination in v1: all owned seeded incidents matching the filter are rendered.
- BR-036 — Statuses differentiated with text and color — never color alone; palette/contrast/touch-target baseline (INSTR-012).
- BR-041 — Mock-first: the only data source for S-02 is the deterministic in-memory FakeIncidentRepository; no network, backend or HTTP transport is required or present.

---

## Dependencies

- US-005
- US-006

---

## Security Considerations

- Sign-out is a local in-memory operation only: it deletes the fake session token from the in-memory TokenStore fake and involves no server, no JWT and no Keystore (mock-phase BR-005).
- The UI can only render owner-scoped fake data; the fake scopes every read to the signed-in mock user and filtering cannot widen scope (INSTR-001, BR-009).
- No network calls and no INTERNET permission in this story — mock data never leaves the device and nothing can be intercepted in transit.
- Token material (the fake session token) is never logged and never rendered in UiState (CONF-002, mock-phase BR-006).

---

## Non-Functional Requirements

- Deterministic in-memory reads resolve effectively instantly (<2 s budget trivially met) and produce identical results on every run; list rendering in LazyColumn with stable keys for smooth scrolling.
- Loading/empty/error states on the screen at all times; retry always available via the fake's simulated failure convention (CONF-009).
- 48dp touch targets; pill chips 999px radius; light theme tokens only (BR-036); no backend or network connectivity required.

---

## Activity Diagram

@startuml
start
:Enter S-02 - load GET /api/incidents (owner-scoped, newest first);
if (Response?) then (network error)
  :Error state + retry (CONF-009);
  stop
else (401)
  :Expired-token recovery - clear token, route S-01;
  stop
else (200)
endif
if (Any incidents for the active filter?) then (no)
  :Empty state "Sin incidencias";
else (yes)
  :Render cards + filter chips with live counts (BR-019, FUNC-004);
endif
:Tap chip -> filtered owned list; tap card -> S-04; tap FAB -> S-03;
if (Salir tapped? (BR-005)) then (yes)
  :Delete Keystore token; toast "Sesión cerrada"; route S-01;
  stop
else (no)
  stop
endif
@enduml
