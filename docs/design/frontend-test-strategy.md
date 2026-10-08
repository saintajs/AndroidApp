## Frontend Test Strategy

Test strategy for `mobile-app` (Kotlin + Jetpack Compose at `apps/mobile`). **Coverage target: ≥90%** (wizard QA threshold from `AGENTS.md`; for the frontend this applies to critical UI logic — ViewModels, validation, and the repository seam — mirroring INSTR-013's backend gate). Enforcement: `./gradlew check` runs the full JVM-side suite; coverage of `ui/` ViewModels, `data/` and `security/` packages measured with JaCoCo/Compose-tooling counts toward the 90% gate on those packages.

### Test types

- **Unit (JVM, JUnit4)** — `app/src/test/java/com/nexti/minisoporte/`:
  - `ui/` ViewModel tests: sign-in validation and generic-failure behavior with submit lock (FUNC-001), list/filter state machine including loading/empty/error+retry (FUNC-003, FUNC-004), new-incident field validation (title 5–100, description 10–1000, required category/priority), duplicate-submission lock and initial `Pendiente` status (FUNC-005, INSTR-002), detail safe no-access state for missing/foreign ids (FUNC-006).
  - `data/` tests: `FakeIncidentRepository` behavior — owner scoping, newest-first ordering, status filter, history consistency (INSTR-001).
  - `security/` tests: `TokenStore` contract — save/read/delete; deletion on sign-out leaves no readable token (FUNC-002, CONF-002). No token value is ever asserted into logs.
- **Component (Compose UI tests)** — `androidx.compose.ui.test` JUnit4 rules (BOM 2026.09.00) driving single screens against `FakeIncidentRepository`: chip filter narrowing + count badges, card → detail drill-down, form error rendering next to fields with live counters, FAB/back navigation, button-disabled-while-submitting states. Test tags per the `s<screen>_<element>` convention.
- **Visual** — no pixel-diff tooling is introduced; visual fidelity is anchored on the approved Pre-Design screenshot (`screenshots/pre_design_mobile.png` in `mockups.md`) reviewed at PR time plus per-screen `@Preview`s kept compiling in `check`. (No screenshot-testing framework requested; nothing added beyond the wizard stack.)

### Mandatory Playwright end-to-end

E2E is REQUIRED and is exercised on the user-approved Pre-Design draft (`docs/design/pre_design_mobile.html`), which implements the full S-01→S-04 interaction model with in-page state — the same journeys the native app must reproduce against `FakeIncidentRepository`:

- **Journeys covered:** (1) sign-in happy path admin/admin → My incidents renders; sign-in failure shows the generic error without account-existence hints; submit button disabled during the request (FUNC-001); (2) filter chips Todas/Pendiente/En progreso/Resuelta narrow the list and update count badges (FUNC-004); (3) create incident via FAB → form validation errors → valid submit → list shows new Pendiente entry + confirmation toast (FUNC-005); (4) card → detail with full status-history timeline → back (FUNC-006); (5) sign-out via "Salir" → token-less state returns to S-01 (FUNC-002).
- **Selectors/test-id convention:** Playwright locators target the draft's stable structure (`#login-email`, `#login-submit`, `.chip[data-filter="pendiente"]`, `.incident-card[data-id]`, `#fab-new`, `#new-submit`, `#toast`, `.timeline li`); the native mirrors of these elements carry `Modifier.testTag("s01_email_input", …)`-style tags so the same journey can be re-expressed as Compose UI tests.
- **Environments:** local only — Playwright runs headless Chromium at the mobile viewport (390x844) against the draft opened via `file://`; no backend, no deployed environment, no CI pipeline (project is internal-testing only). Native end-to-end on device/emulator is covered by instrumented Compose tests driving the same five journeys against `FakeIncidentRepository` (Q-005 — no running backend required).

### Gating

- `./gradlew check :app:assembleDebug` (the contract build command) compiles and runs unit + Compose component tests; it must pass before any internal-test APK is shared.
- The five e2e journeys above are the acceptance mirror of FUNC-001/002/003/004/005/006; a failing journey blocks the build review even when unit tests pass.
