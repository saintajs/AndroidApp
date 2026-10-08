## Backend Test Strategy

Test strategy for the `api` component (`services/api`), satisfying INSTR-013 / BR-034: pytest 8.3.3 unit tests for business rules and integration tests for authorization (ownership isolation, role protection of the status endpoint), **coverage target ≥ 90% of business logic** — the exact wizard QA threshold (`thresholds: 90` in `AGENTS.md`), enforced mechanically with pytest-cov.

### Test types and what each covers

| Type | Framework | Scope | Requirement coverage |
|------|-----------|-------|----------------------|
| Unit | pytest 8.3.3, no DB, no HTTP | Domain services (`auth_service`, `incident_service`, `status_service`), the 3×3 transition matrix incl. Resolved-terminal (INSTR-003), rate-limiter counters with an injected fake clock (Q-004), JWT encode/decode incl. wrong-algorithm and expired-token rejection (Q-011), bcrypt hash/verify round-trip (Q-010), Pydantic schema validation boundaries (title 5–100, description 10–1000, enums — BR-013..017), email normalization (INSTR-006) | INSTR-003/005/006, BR-002/004/007/011/023/024/026 |
| Integration | pytest + httpx `TestClient` (in-process ASGI) against real PostgreSQL 16.15 from `TEST_DATABASE_URL` | Full request→response paths: login flow (success, generic 401, 429 + `Retry-After`, BR-001/002/007), **owner isolation** — employee A never sees employee B's incidents on list, detail, or history; foreign/missing id both return the identical 404 shape (INSTR-001, FUNC-003/006, AC-03), **role protection** — employee `403` on `PATCH /api/incidents/{id}/status`, support transitions succeed and append exactly one history row per accepted change (INSTR-004/005, AC-07), create-always-Pending (INSTR-002), 422 field-level validation errors (INSTR-011), safe `/health` (FUNC-008), inactive-account `403`, sanitized 500 handler | INSTR-001/002/004/011/013, CONF-003, FUNC-001..009 |
| Contract | FastAPI's OpenAPI schema, asserted in one integration test | Every route, access annotation, and response model in `api-contract.md` stays in sync: schemas validate, snake_case field names, error bodies match the documented shapes | CONF-011 |

### Test framework and tooling

- **pytest 8.3.3** (wizard-pinned) + **pytest-cov ≥ 6.0** for the 90% gate + **httpx ≥ 0.28** for the ASGI test client + **freezegun** for token-expiry and rate-limit-window determinism (time is never slept in tests).
- Coverage command (the gate): `pytest --cov=app --cov-report=term-missing --cov-fail-under=90` scoped to business-logic packages `app/domain`, `app/core/security.py`, `app/core/ratelimit.py`, `app/api/deps.py` — the modules where INSTR-013's "business logic" lives. Route wiring is covered transitively by integration tests.
- Test naming: `test_<unit>_<scenario>_<expected_outcome>` (e.g. `test_get_incident_of_other_user_returns_safe_404`); AAA structure (Arrange–Act–Assert) per test; one behavior per test.
- Markers: `@pytest.mark.integration` for DB-dependent tests so `pytest -m "not integration"` runs the fast unit tier without PostgreSQL.

### Test data and fixtures strategy (`tests/conftest.py`)

- **Database provisioning:** the engine is built from `TEST_DATABASE_URL` (PostgreSQL — the sandbox's real `pg-sandbox` server). Alembic runs `upgrade head` once per session against the test database, so the schema under test is always the migration-defined schema (CONF-008), never a parallel hand-written DDL.
- **Isolation:** each test runs inside a transaction that is rolled back at teardown (session-per-test with nested savepoints), so tests are order-independent and share no state.
- **User fixtures:** `employee_a`, `employee_b`, `support_agent` — created with bcrypt-hashed passwords from fixture constants (test-only values, never the demo seed's env passwords); an `inactive_employee` for the deactivation path.
- **Auth helpers:** `auth_headers(user)` issuing a real token through `core/security.py` so every integration test exercises the actual JWT path (HS256 allow-list, 8 h expiry).
- **Incident fixtures:** per-status owned incidents (pending / in_progress / resolved) with consistent history rows, mirroring the demo seed's shape (FUNC-009) but self-contained — tests never depend on the seed having run.
- **Rate-limit tests** inject a fake clock and reset the in-memory counter singleton between tests (deterministic, no sleeping).
- **Negative-config tests** verify startup/seed failures: missing `JWT_SECRET`, missing `DEMO_EMPLOYEE_PASSWORD`/`DEMO_SUPPORT_PASSWORD` fail with clear messages and no default password (BR-033); seed idempotency is proven by running it twice and asserting the same record count.

### How backend behavior is exercised end-to-end

- Each FUNC flow is tested at the HTTP boundary as a sequence of real calls: sign in → list own incidents → filter by status → create incident (asserts `pending` + owner) → fetch detail with history → support transition (asserts history append) → repeat transition rejected `409` → employee transition attempt rejected `403`.
- **Playwright browser end-to-end coverage: N/A — no browser UI exists.** The only client is the native Android app (Kotlin/Compose), which Playwright cannot drive; per binding Q-005 the app's demo/test flows run against the in-memory `FakeIncidentRepository` with the identical API contract, and the Android side is covered by JUnit + Compose UI tests in `apps/mobile/app/src/test` (mirrored ViewModel/validation tests). The API-backed flows are therefore covered end-to-end by the httpx integration tier above, which is the complete e2e surface for this architecture.
- Response-time budget (CONF-009 < 2 s) is respected structurally: the whole suite runs against the local sandbox Postgres, and integration tests assert correctness, not latency; no artificial sleeps exist in production code paths.

### Database sourcing rule (binding)

Integration tests take their PostgreSQL **exclusively from the `TEST_DATABASE_URL` environment variable** — the build sandbox provides real PostgreSQL servers and has **no Docker**, so no test may depend on Docker, docker-compose, or Testcontainers to pass. Testcontainers exists only as an optional developer-machine fallback used when `TEST_DATABASE_URL` is unset, and the suite never requires it. `infra/docker-compose.yml` is a local-dev convenience only and is never on any test's critical path.
