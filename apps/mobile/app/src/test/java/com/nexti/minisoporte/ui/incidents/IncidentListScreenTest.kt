/*
 * Robolectric-backed Compose component tests for the S-02 incident-list
 * screen (US-007, testing.yaml critical_cases — "IncidentListScreen +
 * StatusFilterChips/IncidentCard/StatusBadge/ErrorRetryState with s02_* tags").
 * Renders the REAL IncidentListScreen on the real in-memory fakes
 * (FakeIncidentRepository + InMemoryTokenStore) — no mocks, no network; fully
 * offline inside ./gradlew check (CA-08). All lookups go through the s02_* test
 * tags (or exact Spanish texts); colors are NEVER asserted (testing.yaml
 * rules) — chip selection and the status-badge pairing are verified through
 * the rendered Spanish label/count text and the filter behaviour they drive.
 *
 * Dispatchers: createComposeRule() owns Dispatchers.Main
 * (UnconfinedTestDispatcher) and shares the test thread with Robolectric, so
 * the ViewModel's viewModelScope coroutines resolve eagerly — no
 * MainDispatcherRule here (combining the two starves the UI and freezes
 * recomposition) and no virtual-time advance. Every state transition is
 * flushed with composeTestRule.waitForIdle() — no sleeps.
 */
package com.nexti.minisoporte.ui.incidents

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nexti.minisoporte.data.fake.FakeIncidentRepository
import com.nexti.minisoporte.data.fake.FakeSeed
import com.nexti.minisoporte.security.InMemoryTokenStore
import com.nexti.minisoporte.ui.navigation.Destination
import com.nexti.minisoporte.ui.theme.MiniSoporteTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
// h1280dp: Robolectric's default window (320x470px) clips the five-card list
// below the fold, and even h960dp is not enough once the top bar, chip row
// and bottom bar take their share — LazyColumn only COMPOSES on-viewport
// items, so off-fold cards would be invisible to assertExists and injected
// touch clicks would silently MISS them; a tall viewport keeps chips, all
// five cards, FAB and Salir on-window.
@Config(sdk = [36], qualifiers = "h1280dp")
class IncidentListScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Content journey: the demo employee's FIVE owned incidents render
    // newest-first with title, pills, badge, 'Creada el <date>' caption —
    // and the foreign employee's incident is never visible (owner scoping).
    @Test
    fun ownedCardsRenderNewestFirstAndForeignIncidentIsInvisible() {
        setContentWith()

        composeTestRule.onNodeWithText(TITLE_1).assertExists()
        composeTestRule.onNodeWithText(TITLE_2).assertExists()
        composeTestRule.onNodeWithText(TITLE_3).assertExists()
        composeTestRule.onNodeWithText(TITLE_4).assertExists()
        composeTestRule.onNodeWithText(TITLE_5).assertExists()
        composeTestRule.onNodeWithText(CAPTION_1).assertExists()
        composeTestRule.onNodeWithText(CAPTION_2).assertExists()
        composeTestRule.onNodeWithText(CAPTION_3).assertExists()
        composeTestRule.onNodeWithText(CAPTION_4).assertExists()
        composeTestRule.onNodeWithText(CAPTION_5).assertExists()
        composeTestRule.onNodeWithTag(cardTag(ID_6)).assertDoesNotExist()

        // Newest-first: the card tags appear under the root in seed order
        // …0001..…0005 (LazyColumn children follow composition order).
        val cardTags =
            composeTestRule
                .onRoot()
                .fetchSemanticsNode()
                .collectTags()
                .filter { it.startsWith(TAG_CARD_PREFIX) }
        assertEquals(OWNED_TAGS_IN_ORDER, cardTags)
    }

    // CA-07: every card pairs the Spanish status label with its background —
    // asserted through the badge TEXT inside the card (never hex matching),
    // alongside the category and priority pill labels.
    @Test
    fun cardRendersCategoryPriorityPillsAndSpanishStatusBadgeLabel() {
        setContentWith()

        val card1Texts = cardTexts(ID_1)
        assertTrue(card1Texts.containsAll(listOf("Equipos", "Media", "Pendiente")))
        val card2Texts = cardTexts(ID_2)
        assertTrue(card2Texts.containsAll(listOf("Sistemas", "Alta", "En progreso")))
        val card3Texts = cardTexts(ID_3)
        assertTrue(card3Texts.containsAll(listOf("Acceso", "Baja", "Resuelta")))
    }

    // Journey 2 (list/filter half): the four chips show live counts
    // (Todas 5, Pendiente 2, En progreso 1, Resuelta 2); tapping Pendiente
    // narrows the list to the two pending incidents; tapping Todas restores
    // all five. The ACTIVE chip is verified behaviourally — the filter it
    // drives is applied — because testing.yaml forbids hex color assertions
    // on the dark-green selected fill.
    @Test
    fun pendienteChipFiltersListAndTodasRestoresItWithLiveCounts() {
        setContentWith()

        val todasTexts = chipTexts(TAG_CHIP_TODAS)
        assertTrue(todasTexts.containsAll(listOf(LABEL_TODAS, "5")))
        assertTrue(chipTexts(TAG_CHIP_PENDING).containsAll(listOf(LABEL_PENDIENTE, "2")))
        assertTrue(chipTexts(TAG_CHIP_IN_PROGRESS).containsAll(listOf(LABEL_EN_PROGRESO, "1")))
        assertTrue(chipTexts(TAG_CHIP_RESOLVED).containsAll(listOf(LABEL_RESUELTA, "2")))

        composeTestRule.onNodeWithTag(TAG_CHIP_PENDING).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(TITLE_1).assertExists()
        composeTestRule.onNodeWithText(TITLE_5).assertExists()
        composeTestRule.onNodeWithText(TITLE_2).assertDoesNotExist()
        composeTestRule.onNodeWithText(TITLE_3).assertDoesNotExist()
        composeTestRule.onNodeWithText(TITLE_4).assertDoesNotExist()
        // Counts stay live under the active filter.
        assertTrue(chipTexts(TAG_CHIP_PENDING).contains("2"))
        assertTrue(chipTexts(TAG_CHIP_RESOLVED).contains("2"))

        composeTestRule.onNodeWithTag(TAG_CHIP_TODAS).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(TITLE_1).assertExists()
        composeTestRule.onNodeWithText(TITLE_2).assertExists()
        composeTestRule.onNodeWithText(TITLE_3).assertExists()
        composeTestRule.onNodeWithText(TITLE_4).assertExists()
        composeTestRule.onNodeWithText(TITLE_5).assertExists()
        assertTrue(chipTexts(TAG_CHIP_TODAS).contains("5"))
    }

    // Empty state: a signed-in employee who owns no resolved incident sees
    // 'Sin incidencias' after tapping Resuelta — cards leave the list while
    // the chip counts keep reflecting the full owned set (1 pending, 0
    // resolved).
    @Test
    fun resueltaChipWithoutResolvedIncidentsShowsEmptyState() {
        setContentWith(ownerId = FakeSeed.FOREIGN_EMPLOYEE_ID)
        composeTestRule.onNodeWithText(TITLE_6).assertExists()

        composeTestRule.onNodeWithTag(TAG_CHIP_RESOLVED).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_EMPTY_STATE).assertExists()
        composeTestRule.onNodeWithText(EMPTY_STATE_LABEL).assertExists()
        composeTestRule.onNodeWithTag(cardTag(ID_6)).assertDoesNotExist()
        assertTrue(chipTexts(TAG_CHIP_TODAS).contains("1"))
        assertTrue(chipTexts(TAG_CHIP_RESOLVED).contains("0"))
    }

    // CONF-009 through the screen: a repository failure renders the Error
    // state with the generic message and the Reintentar button; after the
    // failure is cleared, tapping Reintentar reloads the owned list.
    @Test
    fun errorStateShowsRetryButtonAndRetryReloadsContent() {
        val repository = FakeIncidentRepository()
        repository.failureEnabled = true
        setContentWith(repository)

        composeTestRule.onNodeWithText(LOAD_ERROR).assertExists()
        composeTestRule.onNodeWithTag(TAG_RETRY).assertExists()
        composeTestRule.onNodeWithText(TITLE_1).assertDoesNotExist()

        repository.failureEnabled = false
        composeTestRule.onNodeWithTag(TAG_RETRY).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(TITLE_1).assertExists()
        composeTestRule.onNodeWithText(LOAD_ERROR).assertDoesNotExist()
    }

    // FAB tap fires the navigate-to-new-incident callback (S-03 route in
    // production wiring).
    @Test
    fun fabTapFiresNavigateToNewIncident() {
        var fabClicked = false
        setContentWith(onNavigateToNewIncident = { fabClicked = true })

        composeTestRule.onNodeWithTag(TAG_FAB).performClick()
        composeTestRule.waitForIdle()

        assertTrue(fabClicked)
    }

    // Card tap fires the navigate-to-detail callback with THAT incident's id
    // (S-04 route argument in production wiring).
    @Test
    fun cardTapFiresNavigateToDetailWithIncidentId() {
        var detailId: String? = null
        setContentWith(onNavigateToIncidentDetail = { id -> detailId = id })

        composeTestRule.onNodeWithTag(cardTag(ID_1)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(ID_1, detailId)
    }

    // Journey 5 through a REAL NavHost (production wiring shape): Salir shows
    // the system toast 'Sesión cerrada', deletes the session token, lands on
    // the S-01 destination — and the protected S-02 chrome (FAB) is no longer
    // reachable in the composition.
    @Test
    fun signOutShowsToastDeletesTokenAndNavigatesToSignIn() {
        val store = InMemoryTokenStore()
        runBlocking { store.save(SESSION_TOKEN) }
        var signedOut = false
        val viewModel =
            IncidentListViewModel(
                incidentRepository = FakeIncidentRepository(),
                tokenStore = store,
            )
        composeTestRule.setContent {
            MiniSoporteTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = Destination.Incidents.ROUTE,
                ) {
                    composable(Destination.Incidents.ROUTE) {
                        IncidentListScreen(
                            viewModel = viewModel,
                            onNavigateToSignIn = {
                                signedOut = true
                                navController.navigate(Destination.SignIn.ROUTE)
                            },
                        )
                    }
                    composable(Destination.SignIn.ROUTE) {
                        Text(text = S01_MARKER)
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SIGN_OUT).performClick()
        composeTestRule.waitForIdle()

        assertTrue(ShadowToast.showedToast(SIGN_OUT_TOAST))
        composeTestRule.onNodeWithText(S01_MARKER).assertExists()
        composeTestRule.onNodeWithTag(TAG_FAB).assertDoesNotExist()
        assertTrue(signedOut)
        runBlocking { assertNull(store.read()) }
    }

    /** Builds the screen under the app theme with the fakes-built ViewModel. */
    private fun setContentWith(
        repository: FakeIncidentRepository = FakeIncidentRepository(),
        ownerId: String = FakeSeed.DEMO_EMPLOYEE_ID,
        onNavigateToNewIncident: () -> Unit = {},
        onNavigateToIncidentDetail: (String) -> Unit = {},
        onNavigateToSignIn: () -> Unit = {},
    ): IncidentListViewModel {
        val viewModel = IncidentListViewModel(repository, InMemoryTokenStore(), ownerId)
        composeTestRule.setContent {
            MiniSoporteTheme {
                IncidentListScreen(
                    viewModel = viewModel,
                    onNavigateToNewIncident = onNavigateToNewIncident,
                    onNavigateToIncidentDetail = onNavigateToIncidentDetail,
                    onNavigateToSignIn = onNavigateToSignIn,
                )
            }
        }
        composeTestRule.waitForIdle()
        return viewModel
    }

    /** Full text content of one tagged incident card (merged Surface subtree). */
    private fun cardTexts(incidentId: String): List<String> =
        composeTestRule
            .onNodeWithTag(cardTag(incidentId))
            .fetchSemanticsNode()
            .collectTexts()

    /** Full text content of one tagged filter chip (label + live count). */
    private fun chipTexts(tag: String): List<String> =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .collectTexts()

    private companion object {
        const val ID_1 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000001"
        const val ID_2 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000002"
        const val ID_3 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003"
        const val ID_4 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000004"
        const val ID_5 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000005"
        const val ID_6 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006"

        const val TITLE_1 = "Impresora no responde"
        const val TITLE_2 = "VPN corporativa no conecta"
        const val TITLE_3 = "Credenciales de acceso bloqueadas"
        const val TITLE_4 = "Monitor parpadea intermitentemente"
        const val TITLE_5 = "Solicitud de software de diseño"
        const val TITLE_6 = "Cafetera de la zona común averiada"

        const val CAPTION_1 = "Creada el 2026-10-05T10:15:00Z"
        const val CAPTION_2 = "Creada el 2026-10-03T09:00:00Z"
        const val CAPTION_3 = "Creada el 2026-10-01T08:20:00Z"
        const val CAPTION_4 = "Creada el 2026-09-28T14:10:00Z"
        const val CAPTION_5 = "Creada el 2026-09-26T12:00:00Z"

        const val LABEL_TODAS = "Todas"
        const val LABEL_PENDIENTE = "Pendiente"
        const val LABEL_EN_PROGRESO = "En progreso"
        const val LABEL_RESUELTA = "Resuelta"

        const val EMPTY_STATE_LABEL = "Sin incidencias"
        const val LOAD_ERROR = "No se pudieron cargar las incidencias."
        const val SIGN_OUT_TOAST = "Sesión cerrada"
        const val SESSION_TOKEN = "fake-session-token-s02-test"

        const val TAG_CARD_PREFIX = "s02_incident_card_"
        const val TAG_CHIP_TODAS = "s02_filter_chip_todas"
        const val TAG_CHIP_PENDING = "s02_filter_chip_pendiente"
        const val TAG_CHIP_IN_PROGRESS = "s02_filter_chip_en_progreso"
        const val TAG_CHIP_RESOLVED = "s02_filter_chip_resuelta"
        const val TAG_FAB = "s02_fab"
        const val TAG_SIGN_OUT = "s02_sign_out"
        const val TAG_EMPTY_STATE = "s02_empty_state"
        const val TAG_RETRY = "s02_error_retry"

        val OWNED_IDS_IN_ORDER = listOf(ID_1, ID_2, ID_3, ID_4, ID_5)

        /** Full s02_incident_card_ tags of the owned rows, newest-first. */
        val OWNED_TAGS_IN_ORDER = OWNED_IDS_IN_ORDER.map { cardTag(it) }

        /** Marker text of the test-local S-01 destination — plain text, no tag. */
        const val S01_MARKER = "Iniciar sesión (S-01)"

        fun cardTag(incidentId: String) = TAG_CARD_PREFIX + incidentId
    }
}

/**
 * Depth-first collection of every Text value reachable from [this] node —
 * covers both shapes: a NON-merging tagged container (texts live on the child
 * nodes) and a merging clickable node like Card/chip (texts are copied into
 * the node's own merged config).
 */
private fun SemanticsNode.collectTexts(): List<String> {
    val ownTexts = config.getOrNull(SemanticsProperties.Text).orEmpty()
    val childTexts = children.flatMap { child -> child.collectTexts() }
    return ownTexts.map { annotated -> annotated.text } + childTexts
}

/** Depth-first collection of every test tag reachable from [this] node, in tree order. */
private fun SemanticsNode.collectTags(): List<String> {
    val ownTag = listOfNotNull(config.getOrNull(SemanticsProperties.TestTag))
    return ownTag + children.flatMap { child -> child.collectTags() }
}
