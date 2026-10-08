## Overview

MiniSoporte_copy is a greenfield internal incident-reporting product for AndersonMobile, built as a native Android client (Kotlin + Jetpack Compose, application id `com.nexti.minisoporte`, package `apps/mobile`) backed by a FastAPI 0.123.10 / Python 3.10 REST API and a PostgreSQL 16.15 database. Employees sign in with email and password, report internal incidents (systems, equipment, access, or other), and see ONLY the incidents they own — filterable by status — each with a detail view and an immutable chronological status-history timeline. Support agents never touch the mobile app in v1: they advance incident status exclusively through a role-protected API endpoint (`PATCH /api/incidents/{id}/status`), and every accepted transition is recorded as a history entry. Distribution is internal testing only: no cloud infrastructure, no CI/CD pipeline, no Google Play publication.

### Actors and Goals

| Actor | Role / Access | Goals |
|-------|---------------|-------|
| **Employee** | Android app user, `employee` role | Sign in securely (JWT session stored in Android Keystore-backed storage); create a new incident with validated title, description, category, and priority (always starting in Pending); view and filter ONLY their own incidents (All / Pending / In progress / Resolved, newest first); open incident details with a status-history timeline; sign out from the list screen. |
| **Support agent** | Backend role, `support` role — **no mobile screen in v1** | Update incident status through the support-only API endpoint, following the linear lifecycle Pending → In progress → Resolved (Resolved is terminal in v1); each change records previous status, new status, date, and actor. Employees attempting the same endpoint receive a forbidden response. |
| **Operations tooling** | Unauthenticated HTTP client | Verify the backend is up via the public `GET /health` endpoint, which reports health without exposing stack traces, configuration, or internal details; used before pointing the Android client at the service. |
| **Development tooling** | Maintain engineers / internal-test setup | Run an idempotent demo seed that creates one employee (`empleado@minisoporte.test`), one support agent (email from configuration), and three incidents covering all three statuses with consistent history — only in non-production environments, with passwords read exclusively from `DEMO_EMPLOYEE_PASSWORD` / `DEMO_SUPPORT_PASSWORD` (missing variables fail the seed; no default password is ever embedded in source). Per clarification Q-001, the internal-test environment also runs this seed manually with configured emails, since v1 has no registration or user-management flow. |

### Business Context

- **Customer and use:** AndersonMobile employees use MiniSoporte_copy to report internal problems with systems, equipment, and access, and to follow each report's progress through its status history. This is a first version (v1) of a greenfield product.
- **Distribution:** Internal testing only. The deliverable is an installable debug APK (plus a release configuration prepared for externally supplied signing secrets); no Google Play submission, no store listing, no cloud infrastructure.
- **Connectivity:** Online only. There is no offline mode, no synchronization, and no caching layer; every screen talks to the REST API. Network errors must never crash the app and must always offer a retry path; the target API response time is under two seconds under normal conditions.
- **Form factor and language:** Android phones in portrait orientation only; the entire UI is in Spanish (sign-in "Iniciar sesión", list "Mis incidencias", form "Nueva incidencia", detail "Detalle"). Per INSTR-012, statuses are differentiated with text and color — never color alone — on the palette of dark green `#1E3A2A`, yellow accent `#EFC64E`, and light background `#F5F7F5`, with 8-pixel corners, spacing in multiples of eight, compact list cards, and no side navigation.
- **Security posture:** OWASP Mobile and OWASP Top 10 standards apply; JWT (HS256, 8-hour expiration, no refresh) sessions, bcrypt-hashed passwords, HTTPS outside local development, and owner isolation on every incident query are non-negotiable foundations of the product's confidentiality promise.

### In Scope

1. **Employee sign in / sign out** — email + password against `POST /api/auth/login`; signed 8-hour JWT stored in Keystore-backed storage; generic failure message that never reveals whether an email exists; sign-out deletes the local token (client-side deletion plus short TTL accepted per Q-008).
2. **My incidents list** — only the authenticated employee's own incidents, newest first, each showing title, category, priority, status badge, and creation date; loading, empty, error (with retry), and content states; Sign out and New-incident actions.
3. **Status filter** — chips for All / Pending / In progress / Resolved applied on top of owner isolation; fixed status set `pending`, `in_progress`, `resolved`; no pagination in v1 (Q-007).
4. **Create incident** — Spanish form S-03 with title (5–100 chars), description (10–1,000 chars), category (Systems, Equipment, Access, Other), priority (Low, Medium, High); validation on both client and server with field-level errors; duplicate submission prevented; every new incident starts Pending.
5. **Incident details + status history** — owner-restricted `GET /api/incidents/{id}`; detail card plus a chronological status-history timeline; safe (non-leaking) response for missing or foreign incidents; history is immutable for employees.
6. **Support status updates (API-only)** — `PATCH /api/incidents/{id}/status`, support role only, linear transitions with Resolved terminal; every accepted change writes an immutable history entry; no support mobile screen; covered by API tests.
7. **Session security and API hardening** — JWT HS256 8-hour tokens (PyJWT 2.15.1, per Q-011), bcrypt password hashing used directly (Q-010), in-process in-memory rate limiting on failed sign-ins (Q-004), SQLAlchemy 2.0.x ORM + Alembic migrations (Q-003), HTTPS outside local environment, no Android permissions, no push notifications, no deep links.
8. **Operational and development tooling** — public `GET /health`; idempotent, non-production demo seed driven by environment secrets; debug APK build (`./gradlew check :app:assembleDebug`) and release configuration that fails clearly without signing variables.
9. **Testing** — pytest unit and integration tests covering authentication, validation, ownership isolation, role protection, and API error handling, targeting ≥90% coverage of business logic (INSTR-013).

### Out of Scope

The following are explicitly excluded from v1 (per the extractions and wizard constraints):

- **Camera access** and **file attachments** on incidents.
- **Location** services of any kind.
- **Biometric** authentication.
- **Push notifications** and deep links.
- **Offline operation** — the app is online only; no cache-first or sync behavior.
- **Mobile administration panel** — no admin UI on Android.
- **Support-agent mobile screen** — status updates are API-only in v1.
- **Google Play publication** — internal testing distribution only; store submission is a deliberate external action not part of this project.
- **Pagination** on the incident list (Q-007: all owned incidents are returned, newest first).
- **Server-side token invalidation** on sign-out (Q-008: local deletion + short TTL is the accepted mitigation).
- **Registration / in-app user-management flows** — accounts are provisioned by the seed run manually in the internal-test environment (Q-001); there is no sign-up screen or user admin UI.
- **Cloud infrastructure, CI/CD pipelines, and external resources** — forbidden by project constraints; builds and verification are local only.

### Approved UI Starting Point

The user-approved Pre-Design draft (`docs/design/pre_design_mobile.html`, phone mode, Spanish, all four screens wired per `pre_design_dynamism.json`) is the approved starting point for the UI design: **S-01 Iniciar sesión** (sign-in form with generic error and submit lock), **S-02 Mis incidencias** (owned-incident list with status filter chips, counts, FAB, and "Salir" sign-out), **S-03 Nueva incidencia** (live per-field validation, category/priority choice groups, duplicate-submission lock), and **S-04 Detalle** (incident card plus status-history timeline with a safe no-access state for foreign identifiers). Its screens, palette, spacing system, and interaction behaviors are the baseline the frontend design extends — not suggestions to be re-derived.
