# Technical Instructions & Constraints

## INSTR-003 — Linear status transitions with Resolved as terminal

 *(Category: business_rule)* *(Scope: targeted)*

The only valid status transitions are Pending to In progress and In progress to Resolved, and a Resolved incident cannot return to a previous status in version 1. The status-update endpoint must validate transitions before applying them and reject any backwards, skipped, or repeated transition. This keeps the incident lifecycle meaningful for employees reviewing history; a violation would allow misleading progress records such as a resolved issue silently reopening without a trail.

## INSTR-004 — Only the support role can update incident status

 *(Category: business_rule)* *(Scope: targeted)*

Updating an incident's status is reserved for users with the support role; employees can create and read their own incidents but never change status. The PATCH /api/incidents/{id}/status endpoint must be protected by role and covered by API tests proving an ordinary employee receives a forbidden response. Without this rule the owner-based separation of duties collapses, since reporters could mark their own issues as resolved.

## INSTR-005 — Every status change records an immutable history entry

 *(Category: business_rule)* *(Scope: targeted)*

Each accepted status transition must create an IncidentHistory entry capturing the previous status, the new status, the date, and the actor who made the change; the initial creation may leave previous_status empty. Employees cannot edit the owner or alter the history, making the timeline a trustworthy audit trail. Violation would leave status changes unexplained, undermine employee trust, and fail acceptance criterion AC-07.

## INSTR-006 — Email normalization and uniqueness

 *(Category: business_rule)* *(Scope: targeted)*

Email addresses are normalized (trimmed and lowercased) before authentication and must be unique in the User table. The uniqueness constraint prevents duplicate accounts from being confused at sign-in, while normalization ensures the same address typed in different cases still matches one account. Violation would allow ambiguous identities or failed sign-ins for legitimate users.

## INSTR-008 — Passwords stored only as salted hashes and never logged

 *(Category: compliance)* *(Scope: global)*

Passwords must never be logged or stored as plain text; the backend stores only resistant salted hashes in the password_hash field, and passwords never appear in API responses or logs. This limits the impact of any database or log exposure, since attackers would face salted hashes rather than reusable credentials. Violation would directly compromise employee accounts and breach the project's security standards (OWASP Top 10 / OWASP Mobile).

## INSTR-010 — Demo seed restricted to non-production and driven by environment secrets

 *(Category: constraint)* *(Scope: targeted)*

Initial demo records are created only in development or demonstration environments, and never automatically in production. Seed passwords come solely from the DEMO_EMPLOYEE_PASSWORD and DEMO_SUPPORT_PASSWORD environment variables; a missing variable must fail the seed with a clear message, and no default password may be embedded in source code. The seed must be idempotent. Violation would either place hardcoded credentials into the codebase or create live demo accounts in production.

## INSTR-011 — Validate data on both client and server with field-level errors

 *(Category: policy)* *(Scope: targeted)*

All form data must be validated on both the Android client and the REST backend; when validation fails on the New incident screen, the incident is not created and errors appear next to the corresponding fields. Server-side validation is the authoritative guard against malformed or malicious requests, while client-side validation gives immediate feedback. Violation would allow invalid incidents into the database or leave users confused about why a save failed.

## INSTR-012 — Accessibility and visual differentiation rules

 *(Category: compliance)* *(Scope: global)*

The interface must provide readable labels, sufficient contrast, and appropriate touch targets, and statuses must be differentiated with both text and color, never color alone. The visual style is professional and restrained, using the defined palette (dark green #1E3A2A primary, yellow #EFC64E accent, light background #F5F7F5), 8-pixel corners, spacing in multiples of eight, compact list cards, and no side navigation since the app targets a phone. Violation would exclude users with visual impairments and produce ambiguous status recognition.

## INSTR-013 — Mandatory tests with 90 percent coverage of business logic

 *(Category: constraint)* *(Scope: global)*

The backend must include unit tests for business rules and integration tests for authorization (ownership isolation and role protection of the status endpoint), targeting 90 percent coverage of relevant business logic. These tests are the executable proof that BR rules and authorization constraints hold; without them, regressions in owner isolation or role enforcement could reach the internal-test build unnoticed. Violation fails the stated acceptance criteria and the project's quality threshold.

## INSTR-001 — Incident owner isolation

 *(Category: business_rule)* *(Scope: global)*

An incident belongs to exactly one employee, and the system must enforce owner-based authorization on every incident query: an employee cannot view incidents owned by another employee. This rule applies to the list, the detail endpoint, and any history data, including missing or foreign identifiers, which must produce a safe response that does not leak information. Violation would expose private incident data across users, breaking the core confidentiality promise of the product and failing acceptance criteria AC-03.

## INSTR-002 — Initial incident status is Pending

 *(Category: business_rule)* *(Scope: targeted)*

Every incident created by an employee starts its lifecycle in the Pending status; no other initial status is permitted. The creation flow and the database defaults must both enforce this so a client cannot submit an incident directly as In progress or Resolved. Violation would corrupt the status lifecycle, mislead support triage, and break the status-history model, which records the initial creation with an optional empty previous status.
