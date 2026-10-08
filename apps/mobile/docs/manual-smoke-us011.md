# US-011 — Manual Offline Mock-Journey Smoke Checklist (record)

Change: `installable-debug-apk-for-internal-testing-mock-first-build-095b1550` · Story: US-011
Spec source: `openspec/changes/.../testing.yaml` — `components_under_test` (last entry:
*Installed APK mock journey (manual smoke)*) and `e2e.flows`.
Proves: **CA-02** (offline journey works with zero backend) and **BR-041** (zero-permission,
mock-first operation, no network path).

This record has two strictly separated result blocks:

1. **EXECUTED IN CI/SANDBOX** — the automated evidence that already exists in this repo
   (build gate + APK audit + 122 unit/Robolectric Compose tests). Every checklist step below
   cites the exact test class and method that mechanically verifies that step's behaviour.
2. **PENDING HUMAN OPERATOR** — the physical airplane-mode phone pass. This sandbox has no
   phone and no emulator, so the device rows ship unchecked; the operator executes them once
   on hardware and signs.

---

## Artifact identity

| Field | Value |
|---|---|
| APK | `apps/mobile/app/build/outputs/apk/debug/app-debug.apk` |
| applicationId | `com.nexti.minisoporte` |
| versionCode | 1 |
| versionName | `1.0.0-internal` |
| minSdk / targetSdk | 26 / 36 |
| Signing | none — debug APK built with zero secrets (CA-04); unsigned debug deliverable |
| Permissions | **zero** `uses-permission` entries in the merged manifest (BR-041/BR-042) |

Verified against `app/build/outputs/apk/debug/output-metadata.json` and
`app/build.gradle.kts` (`applicationId = "com.nexti.minisoporte"`, `versionCode = 1`,
`versionName = "1.0.0-internal"`).

## Preconditions (operator)

- Physical **portrait phone** (the app is portrait-only; no deep links — launcher icon only).
- **Airplane mode ON** before launch and kept ON for the whole journey (BR-041: proves the
  mock journey needs no network; the app holds no network stack at all — CA-03).
- **No backend** — the app is fully self-contained: `FakeAuthService` + `FakeIncidentRepository`
  seed everything in-process. Wi-Fi and mobile data stay off.
- Demo credentials: username **`admin`** / password **`admin`** (case-sensitive — the only
  accepted pair; anything else shows a generic error).
- Device language note: all UI copy is Spanish (badges `Pendiente` / `En progreso` /
  `Resuelta`, sign-out button `Salir`, toasts below).

## Install

From the machine holding the repo and a USB-connected phone (or transfer the APK by any
sideload channel and tap it — no special permission needed):

```bash
adb install -r apps/mobile/app/build/outputs/apk/debug/app-debug.apk
```

Then launch the **MiniSoporte** app with airplane mode already ON. There is no login
pre-flight network call: S-01 renders fully offline.

## Seed facts (verified against `data/fake/FakeIncidentRepository.kt`)

`FakeIncidentRepository` seeds **5 incidents owned by the demo employee** (newest-first by
`createdAt`) plus **1 foreign incident that must never appear**:

| # | id suffix | Title | Status | createdAt |
|---|---|---|---|---|
| 1 | …0001 | Impresora no responde | Pendiente | 2026-10-05T10:15:00Z |
| 2 | …0002 | VPN corporativa no conecta | En progreso | 2026-10-03T09:00:00Z |
| 3 | …0003 | Credenciales de acceso bloqueadas | Resuelta | 2026-10-01T08:20:00Z |
| 4 | …0004 | Monitor parpadea intermitentemente | Resuelta | 2026-09-28T14:10:00Z |
| 5 | …0005 | Solicitud de software de diseño | Pendiente | 2026-09-26T12:00:00Z |

Foreign seed: **…0006 — "Cafetera de la zona común averiada"** (createdAt 2026-10-04, owned
by another employee). Because it is NEWER than …0002, if owner scoping were broken it would
appear 2nd on the list — its absence is a strong isolation signal.

Created incidents: `createIncident` forces status **PENDING**, assigns deterministic id
**`fake-<n>`** (fake-1, fake-2, …), stamps the fixed `CREATED_AT_PLACEHOLDER`
(**2026-10-06T08:00:00Z** — newer than every seed row), and appends one initial history entry
(`previousStatus = null`). Hence a created incident surfaces **first** on S-02.

---

## Journey checklist — step → expected result → automated evidence

Tags follow the `s<screen>_<element>` pattern per testing.yaml selectors.

### Step 1 — S-01 Sign-in (admin/admin)

**Operator actions:** type `admin` into the email field (`s01_email_input`), `admin` into the
password field (`s01_password_input`), tap the submit button (`s01_submit_button`). The
on-screen demo hint (`s01_demo_credentials`) shows the exact credentials.

**Expected UI:** S-01 renders offline; wrong credentials would show the generic error
(`s01_error_generic`) without revealing which field failed; on success the app navigates to
S-02 (no error shown, button disabled only while the sign-in resolves).

**Automated evidence (Robolectric Compose / ViewModel, all green):**
- `SignInViewModelTest.adminAdminSubmitLocksStoresTokenAndEmitsSingleNavigationEvent`
- `SignInViewModelTest.rejectedPairsShowGenericErrorPreserveValuesAndNeverNavigate`
- `SignInViewModelTest.secondSubmitWhileSubmittingIsIgnored`
- `SignInScreenTest.demoHintBoxRendersExactDemoCredentials`
- `SignInScreenTest.adminAdminSuccessNavigatesToS02Destination`
- `SignInScreenTest.rejectedCredentialsShowGenericErrorAndPreserveTypedValues`
- `SignInScreenTest.submitLockDisablesButtonWhileInFlightThenReenablesAfterFakeResolves`
- `FakeAuthServiceTest.correctDemoCredentialsSignInAndStoreToken` (admin/admin is the ONLY pair)
- `FakeAuthServiceTest.everyOtherPairIsGenericallyRejected`
- `InMemoryTokenStoreTest.saveThenReadRoundTrips`

### Step 2 — S-02 Owned list, newest-first

**Expected UI:** exactly **5 cards** (`s02_incident_card_<id>`), newest-first in the order
…0001 → …0002 → …0003 → …0004 → …0005, each with category/priority pills and the Spanish
status badge. **"Cafetera de la zona común averiada" (…0006) must be absent** — it is newer
than …0002, so any leak is visible.

**Automated evidence:**
- `IncidentListViewModelTest.loadShowsLoadingThenSeededOwnedContentNewestFirst`
- `IncidentListViewModelTest.noFilterValueEverSurfacesAnotherOwnersIncident`
- `IncidentListScreenTest.ownedCardsRenderNewestFirstAndForeignIncidentIsInvisible`
- `IncidentListScreenTest.cardRendersCategoryPriorityPillsAndSpanishStatusBadgeLabel`
- `FakeIncidentRepositoryTest.seedLoadsDemoEmployeeIncidentsNewestFirst`
- `FakeIncidentRepositoryTest.ownerScopingNeverLeaksForeignIncidents`
- `FakeIncidentSeedDataTest.seedRepresentsIncidentsForMoreThanOneMockUser`
- `FakeIncidentSeedDataTest.foreignIncidentReadByDemoEmployeeIsNoAccessFromSeedAlone`
- `FakeSeedS04RepresentationTest.foreignIncidentIsInvisibleToDemoOwnerFromMockDataAlone`

### Step 3 — Status filter chips

**Operator actions:** tap each chip in turn: `s02_filter_chip_pendiente`,
`s02_filter_chip_en_progreso`, `s02_filter_chip_resuelta`, then `s02_filter_chip_todas`.

**Expected UI:** Pendiente narrows to **2** (…0001, …0005); En progreso narrows to **1**
(…0002); Resuelta narrows to **2** (…0003, …0004); Todas restores all **5**, newest-first.
Chip counts refresh live.

**Automated evidence:**
- `IncidentListViewModelTest.pendingFilterNarrowsToTwoPendingAndNullFilterRestoresAllFive`
  (asserts the REAL seed counts 5 / 2 / 1 / 2)
- `IncidentListScreenTest.pendienteChipFiltersListAndTodasRestoresItWithLiveCounts`
- `IncidentListScreenTest.resueltaChipWithoutResolvedIncidentsShowsEmptyState`
  (`s02_empty_state` renders when a filter matches nothing)
- `FakeIncidentRepositoryTest.statusFilterReturnsMatchingSubsetsNewestFirst`
- `FakeIncidentSeedDataTest.seedCoversAllThreeIncidentStatuses`

### Step 4 — FAB → S-03 creation (result Pendiente)

**Operator actions:** tap the FAB (`s02_fab`) → S-03 opens. Fill: título 5–100 chars
(`s03_title_input`, counter `s03_counter_title`), pick a categoría
(`s03_category_option_systems|equipment|access|other`) and prioridad
(`s03_priority_option_low|medium|high`), descripción 10–1000 chars
(`s03_description_input`). The hint `s03_pendiente_hint` states the result will be
Pendiente. Tap `s03_submit`.

**Expected UI:** empty/invalid submits flag per-field errors and never create; on a valid
submit the button locks ("Guardando…"), a toast **"Incidencia creada correctamente"** shows,
the app returns to S-02, and the new card (`fake-1`) is **first** with badge **"Pendiente"**
(createdAt placeholder 2026-10-06T08:00:00Z beats all seed rows). A second create yields
`fake-2`, again first.

**Automated evidence:**
- `NewIncidentViewModelTest.successfulSubmitCreatesPendingWithInitialHistoryAndSingleNavigationEvent`
- `NewIncidentViewModelTest.titleBoundaryMatrixRejects4And101AndAccepts5And100`
- `NewIncidentViewModelTest.descriptionBoundaryMatrixRejects9And1001AndAccepts10And1000`
- `NewIncidentViewModelTest.missingCategoryFlagsOnlyCategoryErrorAndNeverCreates`
- `NewIncidentViewModelTest.missingPriorityFlagsOnlyPriorityErrorAndNeverCreates`
- `NewIncidentViewModelTest.duplicateSubmissionLockIgnoresSecondSubmitAndCreatesExactlyOnce`
- `NewIncidentViewModelTest.createdIncidentCarriesOnlyFormInputsAndFakeAssignedOwnerAndStatus`
- `NewIncidentScreenTest.validSubmitShowsToastNavigatesBackAndNewPendienteIncidentIsFirst`
- `NewIncidentScreenTest.countersStartAtZeroAndUpdatePerKeystrokeWithoutDebounce`
- `NewIncidentScreenTest.invalidEmptySubmitFlagsAllFourFieldsAndNeverTouchesTheRepository`
- `NewIncidentScreenTest.submitLocksLabelGuardandoWhileInFlightAndRepeatedTapCreatesExactlyOne`
- `FakeIncidentRepositoryTest.createIncidentForcesPendingWithDeterministicIdsAndInitialHistory`
- `FakeIncidentRepositoryTest.createdIncidentSurfacesFirstInNewestFirstList`
- `FakeIncidentRepositoryTest.createdIncidentIsOwnedByTheOwnerIdParam`

### Step 5 — S-04 Detail with newest-first mock history

**Operator actions:** from S-02 tap card **…0003 — "Credenciales de acceso bloqueadas"**
(3 history entries — the richest seed timeline).

**Expected UI:** detail card shows id caption (`s04_id_caption`), title (`s04_title`),
description (`s04_description`), categoría / prioridad / **status badge "Resuelta"**
(`s04_status_value`) / fecha (`s04_created_at_value`). Timeline (`s04_timeline_heading`,
rows `s04_timeline_item`) is **newest-first**: row 0 = "Resuelta" (último estado, by
soporte@…, 16:45), then "En progreso" (10:05), and the creation entry **"Creación —
Pendiente"** last (08:20). The timeline is read-only — no edit affordance. Back arrow
(`s04_back`) returns to S-02.

**Automated evidence:**
- `IncidentDetailViewModelTest.loadSeededThreeEntryHistoryRendersTimelineNewestFirst`
- `IncidentDetailViewModelTest.loadSeededTwoEntryHistoryRendersTimelineNewestFirst`
- `IncidentDetailViewModelTest.creationEntryRendersCreacionPendienteAndOnlyCreationEntriesFlagIsCreation`
- `IncidentDetailViewModelTest.isNewestIsTrueOnlyOnTheFirstRenderedEntry`
- `IncidentDetailViewModelTest.missingAndForeignIncidentIdsProduceIdenticalNoAccessState`
- `IncidentDetailScreenTest.timelineRendersThreeRowsNewestFirstWithHighlightOnFirstRow`
- `IncidentDetailScreenTest.creationEntryRendersLastWithCreacionPendienteLine`
- `IncidentDetailScreenTest.statusBadgeShowsResolvedLabel`
- `IncidentDetailScreenTest.detailCardRowsRenderIdTitleDescriptionAndLabelValueRows`
- `IncidentDetailScreenTest.timelineIsReadOnlyWithoutAnyEditAffordance`
- `IncidentDetailScreenTest.backTapFiresOnBackToS02`
- `IncidentDetailScreenTest.missingIncidentIdRendersNoAccessBoxWithoutRetry`
- `IncidentDetailScreenTest.foreignIncidentIdRendersIdenticalNoAccessBox`

### Step 6 — "Salir" sign-out → back to S-01, protected screens unreachable

**Operator actions:** tap the app-bar button **"Salir"** (`s02_sign_out`), then press the
Android BACK button a couple of times and try to re-open the app.

**Expected UI:** a toast **"Sesión cerrada"** shows, the app lands back on S-01, and BACK
cannot reach S-02/S-03/S-04 (the nav stack is popped to `signin`, the graph's
`startDestination`). Re-launch always starts at S-01 (`Destination.SignIn.ROUTE` is the
start destination in `AppNavHost`). No token material is ever left readable.

**Automated evidence:**
- `IncidentListViewModelTest.signOutDeletesTokenAndEmitsToastThenNavigationExactlyOnce`
  (token present before, **NULL** after — CONF-002)
- `IncidentListViewModelTest.noUiStateSnapshotEverCarriesTokenMaterial`
- `IncidentListScreenTest.signOutShowsToastDeletesTokenAndNavigatesToSignIn`
- `FakeAuthServiceTest.signOutClearsStoredToken`
- `InMemoryTokenStoreTest.deleteLeavesNothingReadable`
- `InMemoryTokenStoreTest.deleteOnEmptyStoreIsANoOp`
- `SignInViewModelTest.noUiStateSnapshotEverCarriesTokenMaterial`
- `DestinationRoutesTest.signInRouteIsPinnedToS01` (+ the 8 sibling route-pinning tests:
  S-02/S-03/S-04 ROUTE constants, `ARG_INCIDENT_ID`, S-01 is the start destination)

---

## Result

### (a) EXECUTED IN CI/SANDBOX — automated verification facts

- **Gate:** `./gradlew check :app:assembleDebug` (empty secrets environment, offline-capable
  from the pinned catalog) → **BUILD SUCCESSFUL**; `app-debug.apk` produced at
  `app/build/outputs/apk/debug/` (CA-01). Gate log:
  `apps/mobile/app/build/verify-build-contract.log`.
- **Unit/Compose tests:** **122 unit tests, 0 failures** — counted `@Test` methods across
  `app/src/test/java/com/nexti/minisoporte/**` (FakeAuthService 4, FakeIncidentRepository 9,
  FakeIncidentSeedData 5, FakeSeedS04Representation 5, InMemoryTokenStore 5, ThemeTokens 16,
  DestinationRoutes 9, BuildContract 5, SignIn VM+Screen 13, IncidentList VM+Screen 17,
  NewIncident VM+Screen 17, IncidentDetail VM+Screen 17).
- **Mechanical APK audit:** `scripts/verify-build-contract.sh` → **14/14 PASS**
  (zero-secrets env; gate build; APK present; merged manifest **zero `uses-permission`**
  grants — BR-041/BR-042; aapt binary-APK permission dump zero requests; versionCode=1
  positive integer + versionName `1.0.0-internal` semver-ish (CA-05); debugRuntimeClasspath
  free of retrofit/okhttp/kotlinx-serialization beyond benign AndroidX core transitives
  (CA-03); zero signing/secret references in the tracked tree (CA-04); version catalog pins
  no network stack; versionCode monotonic across gate runs).
- **Offline proof:** every behavioural step of the journey above (sign-in, seed list,
  filters, creation → Pendiente, detail timeline, sign-out) is pinned by the Robolectric
  Compose + ViewModel tests cited per step — the SAME mock code paths the installed APK
  executes, with no backend and no network stack on the classpath or in the manifest.

### (b) PENDING HUMAN OPERATOR — physical airplane-mode device pass

Run once on a portrait phone, airplane mode ON, per the checklist above. Check each row
only when the observed result matches the expected one.

- [ ] Step 1 — S-01 renders offline; admin/admin sign-in navigates to S-02 (no error, no network prompt)
- [ ] Step 2 — S-02 shows exactly the 5 owned seed cards newest-first (…0001…0005); "Cafetera…" (…0006) absent
- [ ] Step 3 — Chips narrow 5→2 (Pendiente) →1 (En progreso) →2 (Resuelta); Todas restores all 5
- [ ] Step 4 — FAB opens S-03; valid create shows "Incidencia creada correctamente" and the new "Pendiente" card is FIRST on S-02
- [ ] Step 5 — S-04 for …0003 shows badge "Resuelta" and a 3-row newest-first timeline ending in "Creación — Pendiente"; read-only; back returns to S-02
- [ ] Step 6 — "Salir" shows "Sesión cerrada", lands on S-01; BACK and re-launch cannot reach S-02/S-03/S-04
- [ ] Whole journey ran with airplane mode ON end-to-end — no crash, no blank state, no connectivity error

Pass criterion (one line): **all seven rows checked = CA-02/BR-041 offline mock journey
proven on the installed APK; any unchecked row = defect, report with the step number.**

Operator signature: ______________________  Date: ____________  Device: ____________
