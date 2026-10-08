# US-012 — Android real-backend integration: Retrofit/OkHttp client, DTOs, mappers, AuthInterceptor, INTERNET permission, real authentication and Keystore-backed TokenStore behind the repository seam

**Epic:** EPIC-002 — Employee Mobile Experience
**Feature:** FEAT-004 — Mobile App Foundation & Theming
**Status:** locked (approved)
**Related Items:** CONF-002, CONF-004, CONF-007, CONF-009, INSTR-008, BR-006, BR-028, BR-031, BR-035, Q-008

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- mobile-app

**APIs**

- POST /api/auth/login
- GET /api/me
- GET /api/incidents
- POST /api/incidents
- GET /api/incidents/{id}
- PATCH /api/incidents/{id}/status

**Flows**

- Employee authentication flow
- Session lifecycle (sign in to sign out)
- Expired-token recovery (401 → re-authenticate)
- Incident creation flow
- Incident review flow (list, filter, details)
- Support status update flow

**Scope note:** The deferred real-backend half of the mobile-app component: it realizes the client side of all six consumed API contracts and the network-dependent branches of the mobile flows (real auth, 401 recovery, live incident data) behind the IncidentRepository seam established by US-005. Entities [] — the client persists no domain data; the Keystore-backed TokenStore holds only the session token. Adds no screens.

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Conventions

- network/ApiClient.kt builds the OkHttp client (timeouts) with AuthInterceptor reading TokenStore; data/remote/IncidentApi.kt declares Retrofit operations mirroring the IncidentRepository contract; data/remote/dto/ holds kotlinx-serialization DTOs mirroring the Pydantic schemas; data/remote/mappers/ converts DTO<->domain so domain models never reach the network layer; data/remote/RetrofitIncidentRepository.kt implements IncidentRepository.
- security/TokenStore.kt gains the Android Keystore-backed implementation; the in-memory fake remains for JVM tests; token material never logged (CONF-002).
- Base URL injected via BuildConfig per build type: debug -> local/emulator backend; release -> HTTPS internal-test host (CONF-004).
- Version pins added to gradle/libs.versions.toml: Retrofit "3.0.0" (+ converter-kotlinx-serialization), OkHttp "5.5.0", kotlinx-serialization-json "1.11.0"; manifest adds android.permission.INTERNET as the only permission (BR-031).
- The wiring switch (fakes -> real implementations) is a single injection point so the mock build (US-011) stays green independently (BR-042).

### Test Strategy

['Robolectric or instrumented tests for the Keystore-backed TokenStore (round-trip, delete leaves nothing readable, no plain-text persistence).', 'Unit tests for DTO<->domain mappers and for AuthInterceptor (header attached from TokenStore; no token in logs).', 'Contract tests against the local/emulator backend for all six endpoints, including 401/422/429/404 branches and the expired-token recovery path.', 'Regression gate: ./gradlew check :app:assembleDebug with the mock wiring still passes and produces the fakes-only APK (BR-042).']

### Flow Slice

This story activates the network branches of the design flows: Employee authentication flow (real POST /api/auth/login with 401/422/429 handling), Expired-token recovery (401 on any protected call -> token cleared, route to S-01, no silent retry), Session lifecycle (Keystore-backed persistence, sign-out deletes the Keystore token), Incident creation/review flows and Support status update flow (real REST data through RetrofitIncidentRepository, 404 indistinguishable safe response). Network failures never crash: they resolve to the retryable error UiState (CONF-009). All of it lands behind the US-005 seams so the mock journeys remain runnable on fakes.

### Notes

Follow-up story explicitly decoupled from the mock MVP: it must never block US-011 or any mock story. Release signing configuration and the signed release APK are not part of this story either — they remain a deliberate external concern outside the mock MVP. This story only swaps the data/session transport and adds the INTERNET permission.

---

## Narrative

Deferred follow-up story that swaps the mock seams for the real backend: Retrofit 3.0.0 + OkHttp 5.5.0 network layer with kotlinx-serialization DTOs and mappers, AuthInterceptor, the android.permission.INTERNET declaration, real authentication via POST /api/auth/login (plus GET /api/me), real REST repository implementations of IncidentRepository, and the Android Keystore-backed TokenStore replacing the in-memory fake. It must not block or be required by the mock debug APK (US-011); it may depend on the completed mock foundation (US-005, US-006, US-009).

---

## Functional Flow

1. The manifest gains exactly one permission, android.permission.INTERNET, consumed solely by the new network layer (BR-031). 2. The version catalog gains Retrofit 3.0.0 (+ converter-kotlinx-serialization), OkHttp 5.5.0 and kotlinx-serialization-json 1.11.0; ApiClient builds the OkHttp client with timeouts and AuthInterceptor. 3. Sign-in switches from FakeAuthService to POST /api/auth/login: 200 stores the JWT via the Keystore-backed TokenStore and routes to S-02; 401 shows the generic error; 422 maps field descriptors to Spanish per-field copy; 429 shows the too-many-attempts message; GET /api/me optionally supplies the profile. 4. IncidentRepository is rewired to RetrofitIncidentRepository: list+filter via GET /api/incidents, create via POST /api/incidents (always Pendiente), detail+ascending history via GET /api/incidents/{id} (404 missing/foreign indistinguishable), status transitions via PATCH /api/incidents/{id}/status. 5. Any protected call returning 401 triggers the expired-token recovery path: token cleared from Keystore-backed storage, no silent retry, route to S-01; transient network failures keep the retryable error state on the current screen (CONF-009, Q-008). 6. Regression gate: with this story reverted or unimplemented, ./gradlew check :app:assembleDebug still produces the mock APK that runs entirely on fakes — this story must never block US-011 (BR-042). Errors: transport failures resolve to the retryable error UiState; only a real 401 ends the session.

---

## Acceptance Criteria

- **CA-01:** CA-01: Given the merged manifest, When inspected after this story, Then it declares exactly one uses-permission entry — android.permission.INTERNET — consumed solely by the network layer, with no additional permissions (BR-031).
- **CA-02:** CA-02: Given the network module, When inspected, Then Retrofit 3.0.0 (+ converter-kotlinx-serialization), OkHttp 5.5.0 and kotlinx-serialization-json 1.11.0 are pinned in gradle/libs.versions.toml, and IncidentApi exposes only operations matching the IncidentRepository contract.
- **CA-03:** CA-03: Given data/remote/dto/ and data/remote/mappers/, When inspected, Then DTOs mirror the backend response schemas for POST /api/auth/login, GET /api/me, GET /api/incidents, POST /api/incidents, GET /api/incidents/{id} and PATCH /api/incidents/{id}/status, and mappers convert DTO<->domain so domain models never reach the network layer.
- **CA-04:** CA-04: Given an authenticated request, When it executes, Then AuthInterceptor attaches Authorization: Bearer <token> read from TokenStore, and no token material appears in logs, DTOs or UiState (CONF-002, INSTR-008).
- **CA-05:** CA-05: Given the Keystore-backed TokenStore implementation, When save/read/delete are exercised in Robolectric or instrumented tests, Then the token round-trips via Android Keystore storage, deletion leaves no readable token, and plain-text persistence is never used; plain JVM unit tests keep using the in-memory fake (BR-006).
- **CA-06:** CA-06: Given real authentication, When sign-in runs against POST /api/auth/login, Then 200 issues and stores the JWT; 401 shows the generic non-enumerating error; 422 maps field descriptors to Spanish per-field copy; 429 shows the too-many-attempts message; and the expired-token recovery path (401 on any protected call) clears the token and routes to S-01 with no silent retry (Q-008, BR-002).
- **CA-07:** CA-07: Given BuildConfig per build type, When inspected, Then the base URL is injected per build type — debug may target the local/emulator backend, release resolves to the HTTPS internal-test host (CONF-004, BR-028).
- **CA-08:** CA-08: Given the mock journey, When ./gradlew check :app:assembleDebug runs after this story, Then the mock-only debug APK still builds and runs identically on in-memory fakes — US-011 and no story in US-005..US-009 require this story to compile or pass (BR-042).
- **CA-09:** CA-09: Given a network failure or a real 401 during REST use, When it occurs, Then failures resolve to the retryable error UiState (CONF-009) and only a real 401 triggers the expired-token recovery path.
- **CA-10:** CA-10: Given the story dependency graph, When inspected, Then this story depends on the completed mock foundation (US-005, US-006, US-009) and the backend endpoints it consumes (US-002, US-003), and no mock story depends on it.

---

## Business Rules

- BR-006 — Production token storage is Keystore-backed only, never plain text, never logged (CONF-002); this story delivers that implementation, replacing the in-memory fake for durable sessions.
- BR-028 — All client-server communication outside the local environment uses HTTPS; the release base URL targets the HTTPS internal-test host (CONF-004).
- BR-031 — The manifest gains android.permission.INTERNET as its only permission, consumed by this network layer; no additional permissions are introduced (CONF-007).
- BR-035 — Network failures never crash the app and always offer the retry path; only a real 401 ends the session (CONF-009, Q-008).
- BR-042 — Real REST integration must not block the mock demonstration: the mock debug APK (US-005..US-009, US-011) builds and demos on in-memory fakes independently of this story.

---

## Dependencies

- US-005
- US-006
- US-009
- US-002
- US-003

---

## Security Considerations

- Session tokens are stored only in Android Keystore-backed storage — never SharedPreferences-in-the-clear, never logcat, never in DTOs or UiState (CONF-002, BR-006).
- AuthInterceptor is the only place the bearer token is attached; token material never appears in logs (INSTR-008).
- HTTPS enforced outside local development via the BuildConfig base URL; cleartext blocked (CONF-004, BR-028).
- android.permission.INTERNET is the only manifest permission added, the minimal surface for the network layer (BR-031, CONF-007).
- Generic 401 messaging prevents account enumeration; only a real 401 ends the session, with no silent retry (INSTR-007, Q-008).

---

## Non-Functional Requirements

- OkHttp connect/read/write timeouts configured; every network failure resolves to a retryable error state, never a crash (CONF-009).
- Keystore-backed TokenStore covered by Robolectric or instrumented tests; plain JVM tests keep the in-memory fake.
- Detail/list fetch target <2 s under normal conditions; expired-token recovery routes to S-01 without auto-retry loops.
- The mock APK build (US-011) remains fully green with or without this story, keeping the follow-up strictly non-blocking (BR-042).

---

## Activity Diagram

_No activity diagram defined._
