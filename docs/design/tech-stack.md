## Tech Stack

All versions below honor the wizard stack and the binding clarification answers verbatim (each pinned row cites its source). Versions verified live against a package registry during this design are marked **(verified)**; versions pinned from current stable knowledge without a clarification answer are marked **†** and must be re-confirmed at first build — no version was invented arbitrarily.

### Frontend (Android)

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | "2.3.21" | Application language; latest stable 2.x line (verified against Maven Central `kotlin-gradle-plugin` metadata; compatible with the pinned BOM generation). |
| Android Gradle Plugin | "8.13.2" | Android build tooling; last stable release of the mature 8.x line (verified against Google Maven metadata; 9.x exists but 8.13 is the conservative, toolchain-compatible choice). |
| Gradle wrapper | "8.14" † | Build orchestration; minimum required by AGP 8.13.x, Kotlin DSL only. |
| Jetpack Compose BOM | "2026.09.00" | Compose runtime/UI/material/foundation version alignment via a pinned lockfile-style version catalog (binding: Q-013). |
| Compose compiler | via Kotlin plugin "2.3.21" | `org.jetbrains.kotlin.plugin.compose` ships the compiler with Kotlin 2.x — no separate compiler pin needed. |
| androidx.activity:activity-compose | "1.11.0" † | Activity integration for Compose (`ComponentActivity`, back handling). |
| androidx.lifecycle:lifecycle-viewmodel-compose | "2.9.0" † | Screen-level state holders wired into Compose. |
| androidx.navigation:navigation-compose | "2.9.0" † | S-01↔S-02↔S-03↔S-04 navigation graph with typed routes. |
| kotlinx-coroutines-android | "1.10.2" † | Async data loading and submission locks on the main-safety boundary. |
| Retrofit | "3.0.0" | Type-safe REST client (binding: Q-012). |
| Retrofit converter | "3.0.0" (`converter-kotlinx-serialization`) | kotlinx.serialization-based request/response conversion, matched to the Retrofit release line. |
| OkHttp | "5.5.0" | HTTP client, logging interceptor, timeouts (binding line: Q-012 "OkHttp 5.x"; concrete 5.5.0 verified against Maven Central as latest stable 5.x). |
| kotlinx-serialization-json | "1.11.0" | JSON (de)serialization of DTOs (verified against Maven Central; 1.12.0 is only an RC and was rejected). |
| compileSdk / targetSdk | "36" † | Compilation and target API level compatible with the pinned BOM and AGP 8.13.x. |
| minSdk | "26" | Android 8.0 baseline (binding: Q-006); recorded in `app/build.gradle.kts`. |
| Android build-tools | "36.0.0" † | Pinned installed build-tools version, per the Android Construction Contract. |
| Java / Kotlin JVM target | "17" | `sourceCompatibility` = `targetCompatibility` = `jvmTarget` (Construction Contract alignment rule). |

AndroidX is enabled explicitly (`android.useAndroidX=true` in `gradle.properties`), all pins live in `gradle/libs.versions.toml`, and the manifest declares zero permissions (CONF-007).

### Backend

| Technology | Version | Purpose |
|------------|---------|---------|
| Python (CPython) | "3.10" | Backend runtime (binding: target plan / CONF-011). |
| FastAPI | "0.123.10" | REST framework; kept exactly as the target plan mandates (binding: Q-009), layered architecture with dependency injection and strict typing. |
| Starlette / Pydantic | as required by FastAPI "0.123.10" (Pydantic v2 line, ">= 2.11") | Request validation and response models; Pydantic v2 is a hard requirement of the pinned FastAPI line. |
| SQLAlchemy | "2.0.54" | ORM; maintenance 2.0.x series, all access parameterized (binding: Q-003; CONF-005). |
| Alembic | "1.20.0" | Reproducible schema migrations (verified against PyPI; requires Python ≥3.10 — compatible; binding tool choice: Q-003, CONF-008). |
| psycopg (psycopg[binary]) | ">= 3.2" | PostgreSQL 3.x driver for SQLAlchemy (minimum stated; exact patch resolved at install). |
| bcrypt | "4.3.0" (4.x line) | Password hashing used directly — no passlib (binding: Q-010; the passlib+bcrypt pairing is broken). |
| PyJWT | "2.15.1" | JWT HS256 signing/validation with an explicit algorithms allow-list on decode (binding: Q-011). |
| uvicorn | "0.54.0" | ASGI server for local/production-preview serving (verified against PyPI). |

### Database

| Technology | Version | Purpose |
|------------|---------|---------|
| PostgreSQL | "16.15" | Relational store for `users`, `incidents`, `incident_history` with FKs, CHECK constraints, and indexes (binding: target plan / CONF-008). |
| Alembic migrations | "1.20.0" | Schema is rebuilt identically from version control in any environment; migrations — not ad-hoc SQL — are the authoritative schema source (CONF-008). |

### Testing

| Technology | Version | Purpose |
|------------|---------|---------|
| pytest | "8.3.3" | Backend unit and integration tests (binding: target plan). Target ≥90% coverage of business logic (INSTR-013). |
| pytest-cov | ">= 6.0" † | Coverage measurement enforcing the 90% business-logic threshold. |
| httpx | ">= 0.28" † | ASGI in-process test client (`TestClient`) used by FastAPI integration tests. |
| TEST_DATABASE_URL (env var) | — | Integration tests take their PostgreSQL connection from this environment variable (sandbox has real `pg-sandbox` servers and NO Docker; tests never depend on containers). |
| Testcontainers | optional fallback only | Used ONLY when `TEST_DATABASE_URL` is unset (a developer machine with Docker); never required to pass the suite. |
| JUnit4 + compose-ui-test (via BOM "2026.09.00") | BOM-managed | Android unit tests in `app/src/test` and Compose UI tests against the `FakeIncidentRepository` (Q-005). |

### Infrastructure / DevOps (local-only — no CI/CD, no cloud)

| Technology | Version | Purpose |
|------------|---------|---------|
| uv | ">= 0.8" † | Python package/dependency management and the workspace root (`[tool.uv.workspace] members = ["services/*"]` in the root `pyproject.toml`). |
| Gradle wrapper (Kotlin DSL) | "8.14" † | Local Android validation/build: `./gradlew check :app:assembleDebug` (debug APK for internal testing, CONF-010) and `./gradlew check :app:assembleRelease` (release APK signed from `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` — fails clearly when absent, never substitutes a debug key). |
| docker-compose.yml (infra/) | n/a | LOCAL development PostgreSQL 16.15 convenience only. Nothing in verification depends on running it: the sandbox has no Docker, and integration tests use `TEST_DATABASE_URL`. |
| .env.example | n/a | Template of every required environment variable (`DATABASE_URL`, `JWT_SECRET`, `DEMO_EMPLOYEE_PASSWORD`, `DEMO_SUPPORT_PASSWORD`, `ENVIRONMENT`, …); real `.env`, keystores, and `key.properties` are Git-excluded (CONF-006). |
| Android Keystore (release signing) | n/a | Externally supplied signing secrets, read only from the four named environment variables at release-build time; no keystore or secret is ever committed. |

### Shared

No shared package exists. The only contract between the two runtimes is REST/JSON over HTTP, owned independently by the Pydantic schemas (`services/api/app/domain/schemas/`) and the kotlinx.serialization DTOs (`apps/mobile/.../data/remote/`); no code is genuinely used by 2+ components, so no `packages/shared` node is created.
