/*
 * S-03 incident-list ViewModel (US-002, design.md Decisions 2-4).
 *
 * Owner-scoped list over the [IncidentRepository] seam (accepted unchanged):
 * newest-first, no pagination. Filter chips (null = "Todas") re-query the
 * seam; status counts are derived CLIENT-SIDE from the full owned list on
 * every load/filter/retry. Every repository outcome maps to a phase —
 * a failure shows the Error phase with a generic message and never crashes
 * (CONF-009). Mock purity (BR-041): no network-stack imports, and token
 * material never enters UiState, event payloads nor any log (CONF-002).
 */
package com.nexti.minisoporte.ui.incidents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexti.minisoporte.data.fake.FakeSeed
import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.repository.IncidentRepository
import com.nexti.minisoporte.security.TokenStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class IncidentListViewModel(
    private val incidentRepository: IncidentRepository,
    private val tokenStore: TokenStore,
    private val ownerId: String = FakeSeed.DEMO_EMPLOYEE_ID,
) : ViewModel() {
    enum class ListPhase { Loading, Content, Empty, Error }

    /**
     * Immutable UI state rendered by the incident-list screen. Holds only
     * renderable data — never token material (CONF-002).
     */
    data class IncidentListUiState(
        val phase: ListPhase = ListPhase.Loading,
        val statusFilter: IncidentStatus? = null,
        val incidents: List<Incident> = emptyList(),
        val counts: Map<IncidentStatus?, Int> = emptyMap(),
        val error: String? = null,
    )

    /**
     * One-shot events: consumed exactly once by the screen inside
     * `LaunchedEffect(Unit)` — never re-delivered on recomposition.
     */
    sealed interface IncidentListEvent {
        data object NavigateToS01 : IncidentListEvent

        data class ShowToast(
            val message: String,
        ) : IncidentListEvent
    }

    private val _uiState = MutableStateFlow(IncidentListUiState())
    val uiState: StateFlow<IncidentListUiState> = _uiState.asStateFlow()

    private val _events = Channel<IncidentListEvent>(Channel.BUFFERED)
    val events: Flow<IncidentListEvent> = _events.receiveAsFlow()

    /**
     * Initial load: clears the filter (null = "Todas"), flips to
     * [ListPhase.Loading] while in flight, then maps the owner-scoped result
     * to Content/Empty. ANY repository exception resolves to the generic
     * [ListPhase.Error] — never rethrown, never a crash (CONF-009).
     */
    fun load() {
        viewModelScope.launch {
            loadList(filter = null)
        }
    }

    /**
     * Applies a status-filter chip and re-queries the seam with it. Shows
     * [ListPhase.Loading] while the query is in flight, then the resulting
     * phase; counts are recomputed on every call.
     */
    fun onFilterSelected(status: IncidentStatus?) {
        viewModelScope.launch {
            loadList(filter = status)
        }
    }

    /** Re-runs [load] after a failure. */
    fun retry() {
        load()
    }

    /**
     * Deletes the session token via the [TokenStore] seam and emits the
     * consumed one-shot events: a confirmation toast, then navigation back
     * to S-01. The token value itself is never read, logged nor exposed
     * (CONF-002) — only deleted.
     */
    fun signOut() {
        viewModelScope.launch {
            tokenStore.delete()
            _events.send(IncidentListEvent.ShowToast(SIGN_OUT_TOAST))
            _events.send(IncidentListEvent.NavigateToS01)
        }
    }

    /**
     * Drains any pending one-shot events. The screen consumes events by
     * collecting [events] (receiveAsFlow delivers each exactly once), so
     * this is only a belt-and-braces reset for re-entry paths.
     */
    fun consumeEvents() {
        while (_events.tryReceive().isSuccess) {
            // Intentionally empty: draining means the event is dropped.
        }
    }

    /**
     * Shared loader for [load] / [onFilterSelected]. Calls the seam twice:
     * once with [filter] for the rendered list and once with `null` for the
     * client-side counts (the `null` key's count = total owned incidents).
     * Phase mapping: in-flight → Loading; filtered result empty → Empty
     * (incidents empty, counts still refreshed); rows present → Content;
     * any exception → Error with the generic message and no stale rows.
     */
    private suspend fun loadList(filter: IncidentStatus?) {
        _uiState.update {
            it.copy(
                phase = ListPhase.Loading,
                statusFilter = filter,
                error = null,
            )
        }
        try {
            val filtered = incidentRepository.listIncidents(ownerId, filter)
            val all = incidentRepository.listIncidents(ownerId, null)
            val counts = buildCounts(all)
            val phase = if (filtered.isEmpty()) ListPhase.Empty else ListPhase.Content
            _uiState.update {
                it.copy(
                    phase = phase,
                    statusFilter = filter,
                    incidents = filtered,
                    counts = counts,
                    error = null,
                )
            }
        } catch (exception: Exception) {
            _uiState.update {
                it.copy(
                    phase = ListPhase.Error,
                    incidents = emptyList(),
                    counts = emptyMap(),
                    error = LOAD_ERROR,
                )
            }
        }
    }

    /**
     * Derives the per-status counts from the FULL owned list (queried with a
     * null filter), so chip badges stay correct under every active filter.
     * Key order: null ("Todas"), PENDING, IN_PROGRESS, RESOLVED.
     */
    private fun buildCounts(all: List<Incident>): Map<IncidentStatus?, Int> {
        val counts = mutableMapOf<IncidentStatus?, Int>()
        counts[null] = all.size
        counts[IncidentStatus.PENDING] = all.count { it.status == IncidentStatus.PENDING }
        counts[IncidentStatus.IN_PROGRESS] = all.count { it.status == IncidentStatus.IN_PROGRESS }
        counts[IncidentStatus.RESOLVED] = all.count { it.status == IncidentStatus.RESOLVED }
        return counts
    }

    private companion object {
        const val LOAD_ERROR = "No se pudieron cargar las incidencias."
        const val SIGN_OUT_TOAST = "Sesión cerrada"
    }
}
