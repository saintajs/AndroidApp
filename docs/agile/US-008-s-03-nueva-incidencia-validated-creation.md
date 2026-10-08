# US-008 — S-03 Nueva incidencia: validated creation form with counters and duplicate-submission lock

**Epic:** EPIC-002 — Employee Mobile Experience
**Feature:** FEAT-007 — Create & Review Incidents
**Status:** locked (approved)
**Related Items:** FUNC-005, INSTR-002, INSTR-011, BR-011, BR-013, BR-014, BR-015, BR-016, BR-018

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- mobile-app

**Screens**

- S-03 — Nueva incidencia

**Flows**

- Incident creation flow

**Scope note:** Mobile half of the creation flow rendered against the in-memory fake: APIs are [] because the real POST /api/incidents contract is realized by US-012 — the mock story creates incidents deterministically in memory only. Entities [] — the fake holds the created incident in memory for the session; the client persists nothing locally.

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Conventions

- Feature package ui/newincident: NewIncidentScreen.kt with NewIncidentViewModel.kt; validation logic (title 5–100, description 10–1000, required category/priority) lives in the ViewModel, unit-testable without Compose; the UI only renders the resulting field-error state and char counters.
- rememberSaveable only for trivial UI state (form field text), never for session/data state (that lives in the ViewModel); stateless composables with UiState + event lambdas; one-shot events (navigation on success, toast) emitted as consumed events.
- Choice-group options and buttons are theme-token styled; no hard-coded hex/DP; 48dp minimum touch targets; per-field error semantics announced via liveRegion; test tags s03_* (e.g. s03_title_input, s03_submit).
- Duplicate-submission lock in the ViewModel (submitting flag) with the button label switching to 'Guardando…'; submission goes through the IncidentRepository seam wired to the deterministic in-memory FakeIncidentRepository — no Retrofit, OkHttp, DTO, JWT or network code is reachable from this screen.
- The client never sends status/owner fields; the fake always sets status Pendiente, a fresh mock id, created_at == updated_at and the initial history entry (previous_status null), mirroring the future server behavior so the US-012 swap cannot change the UI contract (INSTR-002, BR-011).

### Test Strategy

['ViewModel unit tests: title 5–100 and description 10–1000 boundary validation with counters, required category/priority, duplicate-submission lock producing exactly one fake creation.', 'Fake behavior tests: a valid submit inserts exactly one Pendiente mock incident with created_at == updated_at and an initial history entry (previous_status null); the new incident appears first in the owned list (newest-first); repeated identical submissions on a freshly seeded fake are deterministic.', "Compose component tests with s03_* tags: counters, field-level Spanish errors, 'Guardando…' lock, navigation back to S-02 with the new Pendiente card first.", './gradlew check passes with no backend, no network, no secrets.']

### Flow Slice

Incident creation flow (mock-first) — Trigger: On S-02, the employee taps the accent FAB ('+') opening S-03. Step 1: fill 'Título' (live 'n / 100' counter), 'Categoría' choice group, 'Prioridad' choice group, 'Descripción' (live 'n / 1000' counter); live per-field validation enforces the title 5–100 and description 10–1000 rules and required choices locally (BR-013..016); a hint states the incident will be created in 'Pendiente' state (INSTR-002). Step 2: tap 'Guardar incidencia'; the button locks with the label 'Guardando…' (duplicate-submission lock). Step 3: the ViewModel submits through the repository seam to the deterministic in-memory FakeIncidentRepository — no HTTP request is made; the fake always assigns status Pendiente, a fresh mock id, created_at == updated_at and the initial history entry (previous_status null), and never accepts status/owner input (INSTR-002, BR-011). Step 4: on success the app returns to S-02, shows a confirmation, and the new 'Pendiente' incident appears at the top of the list (BR-019). Outcome & errors: invalid fields block submission with Spanish per-field errors and nothing is created (INSTR-011, BR-018) — validation is entirely local, so no 401/422 path exists in the mock MVP. The real POST /api/incidents contract, server-side validation and transport behavior are deferred to US-012.

### Notes

The FakeIncidentRepository simulates the same contract in memory, including the initial history entry (previous_status null), so the flow demos without a backend (Q-005, deterministic results). The client never sends status/owner fields — creation is always Pendiente (INSTR-002, BR-011). The server-authoritative validation and mass-assignment backstop arrive with US-012.

---

## Narrative

Implements the S-03 creation screen exclusively in memory through the FakeIncidentRepository from US-005: the employee fills Título, Categoría, Prioridad and Descripción with live per-field validation and character counters, and a valid submit creates a deterministic mock incident that always starts in Pendiente with its initial history entry, returning to S-02 with the new card first. No POST /api/incidents, no HTTP 401/422, no network errors, no JWT and no Android Keystore — the real API implementation is delivered by US-012.

---

## Functional Flow

1. The employee fills Título (live 'n / 100' counter), Categoría choice group (Sistemas/Equipos/Acceso/Otro), Prioridad choice group (Baja/Media/Alta) and Descripción (live 'n / 1000' counter); a hint states the incident will be created in 'Pendiente' state (INSTR-002). 2. Live per-field validation enforces the rules locally (title 5–100 chars, description 10–1000 chars, category and priority required — BR-013..016); Spanish errors render next to each field (INSTR-011) and submission is blocked. 3. Tapping 'Guardar incidencia' locks the button with the label 'Guardando…' (duplicate-submission lock) and creates the incident exclusively in the in-memory FakeIncidentRepository — no POST /api/incidents, no HTTP; the fake always sets status Pendiente and records the initial history entry (previous_status null), ignoring any status/owner input (INSTR-002, BR-011). 4. On success the app returns to S-02 with a confirmation toast and the new 'Pendiente' incident appears first (newest-first, BR-019). Out of scope (US-012): the real POST /api/incidents contract, HTTP 401/422 handling, server-side authoritative validation and network failure/retry behavior — no network request is ever made in the mock MVP.

---

## Acceptance Criteria

- **CA-01:** CA-01: Given the form, When the employee types in Título or Descripción, Then live 'n / 100' and 'n / 1000' counters update with each keystroke.
- **CA-02:** CA-02: Given a title of 4 or 101 chars, or a description of 9 or 1001 chars, When local validation runs, Then Spanish field-level errors render next to the offending fields, submit is blocked, and nothing is created in the fake (BR-013, BR-014).
- **CA-03:** CA-03: Given no category or no priority selected, When the employee taps Guardar incidencia, Then the required-choice errors render and nothing is submitted (BR-015, BR-016).
- **CA-04:** CA-04: Given a valid form, When Guardar incidencia is tapped, Then the button locks with the label 'Guardando…' and repeated taps produce exactly one creation in the in-memory FakeIncidentRepository (duplicate-submission lock).
- **CA-05:** CA-05: Given a valid submission, When the in-memory creation resolves, Then the fake stores exactly one mock incident with status Pendiente (created_at == updated_at and an initial history entry with previous_status null), the app navigates back to S-02 with a confirmation toast, and the new 'Pendiente' card appears first in the list (BR-019, INSTR-002).
- **CA-06:** CA-06: Given deterministic repeated runs of the same valid submission against a freshly seeded fake, When creation executes, Then the mock result is identical every time — the created incident and its initial history entry are fully deterministic.
- **CA-07:** CA-07: Given the test suites, When ./gradlew check runs, Then the ViewModel validation tests and s03_* Compose tests pass fully offline, and journey 3 of the five mandatory mock journeys holds.
- **CA-08:** CA-08: Given the story scope, When inspected, Then no real API contract logic exists — no POST /api/incidents, no HTTP 401/422 handling, no network error path, no JWT, no Android Keystore — these are explicitly deferred to US-012, and this story depends only on US-005 and US-007.

---

## Business Rules

- BR-011 — Every incident created starts in Pending status; the form hints 'Pendiente', never submits a status, and the fake always sets it (INSTR-002).
- BR-013 — Title required, 5–100 characters, validated live in the mock MVP.
- BR-014 — Description required, 10–1,000 characters, with live counter; validated locally in the mock MVP (server-authoritative validation arrives with US-012).
- BR-015 — Category in the fixed set Systems/Equipment/Access/Other (choice group).
- BR-016 — Priority in the fixed set Low/Medium/High (choice group).
- BR-018 — Local field-level validation with Spanish errors; a failed validation never creates the incident in the fake (INSTR-011); the client+server validation pair is completed by US-012.
- BR-041 — Mock-first: creation runs exclusively against the deterministic in-memory FakeIncidentRepository; no network or backend is required or present.
- Duplicate-submission lock: the submit button is disabled with label 'Guardando…' while the in-memory creation resolves (draft behavior, FUNC-005).

---

## Dependencies

- US-005
- US-007

---

## Security Considerations

- The client never sends status/owner fields; the fake ignores them and always sets Pendiente and the signed-in mock owner — the mass-assignment backstop on the real server arrives with US-012 (INSTR-002).
- Input validation is enforced locally for immediate feedback; no user input leaves the device in the mock MVP (INSTR-011, BR-018).
- No network calls, no INTERNET permission and no credential material in this story — nothing is transmitted, and no token material is kept in UiState (CONF-002, mock-phase BR-006).

---

## Non-Functional Requirements

- Deterministic in-memory creation: identical input on a freshly seeded fake yields an identical mock incident and history entry, keeping tests and demos reproducible (Q-005, BR-041).
- 48dp targets, live counters within the 8dp grid, theme tokens only (BR-036); no backend or network connectivity required.
- Validation is debounce-free and unit-testable in the ViewModel without Compose; the fake creation resolves effectively instantly so the 'Guardando…' lock is exercisable in tests.

---

## Activity Diagram

@startuml
start
:Fill Título, Categoría, Prioridad, Descripción with live counters;
if (All fields within rules? (BR-013..016)) then (no)
  :Spanish field-level errors - nothing created (INSTR-011);
  stop
else (yes)
endif
:Tap Guardar incidencia - locks to "Guardando…";
:POST /api/incidents (Bearer JWT, extra=forbid);
if (Response?) then (201)
  :Return to S-02 - new Pendiente card first (BR-019);
  stop
else (422)
  :Errors next to each field; form data kept;
  stop
else (network error)
  :Keep form data; recoverable error + retry (CONF-009);
  stop
else (401)
  :Expired-token recovery - clear token, route S-01;
  stop
endif
@enduml
