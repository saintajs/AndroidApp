/*
 * Robolectric-backed Compose component tests for the S-04 incident-detail
 * screen (US-009, frontend.yaml `verification` — "Compose s04_* tests —
 * detail card rows, badge text + color, filled dot on the first rendered row
 * via s04_timeline_item, no-access rendering"). Renders the REAL
 * IncidentDetailScreen on the real in-memory fakes (FakeIncidentRepository,
 * fakes-built IncidentDetailViewModel) — no mocks, no network; fully offline
 * inside ./gradlew check (CA-08). The screen's own LaunchedEffect(incidentId)
 * performs the load, exactly as in production.
 *
 * Badge COLOR note: the s04_status_value semantics node exposes only its
 * Text — background color does not exist in the Compose semantics tree and
 * pixel sampling is a Robolectric hack the project rules forbid, so the
 * badge is asserted through its rendered Spanish label "Resuelta". The
 * #DFEFE3 / #1E5B36 resolved pairing itself is pinned by ThemeTokensTest
 * (StatusResolvedBg / StatusResolvedFg constants) and StatusBadge pairs
 * label+color atomically (INSTR-012). The same applies to the timeline's
 * filled dot: its 'último estado' semantics suffix IS the accessible marker
 * of the newest-row highlight, so the highlight is asserted through the row
 * contentDescription — the semantics a TalkBack user actually gets.
 *
 * Dispatchers: createComposeRule() owns Dispatchers.Main
 * (UnconfinedTestDispatcher) and shares the test thread with Robolectric, so
 * the ViewModel's viewModelScope coroutines resolve eagerly — no
 * MainDispatcherRule here (combining the two starves the UI and freezes
 * recomposition). Every state transition is flushed with
 * composeTestRule.waitForIdle() — no sleeps.
 */
package com.nexti.minisoporte.ui.detail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nexti.minisoporte.data.fake.FakeIncidentRepository
import com.nexti.minisoporte.ui.theme.MiniSoporteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// h1280dp: Robolectric's default window (320x470px) clips the detail card
// below the fold and the detail + timeline column easily overflows even
// h960dp (IncidentListScreenTest precedent) — a tall viewport keeps the back
// action clickable on-window. Timeline rows need no viewport height (the
// card column is verticalScroll, so all rows stay composed regardless).
@Config(sdk = [36], qualifiers = "h1280dp")
class IncidentDetailScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Content state: the seeded owned incident …0003 (RESOLVED, three-entry
    // history) renders every detail card row — ID caption (raw uuid), title,
    // description, and the Categoría/Prioridad/Estado/Fecha de creación
    // values with their verbatim Spanish labels.
    @Test
    fun detailCardRowsRenderIdTitleDescriptionAndLabelValueRows() {
        setContentWith()

        assertEquals(listOf(ID_0003), taggedTexts(TAG_ID_CAPTION))
        assertEquals(listOf(TITLE_0003), taggedTexts(TAG_TITLE))
        assertEquals(listOf(DESCRIPTION_0003), taggedTexts(TAG_DESCRIPTION))
        assertEquals(listOf(LABEL_CATEGORY_VALUE), taggedTexts(TAG_CATEGORY_VALUE))
        assertEquals(listOf(LABEL_PRIORITY_VALUE), taggedTexts(TAG_PRIORITY_VALUE))
        assertEquals(listOf(LABEL_RESOLVED), taggedTexts(TAG_STATUS_VALUE))
        assertEquals(listOf(CREATED_AT_0003), taggedTexts(TAG_CREATED_AT_VALUE))
        composeTestRule.onNodeWithText(ROW_LABEL_CATEGORY).assertExists()
        composeTestRule.onNodeWithText(ROW_LABEL_PRIORITY).assertExists()
        composeTestRule.onNodeWithText(ROW_LABEL_STATUS).assertExists()
        composeTestRule.onNodeWithText(ROW_LABEL_CREATED_AT).assertExists()
    }

    // Status badge: the Estado row's badge (tag s04_status_value) shows the
    // Spanish label "Resuelta". Color pairing is NOT assertable through the
    // semantics tree (see file header) — pinned instead by ThemeTokensTest.
    @Test
    fun statusBadgeShowsResolvedLabel() {
        setContentWith()

        assertEquals(listOf(LABEL_RESOLVED), taggedTexts(TAG_STATUS_VALUE))
    }

    // Timeline: exactly three rows for …0003, newest-first, in composition
    // order; the FIRST rendered row is the newest entry — its TalkBack
    // description ends with ', último estado' (the filled-dot highlight
    // marker) and rows 2-3 do NOT carry the suffix.
    @Test
    fun timelineRendersThreeRowsNewestFirstWithHighlightOnFirstRow() {
        setContentWith()

        composeTestRule.onNodeWithTag(TAG_TIMELINE_HEADING).assertExists()
        val rows = timelineRows()
        assertEquals(3, rows.size)
        assertEquals(DESCRIPTION_NEWEST, rows[0].descriptionText())
        assertTrue(
            "the first rendered row must carry the 'último estado' highlight marker",
            rows[0].descriptionText().endsWith(NEWEST_SUFFIX),
        )
        assertFalse(rows[1].descriptionText().endsWith(NEWEST_SUFFIX))
        assertFalse(rows[2].descriptionText().endsWith(NEWEST_SUFFIX))
        assertEquals(DESCRIPTION_TRANSITION, rows[1].descriptionText())
    }

    // The creation entry renders LAST (newest-first reversal): its TalkBack
    // description opens with the em-dash 'Creación — Pendiente' status line.
    @Test
    fun creationEntryRendersLastWithCreacionPendienteLine() {
        setContentWith()

        val rows = timelineRows()
        assertTrue(
            "the last rendered row must be the creation entry",
            rows.last().descriptionText().startsWith(CREATION_LINE_PREFIX),
        )
        assertEquals(DESCRIPTION_CREATION, rows.last().descriptionText())
    }

    // Read-only timeline (BR-012): no timeline row carries a click action
    // and no edit affordance exists anywhere in the composition.
    @Test
    fun timelineIsReadOnlyWithoutAnyEditAffordance() {
        setContentWith()

        timelineRows().forEach { row ->
            assertNull("timeline rows are read-only — no OnClick action", row.config.getOrNull(SemanticsActions.OnClick))
        }
        composeTestRule.onNodeWithText(LABEL_EDIT, substring = true).assertDoesNotExist()
    }

    // NoAccess state, MISSING id (BR-009): the dashed no-access box renders
    // the exact Spanish copy with NO retry affordance — nothing to retry —
    // and no detail card content appears.
    @Test
    fun missingIncidentIdRendersNoAccessBoxWithoutRetry() {
        setContentWith(incidentId = ID_MISSING)

        composeTestRule.onNodeWithTag(TAG_NO_ACCESS).assertExists()
        composeTestRule.onNodeWithText(NO_ACCESS_TITLE).assertExists()
        composeTestRule.onNodeWithText(NO_ACCESS_HINT).assertExists()
        composeTestRule.onNodeWithText(LABEL_RETRY, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TAG_TITLE).assertDoesNotExist()
    }

    // NoAccess state, FOREIGN id …0006 (BR-010/BR-021): an incident that
    // EXISTS but belongs to another employee renders the byte-identical
    // safe box — same title, same hint, no distinguishing information.
    @Test
    fun foreignIncidentIdRendersIdenticalNoAccessBox() {
        setContentWith(incidentId = ID_0006)

        composeTestRule.onNodeWithTag(TAG_NO_ACCESS).assertExists()
        composeTestRule.onNodeWithText(NO_ACCESS_TITLE).assertExists()
        composeTestRule.onNodeWithText(NO_ACCESS_HINT).assertExists()
        composeTestRule.onNodeWithText(LABEL_RETRY, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(TITLE_0006).assertDoesNotExist()
    }

    // Back action: tapping the app bar's 'Mis incidencias' fires the
    // onBackToS02 callback (pop back to the S-02 route in production wiring).
    @Test
    fun backTapFiresOnBackToS02() {
        var backTapped = false
        setContentWith(onBackToS02 = { backTapped = true })

        composeTestRule.onNodeWithTag(TAG_BACK).performClick()
        composeTestRule.waitForIdle()

        assertTrue(backTapped)
    }

    /** Builds the screen under the app theme with the fakes-built ViewModel. */
    private fun setContentWith(
        incidentId: String = ID_0003,
        onBackToS02: () -> Unit = {},
    ) {
        val viewModel = IncidentDetailViewModel(FakeIncidentRepository())
        composeTestRule.setContent {
            MiniSoporteTheme {
                IncidentDetailScreen(
                    incidentId = incidentId,
                    viewModel = viewModel,
                    onBackToS02 = onBackToS02,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Every timeline row node, in composition order (newest-first). */
    private fun timelineRows(): List<SemanticsNode> =
        composeTestRule
            .onAllNodesWithTag(TAG_TIMELINE_ITEM)
            .fetchSemanticsNodes()

    /** Full text content of one tagged node (merged or child texts). */
    private fun taggedTexts(tag: String): List<String> =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .collectTexts()

    private companion object {
        const val ID_0003 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000003"
        const val ID_0006 = "1e6c9a2f-3b7d-4e8f-9c0a-000000000006"
        const val ID_MISSING = "missing-id"

        const val TITLE_0003 = "Credenciales de acceso bloqueadas"
        const val DESCRIPTION_0003 = "La cuenta quedó bloqueada tras varios intentos de inicio de sesión."
        const val CREATED_AT_0003 = "2026-10-01T08:20:00Z"
        const val TITLE_0006 = "Cafetera de la zona común averiada"

        const val LABEL_RESOLVED = "Resuelta"
        const val LABEL_CATEGORY_VALUE = "Acceso"
        const val LABEL_PRIORITY_VALUE = "Baja"

        const val ROW_LABEL_CATEGORY = "Categoría"
        const val ROW_LABEL_PRIORITY = "Prioridad"
        const val ROW_LABEL_STATUS = "Estado"
        const val ROW_LABEL_CREATED_AT = "Fecha de creación"

        const val NEWEST_SUFFIX = ", último estado"
        const val CREATION_LINE_PREFIX = "Creación — Pendiente"

        // TalkBack descriptions of …0003's rows (screen helper
        // timelineRowDescription: "<statusLine>, <changedAt>, <actor>"
        // plus ', último estado' on the newest entry only).
        const val DESCRIPTION_NEWEST = "En progreso → Resuelta, 2026-10-01T16:45:00Z, soporte@minisoporte.es, último estado"
        const val DESCRIPTION_TRANSITION = "Pendiente → En progreso, 2026-10-01T10:05:00Z, soporte@minisoporte.es"
        const val DESCRIPTION_CREATION = "Creación — Pendiente, 2026-10-01T08:20:00Z, empleado@minisoporte.es"

        const val NO_ACCESS_TITLE = "Incidencia no disponible"
        const val NO_ACCESS_HINT = "No se encontró la incidencia o no tienes acceso a ella."
        const val LABEL_RETRY = "Reintentar"
        const val LABEL_EDIT = "Editar"

        const val TAG_BACK = "s04_back"
        const val TAG_ID_CAPTION = "s04_id_caption"
        const val TAG_TITLE = "s04_title"
        const val TAG_DESCRIPTION = "s04_description"
        const val TAG_CATEGORY_VALUE = "s04_category_value"
        const val TAG_PRIORITY_VALUE = "s04_priority_value"
        const val TAG_STATUS_VALUE = "s04_status_value"
        const val TAG_CREATED_AT_VALUE = "s04_created_at_value"
        const val TAG_TIMELINE_HEADING = "s04_timeline_heading"
        const val TAG_TIMELINE_ITEM = "s04_timeline_item"
        const val TAG_NO_ACCESS = "s04_no_access"
    }
}

/**
 * Depth-first collection of every Text value reachable from [this] node —
 * covers both shapes: a NON-merging tagged container (texts live on the child
 * nodes) and a tagged Text node (text on the node's own merged config).
 */
private fun SemanticsNode.collectTexts(): List<String> {
    val ownTexts = config.getOrNull(SemanticsProperties.Text).orEmpty()
    val childTexts = children.flatMap { child -> child.collectTexts() }
    return ownTexts.map { annotated -> annotated.text } + childTexts
}

/** The row's single TalkBack contentDescription, or the empty string. */
private fun SemanticsNode.descriptionText(): String =
    config
        .getOrNull(SemanticsProperties.ContentDescription)
        .orEmpty()
        .joinToString(separator = "")
