# MiniSoporte_copy

Solución móvil compuesta por un cliente nativo y una API independiente con persistencia relacional.

> Estado: build generado y verificado por Agent Fabric. Revisa la configuración de entorno antes de usarlo fuera de desarrollo.

## Arquitectura

| Componente | Tecnología | Código | Responsabilidad |
| --- | --- | --- | --- |
| Aplicación Android | Kotlin 2.3.21 + Jetpack Compose | `apps/mobile` | Experiencia de usuario y artefacto APK |
| API backend | FastAPI 0.123.10 / Python 3.10 | `services/api` | Reglas de negocio, seguridad y API REST |
| Base de datos | PostgreSQL 16.15 | `infra/` | Persistencia relacional y migraciones |

## Stack tecnológico

| Tecnología | Versión | Uso |
| --- | ---: | --- |
| Kotlin | `2.3.21` | Cliente Android |
| Jetpack Compose BOM | `2026.06.01` | Interfaz nativa |
| Android Gradle Plugin | `8.13.2` | Build Android |
| Python | `3.10` | Backend |
| FastAPI | `0.123.10` | API REST |
| PostgreSQL | `16.15` | Persistencia |
| SQLAlchemy | `2.0.54` | ORM |
| Alembic | `1.20.0` | Migraciones |
| pytest | `8.3.3` | Pruebas backend |

## Requisitos previos

- JDK 17 y Android SDK 36
- Python 3.10 y `uv`
- PostgreSQL 16.15 o Docker para desarrollo local

## Configuración local

Crea tu archivo local de configuración; `.env` está excluido de Git:

```bash
cp .env.example .env
```

Variables disponibles:

| Variable | Propósito |
| --- | --- |
| `DATABASE_URL` | Conexión de PostgreSQL usada por la API y las migraciones. |
| `JWT_SECRET` | Secreto para firmar y validar sesiones JWT. |
| `ENVIRONMENT` | Entorno de ejecución de la aplicación. |
| `LOGIN_RATELIMIT_IP_MAX` | Configuración documentada en `.env.example`. |
| `LOGIN_RATELIMIT_EMAIL_MAX` | Configuración documentada en `.env.example`. |
| `LOGIN_RATELIMIT_WINDOW_SECONDS` | Configuración documentada en `.env.example`. |
| `DEMO_SUPPORT_EMAIL` | Configuración documentada en `.env.example`. |
| `DEMO_EMPLOYEE_PASSWORD` | Configuración documentada en `.env.example`. |
| `DEMO_SUPPORT_PASSWORD` | Configuración documentada en `.env.example`. |
| `TEST_DATABASE_URL` | Base de datos desechable para pruebas de integración. |

> Nunca confirmes credenciales, tokens, keystores ni secretos reales.

### Base de datos local

```bash
docker compose -f infra/docker-compose.yml up -d
uv run --directory services/api alembic upgrade head
```

## Compilar y ejecutar

### Aplicación Android

Ejecuta desde `apps/mobile`:

**Compilar**

```bash
./gradlew check :app:assembleDebug
```

### API backend

Ejecuta desde la raíz del repositorio:

**Compilar**

```bash
uv sync
```

**Ejecutar**

```bash
python -m uvicorn app.main:app --port 8000
```

### APK de desarrollo

Después del build, el instalable queda en:

`apps/mobile/app/build/outputs/apk/debug/app-debug.apk`

> El APK `debug` sirve para validación interna. Una distribución productiva requiere una variante `release` firmada y una gestión segura del keystore.

## Calidad y pruebas

**Android**

```bash
cd apps/mobile
./gradlew check
```

**Backend**

```bash
uv run pytest services/api/tests
```

## Documentación

- [Diseño general](docs/design/overview.md)
- [Stack detallado](docs/design/tech-stack.md)
- [Estrategia de ambientes](docs/design/environment-strategy.md)
- [Índice de documentación](docs/index.md)

## Seguridad

- Mantén los secretos fuera del repositorio y suminístralos mediante variables de entorno.
- Usa HTTPS fuera del desarrollo local.
- Ejecuta las pruebas y revisa las migraciones antes de promover una versión.

---

Generado a partir del contrato verificado del proyecto por Agent Fabric.
