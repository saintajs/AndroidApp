/*
 * Seed data test for S-04 (spec "Mock seed represents ownership and
 * filtering" — CA-04): the fixed US-005 mock dataset must represent the
 * behaviours the real system enforces, demonstrable from mock data alone —
 * incidents for more than one mock user, all three statuses, and multi-entry
 * status histories stored ascending by changed_at, so the no-access path for
 * a foreign id needs no backend, no network and no secrets.
 *
 * Every assertion reads the REAL seed through the repository's public API
 * (never fabricated test data) — pure JVM, no Robolectric, no Retrofit/OkHttp
 * symbols (BR-041). Journey 4 of the mandatory mock journeys holds on top of
 * these facts.
 */
package com.nexti.minisoporte.data.fake

import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.repository.IncidentRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeIncidentSeedDataTest {
    @Test
    fun seedRepresentsIncidentsForMoreThanOneMockUser() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val demoIncidents = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)
            val foreignIncidents = repository.listIncidents(FakeSeed.FOREIGN_EMPLOYEE_ID, null)
            val seedOwners = (demoIncidents + foreignIncidents).map { it.ownerUserId }.toSet()

            assertTrue(seedOwners.size > 1)
            assertEquals(
                setOf(FakeSeed.DEMO_EMPLOYEE_ID, FakeSeed.FOREIGN_EMPLOYEE_ID),
                seedOwners,
            )
            assertEquals(5, demoIncidents.size)
            assertTrue(demoIncidents.all { it.ownerUserId == FakeSeed.DEMO_EMPLOYEE_ID })
            assertEquals(1, foreignIncidents.size)
            assertEquals(
                FakeSeed.FOREIGN_EMPLOYEE_ID,
                foreignIncidents.single().ownerUserId,
            )
            assertEquals(
                FakeSeed.FOREIGN_EMPLOYEE.email,
                foreignIncidents.single().ownerEmail,
            )
        }

    @Test
    fun seedCoversAllThreeIncidentStatuses() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val seedIncidents =
                repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null) +
                    repository.listIncidents(FakeSeed.FOREIGN_EMPLOYEE_ID, null)

            assertEquals(
                IncidentStatus.entries.toSet(),
                seedIncidents.map { it.status }.toSet(),
            )
            for (status in IncidentStatus.entries) {
                assertTrue(
                    repository
                        .listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, status)
                        .isNotEmpty(),
                )
            }
        }

    @Test
    fun seededHistoriesAreStoredAscendingWithMultiEntryHistories() =
        runBlocking {
            val repository = FakeIncidentRepository()

            val seedIncidents =
                repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null) +
                    repository.listIncidents(FakeSeed.FOREIGN_EMPLOYEE_ID, null)

            val multiEntryHistories = seedIncidents.count { it.history.size > 1 }
            assertTrue(multiEntryHistories > 0)
            for (incident in seedIncidents) {
                val storedChangedAt = incident.history.map { it.changedAt }
                val historyIncidentIds = incident.history.map { it.incidentId }.distinct()
                assertEquals(incident.id, historyIncidentIds.single())
                assertEquals(storedChangedAt, storedChangedAt.sorted())
                for ((previous, current) in incident.history.zipWithNext()) {
                    assertTrue(previous.changedAt <= current.changedAt)
                    assertEquals(previous.newStatus, current.previousStatus)
                }
                assertEquals(
                    incident.history,
                    repository.getHistory(incident.ownerUserId, incident.id),
                )
            }
            assertEquals(
                3,
                seedIncidents.first { it.id == MULTI_HISTORY_ID }.history.size,
            )
        }

    @Test
    fun foreignIncidentReadByDemoEmployeeIsNoAccessFromSeedAlone() =
        runBlocking {
            val repository = FakeIncidentRepository()

            assertNotNull(repository.getIncident(FakeSeed.FOREIGN_EMPLOYEE_ID, FOREIGN_ID))

            assertNull(repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, FOREIGN_ID))
            assertTrue(
                repository.getHistory(FakeSeed.DEMO_EMPLOYEE_ID, FOREIGN_ID).isEmpty(),
            )
            assertNull(repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, MISSING_ID))
            assertTrue(
                repository.getHistory(FakeSeed.DEMO_EMPLOYEE_ID, MISSING_ID).isEmpty(),
            )
        }

    @Test
    fun repositoryApiExposesNoHistoryMutationPath() {
        val publicApi =
            IncidentRepository::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertEquals(
            setOf("listIncidents", "createIncident", "getIncident", "getHistory"),
            publicApi,
        )
    }

    private companion object {
        const val FOREIGN_ID = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006"
        const val MULTI_HISTORY_ID = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003"
        const val MISSING_ID = "no-such-id"
    }
}
