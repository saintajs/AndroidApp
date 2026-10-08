# 74cc5831-0acd-473b-b54f-f2b6d08934c5-01-minisoporte-requirements-pdf

## Page 1

ANDROID PROJECT
MiniSoporte
Requirements Specification
Android application + backend
Version 1.0
October 5, 2026
New project · Mobile app + backend
Distribution: Internal testing
Implementation technologies are selected in Target system
Project input document · Page 1/10

## Page 2

MiniSoporte · Android requirements
1. Executive summary
MiniSoporte is an Android application that allows employees to report internal incidents related to
systems, equipment, and access, and to review their status and history. The first version must remain
small, secure, and fully buildable as an APK for internal testing.
The product includes an Android application, a REST API, and a database. It does not include a mobile
administration panel, attachments, device permissions, push notifications, offline operation, or
Google Play publication.
2. Objective
Allow an employee to:
 1. Sign in securely. 
2. View only their own incidents. 
3. Filter incidents by status. 
4. Create a new incident. 
5. View incident details and status history. 
3. Actors
Employee
Uses the Android application. The employee can sign in, list, filter, create, and view their own
incidents. Employees cannot view incidents owned by other users or directly change an incident
status.
Support agent
A backend role used to update incident status. This version does not require a support mobile screen.
The status endpoint must be protected by role and covered by API tests.
4. Functional scope
Project input document · Page 2/10

## Page 3

MiniSoporte · Android requirements
4.1 Sign in
 • Fields: email address and password. 
• Both fields are required. 
• Passwords must never be logged or stored as plain text. 
• Successful authentication returns a token-based session. 
• Failed authentication displays a generic message that does not reveal whether the email
exists. 
• Signing out deletes the locally stored token. 
4.2 My incidents
 • Display only incidents owned by the authenticated employee. 
• Default order: newest first. 
• Each item displays title, category, priority, status, and creation date. 
• Available statuses: Pending, In progress, and Resolved. 
• Filters: All, Pending, In progress, and Resolved. 
• Include a visible action to create an incident. 
• Include loading, empty, error, and retry states. 
4.3 New incident
 • Required title between 5 and 100 characters. 
• Required description between 10 and 1,000 characters. 
• Required category: Systems, Equipment, Access, or Other. 
• Required priority: Low, Medium, or High. 
• Every new incident starts in Pending status. 
• After a successful save, return to the list and show confirmation. 
• Prevent duplicate submissions while the request is processing. 
4.4 Incident details
 • Display identifier, title, description, category, priority, status, and creation date. 
• Display a chronological status-change history. 
• Employees cannot edit the owner or alter history. 
• Requests for a missing incident or an incident owned by another employee must return a safe
result without leaking information. 
5. Functional requirements
ID
Requirement
FR-001
The system shall allow users to sign in with
email and password.
FR-002
The system shall issue a token only for valid
credentials.
FR-003
An employee shall see only incidents they own.
Project input document · Page 3/10

## Page 4

MiniSoporte · Android requirements
ID
Requirement
FR-004
The incident list shall be ordered from newest
to oldest.
FR-005
The incident list shall support filtering by
status.
FR-006
An employee shall be able to create a valid
incident.
FR-007
Every new incident shall start in Pending
status.
FR-008
An employee shall be able to view incident
details and history.
FR-009
A support agent shall be able to update status
through a protected endpoint.
FR-010
Every status change shall create a history
entry.
FR-011
The user shall be able to sign out.
FR-012
The API shall expose a health endpoint for
operational validation.
6. Business rules
ID
Rule
BR-001
An incident belongs to exactly one employee.
BR-002
An employee cannot view incidents owned by
another employee.
BR-003
The initial incident status is Pending.
BR-004
Valid transitions are Pending to In progress to
Resolved.
BR-005
A Resolved incident cannot return to a previous
status in version 1.
BR-006
Only the support role can update incident
status.
BR-007
Each transition records previous status, new
status, date, and actor.
BR-008
Email
 addresses
 are
 normalized
 before
authentication and must be unique.
BR-009
Initial demo records are created only in
development or demonstration environments.
7. Screens
S-01 — Sign in
Project input document · Page 4/10

## Page 5

MiniSoporte · Android requirements
Content: product name or logo, email, password, Sign in button, and an error area. The button
remains disabled while the request is processing.
S-02 — My incidents
Content: header, status filter, incident list, New incident action, and Sign out action. It must cover
loading, empty, error, and content states.
S-03 — New incident
Content: title, description, category, priority, Save, and Cancel. Validation errors must appear next to
the corresponding fields.
S-04 — Incident details
Content: primary incident data, a text status badge, and a simple status-history timeline. Include
navigation back to the incident list.
8. Minimum data model
User
 • id: UUID 
• email: unique and normalized 
• password_hash 
• role: employee or support 
• active 
• created_at 
Incident
 • id: UUID 
• owner_id: reference to User 
• title 
• description 
• category: systems, equipment, access, or other 
• priority: low, medium, or high 
• status: pending, in_progress, or resolved 
• created_at 
• updated_at 
IncidentHistory
Project input document · Page 5/10

## Page 6

MiniSoporte · Android requirements
 • id: UUID 
• incident_id 
• previous_status: optional for initial creation 
• new_status 
• changed_by 
• changed_at 
9. Proposed REST API
Method
Route
Access
Purpose
POST
/api/auth/login
Public
Authenticate a user.
GET
/api/me
Authenticated
Return
 the
 current
user.
GET
/api/incidents
Employee
List owned incidents
and optionally filter by
status.
POST
/api/incidents
Employee
Create an incident.
GET
/api/incidents/{id}
Owner
Return incident details
and history.
PATCH
/api/incidents/{id}/st
atus
Support
Update
 status
 and
record history.
GET
/health
Operational
Check API health.
Errors must use a consistent response format without stack traces or sensitive information.
10. Security requirements
 • Store passwords only as resistant salted hashes. 
• Use signed tokens with expiration and signature validation. 
• Enforce owner authorization on every incident query. 
• Protect the status-update endpoint by role. 
• Rate-limit sign-in attempts. 
• Never log passwords, tokens, or authorization headers. 
• Validate data on both client and server. 
• Require HTTPS outside the local environment. 
• Store the mobile token using Android Keystore-backed storage, never as plain text. 
• Use parameterized queries or an ORM to prevent injection. 
• Supply secrets through environment variables. 
• Exclude keystores, .env, key.properties, and credentials from Git. 
• Enable only explicitly selected Android permissions; this version requires none. 
11. Non-functional requirements
 • User interface language: Spanish. 
• Target form factor: phone in portrait orientation. 
• Online operation only; offline synchronization is not required. 
Project input document · Page 6/10

## Page 7

MiniSoporte · Android requirements
• Target API response time under two seconds under normal conditions. 
• Accessibility: readable labels, sufficient contrast, and appropriate touch targets. 
• Network errors must not crash the application and must provide a retry path. 
• Technical logs must not contain sensitive personal data. 
• Database migrations must be reproducible. 
• Include unit tests for business rules and integration tests for authorization. 
• Target test coverage: 90 percent for relevant business logic. 
12. Delivery constraints
 • Target platform: Android phone application with a separate REST backend. 
• The architecture, mobile toolchain, application ID, backend framework, database engine,
testing tools, versions, and project structure are selected manually in Target system. 
• Target system is the only source of truth for implementation technologies. This
document must not be used to infer, replace, or add technologies that the user did not select
there. 
• Authentication requirement: signed token and role-based authorization. 
• Distribution requirement: Internal testing. 
• Android permissions: none. 
• Push notifications: disabled. 
• Deep links: out of scope. 
• Offline operation: disabled. 
13. Initial demo data
Development initialization shall create:
 • One employee: empleado@minisoporte.test. 
• One support agent whose email is supplied through configuration. 
• Three employee incidents: one Pending, one In progress, and one Resolved. 
• Consistent history for incidents whose status has changed. 
Passwords are read from DEMO_EMPLOYEE_PASSWORD and DEMO_SUPPORT_PASSWORD. If either required value
is missing, the seed must fail with a clear message. No default password may be embedded in source
code.
The seed must be idempotent and must never create demo accounts automatically in production.
14. Acceptance criteria
AC-01 — Successful authentication
Given an active employee with a valid password, when the employee signs in, then the My incidents
screen opens and the password does not appear in logs or responses.
Project input document · Page 7/10

## Page 8

MiniSoporte · Android requirements
AC-02 — Failed authentication
Given invalid credentials, when sign-in is attempted, then a generic message is displayed and the
system does not reveal whether the email exists.
AC-03 — Owner isolation
Given an authenticated employee, when the employee requests an incident owned by another user,
then the API returns a safe response without exposing the other user's data.
AC-04 — Incident creation
Given a valid form, when it is saved, then exactly one Pending incident is created and appears in the
list.
AC-05 — Validation
Given an invalid form, when Save is selected, then the incident is not successfully created and
validation errors appear next to the relevant fields.
AC-06 — Status filter
Given incidents in multiple statuses, when a status filter is selected, then only matching incidents are
displayed.
AC-07 — History
Given an authorized update, when support changes incident status, then an immutable history entry
is recorded.
AC-08 — Support authorization
Given a normal employee, when the employee attempts to update status through the API, then the
API returns a forbidden response.
AC-09 — Visual states
Given slow, empty, or failed responses, when the incident list opens, then the interface respectively
displays loading, empty, or recoverable error states.
AC-10 — Android build
Project input document · Page 8/10

## Page 9

MiniSoporte · Android requirements
Given the generated source code, when the tests, lint checks, and Android build command declared
by the selected Target system run, then an installable APK is produced without requiring production
secrets.
AC-11 — Secure release
Given a release build without signing variables, when the build runs, then it fails clearly and does not
substitute a debug signing key.
AC-12 — Sign out
Given an authenticated user, when the user signs out, then the local token is removed and protected
screens are no longer accessible.
15. Visual direction
 • Professional, restrained, and clean visual style. 
• Primary color: dark green #1E3A2A. 
• Action/accent color: yellow #EFC64E. 
• Light background: #F5F7F5. 
• Differentiate statuses with both text and color, never color alone. 
• Use moderate 8-pixel corners and spacing based on multiples of eight. 
• Use compact list cards with clear typographic hierarchy. 
• Do not use side navigation; the application is designed for a phone. 
16. Out of scope
 • Public registration. 
• Password recovery. 
• Attachments and camera access. 
• Location, biometrics, Bluetooth, NFC, or microphone access. 
• Push notifications. 
• Offline operation. 
• Mobile administration panel. 
• Employee editing or deletion of incidents. 
• Google Play publication. 
• iOS. 
17. Expected deliverables
 • Android source code using exactly the mobile technology selected in Target system. 
• REST API and database migrations. 
• Secure development seed data. 
• Unit and integration tests. 
• Local execution documentation. 
Project input document · Page 9/10

## Page 10

MiniSoporte · Android requirements
• A run.apps contract containing a build-only mobile application and an HTTP backend. 
• Debug APK for internal validation. 
• Release configuration prepared for externally supplied signing secrets, without real secrets. 
Project input document · Page 10/10
