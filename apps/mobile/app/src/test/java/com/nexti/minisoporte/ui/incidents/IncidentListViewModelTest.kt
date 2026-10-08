/*
 * Plain JVM unit tests for the S-02 incident-list ViewModel (US-007,
 * testing.yaml critical_cases). Drives the real in-memory fakes
 * (FakeIncidentRepository + InMemoryTokenStore) under virtual time — no
 * mocks, no Robolectric, no network. Asserts the Loading->Content mapping
 * for the five seeded owned incidents newest-first, chip filtering with
 * client-side counts recomputed on every load/filter/retry, the Empty phase
 * for owners with no matches, the CONF-009 error+retry cycle driven by the
 * fake's simulated-failure switch (flipped ONLY from tests), owner scoping
 * under every filter with multi-user seeds, the sign-out TokenStore deletion
 * with exactly one ShowToast + one NavigateToS01 event, and CONF-002 token
 * purity of every emitted UiState snapshot.
 */
package com.nexti.minisoporte.ui.incidents

import com.nexti.minisoporte.data.fake.FakeIncidentRepository
import com.nexti.minisoporte.data.fake.FakeSeed
import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.security.InMemoryTokenStore
import com.nexti.minisoporte.ui.signin.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncidentListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // load(): in-flight Loading, then Content with the five seeded owned
    // incidents newest-first, null filter, full counts, no foreign row.
    @Test
    fun loadShowsLoadingThenSeededOwnedContentNewestFirst() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val tokenStore = InMemoryTokenStore()
            val viewModel = IncidentListViewModel(repository, tokenStore)
            val states = mutableListOf<IncidentListViewModel.IncidentListUiState>()
            val events = mutableListOf<IncidentListViewModel.IncidentListEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.toList(states)
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            viewModel.load()
            assertEquals(
                "the list must show Loading while the query is in flight",
                IncidentListViewModel.ListPhase.Loading,
                viewModel.uiState.value.phase,
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Content, state.phase)
            assertNull("initial load must clear the filter", state.statusFilter)
            assertNull(state.error)
            assertEquals("owned seed rows, newest-first", EXPECTED_OWNED_IDS, state.incidents.map { it.id })
            assertEquals(EXPECTED_COUNTS, state.counts)
            for (incident in state.incidents) {
                assertSeedRowFields(incident)
            }
            assertTrue(
                "no foreign incident may ever appear",
                state.incidents.none { it.id == FOREIGN_INCIDENT_ID },
            )
            assertTrue(
                "no foreign title content may appear",
                state.incidents.none { it.title == FOREIGN_INCIDENT_TITLE },
            )
            assertTrue("a Loading snapshot was observed", states.any { it.phase == IncidentListViewModel.ListPhase.Loading })
            assertTrue("no event on a successful load", events.isEmpty())
        }

    // Pendiente chip: narrows to the two owned PENDING rows (newest-first)
    // while counts stay the FULL owned counts; Todas restores all five.
    @Test
    fun pendingFilterNarrowsToTwoPendingAndNullFilterRestoresAllFive() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel =
                IncidentListViewModel(FakeIncidentRepository(), InMemoryTokenStore())
            viewModel.load()
            advanceUntilIdle()

            viewModel.onFilterSelected(IncidentStatus.PENDING)
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Content, state.phase)
            assertEquals(IncidentStatus.PENDING, state.statusFilter)
            assertEquals(
                listOf(OWNED_ID_0001, OWNED_ID_0005),
                state.incidents.map { it.id },
            )
            assertEquals("counts stay the full owned counts under a filter", EXPECTED_COUNTS, state.counts)

            viewModel.onFilterSelected(null)
            advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Content, state.phase)
            assertNull(state.statusFilter)
            assertEquals(EXPECTED_OWNED_IDS, state.incidents.map { it.id })
            assertEquals(EXPECTED_COUNTS, state.counts)
        }

    // An owner with no seed rows at all: Empty phase, empty list, all-zero
    // counts, no error.
    @Test
    fun loadForOwnerWithoutSeedRowsShowsEmptyWithZeroCounts() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel =
                IncidentListViewModel(FakeIncidentRepository(), InMemoryTokenStore(), UNKNOWN_OWNER_ID)

            viewModel.load()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Empty, state.phase)
            assertTrue(state.incidents.isEmpty())
            assertEquals(
                mapOf(
                    null to 0,
                    IncidentStatus.PENDING to 0,
                    IncidentStatus.IN_PROGRESS to 0,
                    IncidentStatus.RESOLVED to 0,
                ),
                state.counts,
            )
            assertNull(state.error)
        }

    // Foreign owner + Resuelta chip: the owner's only row is PENDING, so the
    // filtered query is Empty while counts are still refreshed (1/1/0/0).
    @Test
    fun resolvedFilterForForeignOwnerShowsEmptyWithCountsStillRefreshed() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel =
                IncidentListViewModel(FakeIncidentRepository(), InMemoryTokenStore(), FakeSeed.FOREIGN_EMPLOYEE_ID)
            viewModel.load()
            advanceUntilIdle()
            assertEquals(IncidentListViewModel.ListPhase.Content, viewModel.uiState.value.phase)

            viewModel.onFilterSelected(IncidentStatus.RESOLVED)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Empty, state.phase)
            assertEquals(IncidentStatus.RESOLVED, state.statusFilter)
            assertTrue("a no-match filter yields an empty list", state.incidents.isEmpty())
            assertEquals(
                mapOf(
                    null to 1,
                    IncidentStatus.PENDING to 1,
                    IncidentStatus.IN_PROGRESS to 0,
                    IncidentStatus.RESOLVED to 0,
                ),
                state.counts,
            )
        }

    // CONF-009: a simulated seam failure maps to the Error phase with the
    // generic message and no stale rows; flipping the switch off and calling
    // retry() restores the full seeded Content without crashing.
    @Test
    fun simulatedFailureShowsErrorAndRetryRestoresSeededContent() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = IncidentListViewModel(repository, InMemoryTokenStore())
            repository.failureEnabled = true

            viewModel.load()
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Error, state.phase)
            assertEquals(LOAD_ERROR_MESSAGE, state.error)
            assertTrue("no stale rows survive a failure", state.incidents.isEmpty())
            assertTrue("no stale counts survive a failure", state.counts.isEmpty())

            repository.failureEnabled = false
            viewModel.retry()
            advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Content, state.phase)
            assertNull(state.error)
            assertEquals(EXPECTED_OWNED_IDS, state.incidents.map { it.id })
            assertEquals("retry recomputes the fresh seed counts", EXPECTED_COUNTS, state.counts)
        }

    // A failure during a FILTERED query also maps to Error; the recovery via
    // retry() re-runs the initial load with fresh counts (5/2/1/2).
    @Test
    fun failureDuringFilteredQueryErrorsAndRetryRecomputesFreshCounts() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = IncidentListViewModel(repository, InMemoryTokenStore())
            viewModel.load()
            advanceUntilIdle()
            viewModel.onFilterSelected(IncidentStatus.PENDING)
            advanceUntilIdle()
            assertEquals(IncidentListViewModel.ListPhase.Content, viewModel.uiState.value.phase)

            repository.failureEnabled = true
            viewModel.onFilterSelected(IncidentStatus.IN_PROGRESS)
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Error, state.phase)
            assertEquals(LOAD_ERROR_MESSAGE, state.error)
            assertTrue(state.incidents.isEmpty())
            assertTrue(state.counts.isEmpty())

            repository.failureEnabled = false
            viewModel.retry()
            advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals(IncidentListViewModel.ListPhase.Content, state.phase)
            assertEquals(EXPECTED_COUNTS, state.counts)
        }

    // Owner scoping under EVERY chip: across null/PENDING/IN_PROGRESS/
    // RESOLVED the foreign employee's row never appears.
    @Test
    fun noFilterValueEverSurfacesAnotherOwnersIncident() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel =
                IncidentListViewModel(FakeIncidentRepository(), InMemoryTokenStore())
            val filters = listOf<IncidentStatus?>(null, IncidentStatus.PENDING, IncidentStatus.IN_PROGRESS, IncidentStatus.RESOLVED)

            for (filter in filters) {
                viewModel.onFilterSelected(filter)
                advanceUntilIdle()

                val state = viewModel.uiState.value
                assertTrue(
                    "foreign owner row leaked under filter $filter",
                    state.incidents.none { it.ownerUserId == FakeSeed.FOREIGN_EMPLOYEE_ID },
                )
                assertTrue(
                    "foreign incident id leaked under filter $filter",
                    state.incidents.none { it.id == FOREIGN_INCIDENT_ID },
                )
                if (filter == null) {
                    assertEquals("Todas still shows only the five owned rows", EXPECTED_OWNED_IDS, state.incidents.map { it.id })
                }
            }
        }

    // Sign-out: the pre-saved token is deleted (nothing readable) and the
    // event sequence is exactly one ShowToast followed by one NavigateToS01.
    @Test
    fun signOutDeletesTokenAndEmitsToastThenNavigationExactlyOnce() =
        runTest(mainDispatcherRule.testDispatcher) {
            val tokenStore = InMemoryTokenStore()
            tokenStore.save(SESSION_TOKEN)
            val viewModel = IncidentListViewModel(FakeIncidentRepository(), tokenStore)
            val events = mutableListOf<IncidentListViewModel.IncidentListEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            viewModel.load()
            advanceUntilIdle()
            assertEquals(SESSION_TOKEN, tokenStore.read())

            viewModel.signOut()
            advanceUntilIdle()

            assertNull("sign-out must leave no readable token", tokenStore.read())
            assertEquals(
                "exactly one ShowToast then one NavigateToS01",
                listOf(
                    IncidentListViewModel.IncidentListEvent.ShowToast(SIGN_OUT_TOAST),
                    IncidentListViewModel.IncidentListEvent.NavigateToS01,
                ),
                events,
            )
        }

    // CONF-002: across load + filter + sign-out, no observed UiState snapshot
    // may carry the session token anywhere in its contents.
    @Test
    fun noUiStateSnapshotEverCarriesTokenMaterial() =
        runTest(mainDispatcherRule.testDispatcher) {
            val tokenStore = InMemoryTokenStore()
            val viewModel = IncidentListViewModel(FakeIncidentRepository(), tokenStore)
            val states = mutableListOf<IncidentListViewModel.IncidentListUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.toList(states)
            }
            tokenStore.save(SESSION_TOKEN)

            viewModel.load()
            advanceUntilIdle()
            viewModel.onFilterSelected(IncidentStatus.PENDING)
            advanceUntilIdle()
            assertEquals("the session token survives load + filter", SESSION_TOKEN, tokenStore.read())

            viewModel.signOut()
            advanceUntilIdle()
            assertNull("sign-out must leave no readable token behind", tokenStore.read())

            assertFalse(states.isEmpty())
            for (state in states) {
                val error = state.error
                if (error != null) {
                    assertFalse("UiState must never carry token material", error.contains(SESSION_TOKEN))
                }
                assertFalse(state.toString().contains(SESSION_TOKEN))
            }
        }

    /**
     * Asserts one seeded row's renderable fields — id, title, category,
     * priority, status and createdAt — against the fixed seed literals.
     */
    private fun assertSeedRowFields(incident: Incident) {
        when (incident.id) {
            OWNED_ID_0001 -> {
                assertEquals("Impresora no responde", incident.title)
                assertEquals(IncidentCategory.EQUIPMENT, incident.category)
                assertEquals(IncidentPriority.MEDIUM, incident.priority)
                assertEquals(IncidentStatus.PENDING, incident.status)
                assertEquals("2026-10-05T10:15:00Z", incident.createdAt)
            }
            OWNED_ID_0002 -> {
                assertEquals("VPN corporativa no conecta", incident.title)
                assertEquals(IncidentCategory.SYSTEMS, incident.category)
                assertEquals(IncidentPriority.HIGH, incident.priority)
                assertEquals(IncidentStatus.IN_PROGRESS, incident.status)
                assertEquals("2026-10-03T09:00:00Z", incident.createdAt)
            }
            OWNED_ID_0003 -> {
                assertEquals("Credenciales de acceso bloqueadas", incident.title)
                assertEquals(IncidentCategory.ACCESS, incident.category)
                assertEquals(IncidentPriority.LOW, incident.priority)
                assertEquals(IncidentStatus.RESOLVED, incident.status)
                assertEquals("2026-10-01T08:20:00Z", incident.createdAt)
            }
            OWNED_ID_0004 -> {
                assertEquals("Monitor parpadea intermitentemente", incident.title)
                assertEquals(IncidentCategory.EQUIPMENT, incident.category)
                assertEquals(IncidentPriority.MEDIUM, incident.priority)
                assertEquals(IncidentStatus.RESOLVED, incident.status)
                assertEquals("2026-09-28T14:10:00Z", incident.createdAt)
            }
            OWNED_ID_0005 -> {
                assertEquals("Solicitud de software de diseño", incident.title)
                assertEquals(IncidentCategory.OTHER, incident.category)
                assertEquals(IncidentPriority.LOW, incident.priority)
                assertEquals(IncidentStatus.PENDING, incident.status)
                assertEquals("2026-09-26T12:00:00Z", incident.createdAt)
            }
            else -> throw AssertionError("unexpected incident id in the owned list: ${incident.id}")
        }
    }

    private companion object {
        const val SESSION_TOKEN = "fake-session-token-admin"
        const val SIGN_OUT_TOAST = "Sesión cerrada"
        const val LOAD_ERROR_MESSAGE = "No se pudieron cargar las incidencias."
        const val UNKNOWN_OWNER_ID = "00000000-0000-0000-0000-00000000ffff"
        const val FOREIGN_INCIDENT_ID = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006"
        const val FOREIGN_INCIDENT_TITLE = "Cafetera de la zona común averiada"
        const val OWNED_ID_0001 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000001"
        const val OWNED_ID_0002 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000002"
        const val OWNED_ID_0003 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003"
        const val OWNED_ID_0004 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000004"
        const val OWNED_ID_0005 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000005"

        val EXPECTED_OWNED_IDS =
            listOf(
                OWNED_ID_0001,
                OWNED_ID_0002,
                OWNED_ID_0003,
                OWNED_ID_0004,
                OWNED_ID_0005,
            )

        val EXPECTED_COUNTS: Map<IncidentStatus?, Int> =
            mapOf(
                null to 5,
                IncidentStatus.PENDING to 2,
                IncidentStatus.IN_PROGRESS to 1,
                IncidentStatus.RESOLVED to 2,
            )
    }
}
