## Backend Code Conventions

Concrete conventions for the `api` component (`services/api`): Python 3.10 (CPython), FastAPI 0.123.10, Pydantic v2, SQLAlchemy 2.0.54, Alembic 1.20.0, PyJWT 2.15.1, bcrypt 4.3.0 (CONF-011, Q-003, Q-009, Q-010, Q-011). Module layout follows `docs/design/archetype.md` exactly; every rule below cites the requirement or design decision it serves.

### File and folder naming

- Modules and packages: `snake_case` (`auth_service.py`, `incident_repository.py`, `ratelimit.py`); package names are single lowercase words (`api`, `core`, `domain`, `db`, `seed`).
- One route module per resource under `app/api/routes/` (`auth.py`, `users.py`, `incidents.py`, `statuses.py`) — one HTTP operation per function, never two methods on one handler (FastAPI best practice).
- Pydantic schemas live in `app/domain/schemas/` split by resource (`auth.py`, `user.py`, `incident.py`); ORM models in the single `app/domain/models.py` (three models, small enough to stay together); Alembic migrations in `migrations/versions/` with sequential numeric prefixes (`0001_initial_schema.py`).
- Test files mirror the source layout under `tests/unit/` and `tests/integration/`, named `test_<module>.py` (INSTR-013 structure from `archetype.md`).

### Casing rules

| Element | Convention | Example |
|---------|-----------|---------|
| Files / modules / packages | `snake_case` | `incident_service.py` |
| Classes (ORM models, Pydantic schemas, services, exceptions) | `PascalCase` | `IncidentHistory`, `LoginRequest`, `InvalidTransitionError` |
| Functions / methods / variables / parameters | `snake_case` | `list_owned_incidents`, `normalize_email` |
| Constants | `UPPER_SNAKE_CASE` | `TOKEN_TTL_SECONDS = 28800` |
| Type aliases for DI | `PascalCase` + `Dep`/`Depends` suffix | `CurrentUserDep`, `DbSession` |
| DB tables / columns | `snake_case`, plural table names | `users`, `incident_history`, `password_hash` |
| JSON field names | `snake_case`, no aliasing layer | `access_token`, `new_status` (per `api-contract.md`) |
| Environment variables | `UPPER_SNAKE_CASE` | `DATABASE_URL`, `JWT_SECRET` |
| Enum members (Python and DB values) | lowercase `snake_case` values | `Status.IN_PROGRESS = "in_progress"` |

### Module layout and layering

Strict one-way dependency direction — `api` → `domain` → `db`, with `core` leaf-used by all (CONF-011 layered clean code):

- `app/api/routes/*` — thin handlers only: declare `Annotated` dependencies, call one domain service, map domain exceptions to HTTP status codes. No business logic, no direct ORM queries in routes.
- `app/domain/services/*` — all business rules: email normalization + generic-failure login (`auth_service.py`, INSTR-006, BR-002), owner-scoped list/detail/create-as-Pending (`incident_service.py`, INSTR-001/002), linear transition matrix + immutable history write (`status_service.py`, INSTR-003/004/005). Services are plain classes/functions receiving a `Session` via DI — testable without HTTP.
- `app/db/repositories/*` — the ONLY place SQLAlchemy queries are written; every incident query filters by `owner_id` here (INSTR-001). Services never import `select` themselves.
- `app/core/security.py` — bcrypt hash/verify used directly, never passlib (Q-010, BR-026); PyJWT encode/decode pinning `algorithms=["HS256"]` (Q-011, BR-004).
- `app/core/ratelimit.py` — in-memory per-IP/per-email counters exposed as FastAPI dependencies (Q-004, BR-007).
- `app/core/config.py` — the only module that touches environment variables.

### Typing style

- Strict typing everywhere (CONF-011 `strict_typing`): every function has full parameter and return annotations; `X | None` (PEP 604) instead of `Optional[X]`; modern collections generics (`list[str]`, `dict[str, Any]`).
- Dependencies declared with `Annotated` type aliases reused across routes (FastAPI best practice):

  ```python
  CurrentUserDep = Annotated[User, Depends(get_current_user)]
  DbSession = Annotated[Session, Depends(get_db)]
  ```

- Pydantic v2: no `...` defaults for required fields, no `RootModel` (banned); request bodies use `Field(min_length=..., max_length=...)` for title/description (BR-013/014) and `Literal`/`Enum` types for category/priority/status (BR-015/016/017); route handlers return typed response models so Pydantic serializes and filters the response (never returning raw ORM objects untyped).

### Sync vs async

All path operations, dependencies, and services use plain `def` (not `async def`): the stack is sync SQLAlchemy 2.0 + psycopg 3 sync, and `def` handlers run in the threadpool so blocking DB calls never stall the event loop (FastAPI rule). No `asyncio`/`Asyncer` mixing is needed in v1.

### Error-handling pattern

- Domain services raise typed domain exceptions defined in `app/domain/` (e.g. `InvalidCredentialsError`, `IncidentNotFoundError`, `ForbiddenRoleError`, `InvalidTransitionError`, `RateLimitExceededError`) — never raise `HTTPException` below the routes layer.
- Route handlers (or small mapper helpers) translate each domain exception to exactly the status code + Spanish `detail` string fixed in `api-contract.md`: `401` `"Credenciales inválidas"` (uniform, BR-002), `403` `"No autorizado"` / `"Cuenta inactiva"`, `404` `"Incidencia no encontrada"` (same body for missing and foreign ids — BR-010), `409` `"Transición de estado no válida"`, `429` `"Demasiados intentos. Inténtelo de nuevo más tarde."` + `Retry-After` header (CONF-003).
- A global exception handler catches unhandled `Exception` and returns sanitized `500 {"detail": "Error interno del servidor"}` — no stack traces, SQL, or config in any response (FUNC-008 discipline applied globally).
- Validation failures are never caught: Pydantic v2 returns the standard structured `422` field-level body (INSTR-011); the client maps descriptors to its own Spanish per-field copy.
- Transaction discipline: repositories commit nothing; the domain service orchestrates the unit of work (transition validation → status update + history insert in one transaction, INSTR-005), and `get_db` rolls back on unhandled exceptions.

### Logging pattern

- Standard library `logging`: one module-level logger per module, `logger = logging.getLogger(__name__)`; configured once in `app/main.py` (level from `ENVIRONMENT`).
- NEVER log passwords, tokens, or `Authorization` headers (INSTR-008, BR-026). Log authentication failures with the request client IP only — never the attempted email in a way that pairs with a success/failure outcome that could aid enumeration; bcrypt verification results and JWT claims are not logged.
- Unexpected exceptions are logged server-side with `logger.exception(...)` (full trace in server logs only) while the client still receives the sanitized 500 body.
- uvicorn access logs remain enabled; no custom request-id middleware in v1.

### Configuration access

- `app/core/config.py` defines one pydantic-settings `Settings` class; every module reads config ONLY through the DI-provided settings object — `os.environ` / `os.getenv` appear nowhere else (CONF-006).
- Required variables: `DATABASE_URL`, `JWT_SECRET`, `ENVIRONMENT`; seed-only: `DEMO_EMPLOYEE_PASSWORD`, `DEMO_SUPPORT_PASSWORD` (no default password ever embedded — BR-033); rate-limit knobs with design defaults: `LOGIN_RATELIMIT_IP_MAX=5`, `LOGIN_RATELIMIT_EMAIL_MAX=5`, `LOGIN_RATELIMIT_WINDOW_SECONDS=900` (Q-004).
- `.env` is Git-excluded; `.env.example` documents every variable with placeholder values (BR-029/039). Missing mandatory variables fail startup with a clear message.

### Dependency injection style

- Framework-native FastAPI DI only — no third-party DI container.
- `get_db` is a `yield` dependency that opens a session per request and closes/rolls it back on exit; repositories and services are instantiated inside route dependencies, receiving the session (composition root in `app/api/deps.py`).
- Auth guards are reusable dependencies: `get_current_user` (validates JWT with the HS256 allow-list, loads the active user), `require_role("support")` wrapping it for the status route (BR-022). Access level per route is declared on the route signature itself — mandatory per CONF-011.
- The rate limiter's counters live in a module-level singleton injected via `Depends` so tests can reset/inject fake clocks.

### Lint, format, and static checks

| Tool | Role | Key rules |
|------|------|-----------|
| Ruff (lint + format) | Format and lint in one tool; single `ruff check . && ruff format --check .` gate | Rules: `E,W` (pycodestyle), `F` (pyflakes), `I` (import order), `B` (bugbear), `UP` (pyupgrade), `S` (bandit — flags hardcoded secrets/SQL strings), `SIM`; line length 100; uv-managed |
| mypy | Strict typing gate (CONF-011 `strict_typing`) | `strict = true`, plugins: `pydantic.mypy`, `sqlalchemy.plugin` if available for the pinned line |
| pytest + pytest-cov | Test and coverage gate (see `## Backend Test Strategy`) | `--cov=app --cov-fail-under=90` on business-logic packages (INSTR-013) |

- Bandit-class rules (`S`) are treated as blocking: any flagged SQL string concatenation or hardcoded secret fails lint (enforces CONF-005/CONF-006 mechanically).
- Alembic migration files are excluded from Ruff format where autogenerated; migration review checks that every schema change ships as a migration, never ad-hoc SQL (CONF-008).

### Database access conventions

- SQLAlchemy 2.0 style only: `select()` constructs with typed `Mapped[...]`/`mapped_column(...)` declarations; no legacy `Query` API; no raw SQL strings anywhere — the parameterization guarantee is structural (CONF-005, BR-027).
- ORM model names mirror table names singular (`User`, `Incident`, `IncidentHistory`); enum columns store the lowercase string values from `data-model.md` via a Python `enum.Enum` shared with Pydantic (`app/domain/enums.py`), so API values, DB values, and transitions use one source of truth.
- Timestamps are timezone-aware `DateTime(timezone=True)` mapped to `TIMESTAMPTZ`, set with `server_default=func.now()`; `updated_at` uses `onupdate=func.now()` (data-model.md).
- History rows are never updated or deleted by application code; the repository exposes append and read-only queries (INSTR-005, BR-012).
