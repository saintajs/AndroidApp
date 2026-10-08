package com.nexti.minisoporte.data.fake

import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentHistoryEntry
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.model.UserProfile
import com.nexti.minisoporte.data.model.UserRole
import com.nexti.minisoporte.data.repository.IncidentRepository

/*
 * The fixed, deterministic mock dataset shared by every fake in the app
 * (design.md Decisions 4 and 5).
 *
 * ALL values are literals: fixed UUID-style ids and fixed ISO-8601 "Z"
 * timestamps. No randomUUID(), no Clock, no system time anywhere — the same
 * run produces identical results every time (CA-05, Q-005).
 *
 * Task 6's FakeAuthService and task 7's unit tests import these constants.
 */
object FakeSeed {
    /** The signed-in demo employee — owner of the S-02/S-04 demo incidents. */
    const val DEMO_EMPLOYEE_ID = "00000000-0000-0000-0000-0000000000a1"

    /** A second employee — proves owner scoping (foreign ids are invisible). */
    const val FOREIGN_EMPLOYEE_ID = "00000000-0000-0000-0000-0000000000b2"

    /** The support agent — performs the seeded pending→in_progress→resolved transitions. */
    const val SUPPORT_USER_ID = "00000000-0000-0000-0000-0000000000a3"

    val DEMO_EMPLOYEE =
        UserProfile(
            id = DEMO_EMPLOYEE_ID,
            email = "empleado@minisoporte.es",
            role = UserRole.EMPLOYEE,
            active = true,
            createdAt = "2026-01-05T09:00:00Z",
        )

    val FOREIGN_EMPLOYEE =
        UserProfile(
            id = FOREIGN_EMPLOYEE_ID,
            email = "otro@minisoporte.es",
            role = UserRole.EMPLOYEE,
            active = true,
            createdAt = "2026-01-06T09:00:00Z",
        )

    val SUPPORT_USER =
        UserProfile(
            id = SUPPORT_USER_ID,
            email = "soporte@minisoporte.es",
            role = UserRole.SUPPORT,
            active = true,
            createdAt = "2026-01-05T09:00:00Z",
        )

    val ALL_USERS =
        listOf(
            DEMO_EMPLOYEE,
            FOREIGN_EMPLOYEE,
            SUPPORT_USER,
        )

    /**
     * Fixed placeholder createdAt stamped on incidents created through the
     * fake. The real clock arrives with the network layer (US-012's server
     * assigned `created_at`); until then a constant keeps results identical
     * on every run. It is deliberately NEWER than every seeded createdAt so
     * fresh creations surface first in the newest-first list.
     */
    const val CREATED_AT_PLACEHOLDER = "2026-10-06T08:00:00Z"
}

/*
 * The deterministic in-memory Adapter behind [IncidentRepository]
 * (design.md Decision 4). Seeded from [FakeSeed]'s fixed users, incidents and
 * status history:
 *
 *  - Owner-scoped in EVERY method: detail/history return null/empty for
 *    missing or foreign ids (never throw, never leak existence — safe 404).
 *  - [listIncidents] returns createdAt DESC (newest first); every
 *    [IncidentStatus] filter value yields at least one demo-employee result.
 *  - [createIncident] forces [IncidentStatus.PENDING], assigns the
 *    deterministic id `fake-<n>` and the fixed [FakeSeed.CREATED_AT_PLACEHOLDER]
 *    timestamp, and appends the initial history entry (previousStatus null).
 *  - Failure hook: set [failureEnabled] and every method throws
 *    [MockFailureException] — ViewModels map it to the error-UiState-with-retry
 *    pattern (CONF-009), never a crash. [reset] restores the pristine seed for
 *    repeatable tests.
 */
class FakeIncidentRepository : IncidentRepository {
    /** When `true`, EVERY interface method throws [MockFailureException] immediately. */
    var failureEnabled: Boolean = false

    private var incidents: MutableList<Incident> = seedIncidents()
    private var nextFakeId: Int = 1

    /** Restores the original seed and disables the failure hook (repeatable tests). */
    fun reset() {
        failureEnabled = false
        incidents = seedIncidents()
        nextFakeId = 1
    }

    override suspend fun listIncidents(
        ownerId: String,
        status: IncidentStatus?,
    ): List<Incident> {
        failIfEnabled()
        return incidents
            .filter { it.ownerUserId == ownerId }
            .filter { status == null || it.status == status }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun createIncident(
        ownerId: String,
        title: String,
        description: String,
        category: IncidentCategory,
        priority: IncidentPriority,
    ): Incident {
        failIfEnabled()
        val owner = FakeSeed.ALL_USERS.firstOrNull { it.id == ownerId }
        val ownerEmail = owner?.email.orEmpty()
        val incidentId = "fake-$nextFakeId"
        val creationEntry =
            IncidentHistoryEntry(
                id = "fake-h$nextFakeId",
                incidentId = incidentId,
                previousStatus = null,
                newStatus = IncidentStatus.PENDING,
                changedByUserId = ownerId,
                changedByEmail = ownerEmail,
                changedAt = FakeSeed.CREATED_AT_PLACEHOLDER,
                comment = null,
            )
        val created =
            Incident(
                id = incidentId,
                title = title,
                description = description,
                category = category,
                priority = priority,
                status = IncidentStatus.PENDING,
                ownerUserId = ownerId,
                ownerEmail = ownerEmail,
                createdAt = FakeSeed.CREATED_AT_PLACEHOLDER,
                resolvedAt = null,
                history = listOf(creationEntry),
            )
        nextFakeId += 1
        incidents.add(created)
        return created
    }

    override suspend fun getIncident(
        ownerId: String,
        incidentId: String,
    ): Incident? {
        failIfEnabled()
        return incidents
            .firstOrNull { it.id == incidentId && it.ownerUserId == ownerId }
    }

    override suspend fun getHistory(
        ownerId: String,
        incidentId: String,
    ): List<IncidentHistoryEntry> {
        failIfEnabled()
        val incident =
            incidents
                .firstOrNull { it.id == incidentId && it.ownerUserId == ownerId }
        return incident?.history?.sortedBy { it.changedAt }.orEmpty()
    }

    private fun failIfEnabled() {
        if (failureEnabled) {
            throw MockFailureException("Fallo simulado: el repositorio mock está configurado para fallar")
        }
    }
}

/*
 * The fixed seed literals. Building fresh instances per construction/reset
 * keeps the fake immutable from the outside: callers can never mutate the
 * shared dataset through a returned reference.
 */
private fun seedIncidents(): MutableList<Incident> =
    mutableListOf(
        Incident(
            id = "1e6c9a2f-3b7d-4e8f-9c0a-000000000001",
            title = "Impresora no responde",
            description = "La impresora de planta primera no responde desde ayer por la tarde.",
            category = IncidentCategory.EQUIPMENT,
            priority = IncidentPriority.MEDIUM,
            status = IncidentStatus.PENDING,
            ownerUserId = FakeSeed.DEMO_EMPLOYEE_ID,
            ownerEmail = FakeSeed.DEMO_EMPLOYEE.email,
            createdAt = "2026-10-05T10:15:00Z",
            resolvedAt = null,
            history =
                listOf(
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000001",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000001",
                        previousStatus = null,
                        newStatus = IncidentStatus.PENDING,
                        changedByUserId = FakeSeed.DEMO_EMPLOYEE_ID,
                        changedByEmail = FakeSeed.DEMO_EMPLOYEE.email,
                        changedAt = "2026-10-05T10:15:00Z",
                        comment = null,
                    ),
                ),
        ),
        Incident(
            id = "1e6c9a2f-3b7d-4e8f-9c0a-000000000002",
            title = "VPN corporativa no conecta",
            description = "Al conectar desde casa la VPN falla con error de autenticación.",
            category = IncidentCategory.SYSTEMS,
            priority = IncidentPriority.HIGH,
            status = IncidentStatus.IN_PROGRESS,
            ownerUserId = FakeSeed.DEMO_EMPLOYEE_ID,
            ownerEmail = FakeSeed.DEMO_EMPLOYEE.email,
            createdAt = "2026-10-03T09:00:00Z",
            resolvedAt = null,
            history =
                listOf(
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000002",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000002",
                        previousStatus = null,
                        newStatus = IncidentStatus.PENDING,
                        changedByUserId = FakeSeed.DEMO_EMPLOYEE_ID,
                        changedByEmail = FakeSeed.DEMO_EMPLOYEE.email,
                        changedAt = "2026-10-03T09:00:00Z",
                        comment = null,
                    ),
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000003",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000002",
                        previousStatus = IncidentStatus.PENDING,
                        newStatus = IncidentStatus.IN_PROGRESS,
                        changedByUserId = FakeSeed.SUPPORT_USER_ID,
                        changedByEmail = FakeSeed.SUPPORT_USER.email,
                        changedAt = "2026-10-03T11:30:00Z",
                        comment = "Revisando el certificado del portal VPN.",
                    ),
                ),
        ),
        Incident(
            id = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003",
            title = "Credenciales de acceso bloqueadas",
            description = "La cuenta quedó bloqueada tras varios intentos de inicio de sesión.",
            category = IncidentCategory.ACCESS,
            priority = IncidentPriority.LOW,
            status = IncidentStatus.RESOLVED,
            ownerUserId = FakeSeed.DEMO_EMPLOYEE_ID,
            ownerEmail = FakeSeed.DEMO_EMPLOYEE.email,
            createdAt = "2026-10-01T08:20:00Z",
            resolvedAt = "2026-10-01T16:45:00Z",
            history =
                listOf(
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000004",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003",
                        previousStatus = null,
                        newStatus = IncidentStatus.PENDING,
                        changedByUserId = FakeSeed.DEMO_EMPLOYEE_ID,
                        changedByEmail = FakeSeed.DEMO_EMPLOYEE.email,
                        changedAt = "2026-10-01T08:20:00Z",
                        comment = null,
                    ),
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000005",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003",
                        previousStatus = IncidentStatus.PENDING,
                        newStatus = IncidentStatus.IN_PROGRESS,
                        changedByUserId = FakeSeed.SUPPORT_USER_ID,
                        changedByEmail = FakeSeed.SUPPORT_USER.email,
                        changedAt = "2026-10-01T10:05:00Z",
                        comment = null,
                    ),
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000006",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003",
                        previousStatus = IncidentStatus.IN_PROGRESS,
                        newStatus = IncidentStatus.RESOLVED,
                        changedByUserId = FakeSeed.SUPPORT_USER_ID,
                        changedByEmail = FakeSeed.SUPPORT_USER.email,
                        changedAt = "2026-10-01T16:45:00Z",
                        comment = "Cuenta desbloqueada y contraseña restablecida.",
                    ),
                ),
        ),
        Incident(
            id = "1e6c9a2f-3b7d-4e8f-9c0a-000000000004",
            title = "Monitor parpadea intermitentemente",
            description = "El monitor de segundo parpadea al conectar el dock.",
            category = IncidentCategory.EQUIPMENT,
            priority = IncidentPriority.MEDIUM,
            status = IncidentStatus.RESOLVED,
            ownerUserId = FakeSeed.DEMO_EMPLOYEE_ID,
            ownerEmail = FakeSeed.DEMO_EMPLOYEE.email,
            createdAt = "2026-09-28T14:10:00Z",
            resolvedAt = "2026-09-29T10:05:00Z",
            history =
                listOf(
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000007",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000004",
                        previousStatus = null,
                        newStatus = IncidentStatus.PENDING,
                        changedByUserId = FakeSeed.DEMO_EMPLOYEE_ID,
                        changedByEmail = FakeSeed.DEMO_EMPLOYEE.email,
                        changedAt = "2026-09-28T14:10:00Z",
                        comment = null,
                    ),
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000008",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000004",
                        previousStatus = IncidentStatus.PENDING,
                        newStatus = IncidentStatus.IN_PROGRESS,
                        changedByUserId = FakeSeed.SUPPORT_USER_ID,
                        changedByEmail = FakeSeed.SUPPORT_USER.email,
                        changedAt = "2026-09-28T16:40:00Z",
                        comment = null,
                    ),
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-000000000009",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000004",
                        previousStatus = IncidentStatus.IN_PROGRESS,
                        newStatus = IncidentStatus.RESOLVED,
                        changedByUserId = FakeSeed.SUPPORT_USER_ID,
                        changedByEmail = FakeSeed.SUPPORT_USER.email,
                        changedAt = "2026-09-29T10:05:00Z",
                        comment = "Cable DisplayPort sustituido.",
                    ),
                ),
        ),
        Incident(
            id = "1e6c9a2f-3b7d-4e8f-9c0a-000000000005",
            title = "Solicitud de software de diseño",
            description = "Solicito la instalación del editor vectorial para el área de diseño.",
            category = IncidentCategory.OTHER,
            priority = IncidentPriority.LOW,
            status = IncidentStatus.PENDING,
            ownerUserId = FakeSeed.DEMO_EMPLOYEE_ID,
            ownerEmail = FakeSeed.DEMO_EMPLOYEE.email,
            createdAt = "2026-09-26T12:00:00Z",
            resolvedAt = null,
            history =
                listOf(
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-00000000000a",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000005",
                        previousStatus = null,
                        newStatus = IncidentStatus.PENDING,
                        changedByUserId = FakeSeed.DEMO_EMPLOYEE_ID,
                        changedByEmail = FakeSeed.DEMO_EMPLOYEE.email,
                        changedAt = "2026-09-26T12:00:00Z",
                        comment = null,
                    ),
                ),
        ),
        Incident(
            id = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006",
            title = "Cafetera de la zona común averiada",
            description = "La cafetera no calienta el agua; pertenece a otro empleado.",
            category = IncidentCategory.OTHER,
            priority = IncidentPriority.LOW,
            status = IncidentStatus.PENDING,
            ownerUserId = FakeSeed.FOREIGN_EMPLOYEE_ID,
            ownerEmail = FakeSeed.FOREIGN_EMPLOYEE.email,
            createdAt = "2026-10-04T15:45:00Z",
            resolvedAt = null,
            history =
                listOf(
                    IncidentHistoryEntry(
                        id = "d1e70000-0000-4000-8000-00000000000b",
                        incidentId = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006",
                        previousStatus = null,
                        newStatus = IncidentStatus.PENDING,
                        changedByUserId = FakeSeed.FOREIGN_EMPLOYEE_ID,
                        changedByEmail = FakeSeed.FOREIGN_EMPLOYEE.email,
                        changedAt = "2026-10-04T15:45:00Z",
                        comment = null,
                    ),
                ),
        ),
    )
