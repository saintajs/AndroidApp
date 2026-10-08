/*
 * Plain JVM unit tests for the deterministic in-memory incident fake
 * (design.md Decision 7, spec "IncidentRepository seam with seeded in-memory
 * fake" — CA-05/Q-005/BR-041).
 *
 * Every assertion pins a DETERMINISTIC seed fact (all literals, no
 * randomness): the 5 demo-employee incidents + 1 foreign incident, owner
 * scoping, newest-first ordering, every status-filter value, detail+history,
 * create-as-Pending with the fake-<n> ids, and the failure-hook -> retryable
 * error path. Each test runs against a fresh repository.
 */
package com.nexti.minisoporte.data.fake

import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeIncidentRepositoryTest {
    @Test
    fun seedLoadsDemoEmployeeIncidentsNewestFirst() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val incidents = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)

            assertEquals(5, incidents.size)
            assertEquals(
                listOf(ID_1, ID_2, ID_3, ID_4, ID_5),
                incidents.map { it.id },
            )
            assertEquals(
                listOf(
                    IncidentStatus.PENDING,
                    IncidentStatus.IN_PROGRESS,
                    IncidentStatus.RESOLVED,
                    IncidentStatus.RESOLVED,
                    IncidentStatus.PENDING,
                ),
                incidents.map { it.status },
            )
            assertEquals(
                listOf(
                    "2026-10-05T10:15:00Z",
                    "2026-10-03T09:00:00Z",
                    "2026-10-01T08:20:00Z",
                    "2026-09-28T14:10:00Z",
                    "2026-09-26T12:00:00Z",
                ),
                incidents.map { it.createdAt },
            )
        }

    @Test
    fun ownerScopingNeverLeaksForeignIncidents() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val demoIds = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null).map { it.id }
            assertFalse(demoIds.contains(ID_6))

            val foreign = repository.listIncidents(FakeSeed.FOREIGN_EMPLOYEE_ID, null)
            assertEquals(listOf(ID_6), foreign.map { it.id })
            assertEquals(FakeSeed.FOREIGN_EMPLOYEE_ID, foreign.single().ownerUserId)

            assertNull(repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, ID_6))
            assertTrue(repository.getHistory(FakeSeed.DEMO_EMPLOYEE_ID, ID_6).isEmpty())
            assertNull(repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, "no-such-id"))
            assertTrue(repository.getHistory(FakeSeed.DEMO_EMPLOYEE_ID, "no-such-id").isEmpty())
        }

    @Test
    fun statusFilterReturnsMatchingSubsetsNewestFirst() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val pending =
                repository
                    .listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, IncidentStatus.PENDING)
                    .map { it.id }
            val inProgress =
                repository
                    .listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, IncidentStatus.IN_PROGRESS)
                    .map { it.id }
            val resolved =
                repository
                    .listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, IncidentStatus.RESOLVED)
                    .map { it.id }

            assertEquals(listOf(ID_1, ID_5), pending)
            assertEquals(listOf(ID_2), inProgress)
            assertEquals(listOf(ID_3, ID_4), resolved)
        }

    @Test
    fun detailAndHistoryReturnSeededIncidentAscendingByChangedAt() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val detail = repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, ID_2)
            val history = repository.getHistory(FakeSeed.DEMO_EMPLOYEE_ID, ID_2)

            assertTrue(detail != null)
            assertEquals(IncidentStatus.IN_PROGRESS, detail?.status)
            assertEquals(2, detail?.history?.size)
            assertEquals(
                listOf(HISTORY_2_ENTRY_1, HISTORY_2_ENTRY_2),
                detail?.history?.map { it.id },
            )
            assertEquals(
                listOf(null, IncidentStatus.PENDING),
                detail?.history?.map { it.previousStatus },
            )
            assertEquals(
                listOf("2026-10-03T09:00:00Z", "2026-10-03T11:30:00Z"),
                detail?.history?.map { it.changedAt },
            )
            assertEquals(detail?.history, history)
        }

    @Test
    fun createIncidentForcesPendingWithDeterministicIdsAndInitialHistory() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val created =
                repository.createIncident(
                    FakeSeed.DEMO_EMPLOYEE_ID,
                    "Nueva incidencia de prueba",
                    "Descripcion de prueba",
                    IncidentCategory.SYSTEMS,
                    IncidentPriority.HIGH,
                )
            val second =
                repository.createIncident(
                    FakeSeed.DEMO_EMPLOYEE_ID,
                    "Segunda incidencia",
                    "Otra descripcion",
                    IncidentCategory.OTHER,
                    IncidentPriority.LOW,
                )

            assertEquals("fake-1", created.id)
            assertEquals(IncidentStatus.PENDING, created.status)
            assertEquals(FakeSeed.CREATED_AT_PLACEHOLDER, created.createdAt)
            assertEquals(FakeSeed.DEMO_EMPLOYEE_ID, created.ownerUserId)
            assertEquals(FakeSeed.DEMO_EMPLOYEE.email, created.ownerEmail)
            assertNull(created.resolvedAt)
            assertEquals(1, created.history.size)
            val creationEntry = created.history.single()
            assertEquals("fake-h1", creationEntry.id)
            assertNull(creationEntry.previousStatus)
            assertEquals(IncidentStatus.PENDING, creationEntry.newStatus)
            assertEquals("fake-2", second.id)
            assertEquals("fake-h2", second.history.single().id)
        }

    @Test
    fun createdIncidentSurfacesFirstInNewestFirstList() =
        runBlocking {
            val repository = FakeIncidentRepository()
            repository.createIncident(
                FakeSeed.DEMO_EMPLOYEE_ID,
                "Nueva incidencia de prueba",
                "Descripcion de prueba",
                IncidentCategory.EQUIPMENT,
                IncidentPriority.MEDIUM,
            )

            val incidents = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)

            assertEquals(
                listOf("fake-1", ID_1, ID_2, ID_3, ID_4, ID_5),
                incidents.map { it.id },
            )
            assertEquals(
                FakeSeed.CREATED_AT_PLACEHOLDER,
                incidents.first().createdAt,
            )
        }

    @Test
    fun createdIncidentIsOwnedByTheOwnerIdParam() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val created =
                repository.createIncident(
                    FakeSeed.FOREIGN_EMPLOYEE_ID,
                    "Incidencia ajena",
                    "Del empleado extranjero",
                    IncidentCategory.ACCESS,
                    IncidentPriority.LOW,
                )
            val demoIds = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null).map { it.id }
            val foreignIds =
                repository.listIncidents(FakeSeed.FOREIGN_EMPLOYEE_ID, null).map { it.id }

            assertEquals(FakeSeed.FOREIGN_EMPLOYEE_ID, created.ownerUserId)
            assertFalse(demoIds.contains(created.id))
            assertEquals(listOf("fake-1", ID_6), foreignIds)
        }

    @Test
    fun failureHookMakesEveryMethodThrowMockFailureException() {
        val repository = FakeIncidentRepository()
        repository.failureEnabled = true

        assertThrows(MockFailureException::class.java) {
            runBlocking {
                repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)
            }
        }
        assertThrows(MockFailureException::class.java) {
            runBlocking {
                repository.createIncident(
                    FakeSeed.DEMO_EMPLOYEE_ID,
                    "Titulo",
                    "Descripcion",
                    IncidentCategory.OTHER,
                    IncidentPriority.LOW,
                )
            }
        }
        assertThrows(MockFailureException::class.java) {
            runBlocking {
                repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, ID_1)
            }
        }
        assertThrows(MockFailureException::class.java) {
            runBlocking {
                repository.getHistory(FakeSeed.DEMO_EMPLOYEE_ID, ID_1)
            }
        }
    }

    @Test
    fun resetRestoresPristineSeedAndClearsTheHook() =
        runBlocking {
            val repository = FakeIncidentRepository()
            repository.createIncident(
                FakeSeed.DEMO_EMPLOYEE_ID,
                "Se perdera tras reset",
                "Descripcion",
                IncidentCategory.OTHER,
                IncidentPriority.LOW,
            )
            repository.failureEnabled = true

            repository.reset()

            assertFalse(repository.failureEnabled)
            assertEquals(
                listOf(ID_1, ID_2, ID_3, ID_4, ID_5),
                repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null).map { it.id },
            )
            val recreated =
                repository.createIncident(
                    FakeSeed.DEMO_EMPLOYEE_ID,
                    "Primera tras reset",
                    "Descripcion",
                    IncidentCategory.OTHER,
                    IncidentPriority.LOW,
                )
            assertEquals("fake-1", recreated.id)
        }

    private companion object {
        const val ID_1 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000001"
        const val ID_2 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000002"
        const val ID_3 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003"
        const val ID_4 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000004"
        const val ID_5 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000005"
        const val ID_6 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006"
        const val HISTORY_2_ENTRY_1 = "d1e70000-0000-4000-8000-000000000002"
        const val HISTORY_2_ENTRY_2 = "d1e70000-0000-4000-8000-000000000003"
    }
}
