## Environment Strategy

The project is internal-testing only (no production deployment, no cloud infrastructure, no CI/CD per the wizard constraints). Configuration is therefore simple: **two local-facing environments** — `development` and `internal-test` (staging-equivalent) — plus a `test` mode for the pytest suite. All secrets live in environment variables; nothing sensitive is ever committed (CONF-006).

### Environments

| Environment | Purpose | Database | Secrets | Transport |
|-------------|---------|----------|---------|-----------|
| `development` | Local backend + Android emulator/device work; demo seed allowed (FUNC-009) | Local PostgreSQL 16.15 (`infra/docker-compose.yml` convenience, or the sandbox `pg-sandbox` via `DATABASE_URL`) | Developer-owned `.env` (Git-excluded), values supplied per machine | Plain HTTP allowed locally (CONF-004) |
| `internal-test` | The deliverable environment: backend reachable from reviewer devices; seeded manually per Q-001 | PostgreSQL 16.15 instance reachable from the test network, schema via Alembic | Supplied by the operator as real environment variables (never a committed file); demo seed run manually with configured emails/passwords | **HTTPS required** (CONF-004) |
| `test` (pytest) | Automated verification | PostgreSQL from **`TEST_DATABASE_URL`** (real server, no Docker in the verification sandbox); Testcontainers only as an optional fallback on a Docker-equipped dev machine | Throwaway values injected by `conftest.py`; demo passwords from test-scoped env vars | In-process ASGI client (httpx) |

`production` is explicitly out of scope: the seed never runs automatically outside development/demo contexts (INSTR-010), and no production deploy, store publication, or cloud resource exists (wizard constraints).

### Configuration sources (build-time vs runtime)

- **Runtime (backend):** `services/api` reads configuration exclusively from environment variables via pydantic-settings (`app/core/config.py`) — no config is baked into images or source. Required variables:
  - `DATABASE_URL` — PostgreSQL connection string (Alembic's `env.py` reads the same value).
  - `JWT_SECRET` — HS256 signing key; must be a long random value unique per environment; compromise ⇒ rotate (all tokens invalidated by rotation, acceptable given the 8-hour TTL).
  - `ENVIRONMENT` — `development` | `internal-test` (gates the demo seed).
  - `DEMO_EMPLOYEE_PASSWORD`, `DEMO_SUPPORT_PASSWORD` — seed-only; the seed **fails with a clear message if either is missing and no default is embedded** (FUNC-009, INSTR-010).
  - `DEMO_SUPPORT_EMAIL` — support account email for the seed, per Q-001 (the demo employee is fixed at `empleado@minisoporte.test`).
- **Runtime (mobile):** the Android app takes its backend base URL per build type — debug builds point at the local/emulator backend (or run fully on the `FakeIncidentRepository`, per Q-005: debug builds, previews and tests need no running backend); release/internal-test builds point at the HTTPS internal-test host. No secrets are embedded in the APK — the app holds no credentials beyond the user's own session token in Keystore-backed storage.
- **Build-time (Android release signing only):** `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` are read **only** when `assembleRelease`/`bundleRelease` is explicitly requested; `check` and `assembleDebug` run with no production secrets. A release build with any variable absent **fails with a clear message and never substitutes a debug key** (CONF-006, Construction Contract).
- **Template, not truth:** `.env.example` (root and per-component) lists every variable with no real values; real `.env` files are Git-excluded.

### Secrets management and rotation

- **Where secrets live:** environment variables only — a local `.env` file for development, operator-supplied process environment for internal-test. No cloud secret manager exists (no cloud is permitted).
- **Git exclusion:** `.gitignore` blocks `.env`, `*.keystore`/`*.jks`, `key.properties`, and build outputs (CONF-006). Keystores live outside the repository at the path named by `ANDROID_KEYSTORE_PATH`.
- **Rotation:** `JWT_SECRET` rotation invalidates outstanding sessions (bounded by the 8-hour TTL, so rotation is low-impact); demo passwords are re-set by re-running the idempotent seed with new values; the Android keystore is replaced by the operator out-of-band if compromised, producing a new signing identity for subsequent internal-test builds.
- **Version control hygiene:** no secrets, keystores, or `.env` files are ever committed (wizard constraint); the release artifact itself contains no secrets (CONF-010).

### How each component reads its configuration

| Component | Reads | At what time |
|-----------|-------|--------------|
| `services/api` app | `ENVIRONMENT`, `DATABASE_URL`, `JWT_SECRET` | Process start (pydantic-settings); fail fast on missing required values |
| Demo seed (`app/seed/demo_seed.py`) | `ENVIRONMENT`, `DATABASE_URL`, `DEMO_EMPLOYEE_PASSWORD`, `DEMO_SUPPORT_PASSWORD`, `DEMO_SUPPORT_EMAIL` | Manual invocation only; refuses to run in production and without the password variables |
| Alembic (`migrations/env.py`) | `DATABASE_URL` | Migration run time |
| pytest (`tests/conftest.py`) | `TEST_DATABASE_URL` (falls back to Testcontainers only when unset) | Test session start |
| Android `app/build.gradle.kts` | The four `ANDROID_KEY*` variables | Release-build invocation only |
| Android runtime | Backend base URL (BuildConfig per build type); token from Keystore-backed `TokenStore` | App start / first API call |
