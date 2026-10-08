# Agile Backlog — Index

Epics → features → user stories for this project. Each story is one `<story-id>-*.md`
file; `EPICS.md` is the registry. Read this index first, then open the story you need.
The **Implements** column summarizes each story's grounded `design_traceability`
(the design components/screens/APIs/entities/flows it delivers).

## Summary

- **Epics:** 3
- **Features:** 9
- **Stories:** 12

## Component coverage

Stories grouped by the design component they implement (a story may touch several).

| Component | Stories | Implemented |
|-----------|---------|-------------|
| PostgreSQL 16.15 | 1 | 0/1 |
| api | 5 | 0/5 |
| mobile-app | 7 | 0/7 |

## Backlog

| Epic | Feature | Story | Title | Domain | Done | Implements |
|------|---------|-------|-------|--------|------|------------|
| EPIC-001 | FEAT-001 | [US-001](US-001-backend-foundation-workspace-layered-fas.md) | Backend foundation: workspace, layered FastAPI skeleton, PostgreSQL schema and /health | foundation |   | api, PostgreSQL 16.15 · GET /health · users, incidents… · Development environment initial… |
| EPIC-001 | FEAT-002 | [US-002](US-002-sign-in-session-jwt-and-profile-api-with.md) | Sign-in, session JWT and profile API with rate-limited generic failures | backend |   | api · POST /api/auth/login, GET /api/me · users · Employee authentication flow, Session l… |
| EPIC-001 | FEAT-003 | [US-003](US-003-incident-rest-api-owner-scoped-list-stat.md) | Incident REST API: owner-scoped list + status filter, create-as-Pending, detail with history | backend |   | api · GET /api/incidents, POST /api/incidents… · incidents, incident_history · Incident c… |
| EPIC-001 | FEAT-003 | [US-004](US-004-support-only-status-transition-api-with-.md) | Support-only status transition API with linear matrix and immutable history | backend |   | api · PATCH /api/incidents/{id}/status · incidents, incident_history · Support status upd… |
| EPIC-002 | FEAT-004 | [US-005](US-005-android-mock-first-foundation-gradlecomp.md) | Android mock-first foundation: Gradle/Compose project, Material 3 theme, navigation S-01..S-04, repository seam with in-memory fakes, fake admin/admin authentication and seeded mock data | frontend-foundation |   | mobile-app |
| EPIC-002 | FEAT-004 | [US-012](US-012-android-real-backend-integration-retrofi.md) | Android real-backend integration: Retrofit/OkHttp client, DTOs, mappers, AuthInterceptor, INTERNET permission, real authentication and Keystore-backed TokenStore behind the repository seam | frontend-infrastructure |   | mobile-app · POST /api/auth/login, GET /api/me… · Employee authentication flow, Session l… |
| EPIC-002 | FEAT-005 | [US-006](US-006-s-01-iniciar-sesion-validated-mock-sign-.md) | S-01 Iniciar sesión: validated mock sign-in with demo credentials (admin/admin), submit lock and generic failure | frontend-ui-ux |   | mobile-app · S-01 — Iniciar sesión · Employee authentication flow, Session lifecycle (sig… |
| EPIC-002 | FEAT-006 | [US-007](US-007-s-02-mis-incidencias-owned-list-with-sta.md) | S-02 Mis incidencias: owned list with status filter chips, states and sign-out | frontend-ui-ux |   | mobile-app · S-02 — Mis incidencias · Incident review flow (list, filter, details), Sessi… |
| EPIC-002 | FEAT-007 | [US-008](US-008-s-03-nueva-incidencia-validated-creation.md) | S-03 Nueva incidencia: validated creation form with counters and duplicate-submission lock | frontend-ui-ux |   | mobile-app · S-03 — Nueva incidencia · Incident creation flow |
| EPIC-002 | FEAT-007 | [US-009](US-009-s-04-detalle-de-incidencia-detail-card-a.md) | S-04 Detalle de incidencia: detail card and newest-first mock status-history timeline with safe no-access state | frontend-ui-ux |   | mobile-app · S-04 — Detalle de incidencia · Incident review flow (list, filter, details) |
| EPIC-003 | FEAT-008 | [US-010](US-010-idempotent-demo-seed-with-env-only-passw.md) | Idempotent demo seed with env-only passwords and the >=90% business-logic coverage gate | backend |   | api · users, incidents… · Development environment initialization |
| EPIC-003 | FEAT-009 | [US-011](US-011-installable-debug-apk-for-internal-testi.md) | Installable debug APK for internal testing: mock-first build contract without signing secrets | infrastructure |   | mobile-app |
