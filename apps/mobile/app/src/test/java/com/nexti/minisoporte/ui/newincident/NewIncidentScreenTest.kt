/*
 * Robolectric-backed Compose component tests for the S-03 new-incident
 * screen (US-008, testing.yaml critical_cases — "NewIncidentScreen
 * composables with s03_* tags"). Renders the REAL NewIncidentScreen on the
 * real in-memory fake (FakeIncidentRepository) — no mocks, no network; fully
 * offline inside ./gradlew check (CA-07). All lookups go through the s03_*
 * test tags; colors are NEVER asserted (testing.yaml rules) — choice-option
 * selection is verified through the behaviour it drives (the group's error
 * clears, the created incident carries the last-tapped value).
 *
 * Dispatchers: createComposeRule() owns Dispatchers.Main
 * (UnconfinedTestDispatcher) and shares the test thread with Robolectric, so
 * the ViewModel's viewModelScope coroutines resolve eagerly — no
 * MainDispatcherRule here (combining the two starves the UI and freezes
 * recomposition) and no virtual-time advance. The only non-eager suspension
 * is the CA-04 submit lock, observed by gating the fake's createIncident on a
 * CompletableDeferred (GatedIncidentRepository below — the GatedTokenStore
 * pattern from SignInScreenTest). Every state transition is flushed with
 * composeTestRule.waitForIdle() — no sleeps.
 */
package com.nexti.minisoporte.ui.newincident

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.nexti.minisoporte.data.fake.FakeIncidentRepository
import com.nexti.minisoporte.data.fake.FakeSeed
import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentHistoryEntry
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.repository.IncidentRepository
import com.nexti.minisoporte.ui.theme.MiniSoporteTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
// h1280dp: the S-03 form is tall — top bar, Título field + counter, 4-option
// Categoría row, 3-option Prioridad row, Descripción field + counter, hint
// box and the Guardar incidencia button. The screen's Column scrolls
// (verticalScroll) so off-fold nodes are still COMPOSED, but injected touch
// clicks would silently MISS a below-fold button (onClick never fires, no
// error — KNOWLEDGE mobile.compose-test); a tall viewport keeps the whole
// form, including s03_submit, on-window.
@Config(sdk = [36], qualifiers = "h1280dp")
class NewIncidentScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Counters: they render "0 / 100" and "0 / 1000" on the pristine form
    // and track every keystroke with no debounce (testing.yaml critical
    // case 1) — 7 typed title chars -> "7 / 100", 12 typed description
    // chars -> "12 / 1000". The counter tag sits ON its Text node, so the
    // text is read from the node's own semantics config.
    @Test
    fun countersStartAtZeroAndUpdatePerKeystrokeWithoutDebounce() {
        setContentWith()

        assertEquals(COUNTER_EMPTY_TITLE, counterText(TAG_COUNTER_TITLE))
        assertEquals(COUNTER_EMPTY_DESCRIPTION, counterText(TAG_COUNTER_DESCRIPTION))

        composeTestRule.onNodeWithTag(TAG_TITLE_INPUT).performTextInput(TITLE_7)
        composeTestRule.waitForIdle()
        assertEquals(COUNTER_TITLE_7, counterText(TAG_COUNTER_TITLE))

        composeTestRule.onNodeWithTag(TAG_DESCRIPTION_INPUT).performTextInput(DESCRIPTION_12)
        composeTestRule.waitForIdle()
        assertEquals(COUNTER_DESCRIPTION_12, counterText(TAG_COUNTER_DESCRIPTION))
    }

    // Invalid submit from the pristine form: all four Spanish error nodes
    // appear (every field offends), the submit button stays enabled for
    // retry with its normal label, and the fake's owned list is untouched —
    // the repository is never called on an invalid submit (INSTR-011).
    @Test
    fun invalidEmptySubmitFlagsAllFourFieldsAndNeverTouchesTheRepository() {
        val repository = FakeIncidentRepository()
        setContentWith(repository = repository)

        composeTestRule.onNodeWithTag(TAG_SUBMIT).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_ERROR_TITLE).assertExists()
        composeTestRule.onNodeWithTag(TAG_ERROR_DESCRIPTION).assertExists()
        composeTestRule.onNodeWithTag(TAG_ERROR_CATEGORY).assertExists()
        composeTestRule.onNodeWithTag(TAG_ERROR_PRIORITY).assertExists()
        composeTestRule.onNodeWithTag(TAG_SUBMIT).assertIsEnabled()
        assertTrue(submitTexts().contains(LABEL_SUBMIT))
        assertEquals(SEED_SIZE, ownedIncidents(repository).size)
    }

    // Errors render ONLY next to offending fields (testing.yaml critical
    // case 2): a 4-char title + a valid description submit flags the title,
    // category and priority groups — but NOT the description, whose content
    // is inside the BR-014 range — and the fake's list is unchanged.
    @Test
    fun invalidSubmitShowsErrorsOnlyNextToOffendingFields() {
        val repository = FakeIncidentRepository()
        setContentWith(repository = repository)
        composeTestRule.onNodeWithTag(TAG_TITLE_INPUT).performTextInput(TITLE_TOO_SHORT)
        composeTestRule.onNodeWithTag(TAG_DESCRIPTION_INPUT).performTextInput(VALID_DESCRIPTION)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SUBMIT).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_ERROR_TITLE).assertExists()
        composeTestRule.onNodeWithTag(TAG_ERROR_DESCRIPTION).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TAG_ERROR_CATEGORY).assertExists()
        composeTestRule.onNodeWithTag(TAG_ERROR_PRIORITY).assertExists()
        assertEquals(SEED_SIZE, ownedIncidents(repository).size)
    }

    // Selecting a choice option clears ONLY its own group's error
    // (BR-015/BR-016): tapping a Categoría option removes s03_error_category
    // while s03_error_priority stays, then tapping a Prioridad option
    // removes s03_error_priority — and nothing was created (the form was
    // never validly submitted).
    @Test
    fun categoryAndPriorityOptionsClearOnlyTheirOwnGroupError() {
        val repository = FakeIncidentRepository()
        setContentWith(repository = repository)
        composeTestRule.onNodeWithTag(TAG_TITLE_INPUT).performTextInput(VALID_TITLE)
        composeTestRule.onNodeWithTag(TAG_DESCRIPTION_INPUT).performTextInput(VALID_DESCRIPTION)
        composeTestRule.onNodeWithTag(TAG_SUBMIT).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_ERROR_CATEGORY).assertExists()
        composeTestRule.onNodeWithTag(TAG_ERROR_PRIORITY).assertExists()
        composeTestRule.onNodeWithTag(TAG_ERROR_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TAG_ERROR_DESCRIPTION).assertDoesNotExist()

        composeTestRule.onNodeWithTag(TAG_CATEGORY_OPTION_SYSTEMS).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_ERROR_CATEGORY).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TAG_ERROR_PRIORITY).assertExists()

        composeTestRule.onNodeWithTag(TAG_PRIORITY_OPTION_MEDIUM).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_ERROR_PRIORITY).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TAG_ERROR_CATEGORY).assertDoesNotExist()
        assertEquals(SEED_SIZE, ownedIncidents(repository).size)
    }

    // Single-select behaviour pinned on the OUTCOME (colors are never
    // asserted — testing.yaml rules): within a group the last-tapped option
    // wins, so after systems->access and medium->high the created incident
    // carries ACCESS/HIGH.
    @Test
    fun singleSelectLastTappedOptionWinsInCreatedIncident() {
        val repository = FakeIncidentRepository()
        var navigated = false
        setContentWith(repository = repository, onNavigateBackToS02 = { navigated = true })
        fillValidForm()
        composeTestRule.onNodeWithTag(TAG_CATEGORY_OPTION_ACCESS).performClick()
        composeTestRule.onNodeWithTag(TAG_PRIORITY_OPTION_HIGH).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SUBMIT).performClick()
        composeTestRule.waitForIdle()

        assertTrue(navigated)
        val created = ownedIncidents(repository).first()
        assertEquals(CREATED_ID, created.id)
        assertEquals(IncidentCategory.ACCESS, created.category)
        assertEquals(IncidentPriority.HIGH, created.priority)
    }

    // CA-04 duplicate-submission lock through the screen: with the fake's
    // createIncident gated mid-flight the button is DISABLED and relabelled
    // 'Guardando…'; a repeated tap on the locked button is a no-op; once the
    // gate releases, the resolution completes and EXACTLY one incident was
    // created (owned list +1), with the navigation callback fired once.
    @Test
    fun submitLocksLabelGuardandoWhileInFlightAndRepeatedTapCreatesExactlyOne() {
        val delegate = FakeIncidentRepository()
        val gated = GatedIncidentRepository(delegate)
        var navigated = false
        setContentWith(repository = gated, onNavigateBackToS02 = { navigated = true })
        fillValidForm()

        composeTestRule.onNodeWithTag(TAG_SUBMIT).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SUBMIT).assertIsNotEnabled()
        assertTrue(submitTexts().contains(LABEL_GUARDANDO))
        assertEquals(SEED_SIZE, ownedIncidents(delegate).size)
        assertFalse(navigated)

        // Repeated tap on the locked button: no second creation.
        composeTestRule.onNodeWithTag(TAG_SUBMIT).performClick()
        composeTestRule.waitForIdle()
        assertEquals(SEED_SIZE, ownedIncidents(delegate).size)

        gated.release()
        composeTestRule.waitForIdle()

        assertTrue(navigated)
        val incidents = ownedIncidents(delegate)
        assertEquals(SEED_SIZE + 1, incidents.size)
        assertEquals(CREATED_ID, incidents.first().id)
    }

    // Journey 3 (mandatory mock journey) at unit level: fill Título, pick
    // Categoría and Prioridad, fill Descripción, tap Guardar incidencia —
    // the S-03 exit lambda fires, the Spanish confirmation toast posts, and
    // the fake stores the new PENDIENTE incident first (createdAt == the
    // fixed placeholder, newer than every seed row). The S-02 screen itself
    // is US-007's — asserted here through the fake's newest-first ordering.
    @Test
    fun validSubmitShowsToastNavigatesBackAndNewPendienteIncidentIsFirst() {
        val repository = FakeIncidentRepository()
        var navigated = false
        setContentWith(repository = repository, onNavigateBackToS02 = { navigated = true })
        composeTestRule.onNodeWithTag(TAG_PENDIENTE_HINT).assertExists()
        fillValidForm()

        composeTestRule.onNodeWithTag(TAG_SUBMIT).performClick()
        composeTestRule.waitForIdle()

        assertTrue(navigated)
        assertTrue(ShadowToast.showedToast(SUCCESS_TOAST))
        val incidents = ownedIncidents(repository)
        assertEquals(SEED_SIZE + 1, incidents.size)
        val created = incidents.first()
        assertEquals(CREATED_ID, created.id)
        assertEquals(IncidentStatus.PENDING, created.status)
        assertEquals(FakeSeed.CREATED_AT_PLACEHOLDER, created.createdAt)
        assertEquals(FakeSeed.DEMO_EMPLOYEE_ID, created.ownerUserId)
    }

    /** Builds the screen under the app theme with the fakes-built ViewModel. */
    private fun setContentWith(
        repository: IncidentRepository = FakeIncidentRepository(),
        onNavigateBackToS02: () -> Unit = {},
    ) {
        val viewModel = NewIncidentViewModel(repository)
        composeTestRule.setContent {
            MiniSoporteTheme {
                NewIncidentScreen(
                    viewModel = viewModel,
                    onNavigateBackToS02 = onNavigateBackToS02,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Fills the four form inputs with a fully valid S-03 submission. */
    private fun fillValidForm() {
        composeTestRule.onNodeWithTag(TAG_TITLE_INPUT).performTextInput(VALID_TITLE)
        composeTestRule.onNodeWithTag(TAG_DESCRIPTION_INPUT).performTextInput(VALID_DESCRIPTION)
        composeTestRule.onNodeWithTag(TAG_CATEGORY_OPTION_SYSTEMS).performClick()
        composeTestRule.onNodeWithTag(TAG_PRIORITY_OPTION_MEDIUM).performClick()
        composeTestRule.waitForIdle()
    }

    /** Text of one tagged counter — the tag sits ON its own Text node. */
    private fun counterText(tag: String): String {
        val texts =
            composeTestRule
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
        return texts.single().text
    }

    /** Full text content of the tagged submit button (merged Button subtree). */
    private fun submitTexts(): List<String> =
        composeTestRule
            .onNodeWithTag(TAG_SUBMIT)
            .fetchSemanticsNode()
            .collectTexts()

    /** The demo employee's owned incidents straight from the fake (newest first). */
    private fun ownedIncidents(repository: IncidentRepository): List<Incident> =
        runBlocking {
            repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null)
        }

    private companion object {
        const val SEED_SIZE = 5
        const val CREATED_ID = "fake-1"

        const val TITLE_7 = "Teclado"
        const val DESCRIPTION_12 = "Teclado roto"
        const val TITLE_TOO_SHORT = "Pana"
        const val VALID_TITLE = "Impresora averiada"
        const val VALID_DESCRIPTION = "La impresora no responde desde ayer por la tarde."

        const val COUNTER_EMPTY_TITLE = "0 / 100"
        const val COUNTER_EMPTY_DESCRIPTION = "0 / 1000"
        const val COUNTER_TITLE_7 = "7 / 100"
        const val COUNTER_DESCRIPTION_12 = "12 / 1000"

        const val LABEL_SUBMIT = "Guardar incidencia"
        const val LABEL_GUARDANDO = "Guardando…"
        const val SUCCESS_TOAST = "Incidencia creada correctamente"

        const val TAG_TITLE_INPUT = "s03_title_input"
        const val TAG_DESCRIPTION_INPUT = "s03_description_input"
        const val TAG_COUNTER_TITLE = "s03_counter_title"
        const val TAG_COUNTER_DESCRIPTION = "s03_counter_description"
        const val TAG_ERROR_TITLE = "s03_error_title"
        const val TAG_ERROR_DESCRIPTION = "s03_error_description"
        const val TAG_ERROR_CATEGORY = "s03_error_category"
        const val TAG_ERROR_PRIORITY = "s03_error_priority"
        const val TAG_CATEGORY_OPTION_SYSTEMS = "s03_category_option_systems"
        const val TAG_CATEGORY_OPTION_ACCESS = "s03_category_option_access"
        const val TAG_PRIORITY_OPTION_MEDIUM = "s03_priority_option_medium"
        const val TAG_PRIORITY_OPTION_HIGH = "s03_priority_option_high"
        const val TAG_SUBMIT = "s03_submit"
        const val TAG_PENDIENTE_HINT = "s03_pendiente_hint"
    }
}

/**
 * Depth-first collection of every Text value reachable from [this] node —
 * covers both shapes: a NON-merging tagged container (texts live on the child
 * nodes) and a merging clickable node like Button/option (texts are copied
 * into the node's own merged config).
 */
private fun SemanticsNode.collectTexts(): List<String> {
    val ownTexts = config.getOrNull(SemanticsProperties.Text).orEmpty()
    val childTexts = children.flatMap { child -> child.collectTexts() }
    return ownTexts.map { annotated -> annotated.text } + childTexts
}

/**
 * IncidentRepository decorator whose createIncident suspends until released:
 * gates the fake mid-flight so the CA-04 duplicate-submission lock
 * (isSubmitting) is observable in the Compose tree. Otherwise a transparent
 * pass-through to the real [FakeIncidentRepository].
 */
private class GatedIncidentRepository(
    private val delegate: FakeIncidentRepository,
) : IncidentRepository {
    private val gate = CompletableDeferred<Unit>()

    override suspend fun listIncidents(
        ownerId: String,
        status: IncidentStatus?,
    ): List<Incident> = delegate.listIncidents(ownerId, status)

    override suspend fun createIncident(
        ownerId: String,
        title: String,
        description: String,
        category: IncidentCategory,
        priority: IncidentPriority,
    ): Incident {
        gate.await()
        return delegate.createIncident(
            ownerId = ownerId,
            title = title,
            description = description,
            category = category,
            priority = priority,
        )
    }

    override suspend fun getIncident(
        ownerId: String,
        incidentId: String,
    ): Incident? = delegate.getIncident(ownerId, incidentId)

    override suspend fun getHistory(
        ownerId: String,
        incidentId: String,
    ): List<IncidentHistoryEntry> = delegate.getHistory(ownerId, incidentId)

    /** Resumes the gated create — completion becomes deterministic. */
    fun release() {
        gate.complete(Unit)
    }
}
