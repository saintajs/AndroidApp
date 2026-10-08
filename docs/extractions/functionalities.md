# Functionalities

| ID | Name | Description |
|----|------|-------------|
| FUNC-001 | Sign in with email and password | The system allows an employee to authenticate using an email address and a password through the sign… |
| FUNC-002 | Sign out | An authenticated employee can sign out from the My incidents screen (S-02 includes the Sign out acti… |
| FUNC-003 | View my incidents list | After authentication the employee lands on the My incidents screen (S-02), which displays only the i… |
| FUNC-004 | Filter incidents by status | The My incidents screen provides a status filter with the options All, Pending, In progress, and Res… |
| FUNC-005 | Create a new incident | From the My incidents screen the employee opens the New incident form (S-03) and submits it via POST… |
| FUNC-006 | View incident details and status history | From the incident list the employee opens the Incident details screen (S-04), served by GET /api/inc… |
| FUNC-007 | Support agent updates incident status | A support agent updates an incident's status through the PATCH /api/incidents/{id}/status endpoint, … |
| FUNC-008 | API health check | The API exposes a public GET /health endpoint used for operational validation of the service. It rep… |
| FUNC-009 | Development demo data seed | Development initialization creates a small, consistent set of demo records: one employee (empleado@m… |

## FUNC-001 — Sign in with email and password

The system allows an employee to authenticate using an email address and a password through the sign-in screen (S-01) and the public POST /api/auth/login endpoint. Both fields are required; when either is missing or invalid the request is rejected with field-level validation feedback. On success the backend issues a signed, expiring token that represents the session, the My incidents screen opens, and the token is stored securely on the device. On failure the application displays a single generic message that does not reveal whether the email exists. The sign-in button remains disabled while the request is processing to prevent duplicate submissions.

**Actors:** Employee

**Acceptance Criteria:**
- Given an active employee with a valid password, when the employee signs in, then the My incidents screen opens and the password does not appear in logs or responses.
- Given invalid credentials, when sign-in is attempted, then a generic message is displayed and the system does not reveal whether the email exists.

**Business Rules:**
- Both email and password are required
- Failed authentication shows a generic message that does not reveal whether the email exists
- The sign-in button stays disabled while the request is processing

## FUNC-002 — Sign out

An authenticated employee can sign out from the My incidents screen (S-02 includes the Sign out action). When the user signs out, the locally stored session token is deleted from the device so that no protected screen remains accessible. Any subsequent navigation to a protected screen requires a new sign-in. This closes the session lifecycle started at sign-in and prevents token reuse on a shared or lost device.

**Actors:** Employee

**Acceptance Criteria:**
- Given an authenticated user, when the user signs out, then the local token is removed and protected screens are no longer accessible.

**Business Rules:**
- Signing out deletes the locally stored token

## FUNC-003 — View my incidents list

After authentication the employee lands on the My incidents screen (S-02), which displays only the incidents owned by the authenticated employee, fetched through GET /api/incidents. Each list item shows the title, category, priority, status, and creation date. The default order is newest first by creation date. The screen must cover loading, empty, error, and content states, with a retry path when a network error occurs, and it exposes a visible action to create a new incident plus the Sign out action. Employees cannot view incidents owned by other users.

**Actors:** Employee

**Acceptance Criteria:**
- Given an authenticated employee, when the list loads, then only incidents owned by that employee are shown, ordered newest first.
- Given slow, empty, or failed responses, when the incident list opens, then the interface respectively displays loading, empty, or recoverable error states.

**Business Rules:**
- Display only incidents owned by the authenticated employee
- Default order: newest first
- Each item shows title, category, priority, status, and creation date

## FUNC-004 — Filter incidents by status

The My incidents screen provides a status filter with the options All, Pending, In progress, and Resolved. When the employee selects a filter, the application requests or narrows the incident list so that only incidents whose status matches the selection are displayed; All removes the restriction. Statuses are the fixed set Pending, In progress, and Resolved. Filtering applies on top of owner isolation, so a filter never exposes another employee's incidents.

**Actors:** Employee

**Acceptance Criteria:**
- Given incidents in multiple statuses, when a status filter is selected, then only matching incidents are displayed.

**Business Rules:**
- Available statuses: Pending, In progress, Resolved
- Filter options: All, Pending, In progress, Resolved

## FUNC-005 — Create a new incident

From the My incidents screen the employee opens the New incident form (S-03) and submits it via POST /api/incidents. The form requires a title of 5 to 100 characters, a description of 10 to 1,000 characters, one category (Systems, Equipment, Access, or Other) and one priority (Low, Medium, or High). Validation is enforced on both client and server, and errors appear next to the corresponding fields. Every new incident is created in Pending status, owned by the authenticated employee. Duplicate submissions are prevented while the request is processing, and after a successful save the app returns to the list and shows a confirmation.

**Actors:** Employee

**Acceptance Criteria:**
- Given a valid form, when it is saved, then exactly one Pending incident is created and appears in the list.
- Given an invalid form, when Save is selected, then the incident is not successfully created and validation errors appear next to the relevant fields.

**Business Rules:**
- Title required, 5-100 characters
- Description required, 10-1000 characters
- Category required: Systems, Equipment, Access, Other
- Priority required: Low, Medium, High
- Every new incident starts in Pending status
- Prevent duplicate submissions while the request is processing

## FUNC-006 — View incident details and status history

From the incident list the employee opens the Incident details screen (S-04), served by GET /api/incidents/{id} for the owner. The screen shows the identifier, title, description, category, priority, status as a text badge, and creation date, followed by a simple chronological status-history timeline built from IncidentHistory entries. Employees cannot edit the owner or alter the history. When the incident does not exist or is owned by another employee, the API and the app return a safe result that does not leak information about other users' incidents. The screen includes navigation back to the incident list.

**Actors:** Employee

**Acceptance Criteria:**
- Given an authenticated employee, when the employee requests an incident owned by another user, then the API returns a safe response without exposing the other user's data.
- Given an owned incident, when details open, then the primary data and the chronological status history are displayed.

**Business Rules:**
- Employees cannot edit the owner or alter history
- Requests for a missing or foreign incident must return a safe result without leaking information

## FUNC-007 — Support agent updates incident status

A support agent updates an incident's status through the PATCH /api/incidents/{id}/status endpoint, which is protected by role so that only the support role can call it; an employee who attempts it receives a forbidden response. Valid transitions follow the linear path Pending to In progress to Resolved, and a Resolved incident cannot return to a previous status in version 1. Every accepted status change records a history entry with the previous status, new status, date, and actor, and this history is immutable for employees. This version does not require a support mobile screen; the capability is API-only and must be covered by API tests.

**Actors:** Support agent

**Acceptance Criteria:**
- Given an authorized update, when support changes incident status, then an immutable history entry is recorded.
- Given a normal employee, when the employee attempts to update status through the API, then the API returns a forbidden response.

**Business Rules:**
- Only the support role can update incident status
- Valid transitions are Pending to In progress to Resolved
- A Resolved incident cannot return to a previous status in version 1
- Each transition records previous status, new status, date, and actor

## FUNC-008 — API health check

The API exposes a public GET /health endpoint used for operational validation of the service. It reports that the API is up and able to respond, without requiring authentication and without disclosing sensitive configuration, stack traces, or internal details. Deployment and local-run tooling use it to verify that the backend started correctly before the Android client is exercised against it.

**Actors:** Operations tooling

**Acceptance Criteria:**
- Given a running backend, when GET /health is called, then the service reports healthy without requiring authentication.

**Business Rules:**
- Health responses must not expose stack traces or sensitive information

## FUNC-009 — Development demo data seed

Development initialization creates a small, consistent set of demo records: one employee (empleado@minisoporte.test), one support agent whose email is supplied through configuration, and three employee incidents covering one Pending, one In progress, and one Resolved status, including consistent history entries for the incidents whose status has changed. Passwords are read exclusively from the DEMO_EMPLOYEE_PASSWORD and DEMO_SUPPORT_PASSWORD environment variables; if either value is missing the seed fails with a clear message and no default password is embedded in source code. The seed is idempotent and never creates demo accounts automatically in production environments.

**Actors:** Development tooling

**Acceptance Criteria:**
- Given a development environment with both password variables set, when the seed runs, then the demo employee, support agent, and three incidents with consistent history are created.
- Given either password variable is missing, when the seed runs, then initialization fails with a clear message and no default password is used.

**Business Rules:**
- Demo passwords come only from DEMO_EMPLOYEE_PASSWORD and DEMO_SUPPORT_PASSWORD
- Missing password variables fail the seed with a clear message
- No default password may be embedded in source code
- Seed is idempotent and never runs automatically in production
