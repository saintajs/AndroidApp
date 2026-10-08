# US-011 — Installable debug APK for internal testing: mock-first build contract without signing secrets

**Epic:** EPIC-003 — Operations, Seed & Internal Release
**Feature:** FEAT-009 — Installable Internal-Test Debug APK (No Release Signing)
**Status:** locked (approved)
**Related Items:** CONF-010, BR-037, BR-038, BR-040, Q-005

---

## Implementation Map

_What this story implements, grounded in the approved general design._

**Components**

- mobile-app

**Scope note:** Build-configuration/distribution change only: no user-facing surface, no endpoints, no data change. It finalizes the mock-first internal-test build contract (CONF-010 build-only run entry, BR-038 no publication, BR-037 versioning). Release signing and the HTTPS release base URL are out of scope of the mock MVP; real backend integration remains the non-blocking follow-up US-012, which this story must not require.

---

## Technical Analysis

_The story-scoped design slice, copied verbatim from the approved design. This story file is
self-contained: the spec phase reads it INSTEAD of the design documents._

### Conventions

- All version pins and build config live in app/build.gradle.kts + gradle/libs.versions.toml; gradle.properties sets android.useAndroidX=true; aligned sourceCompatibility/targetCompatibility == jvmTarget (17); pinned installed build-tools 36.0.0; monotonic versionCode with human-readable versionName (BR-037).
- Local build contract: ./gradlew check :app:assembleDebug from apps/mobile -> app/build/outputs/apk/debug/app-debug.apk; the check suite (unit + Compose component tests, ktlint, Android Lint) gates the build; the mobile run entry is build-only (no serve/port/health) (CONF-010, BR-040).
- No release signing configuration exists in this story: no signingConfigs block, no keystore reference, no key.properties, no ANDROID_KEY* variable read; assembleRelease is not part of this story's contract and release readiness is deferred as a deliberate later concern outside the mock MVP.
- The APK ships the mock wiring from US-005 (FakeIncidentRepository seed, FakeAuthService admin/admin, in-memory TokenStore fake) and the screens from US-006..US-009; no US-012 network code is required for the artifact to build or run.
- No publication: no upload, Play submission or distribution task exists; the artifact is copied manually to reviewer devices for internal testing (BR-038).

### Test Strategy

['CI-style local gate: ./gradlew check :app:assembleDebug must pass on a clean machine with zero environment secrets.', 'Artifact assertion: app/build/outputs/apk/debug/app-debug.apk exists after the build and is installable on a portrait phone.', 'Smoke verification of the mock journey on the installed APK: admin/admin sign-in, seeded list/filter, creation, detail with newest-first mock history, sign-out — all offline.']

### Flow Slice

N/A — this story realizes no user flow from flows.md; it finalizes the mock-first internal-test build/distribution contract recorded in the wizard Constraints and CONF-010. The delivered artifact exercises the mock flows implemented by US-006..US-009 on the US-005 fakes.

### Notes

Deliberate scope: the mock MVP ships as an installable debug APK with zero signing secrets and zero backend dependency. Release signing configuration and the signed release APK are explicitly out of scope here, and real REST integration (US-012) is a non-blocking follow-up — this story must remain buildable, installable and demoable without it.

---

## Narrative

Produces the installable app/build/outputs/apk/debug/app-debug.apk from apps/mobile via ./gradlew check :app:assembleDebug for internal testing: it runs end-to-end on the seeded mock data with the admin/admin fake login, requires no release signing keys, no real backend integration and no US-012, and performs no publication of any kind.

---

## Functional Flow

1. From apps/mobile, the operator runs ./gradlew check :app:assembleDebug; the check suite (unit, Compose component, ktlint, lint) passes and app/build/outputs/apk/debug/app-debug.apk is produced with no signing keys, no secrets and no network library required. 2. The operator installs the APK on a phone (portrait); the app launches on S-01 with the demo hint box. 3. The reviewer signs in with admin / admin (fake authentication from US-005), lands on S-02 with the seeded mock incidents newest-first, can filter by status, create a mock incident, open S-04 detail with the newest-first mock history timeline, and sign out — all without any backend or network connectivity. 4. versionCode is monotonically increasing across internal-test builds with a human-readable versionName (BR-037). 5. No build task uploads, publishes or submits anything anywhere — the artifact stays a local file for internal distribution (BR-038); the mobile run entry remains build-only (BR-040). Errors: a failing check suite fails the build with the standard Gradle report; no signing configuration exists to fail.

---

## Acceptance Criteria

- **CA-01:** CA-01: Given a clean checkout of apps/mobile with zero secrets configured, When ./gradlew check :app:assembleDebug runs, Then the full check suite passes and app/build/outputs/apk/debug/app-debug.apk is produced without any release signing configuration or ANDROID_KEY* variable.
- **CA-02:** CA-02: Given the built debug APK installed on a phone, When the reviewer signs in with admin / admin, Then the complete mock journey works end-to-end on seeded mock data — sign-in, owned incident list with status filter, creation, detail with newest-first mock history, sign-out — with no backend and no network connectivity (BR-041).
- **CA-03:** CA-03: Given the story dependency graph, When inspected, Then US-011 depends only on the mock flow stories (US-005, US-006, US-007, US-008, US-009), does not require US-012 in any form, and the APK builds and runs identically whether or not US-012 is later implemented.
- **CA-04:** CA-04: Given the build configuration, When inspected, Then no release signing block, no keystore reference, no key.properties and no ANDROID_KEY* environment variable is required or read — release signing is explicitly out of scope of the mock MVP.
- **CA-05:** CA-05: Given consecutive internal-test builds, When versions are compared, Then versionCode is monotonically increasing and versionName is the human-readable application version (BR-037).
- **CA-06:** CA-06: Given any build task, When executed, Then nothing is uploaded, published, or submitted to any store — no Google Play publication exists; the artifact stays a local file for internal distribution (BR-038).
- **CA-07:** CA-07: Given the project contract, When inspected, Then the mobile run entry is build-only (no serve/port/health) and the pinned version catalog/wrapper make the build reproducible on any machine (BR-040, BR-037).

---

## Business Rules

- BR-037 — Monotonically increasing versionCode with a human-readable versionName; reproducible builds via the pinned version catalog and Gradle wrapper.
- BR-038 — Internal testing only: no production deployment, no Google Play publication, no cloud infrastructure; no store upload is ever triggered by a build.
- BR-040 — Kotlin/Compose fixed; the mobile run entry is build-only (CONF-010).
- BR-041 — Mock-first: the distributed internal-test APK runs exclusively on the in-memory fake repositories with the seeded mock data and the admin/admin fake login; it must not depend on the real backend integration (US-012).

---

## Dependencies

- US-005
- US-006
- US-007
- US-008
- US-009

---

## Security Considerations

- No signing secrets, keystores, key.properties or ANDROID_KEY* variables are required, read, or embedded — the debug APK is built entirely without production secrets.
- The demo credentials admin/admin are embedded in the debug APK by design (mock MVP) and never grant access to real data; the real authentication path is US-012 scope.
- No network stack ships in this APK — it makes no outbound connections and declares no permissions.
- No secrets in source control: the repository contains no keystore, no key.properties and no credential values.

---

## Non-Functional Requirements

- Internal-test distribution only via the local debug APK; store submission and release signing are deliberate external concerns outside the mock MVP (BR-038).
- Build reproducibility via the pinned version catalog and Gradle wrapper (BR-037).
- The build requires no backend, no network and no secrets, so internal-test builds are never blocked on environment setup (Q-005, BR-041).

---

## Activity Diagram

@startuml
start
:Operator requests ./gradlew check :app:assembleDebug from apps/mobile;
:check suite runs (unit + Compose tests, ktlint, lint) - zero secrets required;
if (check suite passes?) then (no)
  :Build fails with the standard Gradle report - no signing configuration exists to fail;
  stop
else (yes)
endif
:app/build/outputs/apk/debug/app-debug.apk produced - no keystore, no key.properties, no ANDROID_KEY* variables (BR-037);
:Operator installs the APK on a portrait phone;
:Reviewer demos the mock journey offline: admin/admin sign-in -> seeded list/filter -> create -> detail -> sign-out (BR-041);
:Local artifact only - no upload, no publication (BR-038);
stop
@enduml
