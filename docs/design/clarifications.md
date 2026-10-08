# Design Clarifications

## Q-001: How are employee and support accounts provisioned, since no registration endpoint, admin screen, or user-management flow exists in v1 and the seed only creates demo accounts in non-production?

*Context: FUNC-009 only seeds one demo employee and one demo support agent in development; INSTR-010 forbids seeding in production. Without a stated provisioning path, real employees in the internal-test deployment have no way to get an account or password.*

**Answer:** Extend the demo seed so the internal-test environment also runs it manually with configured emails

## Q-002: What concrete token format and expiration should the signed session token use?

*Context: CONF-001 mandates signed tokens with expiration and signature validation but does not name a format or lifetime; the Android client (CONF-002) stores one token with no refresh flow, so the TTL decides how often employees are forced to sign in again.*

**Answer:** JWT (HS256) with 8-hour expiration, no refresh token

## Q-003: Which data-access and migration approach should the backend use for PostgreSQL 16.15?

*Context: CONF-005 allows 'parameterized queries or ORM' and CONF-008 requires reproducible migrations, but neither an ORM nor a migration tool is named. SQLAlchemy's download page (checked Oct 2026) shows 2.0.54 as the maintenance series and 2.1.3 as the current series, so a series must be picked deliberately.*

**Answer:** SQLAlchemy 2.0.x ORM (maintenance series, 2.0.54) + Alembic migrations

## Q-004: How should rate limiting on POST /api/auth/login be implemented, given no cache/Redis tier exists in the target plan?

*Context: CONF-003 requires throttling of failed sign-in attempts without leaking account existence, but no limiter technology is named. The target plan has only FastAPI + PostgreSQL, so the limiter's state store is an open infrastructure decision.*

**Answer:** In-process in-memory limiter (per-IP and per-email counters) built with FastAPI dependencies

## Q-005: Which HTTP client and JSON serialization stack should the Android app use to call the REST API?

*Context: CONF-011 defines the API surface and the mobile contract fixes Kotlin/Compose, but no networking or serialization library is named; the token header, error parsing, and retry paths (CONF-009) all depend on this choice.*

**Answer:** For v1, use an in-memory FakeIncidentRepository with mock users, incidents and status history. It must be used by debug builds, Compose previews, automated tests and the App Builder viewer. Define a repository interface for future REST integration, but do not require a running backend for the demo flow.

## Q-006: Which Android minSdk should the app target (the wizard leaves the recommended minimum unpinned)?

*Context: The mobile delivery contract requires 'the supported recommended minimum' but records no API level, and the value must be pinned in app/build.gradle.kts. Jetpack Compose requires API 21+, and modern AndroidX/Compose tooling works best at 24+; the choice affects internal-test device coverage.*

**Answer:** minSdk 26 (Android 8.0) — modern baseline, covers most active devices

## Q-007: Should GET /api/incidents return all of the employee's incidents in v1, or support pagination?

*Context: FUNC-003 and CONF-011 define only an optional status filter, newest-first; no page size, cursor, or offset is specified anywhere. Deciding now prevents an unplanned API-shape change later while keeping v1 simple.*

**Answer:** No pagination in v1 — return all owned incidents matching the filter, newest first

## Q-008: Does sign-out require server-side token invalidation, or is deleting the local token sufficient?

*Context: FUNC-002 only mandates deleting the locally stored token, and CONF-001 mandates signed expiring tokens; if tokens remain valid server-side until expiry, a signed-out device's token still works if copied. This is a security posture decision the extractions leave ambiguous.*

**Answer:** Client-side deletion plus short token TTL (e.g. 8h) accepted as the mitigation

## Q-009: FastAPI 0.123.10 (pinned in the target plan, released Dec 5, 2025) is now a year behind the current 0.142.2 (Sep 30, 2026 per PyPI) — should the project keep the exact pin or move to the latest 0.142.x patch line?

*Context: PyPI release history (checked Oct 2026) shows 0.123.10 as the final 0.123.x patch and 0.142.2 as current; both lines require Python >=3.10 and Pydantic v2, so either works with the pinned runtime, but 0.123.x receives no further fixes. The target plan is the source of truth, so staying put should be an explicit decision.*

**Answer:** Keep exactly FastAPI 0.123.10 as the target plan mandates

## Q-010: Which password-hashing implementation should store password_hash, given that the common passlib+bcrypt pairing is broken?

*Context: Verified via pyca/bcrypt issue #684 (open): passlib 1.7.4 — last released Oct 8, 2020 per PyPI — raises 'AttributeError: module bcrypt has no attribute __about__' with bcrypt 4.1+ (the FastAPI documentation's classic snippet). The project must hash with a maintained library directly.*

**Answer:** bcrypt library used directly (no passlib), current 4.x

## Q-011: Which JWT library should sign and validate the session tokens?

*Context: PyPI (checked Sep/Oct 2026) shows PyJWT at 2.15.1 released Sep 28, 2026, production/stable, supporting Python 3.9+ including 3.10 — actively maintained. The alternative most FastAPI tutorials use, python-jose, has long gaps between releases, so the choice should be deliberate rather than copied from tutorials.*

**Answer:** PyJWT (actively released, 2.15.1) with HS256 and explicit algorithms allow-list on decode

## Q-012: Retrofit released a Kotlin-first 3.0.0 (May 2025 per Maven Central) after the 2.x line ended at 2.11.0 — which major version should the Android client adopt?

*Context: Maven Central metadata for com.squareup.retrofit2:retrofit (fetched Oct 2026) shows latest/release 3.0.0 with 2.12.0 the last 2.x point release. Retrofit 3.0.0 is the going-forward line with built-in Kotlin support, but 2.11/2.12 remains battle-tested if maximum familiarity is preferred.*

**Answer:** Retrofit 3.0.0 (current release line, Kotlin-first) + OkHttp 5.x + kotlinx.serialization

## Q-013: Which Jetpack Compose BOM generation should the build pin, given the current BOM moves monthly?

*Context: Google Maven metadata for androidx.compose:compose-bom (fetched Oct 2026) lists 2026.09.00 as the current release with the 2025.x line (through 2025.12.01) still available. Pinning an arbitrary older BOM loses stable fixes; pinning the newest couples the build to the bleeding edge, so the generation should be an explicit decision for a reproducible internal build.*

**Answer:** Latest stable BOM (2026.09.00) with a pinned lockfile-style version catalog
