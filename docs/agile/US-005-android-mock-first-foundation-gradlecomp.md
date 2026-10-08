# US-005 — Android mock-first foundation: Gradle/Compose project, Material 3 theme, navigation S-01..S-04, repository seam with in-memory fakes, fake admin/admin authentication and seeded mock data

**Epic:** EPIC-002 — Employee Mobile Experience
**Feature:** FEAT-004 — Mobile App Foundation & Theming
**Status:** locked (approved)
**Related Items:** CONF-002, CONF-007, CONF-009, CONF-010, BR-031, BR-035, BR-036, BR-037, BR-040, Q-005

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- mobile-app

**Scope note:** Foundation-only change: it delivers the mobile-app component shell (theme, navigation, data seams, fake auth) that S-01..S-04 stories build on. Screens/apis/entities/flows are [] because this change renders no screen content and realizes no endpoint or table — it establishes the in-memory seams they use. The real API contracts and entity mapping are realized by US-012.

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Conventions

- Project layout per archetype.md: apps/mobile with its own settings.gradle.kts; packages under com.nexti.minisoporte: ui/theme, ui/navigation (NavGraph.kt, AppNavHost.kt), ui/components (StatusBadge.kt, IncidentCard.kt, ErrorRetryState.kt), data/model (+ Enums.kt), data/repository/IncidentRepository.kt (interface — the Q-005 seam), data/fake/FakeIncidentRepository.kt, data/fake/FakeAuthService.kt, security/TokenStore.kt (interface) with its in-memory fake; single-Activity host MainActivity.kt, no Fragments; tests mirror under app/src/test/java/com/nexti/minisoporte/.
- Version pins live in gradle/libs.versions.toml (Q-013): Kotlin "2.3.21", AGP "8.13.2", Gradle wrapper "8.14", Compose BOM "2026.09.00", activity-compose "1.11.0", lifecycle-viewmodel-compose "2.9.0", navigation-compose "2.9.0", coroutines-android "1.10.2"; compileSdk/targetSdk 36, minSdk 26, buildToolsVersion 36.0.0, JVM target 17 aligned across Java and Kotlin; gradle.properties sets android.useAndroidX=true; manifest declares ZERO permissions, portrait-only, no deep links (CONF-007). No Retrofit, OkHttp, kotlinx-serialization or network dependency is pinned in this story — those arrive with US-012.
- Material 3 with the project theme in ui/theme/ (Color.kt, Type.kt, Theme.kt) mapping the design tokens verbatim: primary #1E3A2A, secondary #EFC64E, background #F5F7F5, surface #FFFFFF, status badge pairs, 8dp corner radius, 8dp spacing grid; only theme tokens in composables — no hard-coded hex/DP in feature code; status always text + color together (StatusBadge), never color alone (INSTR-012); light theme only; Material Icons outlined via material-icons-extended (BOM-managed); Roboto FontFamily.Default throughout.
- State management: unidirectional data flow — each ViewModel exposes val uiState: StateFlow<Out> via MutableStateFlow + asStateFlow(), updated with update { it.copy(...) }; screens collect with collectAsStateWithLifecycle(); ViewModel is the only owner of mutable screen state and coroutine launches; one-shot events emitted as consumed events.
- Data access: all access through the IncidentRepository interface, wired to the in-memory FakeIncidentRepository seeded with mock users/incidents/status history (owner-scoped, newest-first, all statuses and filter values represented); FakeAuthService accepts exactly admin/admin and issues a fake session token stored via the in-memory TokenStore fake; suspending functions on the repository boundary; failure paths always resolve to an error UiState with a retry event — never an unhandled exception or crash (CONF-009); token material never appears in logs or UiState (CONF-002).
- ktlint (standard ruleset) enforced in the Gradle check task — 4-space indent, no wildcard imports, trailing commas; Android Lint with errors on ContentDescription, UnusedResources, hardcoded-text; @Preview composables per screen against FakeIncidentRepository data kept compiling (part of check).

### Test Strategy

['Plain JVM unit tests for the TokenStore in-memory fake (save/read/delete round-trip, deletion leaves nothing readable).', 'Plain JVM unit tests for FakeAuthService (admin/admin accepted, any other pair rejected generically) and FakeIncidentRepository (seed loading, owner scoping, newest-first ordering, status filtering, detail + history retrieval).', 'Compose component tests for theme tokens and navigation routes; @Preview composables compile as part of check.', './gradlew check :app:assembleDebug must pass with no backend, no network and no secrets.']

### Flow Slice

Screens/navigation base from the flow prologue: in debug builds, Compose previews, and automated tests the Android app's data access goes exclusively through the in-memory FakeIncidentRepository and FakeAuthService behind their interfaces, so the user flows run end-to-end without any backend or network stack; the future Retrofit-backed implementation (US-012) will target the exact same contract. Screen map: S-01 Iniciar sesión <-> S-02 Mis incidencias <-> S-03 Nueva incidencia <-> S-04 Detalle de incidencia — navigation switches via Compose navigation (typed routes) and scrolls each view back to top; every screen talks to the repository and failure paths resolve to a retryable error state, never a crash (CONF-009).

### Notes

This is the foundation every other mobile story depends on. No network layer, Keystore code, DTO, mapper, AuthInterceptor or permission exists in this story — release signing and real REST integration are explicitly out of scope; the real-backend path is delivered separately by US-012, on which nothing in the mock journey depends.

---

## Narrative

Lightweight Android foundation: Gradle Kotlin DSL project with pinned versions, Material 3 theme, typed navigation routes S-01..S-04, repository interfaces/seams with in-memory fake implementations, fake authentication accepting admin/admin, in-memory TokenStore fake, and seeded mock incidents/status-history data. No real REST connection, no Retrofit/OkHttp/DTOs/mappers/AuthInterceptor, no Android Keystore, no network TokenStore, no INTERNET permission, no release signing, no store publication.

---

## Functional Flow

1. ./gradlew check :app:assembleDebug compiles the version-catalog-pinned project (Kotlin 2.3.21, AGP 8.13.2, Gradle wrapper 8.14, Compose BOM 2026.09.00) and produces app/build/outputs/apk/debug/app-debug.apk with no secrets required and a ZERO-permission manifest (CONF-007). 2. MainActivity hosts the Compose tree; NavGraph declares typed routes for S-01..S-04, portrait-only. 3. The theme maps the design tokens verbatim (primary #1E3A2A, secondary #EFC64E, background #F5F7F5, status badge pairs, 8dp radius/grid). 4. IncidentRepository (interface) is wired to the in-memory FakeIncidentRepository, seeded with mock users, incidents and status history (owner-scoped, newest-first, all three statuses represented, filtering representable in the seed); FakeAuthService accepts exactly username admin / password admin; TokenStore is the interface plus its in-memory fake. 5. Through the repository seam, failure paths resolve to an error UiState with retry — never an unhandled exception or crash (CONF-009); the fake can simulate a failure state so the retry pattern is established and testable. No network stack, Keystore code, or permission exists in this story.

---

## Acceptance Criteria

- **CA-01:** CA-01: Given a clean checkout of apps/mobile, When ./gradlew check :app:assembleDebug runs, Then unit and Compose component tests pass and app/build/outputs/apk/debug/app-debug.apk is produced with no backend, no database, no network connection, no signing secrets and no network library on the classpath.
- **CA-02:** CA-02: Given the merged manifest, When inspected, Then it declares ZERO uses-permission entries — specifically no android.permission.INTERNET and no notification/camera/location/storage/contacts/microphone permissions — portrait-only orientation, and no deep-link intent filters (BR-031, CONF-007); INTERNET is added only by US-012.
- **CA-03:** CA-03: Given ui/theme/, When the theme is inspected, Then Color.kt/Type.kt/Shape.kt map the approved design tokens verbatim (primary #1E3A2A, secondary #EFC64E, background #F5F7F5, the six status badge hex pairs, 8dp radius, 8dp spacing grid) and feature code uses only MaterialTheme tokens (INSTR-012, BR-036).
- **CA-04:** CA-04: Given the navigation graph, When the app launches, Then MainActivity hosts the single Compose tree and typed routes exist for S-01..S-04 without any screen content yet.
- **CA-05:** CA-05: Given the data layer, When the seam is inspected, Then IncidentRepository is an interface of suspending functions and FakeIncidentRepository implements it in memory with seeded mock users, incidents and status history — owner-scoped, newest-first, covering every status and the status filter — so fake creation, filtering, detail and history retrieval work end-to-end in memory (Q-005, BR-041).
- **CA-06:** CA-06: Given the fake authentication path, When a debug build signs in with username admin / password admin, Then sign-in succeeds via the fake auth service, the session token is stored via the in-memory TokenStore fake, and any other credential pair fails with the generic error; the full mock journey (sign-in -> list -> filter -> create -> detail -> history -> sign-out) completes without any backend.
- **CA-07:** CA-07: Given TokenStore, When save/read/delete are exercised in plain JVM unit tests against the in-memory fake, Then the token round-trips, deletion leaves no readable token, and no token material appears in logs or UiState (CONF-002); no Android Keystore code exists in this story — the Keystore-backed implementation is delivered by US-012.
- **CA-08:** CA-08: Given the Gradle setup, When inspected, Then all versions are pinned in gradle/libs.versions.toml, android.useAndroidX=true, sourceCompatibility/targetCompatibility == jvmTarget == 17, minSdk 26 / compileSdk 36 / buildToolsVersion 36.0.0, and ktlint gates check (BR-037).
- **CA-09:** CA-09: Given the project dependency graph and source tree, When inspected, Then no Retrofit, OkHttp, DTO, API mapper, AuthInterceptor, Android Keystore, network TokenStore or INTERNET permission exists anywhere in this story's code or manifest — debug builds, previews, tests and the mock APK compile and run without them — and no story US-006..US-009 depends on US-012 or US-011.
- **CA-10:** CA-10: Given the app conventions, When inspected, Then UDF state (StateFlow via asStateFlow(), collectAsStateWithLifecycle()), repository-boundary suspend functions, and the error-UiState-with-retry pattern are established as the pattern every screen story follows (CONF-009).

---

## Business Rules

- BR-006 (mock-first phase) — During the mock MVP, session tokens are handled exclusively by the in-memory TokenStore fake and are never logged; the Android Keystore-backed TokenStore implementation is delivered by US-012 with Robolectric or instrumented-test coverage (CONF-002).
- BR-031 — The Android manifest declares ZERO permissions in the mock MVP: no INTERNET, no dangerous permissions, no push notifications, no deep links, no offline storage, no background synchronization (CONF-007); the INTERNET permission is added only by US-012 together with the network layer.
- BR-035 — Online-only Spanish portrait app; through the repository seam, failure paths always resolve to an error UiState with retry, never a crash (CONF-009).
- BR-036 — Labels, contrast, touch targets and the defined palette (#1E3A2A / #EFC64E / #F5F7F5, 8px corners, 8-multiple spacing) (INSTR-012).
- BR-037 — Native Kotlin + Compose with application id com.nexti.minisoporte, android.useAndroidX=true, pinned installed build-tools, aligned Java/Kotlin JVM targets, monotonic versionCode.
- BR-040 — The Android app must not be replaced with another mobile framework; the mobile run entry is build-only (no serve/port/health).
- BR-041 — Mock-first: debug builds, Compose previews and automated tests run exclusively on in-memory fake repositories with zero backend/db/network/secrets; the fake sign-in accepts username admin / password admin, and the fake incident journey (create, filter, detail, history, sign-out) works end-to-end in memory.

---

## Dependencies

_No dependencies._

---

## Security Considerations

- The Android manifest declares ZERO permissions: no android.permission.INTERNET, no dangerous permissions and no deep links — the mock app makes no external calls of any kind and no data leaves the device (BR-031, CONF-007); the INTERNET permission is added only by US-012.
- No external calls: no network stack exists in this story's code path — no Retrofit, OkHttp, HTTP client, DTO or backend endpoint is reachable from debug builds, previews or tests; all data access resolves in memory through the fake seams.
- No embedded production credentials: no secrets, passwords, API keys, keystores or key.properties exist in the source, the version catalog or the built APK — there is nothing to leak and nothing committed (BR-039 convention).
- Demo credentials only for the mock build: FakeAuthService accepts exactly username admin / password admin, exists only in the mock/debug MVP, is displayed on S-01 by design, and never grants access to real data — the real authentication path is US-012.
- The fake session token is held only in the in-memory TokenStore fake, never logged and never rendered in UiState (CONF-002, mock-phase BR-006); no Android Keystore code exists in this story.

---

## Non-Functional Requirements

- Deterministic in-memory mocks: FakeAuthService and FakeIncidentRepository are seeded with fixed data, produce identical results on every run and resolve effectively instantly, making UI flows and tests reproducible (Q-005, BR-041).
- Fast local build: ./gradlew check :app:assembleDebug completes without any backend, database, network connection or secrets and produces app/build/outputs/apk/debug/app-debug.apk.
- Reproducible tests: the check suite (unit + Compose component tests, ktlint, Android Lint) runs fully offline and is pinned by the version catalog and Gradle wrapper, so results are identical on any machine (BR-037).
- Installable debug APK: every check run yields an app-debug.apk installable on a portrait phone, demoable end-to-end on the mock journey with zero environment setup (CONF-010, BR-040).

---

## Activity Diagram

_No activity diagram defined._
