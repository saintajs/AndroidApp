## Frontend Code Conventions

Conventions for the single frontend component `mobile-app` (Kotlin 2.3.21 + Jetpack Compose, BOM 2026.09.00, `apps/mobile`, package `com.nexti.minisoporte`, minSdk 26 / compileSdk 36 / jvmTarget 17). These are architect defaults aligned with the pinned stack in `tech-stack.md`, the package layout in `archetype.md`, and the user-approved Pre-Design draft.

### Project & file organization

- Feature-first packages under `ui/`: `ui/theme`, `ui/navigation`, `ui/signin` (S-01), `ui/incidents` (S-02), `ui/newincident` (S-03), `ui/detail` (S-04), `ui/components` (shared composables), per `archetype.md`.
- One top-level composable per screen file, named after the screen: `SignInScreen.kt`, `IncidentListScreen.kt`, `NewIncidentScreen.kt`, `IncidentDetailScreen.kt`; its state holder beside it (`SignInViewModel.kt`, …). Shared pieces live in `ui/components/` (`StatusBadge.kt`, `IncidentCard.kt`, `ErrorRetryState.kt`).
- Data layer: `data/model/` (domain models + `Enums.kt`), `data/repository/IncidentRepository.kt` (interface — the Q-005 seam), `data/fake/FakeIncidentRepository.kt` (v1 in-memory implementation used by debug builds, previews and tests), `data/remote/` (Retrofit interface, kotlinx.serialization DTOs, mappers, future `RetrofitIncidentRepository`). Networking in `network/` (`ApiClient.kt`, `AuthInterceptor.kt`); token storage in `security/TokenStore.kt`.
- Single-Activity host: `MainActivity.kt` hosts the entire Compose tree; no Fragments. All version pins live in `gradle/libs.versions.toml` (Q-013); `gradle.properties` sets `android.useAndroidX=true`.

### Naming & casing

- Composables, classes, data classes: `PascalCase` (`IncidentCard`, `StatusBadge`). Functions and properties: `camelCase`. Constants: `SCREAMING_SNAKE_CASE`. Packages: lowercase, no underscores (`com.nexti.minisoporte.ui.newincident`).
- Files named after the single top-level declaration they contain (`StatusBadge.kt` contains `StatusBadge`).
- State classes: `<Feature>UiState` (`IncidentListUiState`, `SignInUiState`); events as sealed interfaces or plain lambdas passed down.
- Compose test tags: `Modifier.testTag("s02_filter_chip_pendiente")` style — `s<screen><element>` snake-case suffix, mirroring the e2e selector convention in `frontend-test-strategy.md`.

### Component structure

- Stateless composables first: screen composables take a `UiState` value + event lambdas; state hoisting is mandatory (no business logic inside composables). `Modifier` is the first optional parameter of every composable; slot APIs for reusable scaffolds.
- Screens compose `Scaffold` with a top app bar and (on S-02) the bottom navigation bar; content goes in `LazyColumn` for lists with stable `key = { it.id }` items.
- Recomposition hygiene: no side effects during composition — data loads happen in `LaunchedEffect(key)`; derived values use `derivedStateOf`; state that must survive configuration changes uses `rememberSaveable` only for trivial UI state (form field text), never for session/data state (that lives in the ViewModel).
- Validation logic (title 5–100, description 10–1000, required category/priority) lives in the ViewModel, unit-testable without Compose; the UI only renders the resulting field-error state and char counters.

### State management

- Unidirectional data flow: `UiState` flows down, events flow up. Each ViewModel exposes `val uiState: StateFlow<Out>` via `MutableStateFlow` + `asStateFlow()`, updated with `update { it.copy(...) }`; screens collect with `collectAsStateWithLifecycle()`.
- `ViewModel` is the only owner of mutable screen state and coroutine launches (`viewModelScope`); composables never hold repository references.
- One-shot events (toast "Sesión cerrada", navigation on success) are emitted as consumed events from the ViewModel, not stored in state.

### Data fetching

- All data access goes through `IncidentRepository` (interface). v1 wires `FakeIncidentRepository`; the Retrofit implementation (`RetrofitIncidentRepository` + `IncidentApi` + DTO mappers) is swapped in behind the same interface — screens and ViewModels never know which is active (Q-005).
- Coroutines on the repository boundary; suspending functions for every read/write. Network failure paths always resolve to an error `UiState` with a retry event — never an unhandled exception or crash (CONF-009).
- The bearer token is attached by `AuthInterceptor` reading `TokenStore` (Android Keystore-backed); token material never appears in logs, DTOs, or `UiState` (CONF-002, INSTR-008).
- Serialization: kotlinx.serialization DTOs in `data/remote/dto/` mirroring the Pydantic response schemas; domain models never leak into the network layer (mappers in `data/remote/mappers/`).

### Styling

- Material 3 with the project theme in `ui/theme/` (`Color.kt`, `Type.kt`, `Theme.kt`) mapping the design tokens from `design-system.md` verbatim: primary #1E3A2A, secondary #EFC64E, background #F5F7F5, surface #FFFFFF, status badge pairs, 8dp corner radius, 8dp spacing grid.
- Only theme tokens are used in composables (`MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*`) — no hard-coded hex/DP in feature code. Cards: 8dp radius, 1dp border #DDE4DF equivalent, card shadow per design system. Chips/badges are pill-shaped (50% radius).
- Status is always rendered as text + color together (`StatusBadge`), never color alone (INSTR-012). Light theme only (the approved draft defines no dark palette).
- Portrait-only, phone form factor: no landscape or tablet layout variants; `AndroidManifest.xml` locks orientation and declares zero permissions (CONF-007).

### Accessibility baseline (WCAG AA)

- Minimum touch target 48dp for all interactive elements (matches the draft's field/button min-heights); visible focus handling via Material default focus rings.
- All icons carry `contentDescription` (or `null` when purely decorative, as with status-badge dots that are paired with text labels); toasts and errors use semantics so TalkBack announces them (`role="alert"`/`role="status"` equivalents via `liveRegion`).
- Contrast: text/background pairs from the palette meet AA (dark text #1C2B22 on #F5F7F5/#FFFFFF; white on #1E3A2A; status fg/bg pairs verified in the draft).
- Form fields use `OutlinedTextField`-style labels bound with semantics so errors are announced per field, mirroring the draft's per-field error pattern.

### Lint & format tooling

- **ktlint** (standard ruleset) for Kotlin formatting — 4-space indent, no wildcard imports, trailing commas per Kotlin style; enforced in the Gradle `check` task so `./gradlew check` gates formatting.
- **Android Lint** (bundled with AGP 8.13.2) with default severity plus errors on `ContentDescription`, `UnusedResources`, and hardcoded-text checks; no extra third-party static analysis tooling is introduced.
- Compose compiler metrics not enabled by default; `@Preview` composables are provided per screen against `FakeIncidentRepository` data and kept compiling (they are part of `check`).
