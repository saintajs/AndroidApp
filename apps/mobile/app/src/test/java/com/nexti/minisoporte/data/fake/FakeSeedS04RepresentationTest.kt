/*
 * Seed-representation tests for S-04 (CA-04, spec "Mock seed represents
 * ownership and filtering"): the FakeIncidentRepository seed must carry, from
 * mock data alone, everything the detail journey needs — incidents for more
 * than one mock user, all three statuses across the demo employee, multi-entry
 * and 3-entry histories stored ASCENDING by changedAt, exactly one creation
 * entry (previousStatus null) first in every history, and a foreign incident
 * that the demo employee cannot reach (the safe no-access path of S-04).
 *
 * Pure JVM, deterministic literals, one fresh repository per test — mirrors
 * FakeIncidentRepositoryTest (US-005) without weakening any of its assertions.
 */
package com.nexti.minisoporte.data.fake

import com.nexti.minisoporte.data.model.IncidentStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSeedS04RepresentationTest {
    @Test
    fun seedRepresentsMoreThanOneMockUser() =
        runTest {
            val repository = FakeIncidentRepository()

            val demo = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)
            val foreign = repository.listIncidents(FakeSeed.FOREIGN_EMPLOYEE_ID, null)

            assertTrue(demo.isNotEmpty())
            assertTrue(foreign.isNotEmpty())
            assertTrue(
                demo.all { it.ownerUserId == FakeSeed.DEMO_EMPLOYEE_ID },
            )
            assertTrue(
                foreign.all { it.ownerUserId == FakeSeed.FOREIGN_EMPLOYEE_ID },
            )
            val demoIds = demo.map { it.id }.toSet()
            val foreignIds = foreign.map { it.id }.toSet()
            assertTrue(demoIds.intersect(foreignIds).isEmpty())
        }

    @Test
    fun demoEmployeeSeedCoversAllThreeStatuses() =
        runTest {
            val repository = FakeIncidentRepository()

            val incidents = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)

            assertEquals(
                setOf(
                    IncidentStatus.PENDING,
                    IncidentStatus.IN_PROGRESS,
                    IncidentStatus.RESOLVED,
                ),
                incidents.map { it.status }.toSet(),
            )
        }

    @Test
    fun demoSeedHasMultiEntryAndThreeEntryHistoriesAscending() =
        runTest {
            val repository = FakeIncidentRepository()

            val incidents = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)
            val historySizes = incidents.map { it.history.size }

            assertTrue(historySizes.any { it >= MULTI_ENTRY_MIN })
            assertTrue(historySizes.any { it == THREE_ENTRY_SIZE })
            incidents.forEach { incident ->
                assertEquals(
                    incident.history.sortedBy { it.changedAt },
                    incident.history,
                )
            }
        }

    @Test
    fun foreignIncidentIsInvisibleToDemoOwnerFromMockDataAlone() =
        runTest {
            val repository = FakeIncidentRepository()

            assertNull(repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, FOREIGN_ID))
            assertTrue(
                repository.getHistory(FakeSeed.DEMO_EMPLOYEE_ID, FOREIGN_ID).isEmpty(),
            )

            val foreignView =
                repository.getIncident(FakeSeed.FOREIGN_EMPLOYEE_ID, FOREIGN_ID)
            assertNotNull(foreignView)
            assertEquals(FakeSeed.FOREIGN_EMPLOYEE_ID, foreignView?.ownerUserId)
            assertEquals(1, foreignView?.history?.size)
        }

    @Test
    fun everySeededHistoryHasExactlyOneCreationEntryAsOldestFirst() =
        runTest {
            val repository = FakeIncidentRepository()

            val demo = repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)
            val foreign = repository.listIncidents(FakeSeed.FOREIGN_EMPLOYEE_ID, null)
            val allSeeded = demo + foreign

            assertTrue(allSeeded.isNotEmpty())
            allSeeded.forEach { incident ->
                val history = repository.getHistory(incident.ownerUserId, incident.id)
                val creationEntries = history.filter { it.previousStatus == null }
                assertEquals(1, creationEntries.size)
                assertEquals(creationEntries.single().id, history.first().id)
                assertNull(history.first().previousStatus)
            }
        }

    private companion object {
        const val FOREIGN_ID = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006"
        const val MULTI_ENTRY_MIN = 2
        const val THREE_ENTRY_SIZE = 3
    }
}
