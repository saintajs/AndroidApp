/*
 * Plain JVM unit tests for the S-04 incident-detail ViewModel (US-009,
 * testing.yaml critical_cases). Drives the real in-memory
 * FakeIncidentRepository under virtual time — no mocks, no Robolectric, no
 * network. Asserts the explicit newest-first reversal of the ascending
 * seeded history (two-entry …0002 and three-entry …0003), the
 * 'Creación — Pendiente' semantics for previousStatus-null entries versus
 * '<previous label> → <new label>' transition lines, isNewest on the first
 * rendered entry only, the byte-identical NoAccess state shared by missing
 * AND foreign ids (BR-009/BR-010/BR-021) so the Content branch is never
 * reachable for another user's incident, re-load reflecting the fake's
 * current in-memory state across reset(), the pure buildTimelineEntries
 * reversal contract pinned independently of the seed, and exactly one
 * NavigateBackToS02 event from onBackToMisIncidencias().
 */
package com.nexti.minisoporte.ui.detail

import com.nexti.minisoporte.data.fake.FakeIncidentRepository
import com.nexti.minisoporte.data.fake.FakeSeed
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentHistoryEntry
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.ui.signin.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncidentDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // The two-entry seeded history of …0002 (ASC: creation 09:00, transition
    // 11:30) renders newest-first: timeline[0] is the support agent's
    // Pendiente → En progreso transition, timeline[1] the creation entry.
    @Test
    fun loadSeededTwoEntryHistoryRendersTimelineNewestFirst() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = IncidentDetailViewModel(FakeIncidentRepository())

            viewModel.load(OWNED_ID_0002)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("an owned id must resolve to Content", state is IncidentDetailUiState.Content)
            val content = state as IncidentDetailUiState.Content
            assertEquals(OWNED_ID_0002, content.incident.id)
            assertEquals(2, content.timeline.size)
            val newest = content.timeline[0]
            assertEquals("Pendiente → En progreso", newest.statusLine)
            assertEquals(FakeSeed.SUPPORT_USER.email, newest.actor)
            assertEquals("2026-10-03T11:30:00Z", newest.changedAt)
            val oldest = content.timeline[1]
            assertEquals(CREATION_STATUS_LINE_PENDING, oldest.statusLine)
            assertEquals(FakeSeed.DEMO_EMPLOYEE.email, oldest.actor)
            assertEquals("2026-10-03T09:00:00Z", oldest.changedAt)
        }

    // The three-entry seeded history of …0003 (ASC: 08:20 creation, 10:05
    // and 16:45 transitions) renders newest-first end to end.
    @Test
    fun loadSeededThreeEntryHistoryRendersTimelineNewestFirst() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = IncidentDetailViewModel(FakeIncidentRepository())

            viewModel.load(OWNED_ID_0003)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("an owned id must resolve to Content", state is IncidentDetailUiState.Content)
            val content = state as IncidentDetailUiState.Content
            val lines = content.timeline.map { it.statusLine }
            assertEquals(
                listOf(
                    "En progreso → Resuelta",
                    "Pendiente → En progreso",
                    CREATION_STATUS_LINE_PENDING,
                ),
                lines,
            )
            assertEquals(
                "changedAt renders newest-first too",
                listOf("2026-10-01T16:45:00Z", "2026-10-01T10:05:00Z", "2026-10-01T08:20:00Z"),
                content.timeline.map { it.changedAt },
            )
            assertEquals(
                "every rendered entry attributes the support agent's transitions",
                listOf(
                    FakeSeed.SUPPORT_USER.email,
                    FakeSeed.SUPPORT_USER.email,
                    FakeSeed.DEMO_EMPLOYEE.email,
                ),
                content.timeline.map { it.actor },
            )
        }

    // 'Creación — Pendiente' semantics: entries whose previousStatus was
    // null render the em-dash creation line with isCreation true; transition
    // entries render '<previous label> → <new label>' with isCreation false.
    @Test
    fun creationEntryRendersCreacionPendienteAndOnlyCreationEntriesFlagIsCreation() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = IncidentDetailViewModel(FakeIncidentRepository())

            viewModel.load(OWNED_ID_0003)
            advanceUntilIdle()

            val content = viewModel.uiState.value as IncidentDetailUiState.Content
            val creationEntry = content.timeline[2]
            assertEquals("Creación — Pendiente", creationEntry.statusLine)
            assertTrue("previousStatus null means the creation entry", creationEntry.isCreation)
            val transitionEntries = content.timeline.subList(0, 2)
            for (entry in transitionEntries) {
                assertFalse("a transition entry is never a creation", entry.isCreation)
                assertTrue(
                    "a transition line joins the two Spanish labels with ' → '",
                    entry.statusLine.contains(" → "),
                )
            }
        }

    // isNewest is true on the FIRST rendered (newest) entry only — indices
    // 1 and 2 of the three-entry timeline are plain dots.
    @Test
    fun isNewestIsTrueOnlyOnTheFirstRenderedEntry() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = IncidentDetailViewModel(FakeIncidentRepository())

            viewModel.load(OWNED_ID_0003)
            advanceUntilIdle()

            val content = viewModel.uiState.value as IncidentDetailUiState.Content
            assertTrue("only the newest entry gets the filled dot", content.timeline[0].isNewest)
            assertFalse(content.timeline[1].isNewest)
            assertFalse(content.timeline[2].isNewest)
        }

    // BR-009/BR-010/BR-021: a missing id and a foreign (other user's) id
    // collapse into the byte-identical NoAccess state — the ViewModel can
    // never distinguish the two.
    @Test
    fun missingAndForeignIncidentIdsProduceIdenticalNoAccessState() =
        runTest(mainDispatcherRule.testDispatcher) {
            val missingViewModel = IncidentDetailViewModel(FakeIncidentRepository())
            val foreignViewModel =
                IncidentDetailViewModel(FakeIncidentRepository())

            missingViewModel.load(MISSING_INCIDENT_ID)
            advanceUntilIdle()
            foreignViewModel.load(FOREIGN_INCIDENT_ID)
            advanceUntilIdle()

            assertEquals(IncidentDetailUiState.NoAccess, missingViewModel.uiState.value)
            assertEquals(IncidentDetailUiState.NoAccess, foreignViewModel.uiState.value)
            assertEquals(
                "missing and foreign must be byte-identical",
                missingViewModel.uiState.value,
                foreignViewModel.uiState.value,
            )
        }

    // Owner-scoped read: loading the foreign cafetera incident as the demo
    // employee never reaches the Content branch — no incident object, no
    // timeline, no foreign title can ever render.
    @Test
    fun ownerScopedLoadNeverRendersAnotherUsersIncident() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = IncidentDetailViewModel(FakeIncidentRepository())

            viewModel.load(FOREIGN_INCIDENT_ID)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(IncidentDetailUiState.NoAccess, state)
            when (state) {
                is IncidentDetailUiState.Content -> {
                    throw AssertionError("the foreign cafetera incident must never render")
                }
                IncidentDetailUiState.Loading -> {
                    throw AssertionError("the load must complete under virtual time")
                }
                IncidentDetailUiState.NoAccess -> {
                    assertTrue("the expected safe no-access state", true)
                }
            }
        }

    // Every load() re-reads the in-memory store: a just-created incident
    // renders Content with its creation timeline, but after the fake's
    // reset() the same id resolves to NoAccess.
    @Test
    fun reloadReflectsCurrentFakeState() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = IncidentDetailViewModel(repository)
            val created =
                repository.createIncident(
                    FakeSeed.DEMO_EMPLOYEE_ID,
                    CREATED_TITLE,
                    CREATED_DESCRIPTION,
                    IncidentCategory.OTHER,
                    IncidentPriority.LOW,
                )

            viewModel.load(created.id)
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertTrue("the created id must render Content", state is IncidentDetailUiState.Content)
            var content = state as IncidentDetailUiState.Content
            assertEquals(1, content.timeline.size)
            assertEquals(CREATION_STATUS_LINE_PENDING, content.timeline[0].statusLine)
            assertTrue(content.timeline[0].isCreation)
            assertTrue(content.timeline[0].isNewest)
            assertEquals(FakeSeed.CREATED_AT_PLACEHOLDER, content.timeline[0].changedAt)
            assertEquals(FakeSeed.DEMO_EMPLOYEE.email, content.timeline[0].actor)

            repository.reset()
            viewModel.load(created.id)
            advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals("after reset the fake no longer holds the id", IncidentDetailUiState.NoAccess, state)
        }

    // The pure mapping, pinned independently of the seed: a hand-built ASC
    // history reverses to newest-first with exact status lines and
    // isNewest only at index 0.
    @Test
    fun buildTimelineEntriesReversesAscendingHistoryIndependentOfSeed() {
        val ascendingHistory =
            listOf(
                IncidentHistoryEntry(
                    id = "h-1",
                    incidentId = "incident-1",
                    previousStatus = null,
                    newStatus = IncidentStatus.PENDING,
                    changedByUserId = FakeSeed.DEMO_EMPLOYEE_ID,
                    changedByEmail = FakeSeed.DEMO_EMPLOYEE.email,
                    changedAt = "2026-11-01T08:00:00Z",
                    comment = null,
                ),
                IncidentHistoryEntry(
                    id = "h-2",
                    incidentId = "incident-1",
                    previousStatus = IncidentStatus.PENDING,
                    newStatus = IncidentStatus.IN_PROGRESS,
                    changedByUserId = FakeSeed.SUPPORT_USER_ID,
                    changedByEmail = FakeSeed.SUPPORT_USER.email,
                    changedAt = "2026-11-01T09:30:00Z",
                    comment = null,
                ),
                IncidentHistoryEntry(
                    id = "h-3",
                    incidentId = "incident-1",
                    previousStatus = IncidentStatus.IN_PROGRESS,
                    newStatus = IncidentStatus.RESOLVED,
                    changedByUserId = FakeSeed.SUPPORT_USER_ID,
                    changedByEmail = FakeSeed.SUPPORT_USER.email,
                    changedAt = "2026-11-01T12:00:00Z",
                    comment = null,
                ),
            )

        val timeline = buildTimelineEntries(ascendingHistory)

        assertEquals(3, timeline.size)
        assertEquals(
            listOf(
                "En progreso → Resuelta",
                "Pendiente → En progreso",
                "Creación — Pendiente",
            ),
            timeline.map { it.statusLine },
        )
        assertEquals(
            listOf("2026-11-01T12:00:00Z", "2026-11-01T09:30:00Z", "2026-11-01T08:00:00Z"),
            timeline.map { it.changedAt },
        )
        assertEquals(
            listOf(FakeSeed.SUPPORT_USER.email, FakeSeed.SUPPORT_USER.email, FakeSeed.DEMO_EMPLOYEE.email),
            timeline.map { it.actor },
        )
        assertTrue(timeline[0].isNewest)
        assertFalse(timeline[1].isNewest)
        assertFalse(timeline[2].isNewest)
        assertFalse(timeline[0].isCreation)
        assertFalse(timeline[1].isCreation)
        assertTrue(timeline[2].isCreation)
    }

    // onBackToMisIncidencias() emits exactly one NavigateBackToS02, consumed
    // through the events flow.
    @Test
    fun onBackToMisIncidenciasEmitsExactlyOneNavigateBackToS02Event() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = IncidentDetailViewModel(FakeIncidentRepository())
            val events = mutableListOf<IncidentDetailEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            viewModel.onBackToMisIncidencias()
            advanceUntilIdle()

            assertEquals(
                "exactly one NavigateBackToS02 event",
                listOf(IncidentDetailEvent.NavigateBackToS02),
                events,
            )
        }

    private companion object {
        const val OWNED_ID_0002 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000002"
        const val OWNED_ID_0003 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003"
        const val FOREIGN_INCIDENT_ID = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006"
        const val MISSING_INCIDENT_ID = "does-not-exist"
        const val CREATION_STATUS_LINE_PENDING = "Creación — Pendiente"
        const val CREATED_TITLE = "Impresora averiada en la planta primera"
        const val CREATED_DESCRIPTION = "El equipo no arranca desde esta mañana en la oficina de soporte."
    }
}
