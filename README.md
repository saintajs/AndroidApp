# MiniSoporte_copy

Application generated and assembled by Agent Fabric.

## Components

- **mobile-app** — `apps/mobile`
- **api** — `.`

## Build, test and run

### mobile-app

Run from `apps/mobile`:

**Build**

```bash
./gradlew check :app:assembleDebug
```

### api

Run from `.`:

**Build**

```bash
uv sync
```

**Run**

```bash
python -m uvicorn app.main:app --port 8000
```

## Configuration

Copy `.env.example` to `.env` and provide the required local values. Do not commit credentials or production secrets.

## Project structure

See `docs/` for project documentation when present. Application source code is kept in the component directories listed above.
