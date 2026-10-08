# SPEC PROGRESS

## Active Spec

- **Change Name:** android-real-backend-integration-retrofitokhttp-client-dtos-ab52ce56
- **Story:** US-012 — Android real-backend integration: Retrofit/OkHttp client, DTOs, mappers, AuthInterceptor, INTERNET permission, real authentication and Keystore-backed TokenStore behind the repository seam
- **Story File:** docs/agile/US-012-android-real-backend-integration-retrofi.md
- **Status:** generating

## Story Context

## Android real-backend integration: Retrofit/OkHttp client, DTOs, mappers, AuthInterceptor, INTERNET permission, real authentication and Keystore-backed TokenStore behind the repository seam

Deferred follow-up story that swaps the mock seams for the real backend: Retrofit 3.0.0 + OkHttp 5.5.0 network layer with kotlinx-serialization DTOs and mappers, AuthInterceptor, the android.permission.INTERNET declaration, real authentication via POST /api/auth/login (plus GET /api/me), real REST repository implementations of IncidentRepository, and the Android Keystore-backed TokenStore replacing the in-memory fake. It must not block or be required by the mock debug APK (US-011); it may depend on the completed mock foundation (US-005, US-006, US-009).

**Acceptance criteria:**
- CA-01: Given the merged manifest, When inspected after this story, Then it declares exactly one uses-permission entry — android.permission.INTERNET — consumed solely by the network layer, with no additional permissions (BR-031).
- CA-02: Given the network module, When inspected, Then Retrofit 3.0.0 (+ converter-kotlinx-serialization), OkHttp 5.5.0 and kotlinx-serialization-json 1.11.0 are pinned in gradle/libs.versions.toml, and IncidentApi exposes only operations matching the IncidentRepository contract.
- CA-03: Given data/remote/dto/ and data/remote/mappers/, When inspected, Then DTOs mirror the backend response schemas for POST /api/auth/login, GET /api/me, GET /api/incidents, POST /api/incidents, GET /api/incidents/{id} and PATCH /api/incidents/{id}/status, and mappers convert DTO<->domain so domain models never reach the network layer.
- CA-04: Given an authenticated request, When it executes, Then AuthInterceptor attaches Authorization: Bearer <token> read from TokenStore, and no token material appears in logs, DTOs or UiState (CONF-002, INSTR-008).
- CA-05: Given the Keystore-backed TokenStore implementation, When save/read/delete are exercised in Robolectric or instrumented tests, Then the token round-trips via Android Keystore storage, deletion leaves no readable token, and plain-text persistence is never used; plain JVM unit tests keep using the in-memory fake (BR-006).
- CA-06: Given real authentication, When sign-in runs against POST /api/auth/login, Then 200 issues and stores the JWT; 401 shows the generic non-enumerating error; 422 maps field descriptors to Spanish per-field copy; 429 shows the too-many-attempts message; and the expired-token recovery path (401 on any protected call) clears the token and routes to S-01 with no silent retry (Q-008, BR-002).
- CA-07: Given BuildConfig per build type, When inspected, Then the base URL is injected per build type — debug may target the local/emulator backend, release resolves to the HTTPS internal-test host (CONF-004, BR-028).

## Assets

_No mockup images for this change._

## Story Backlog

See `docs/agile/EPICS.md` for the full epic → feature → story registry
(includes each story's Implements column).

---

> **Agent reading order:**
> 1. Read this file (PROGRESS.md)
> 2. Read the story file above — its **## Implementation Map** is your scope and its
>    **## Technical Analysis** is the authoritative design contract (endpoints, entities,
>    screens, tokens — verbatim)
> 3. Read `docs/agile/EPICS.md` — full backlog (know what sibling stories own)
> 4. Read `openspec/config.yaml` — project-general context (stack + versions, archetype,
>    conventions, security baseline, design tokens)
> 5. View every image in **Assets** above using the Read tool
> Do NOT read `docs/design/`, `docs/extractions/`, or `docs/input/` — everything this change
> needs is in the story file and `openspec/`.
