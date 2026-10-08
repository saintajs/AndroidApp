/*
 * S-04 incident-detail ViewModel (US-009, frontend.yaml `services.IncidentDetailViewModel`,
 * design.md Decisions 2 and 3).
 *
 * Loads the owned incident + its ASC-by-changedAt history through the
 * [IncidentRepository] seam (accepted unchanged, backed by the in-memory
 * FakeIncidentRepository — owner-scoped, zero network) and maps them into the
 * rendered UiState. The ascending history is EXPLICITLY reversed here into
 * newest-first [TimelineEntry] display values (Decision 3) — the composable
 * never reverses. Missing AND foreign ids collapse into the byte-identical
 * [IncidentDetailUiState.NoAccess] state (BR-009, BR-010, BR-021): the seam
 * returns null for both, and the ViewModel never distinguishes them.
 *
 * Failure mapping (CONF-009): ANY repository exception (e.g. the fake's
 * MockFailureException hook) is mapped to the same NoAccess state instead of
 * crashing or inventing a third error state — on this mock-only screen a
 * load failure is indistinguishable from a wrong id, and US-012 keeps the
 * same seam, so no information is lost.
 *
 * Mock purity (BR-041, BR-042): no network-stack imports — the real
 * GET /api/incidents/{id} contract belongs to US-012 — and no token material
 * ever enters UiState, events nor any log (CONF-002).
 */
package com.nexti.minisoporte.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexti.minisoporte.data.fake.FakeSeed
import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentHistoryEntry
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.repository.IncidentRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Display timeline entry — newest-first output of the ViewModel's explicit
 * reversal (design.md Decision 3).
 *
 * @property statusLine 'Creación — Pendiente' when the source entry was the
 *   creation transition, otherwise '<previous label> → <new label>' with the
 *   Spanish [IncidentStatus.label] values (em-dash U+2014 and ' → '
 *   separator verbatim).
 * @property changedAt the ISO-8601 timestamp from the seed entry, verbatim.
 * @property actor the mock actor attribution (the entry's changer email).
 * @property isNewest true ONLY on the first rendered (newest) entry — drives
 *   the filled dot on the timeline.
 * @property isCreation true when the source entry's previousStatus was null.
 */
data class TimelineEntry(
    val statusLine: String,
    val changedAt: String,
    val actor: String,
    val isNewest: Boolean,
    val isCreation: Boolean,
)

/**
 * Immutable UI state rendered by the incident-detail screen. Holds only
 * renderable data — never token material (CONF-002).
 *
 * [NoAccess] is a single value shared by missing AND foreign ids
 * (BR-009/BR-010/BR-021): nothing in the state may let a caller tell the
 * two apart.
 */
sealed interface IncidentDetailUiState {
    /** Transient initial state while the owner-scoped load is in flight. */
    data object Loading : IncidentDetailUiState

    /** The owned incident plus its newest-first timeline. */
    data class Content(
        val incident: Incident,
        val timeline: List<TimelineEntry>,
    ) : IncidentDetailUiState

    /** The single safe no-access state — identical for missing and foreign ids. */
    data object NoAccess : IncidentDetailUiState
}

/**
 * One-shot events: consumed exactly once by the screen inside
 * `LaunchedEffect(Unit)` — never re-delivered on recomposition.
 */
sealed interface IncidentDetailEvent {
    /** Pops back to the S-02 incident list ('Mis incidencias'). */
    data object NavigateBackToS02 : IncidentDetailEvent
}

/**
 * Pure mapping from the seam's ASC-by-changedAt history to the newest-first
 * display timeline (design.md Decision 3). The reversal is EXPLICIT
 * ([List.reversed]) so the newest-first rendering contract survives the
 * US-012 repository swap; [TimelineEntry.isNewest] is true only at index 0
 * (the first rendered entry) and [TimelineEntry.isCreation] when the source
 * entry's previousStatus was null.
 */
internal fun buildTimelineEntries(history: List<IncidentHistoryEntry>): List<TimelineEntry> =
    history
        .reversed()
        .mapIndexed { index, entry ->
            TimelineEntry(
                statusLine = statusLineOf(entry),
                changedAt = entry.changedAt,
                actor = entry.changedByEmail,
                isNewest = index == 0,
                isCreation = entry.previousStatus == null,
            )
        }

/** Prefix of the creation entry's status line (em-dash U+2014 verbatim). */
private const val CREATION_STATUS_LINE = "Creación — "

/** Separator between the previous and new status labels (verbatim). */
private const val STATUS_LINE_SEPARATOR = " → "

/** Builds the status line: creation entry vs status transition. */
private fun statusLineOf(entry: IncidentHistoryEntry): String {
    val previousStatus = entry.previousStatus
    return if (previousStatus == null) {
        CREATION_STATUS_LINE + entry.newStatus.label
    } else {
        previousStatus.label + STATUS_LINE_SEPARATOR + entry.newStatus.label
    }
}

class IncidentDetailViewModel(
    private val incidentRepository: IncidentRepository,
    private val ownerId: String = FakeSeed.DEMO_EMPLOYEE_ID,
) : ViewModel() {
    private val _uiState = MutableStateFlow<IncidentDetailUiState>(IncidentDetailUiState.Loading)
    val uiState: StateFlow<IncidentDetailUiState> = _uiState.asStateFlow()

    private val _events = Channel<IncidentDetailEvent>(Channel.BUFFERED)
    val events: Flow<IncidentDetailEvent> = _events.receiveAsFlow()

    /**
     * Loads the incident + history for [incidentId] through the seam. The
     * screen calls this from `LaunchedEffect(key = incidentId)`, so every
     * re-entry re-reads the in-memory store and reflects its current state.
     *
     * Mapping: flips to [IncidentDetailUiState.Loading] first, then a `null`
     * seam result (missing OR foreign id — the ViewModel can never tell them
     * apart, BR-009/BR-010/BR-021) resolves to the byte-identical
     * [IncidentDetailUiState.NoAccess]; a non-null incident re-reads the
     * ascending history and maps it via [buildTimelineEntries] into
     * [IncidentDetailUiState.Content]. ANY repository exception also resolves
     * to NoAccess — never rethrown, never a crash (CONF-009).
     */
    fun load(incidentId: String) {
        viewModelScope.launch {
            _uiState.value = IncidentDetailUiState.Loading
            try {
                val incident = incidentRepository.getIncident(ownerId, incidentId)
                if (incident == null) {
                    _uiState.value = IncidentDetailUiState.NoAccess
                } else {
                    val history = incidentRepository.getHistory(ownerId, incidentId)
                    _uiState.value =
                        IncidentDetailUiState.Content(
                            incident = incident,
                            timeline = buildTimelineEntries(history),
                        )
                }
            } catch (exception: Exception) {
                // Swallowed: a failed load renders the same safe no-access
                // state — never a crash (CONF-009).
                _uiState.value = IncidentDetailUiState.NoAccess
            }
        }
    }

    /** Emits the consumed [IncidentDetailEvent.NavigateBackToS02] one-shot. */
    fun onBackToMisIncidencias() {
        viewModelScope.launch {
            _events.send(IncidentDetailEvent.NavigateBackToS02)
        }
    }
}
