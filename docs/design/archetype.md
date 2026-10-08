## Archetype

No reference archetype document exists (`docs/extractions/reference/` is absent), so the layout below was derived directly from CONF-011's layered clean-code / dependency-injection requirement (driving `services/api`) and the AGENTS.md Android Construction Contract (driving `apps/mobile` with `app/src/main`, `app/src/test`, and Gradle Kotlin DSL). No `packages/shared` is included: the only cross-component contract is REST/JSON, and no code is genuinely shared by two components.

```
/workspace
├── pyproject.toml                                  # uv workspace ROOT: [tool.uv.workspace] members = ["services/*"]
├── .env.example                                    # template: DATABASE_URL, JWT_SECRET, ENVIRONMENT,
│                                                   #   DEMO_EMPLOYEE_PASSWORD, DEMO_SUPPORT_PASSWORD (CONF-006)
├── .gitignore                                      # excludes .env, key.properties, keystores, build outputs
├── README.md                                       # local run + test instructions (backend + Android)
│
├── apps/
│   └── mobile/                                     # ANDROID APP — Gradle-managed (own settings.gradle.kts)
│       ├── settings.gradle.kts                     # root project name, repositories (google/mavenCentral)
│       ├── build.gradle.kts                        # plugin declarations via version catalog
│       ├── gradle.properties                       # android.useAndroidX=true
│       ├── gradle/
│       │   ├── libs.versions.toml                  # pinned lockfile-style version catalog (Q-013)
│       │   └── wrapper/gradle-wrapper.properties   # pinned Gradle distribution
│       ├── gradlew                                 # wrapper entrypoint
│       └── app/
│           ├── build.gradle.kts                    # applicationId com.nexti.minisoporte, minSdk 26,
│           │                                       #   compileSdk 36, jvmTarget 17, buildToolsVersion,
│           │                                       #   release signing from the 4 keystore env vars (fails clearly)
│           ├── proguard-rules.pro
│           └── src/
│               ├── main/
│               │   ├── AndroidManifest.xml         # ZERO permissions, portrait-only, no deep links (CONF-007)
│               │   └── java/com/nexti/minisoporte/
│               │       ├── MiniSoporteApp.kt                       # Application class
│               │       ├── MainActivity.kt                         # single-Activity Compose host
│               │       ├── ui/
│               │       │   ├── theme/                              # palette #1E3A2A / #EFC64E / #F5F7F5,
│               │       │   │                                       #   8dp corners, Type.kt (INSTR-012)
│               │       │   ├── navigation/
│               │       │   │   ├── NavGraph.kt                     # S-01↔S-02↔S-03↔S-04 typed routes
│               │       │   │   └── AppNavHost.kt
│               │       │   ├── signin/
│               │       │   │   ├── SignInScreen.kt                 # S-01 Iniciar sesión
│               │       │   │   └── SignInViewModel.kt              # generic error, submit lock (FUNC-001)
│               │       │   ├── incidents/
│               │       │   │   ├── IncidentListScreen.kt           # S-02 Mis incidencias
│               │       │   │   ├── StatusFilterChips.kt            # Todas/Pendiente/En progreso/Resuelta + counts
│               │       │   │   └── IncidentListViewModel.kt        # loading/empty/error+retry states (FUNC-003/004)
│               │       │   ├── newincident/
│               │       │   │   ├── NewIncidentScreen.kt            # S-03 Nueva incidencia
│               │       │   │   └── NewIncidentViewModel.kt         # field-level validation, dup lock (FUNC-005)
│               │       │   ├── detail/
│               │       │   │   ├── IncidentDetailScreen.kt         # S-04 Detalle + history timeline
│               │       │   │   └── IncidentDetailViewModel.kt      # safe no-access state (FUNC-006)
│               │       │   └── components/
│               │       │       ├── StatusBadge.kt                  # text + color, never color alone (INSTR-012)
│               │       │       ├── IncidentCard.kt
│               │       │       └── ErrorRetryState.kt              # network error + retry (CONF-009)
│               │       ├── data/
│               │       │   ├── model/                              # Incident, IncidentHistoryEntry, User,
│               │       │   │   └── Enums.kt                        #   Status/Category/Priority enums
│               │       │   ├── repository/
│               │       │   │   └── IncidentRepository.kt           # INTERFACE (Q-005 seam)
│               │       │   ├── fake/
│               │       │   │   └── FakeIncidentRepository.kt       # in-memory mock data — debug/previews/tests (Q-005)
│               │       │   └── remote/
│               │       │       ├── IncidentApi.kt                  # Retrofit interface (Q-012)
│               │       │       ├── dto/                            # kotlinx.serialization DTOs
│               │       │       ├── mappers/                        # DTO ↔ domain model
│               │       │       └── RetrofitIncidentRepository.kt   # future REST implementation
│               │       ├── network/
│               │       │   ├── ApiClient.kt                        # Retrofit 3.0.0 + OkHttp 5.5.0 builder, timeouts
│               │       │   └── AuthInterceptor.kt                  # bearer token header from TokenStore
│               │       └── security/
│               │           └── TokenStore.kt                       # Android Keystore-backed token storage (CONF-002)
│               └── test/java/com/nexti/minisoporte/                # JUnit unit tests mirroring main/
│                   ├── ui/                                         # ViewModel + validation logic tests
│                   ├── data/                                       # FakeIncidentRepository behavior tests
│                   └── security/                                   # TokenStore contract tests
│
├── services/
│   └── api/                                        # FASTAPI LAYERED REST API — Python 3.10
│       ├── pyproject.toml                          # project deps (uv-managed, workspace member)
│       ├── .env.example                            # backend-specific env template
│       ├── alembic.ini
│       ├── app/
│       │   ├── main.py                             # app factory, /health, router registration, CORS
│       │   ├── api/
│       │   │   ├── deps.py                         # get_db, CurrentUser, require_role guards,
│       │   │   │                                   #   login-rate-limit dependencies (Q-004)
│       │   │   └── routes/
│       │   │       ├── auth.py                     # POST /api/auth/login (public, rate-limited)
│       │   │       ├── users.py                    # GET /api/me (authenticated)
│       │   │       ├── incidents.py                # GET/POST /api/incidents, GET /api/incidents/{id} (owner)
│       │   │       └── statuses.py                 # PATCH /api/incidents/{id}/status (support only)
│       │   ├── core/
│       │   │   ├── config.py                       # pydantic-settings: env-driven config (CONF-006)
│       │   │   ├── security.py                     # bcrypt hash/verify (Q-010), PyJWT HS256 encode/decode (Q-011)
│       │   │   └── ratelimit.py                    # in-memory per-IP/per-email limiter (CONF-003, Q-004)
│       │   ├── domain/
│       │   │   ├── enums.py                        # Status/Category/Priority/Role enums
│       │   │   ├── models.py                       # SQLAlchemy 2.0 ORM: User, Incident, IncidentHistory (CONF-008)
│       │   │   ├── schemas/                        # Pydantic v2 request/response models
│       │   │   │   ├── auth.py
│       │   │   │   ├── user.py
│       │   │   │   └── incident.py
│       │   │   └── services/
│       │   │       ├── auth_service.py             # email normalization, generic-failure login (INSTR-006, FUNC-001)
│       │   │       ├── incident_service.py         # owner isolation, list/filter, create-as-Pending (INSTR-001/002)
│       │   │       └── status_service.py           # linear transitions + immutable history write (INSTR-003/004/005)
│       │   ├── db/
│       │   │   ├── session.py                      # engine + SessionLocal + get_db (ORM-only access, CONF-005)
│       │   │   └── repositories/
│       │   │       ├── user_repository.py
│       │   │       ├── incident_repository.py      # owner-scoped queries, newest-first, status filter
│       │   │       └── history_repository.py
│       │   └── seed/
│       │       └── demo_seed.py                    # idempotent demo data, env-only passwords (FUNC-009, INSTR-010)
│       ├── migrations/
│       │   ├── env.py                              # Alembic environment bound to SQLAlchemy metadata
│       │   ├── script.py.mako
│       │   └── versions/
│       │       └── 0001_initial_schema.py          # users, incidents, incident_history + FKs/constraints/indexes
│       └── tests/
│           ├── conftest.py                         # app + DB fixtures from TEST_DATABASE_URL
│           ├── unit/                               # services, transition matrix, limiter, JWT, bcrypt
│           │   ├── test_auth_service.py
│           │   ├── test_status_service.py
│           │   ├── test_ratelimit.py
│           │   └── test_schemas.py
│           └── integration/                        # authorization + ownership + API error handling (INSTR-013)
│               ├── test_login_flow.py
│               ├── test_owner_isolation.py
│               ├── test_role_protection.py
│               └── test_health.py
│
└── infra/
    ├── docker-compose.yml                          # LOCAL DEV ONLY: PostgreSQL 16.15 (pg-sandbox equivalent);
    │                                               #   verification sandbox has NO Docker — tests use TEST_DATABASE_URL
    └── init/
        └── 01-create-database.sql                  # optional bootstrap (create db/user); schema itself comes
                                                    #   from Alembic migrations, never from this file
```

### Top-level annotations

- `pyproject.toml` (root) — the uv workspace root artifact: `[tool.uv.workspace] members = ["services/*"]` with one Python member; all backend deps resolve through one root lockfile.
- `apps/mobile` — the Android application, Gradle-managed with its own `settings.gradle.kts` (Android is not a uv member); feature-organized Compose UI over a repository seam (interface → in-memory fake for v1, Retrofit remote for REST integration), Keystore token storage, and mirrored unit tests.
- `services/api` — the FastAPI backend as a layered uv-workspace member: `api` (routes + DI deps) → `domain` (ORM models, Pydantic schemas, business services) → `db` (session + repositories), with `core` (config/security/rate-limit), `seed`, Alembic `migrations`, and `tests/unit` + `tests/integration` mirroring the source layout.
- `infra/` — local development support files only: a dev-Postgres docker-compose (not usable in the verification sandbox) and a bootstrap SQL stub; the authoritative schema always comes from Alembic migrations.
- `.env.example` / `.gitignore` — environment/secrets stubs enforcing CONF-006: secrets only via environment variables, `.env`, keystores and `key.properties` never committed.

No CI/CD pipelines, cloud resources, or additional tooling are included — the project is built and verified locally per the wizard's constraints (internal testing only).
