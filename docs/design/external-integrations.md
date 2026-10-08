## External Integrations

**This system has no external third-party integrations.** Every connection in the architecture is internal to the product itself: the Android client talks to the project's own FastAPI backend, and the backend talks to the project's own PostgreSQL database. This is a deliberate consequence of the project constraints — AGENTS.md forbids cloud infrastructure and external resources, the deployment target is internal testing only, and the extractions name no third-party service (no email, no push service, no storage provider, no identity provider, no payment or analytics provider). FUNC-008's `GET /health` and CONF-007's zero-permission, no-deep-link posture were designed precisely to keep the attack and integration surface at zero.

The only cross-boundary interaction worth designing for is the first-party client ↔ API link, documented here for completeness:

### Android app ↔ REST API (first-party, internal)

| Aspect | Design |
|--------|--------|
| Direction | Outbound from `apps/mobile` to `services/api`. |
| Protocol | HTTPS REST/JSON (Retrofit 3.0.0 + OkHttp 5.x + kotlinx.serialization, per Q-012). Plain HTTP is permitted only against a local development backend (CONF-004); the Android release/network security configuration rejects cleartext everywhere else. |
| Auth method | `Authorization: Bearer <JWT>` header on every protected call; the token is the HS256 8-hour JWT issued by `POST /api/auth/login` (Q-002, Q-011). No refresh flow (Q-008): on `401` the app clears the local token and routes back to S-01. |
| Data exchanged | Login credentials → token; incident list/create/detail/status-filter payloads; the current user profile (`GET /api/me`); nothing else. |
| Failure/retry handling | Online only (CONF-009): network errors never crash the app — every screen renders a recoverable error state with an explicit retry action; timeouts on OkHttp; the UI language reports the failure generically. Sign-in additionally throttles repeated failures server-side (CONF-003). |

### Boundary notes

- **No outbound calls from the backend.** `services/api` performs no webhook delivery, no SMTP, no external API calls; its only dependency is the local PostgreSQL instance.
- **No inbound callers besides the Android client and operations tooling.** `GET /health` serves local run/deployment validation only and discloses no internal details (FUNC-008).
- **Supply-chain note (not an integration):** Gradle/Maven and PyPI dependencies are resolved at build time from public registries; versions are pinned in `gradle/libs.versions.toml` and the uv lockfile so builds are reproducible and no runtime registry access is required.
