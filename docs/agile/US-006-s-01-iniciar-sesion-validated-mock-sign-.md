# US-006 — S-01 Iniciar sesión: validated mock sign-in with demo credentials (admin/admin), submit lock and generic failure

**Epic:** EPIC-002 — Employee Mobile Experience
**Feature:** FEAT-005 — Sign In & Session Handling
**Status:** locked (approved)
**Related Items:** FUNC-001, INSTR-007, INSTR-012, CONF-002, CONF-009, BR-001, BR-002, BR-003, BR-036

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- mobile-app

**Screens**

- S-01 — Iniciar sesión

**Flows**

- Employee authentication flow
- Session lifecycle (sign in to sign out)

**Scope note:** Implements the mobile side of the authentication flow on the mock foundation: the S-01 screen per the approved mockup (hero band, login card, demo hint box) with the fake admin/admin path. APIs are [] because the real POST /api/auth/login and GET /api/me contracts are realized by US-012 — the mock story only represents the flow against FakeAuthService. Entities [] because the client persists no domain data — only the in-memory fake token.

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Conventions

- Feature-first package ui/signin (S-01): SignInScreen.kt (one top-level composable) with SignInViewModel.kt beside it; stateless composables take a UiState value + event lambdas; state hoisting mandatory; Modifier first optional parameter.
- SignInUiState exposed as val uiState: StateFlow<Out> via MutableStateFlow + asStateFlow(); validation logic lives in the ViewModel, unit-testable without Compose; one-shot navigation-on-success events emitted as consumed events.
- Sign-in fields use OutlinedTextField-style labels bound with semantics so errors are announced per field; errors use liveRegion so TalkBack announces them; minimum touch target 48dp.
- Compose test tags: Modifier.testTag("s01_email_input") style — s<screen><element> snake-case suffix; s01_demo_credentials tag on the demo hint box.
- Authentication goes through the FakeAuthService seam from US-005; the fake session token is stored via the in-memory TokenStore fake; token material never appears in logs or UiState (CONF-002); ViewModel launches in viewModelScope; suspend calls on the repository boundary. No AuthInterceptor, no network client, no DTO in this story.

### Test Strategy

['ViewModel unit tests: required-field validation (BR-001), admin/admin success path with token stored in the in-memory TokenStore fake, generic failure for any other pair (BR-002), submit lock while in flight (BR-003).', "Compose component tests with s01_* tags: demo hint box renders 'Demo: admin / admin' (CA-04), per-field errors, generic error alert, navigation to S-02 on success.", './gradlew check passes with no backend, no network, no secrets.']

### Flow Slice

Employee authentication flow (mock-first): Step 1 — on S-01 the tester enters Correo electrónico and Contraseña; the Compose form enforces required-field validation client-side (BR-001) and the primary button is disabled while the fake authentication is in flight (BR-003). Step 2 — FakeAuthService accepts exactly username admin / password admin; on success the fake session token is stored in the in-memory TokenStore fake (CONF-002, mock-phase BR-006) and the app routes to S-02 Mis incidencias. Outcome & errors — any other credential pair shows the generic inline error with no account-existence hint (BR-002, INSTR-007) and preserves form state. The real POST /api/auth/login request/response contract, 422 field-descriptor mapping, 429 rate-limit handling, 401 body handling and the expired-token recovery flow are deferred to US-012; transient failure simulation and the error+retry state convention are established by US-005's fake seams (CONF-009).

### Notes

The approved mockup's demo hint box is a first-class mock-MVP element: the admin/admin pair is the explicit demo credential shown on screen, served by FakeAuthService from US-005. Spanish UI copy throughout: 'Iniciar sesión', 'Correo electrónico', 'Contraseña', generic error copy without account-existence hints. The real API contract (POST /api/auth/login, GET /api/me) and the 401/422/429 handling including expired-token recovery are realized by US-012.

---

## Narrative

Implements the S-01 sign-in screen on the mock foundation: required-field validation, submit lock, generic failure messaging, and the demo hint box showing admin/admin, authenticated by the fake auth service from US-005 with the in-memory TokenStore fake. No real backend, no network connectivity and no API dependency — the real POST /api/auth/login contract and expired-token recovery are delivered by US-012.

---

## Functional Flow

1. The employee opens the app on S-01 and enters Correo electrónico (or username) and Contraseña; the ViewModel enforces required-field validation client-side and shows Spanish per-field errors (BR-001). 2. Tapping the primary button submits; the button is disabled while the fake authentication is in flight (BR-003). 3. The app calls FakeAuthService from US-005 (in-memory, no network); exactly username admin / password admin succeeds. 4. On success the fake session token is stored in the in-memory TokenStore fake and the app navigates to S-02 Mis incidencias. 5. On any other credential pair the generic inline error appears without revealing whether the account exists (INSTR-007, BR-002); the form state is preserved. 6. The demo hint box rendered per the approved mockup shows 'Demo: admin / admin' so testers know the credentials without documentation. Out of scope (US-012): 401/422/429 response handling and the expired-token recovery path — no such logic exists in the mock MVP and no network request is ever made.

---

## Acceptance Criteria

- **CA-01:** CA-01: Given empty email/username or password, When the employee taps Iniciar sesión, Then Spanish per-field required errors render next to the offending fields and no authentication call is made (BR-001).
- **CA-02:** CA-02: Given the demo credentials username admin and password admin, When the form is submitted, Then the button locks while the fake authentication resolves, the fake session token is stored via the in-memory TokenStore fake, and the app navigates to S-02 (BR-003).
- **CA-03:** CA-03: Given any credential pair other than admin/admin, When the form is submitted, Then the generic inline error renders and the UI gives no hint about whether the account exists (BR-002, INSTR-007).
- **CA-04:** CA-04: Given the mock MVP, When S-01 renders, Then the demo hint box displays the demo credentials 'Demo: admin / admin' using the approved mockup tokens (accent-soft #FBF1D4 background, 1px #EFC64E border, caption typography) and is verified by a Compose test using the s01_* tags.
- **CA-05:** CA-05: Given an environment with no backend and no network connectivity, When the full sign-in journey runs, Then it completes end-to-end on the fake auth service with zero network calls — no Retrofit/OkHttp code path is reachable from this screen (BR-041).
- **CA-06:** CA-06: Given the test suites, When ./gradlew check runs, Then sign-in ViewModel tests (required-field validation, admin/admin success, generic failure on other pairs, submit lock) and Compose component tests with s01_* tags pass, and journey 1 of the mandatory mock journeys holds.
- **CA-07:** CA-07: Given the story scope, When inspected, Then no real API contract logic exists — no 401 expired-token recovery, no 422 server-validation mapping, no 429 rate-limit handling — these are explicitly deferred to US-012, and this story depends only on US-005.

---

## Business Rules

- BR-001 — Both email/username and password are required at sign-in; missing fields produce per-field errors in the form and no authentication attempt.
- BR-002 — Sign-in failures show one generic message that never reveals whether the account exists (INSTR-007); in the mock MVP this applies to any non-admin/admin pair rejected by the fake auth service.
- BR-003 — The sign-in button stays disabled while the authentication request is in flight (duplicate-submission lock), including the fake authentication call.
- BR-006 (mock-first phase) — The fake session token is stored only via the in-memory TokenStore fake from US-005 and is never logged or shown in UiState; the Keystore-backed implementation arrives with US-012 (CONF-002).
- BR-041 — Mock-first: the only authentication path in the mock MVP is FakeAuthService accepting exactly admin/admin, with the demo credentials displayed on the sign-in screen; no real backend or network connectivity is required.

---

## Dependencies

- US-005

---

## Security Considerations

- Demo credentials admin/admin are public by design, exist only in the mock auth service of the debug/mock MVP, and never grant access to real data — the real authentication path is delivered by US-012.
- No network calls and no INTERNET permission in this story — the sign-in path cannot exfiltrate credentials.
- Token material (the fake session token) is kept in the in-memory TokenStore fake only, never logged and never rendered in UiState (CONF-002, mock-phase BR-006).
- Generic failure messaging prevents any account-existence probing pattern even against the fake (INSTR-007, BR-002).

---

## Non-Functional Requirements

- No backend and no network connectivity required — the flow completes with the fake auth service (Q-005, BR-041).
- Accessibility: 48dp targets, per-field error announcements via semantics/liveRegion, visible focus handling (BR-036 baseline).
- The fake authentication resolves in <2 s so the submit-lock state is exercisable in tests; the UI remains responsive during the lock.

---

## Activity Diagram

@startuml
start
:Employee opens app - S-01 Iniciar sesión;
if (Both fields filled? (BR-001)) then (no)
  :Spanish per-field required errors;
  stop
else (yes)
endif
:Tap primary button - locked while the fake authentication resolves (BR-003);
:FakeAuthService checks the credential pair in memory - no network request (BR-041);
if (Credentials == admin/admin?) then (yes)
  :Store fake session token in the in-memory TokenStore fake (mock-phase BR-006);
  :Navigate to S-02 Mis incidencias;
  stop
else (no)
  :Generic inline error - no account-existence hint (BR-002, INSTR-007);
  :Form state preserved;
  stop
endif
@enduml
