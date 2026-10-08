# US-009 — S-04 Detalle de incidencia: detail card and newest-first mock status-history timeline with safe no-access state

**Epic:** EPIC-002 — Employee Mobile Experience
**Feature:** FEAT-007 — Create & Review Incidents
**Status:** locked (approved)
**Related Items:** FUNC-006, INSTR-001, INSTR-005, INSTR-012, CONF-009, BR-009, BR-010, BR-012, BR-021, BR-036

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- mobile-app

**Screens**

- S-04 — Detalle de incidencia

**Flows**

- Incident review flow (list, filter, details)

**Scope note:** Completes the review flow's detail half (list half delivered by US-007) against the seeded mock data. APIs are [] because the real GET /api/incidents/{id} contract is realized by US-012 — this story represents the flow on the in-memory repository only. Entities [] — the timeline is rendered from the in-memory fake with no local persistence; history stays read-only for employees (BR-012).

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Conventions

- Feature package ui/detail: IncidentDetailScreen.kt with IncidentDetailViewModel.kt; stateless composable taking IncidentDetailUiState + event lambdas; loads in LaunchedEffect(key = incidentId) so the timeline refreshes on re-entry from the in-memory store.
- Timeline rows are read-only rendered data; history is immutable for employees — no edit affordance exists and the fake exposes no mutation path (INSTR-005, BR-012); StatusBadge renders status as text + color together (INSTR-012).
- The ViewModel explicitly reverses the fake's ascending-by-changed_at history for display, keeping 'Creación — Pendiente' first-entry semantics; this transformation is unit-tested so it cannot regress silently.
- Safe no-access state reuses the shared ErrorRetryState/placeholder style (dashed-border box, lock icon) with Spanish copy; identical for missing and foreign ids (BR-010).
- Timeline semantics: entries announced with status line, timestamp and actor via contentDescription/liveRegion for TalkBack; 48dp back-button target; test tags s04_* (e.g. s04_timeline_item).
- All data comes from FakeIncidentRepository (US-005 seed); no Retrofit/OkHttp/DTO/network code is reachable from this screen.

### Test Strategy

["ViewModel unit tests: ascending seeded history is reversed to newest-first; 'Creación — Pendiente' semantics for previous_status null; safe no-access state for missing id and for foreign id with identical output.", 'Compose component tests with s04_* tags: detail card rows, status badge text + color, filled-dot highlight on the first rendered timeline row, no-access state rendering.', 'Seed data test: the US-005 mock seed contains multi-entry histories and incidents for more than one mock user so ownership isolation is demonstrable.', './gradlew check passes with no backend, no network, no secrets.']

### Flow Slice

Incident review flow (mock-first) — Step 3: the tester taps a seeded mock card on S-02; the app opens S-04 and loads the detail through the repository seam exclusively from the in-memory FakeIncidentRepository. The fake returns the full incident (including description) plus the history array stored ascending by changed_at — the ViewModel reverses it and S-04 renders the 'Historial de estados' timeline newest-first, with 'Creación — Pendiente' for the initial entry (previous_status null) and actor attribution from the seed (INSTR-005, BR-021). Step 4: 'Mis incidencias' navigates back to S-02; the timeline refreshes on each re-entry by re-reading the in-memory store. Outcome & errors: a missing or foreign id yields the identical safe no-access state — 'Incidencia no disponible' with the hint 'No se encontró la incidencia o no tienes acceso a ella.' (INSTR-001, BR-010). The real GET /api/incidents/{id} contract, 401 expired-token recovery and transport-failure mapping are deferred to US-012.

### Notes

The fake stores history ascending by changed_at exactly as the future real endpoint will return it; the ViewModel reversal and the newest-first rendering with the filled-dot highlight are covered by dedicated unit/Compose tests so the ordering contract cannot regress when US-012 swaps in the real repository implementation. The seeded mock data deliberately includes incidents for more than one mock user and multi-entry histories so ownership, filtering and history behavior are all represented without any backend.

---

## Narrative

Implements the S-04 detail screen on the mock foundation: the full incident card and the 'Historial de estados' timeline rendered newest-first from the seeded in-memory FakeIncidentRepository, with filtering and incident ownership represented in the mock seed and the safe no-access state for missing or foreign ids. No REST API dependency — the real GET /api/incidents/{id} contract is delivered by US-012.

---

## Functional Flow

1. Tapping a card on S-02 navigates to S-04 with the incident id; the ViewModel loads the detail through the repository seam exclusively from the in-memory FakeIncidentRepository (no network, no REST API). 2. The detail card renders ID <uuid> (caption), title (heading-md) with its status badge (color + label, INSTR-012), the description block, and label/value rows for Categoría, Prioridad, Estado and Fecha de creación. 3. The 'Historial de estados' timeline renders the seeded mock history — stored ascending by changed_at — explicitly reversed by the ViewModel so the screen shows newest-first, with 'Creación — Pendiente' for the initial entry (previous_status null), actor attribution per the seed's mock actor, and the latest entry highlighted with a filled dot. 4. Given a missing or foreign id (the fake scopes all reads to the signed-in mock user's incidents), S-04 renders the safe no-access state: dashed-border box with a lock icon, 'Incidencia no disponible' and the hint 'No se encontró la incidencia o no tienes acceso a ella.' — identical for both cases (BR-010). 5. 'Mis incidencias' navigates back to S-02; re-entering S-04 re-reads the in-memory store so the timeline reflects the current fake state. Out of scope (US-012): the real GET /api/incidents/{id} endpoint contract, 401 expired-token recovery and transport-failure handling.

---

## Acceptance Criteria

- **CA-01:** CA-01: Given the employee taps an owned seeded mock card on S-02, When S-04 loads, Then the detail card shows ID <uuid>, title with status badge (label + color), description, and the Categoría/Prioridad/Estado/Fecha de creación rows, all served by the in-memory FakeIncidentRepository (BR-021).
- **CA-02:** CA-02: Given the fake stores the mock history ascending by changed_at, When the ViewModel maps the seeded payload for display, Then it explicitly reverses it to newest-first, and the timeline renders entries as 'Pendiente → En progreso' style status lines with 'Creación — Pendiente' for the initial creation entry (previous_status null), each with timestamp and mock actor, with the newest entry visually highlighted via a filled dot (INSTR-005, BR-021).
- **CA-03:** CA-03: Given a missing incident id, and separately a foreign (not owned by the signed-in mock user) incident id, When S-04 loads against the fake, Then both render the identical safe no-access state ('Incidencia no disponible' + hint) with no distinguishing information (BR-010).
- **CA-04:** CA-04: Given the seeded mock data, When inspected, Then it represents filtering and ownership behavior — incidents for more than one mock user with different statuses and multi-entry histories — so the owner-scoped, newest-first, filterable behavior is demonstrable from mock data alone (Q-005, BR-041).
- **CA-05:** CA-05: Given the mock MVP, When the whole detail journey runs, Then it completes with zero network calls — no Retrofit/OkHttp code path is reachable from this screen and no REST API is required (BR-041).
- **CA-06:** CA-06: Given 'Mis incidencias' is tapped, When navigation completes, Then the app returns to S-02 scrolled to top; re-entering S-04 re-reads the in-memory store so the timeline reflects the current fake state.
- **CA-07:** CA-07: Given the test suites, When ./gradlew check runs, Then detail ViewModel tests (safe no-access for missing and foreign ids, ascending-to-newest-first mapping) and s04_* Compose tests pass, with the filled-dot highlight on the first rendered row asserted via the s04_timeline_item tag, and journey 4 of the mandatory mock journeys holds.
- **CA-08:** CA-08: Given the story scope, When inspected, Then no real API contract logic exists — no GET /api/incidents/{id} endpoint contract, no 401 expired-token recovery, no transport-failure handling — these are explicitly deferred to US-012, and this story depends only on US-005 and US-007.

---

## Business Rules

- BR-009 — Detail and history are owner-scoped; the fake repository scopes every read to the signed-in mock user, so the screen renders only that user's seeded incidents (INSTR-001).
- BR-010 — Missing or foreign identifiers produce the identical safe no-access state in the fake; S-04 shows 'Incidencia no disponible' without leaking existence (INSTR-001).
- BR-012 — Employees cannot change the owner or alter history; the timeline has no edit affordance and the fake exposes no mutation path for history (INSTR-005).
- BR-021 — The detail screen shows identifier, title, description, category, priority, status badge, creation date, and the chronological status-history timeline (rendered newest-first from the seeded mock data).
- BR-041 — Mock-first: S-04 runs exclusively on the in-memory FakeIncidentRepository seeded by US-005, with history, ownership and filter behavior represented in mock data; no REST API is required.

---

## Dependencies

- US-005
- US-007

---

## Security Considerations

- The UI never leaks another user's incident: the fake scopes reads to the signed-in mock user and both missing and foreign ids render the same no-access state (BR-010, INSTR-001).
- History is immutable and read-only: the fake exposes no history mutation path and the screen has no edit affordance (INSTR-005, BR-012).
- No network calls and no INTERNET permission in this story — no data leaves the device.
- Actor attribution shows only the mock actor recorded in the seeded history entries.

---

## Non-Functional Requirements

- In-memory fetches resolve effectively instantly (<2 s budget trivially met); timeline refresh on every screen entry (Q-005, CONF-009 pattern).
- Accessibility: status text + color, timeline semantics for TalkBack, 48dp back target (INSTR-012, BR-036).
- Light theme tokens only; 8dp grid and card styling per the design system; no backend or network connectivity required.

---

## Activity Diagram

@startuml
start
:Tap incident card on S-02 -> navigate S-04 with id;
:Load detail from in-memory FakeIncidentRepository - owner-scoped, no network (BR-041);
if (Owned seeded id?) then (yes)
  :Render detail card (ID, title + status badge, description, Categoría/Prioridad/Estado/Fecha);
  :Reverse ascending history -> "Historial de estados" newest-first, "Creación — Pendiente" initial entry, latest highlighted;
  :"Mis incidencias" back to S-02;
  stop
else (no - missing or foreign, indistinguishable)
  :Safe no-access state "Incidencia no disponible" + hint (BR-010);
  stop
endif
@enduml
