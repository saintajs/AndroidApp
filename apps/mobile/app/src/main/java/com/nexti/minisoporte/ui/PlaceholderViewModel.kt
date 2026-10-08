package com.nexti.minisoporte.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * REFERENCE PATTERN for S-01..S-04 screen ViewModels (CA-10, design.md Decision 6) —
 * a deliberate, compilable demo with no screen content. Screen stories DELETE or
 * replace this class; until then it exists so the conventions are copied, not
 * reinvented:
 *
 * 1. State: a single immutable `UiState` data class exposed as
 *    `val uiState: StateFlow<Out>` built from `private val _uiState =
 *    MutableStateFlow(...)` + `_uiState.asStateFlow()`, mutated ONLY through
 *    `_uiState.update { it.copy(...) }` (never from outside the ViewModel).
 * 2. Collection: screens read it with `uiState.collectAsStateWithLifecycle()`.
 * 3. One-shot events (navigation, snackbars, toasts) NEVER live in `uiState`: they
 *    travel on `Channel(BUFFERED).receiveAsFlow()` and are consumed exactly once by
 *    the screen inside `LaunchedEffect(Unit)` (consume-on-collect).
 * 4. Failure resolves to an error UiState with a retry entry point (`errorMessage` +
 *    `retry()`) — never an unhandled exception or crash (CONF-009).
 * 5. Any real data access is a suspend function call at the repository boundary,
 *    launched in `viewModelScope` — shown here as the simulated `retry()` load.
 */
class PlaceholderViewModel : ViewModel() {
    /**
     * Immutable UI state rendered by [PlaceholderScreen].
     */
    data class PlaceholderUiState(
        val isLoading: Boolean = false,
        val counter: Int = 0,
        val errorMessage: String? = null,
    )

    /**
     * One-shot events: consumed once, never re-delivered after process death or
     * recomposition. Screen stories model their real events here (e.g. NavigateTo).
     */
    sealed interface PlaceholderEvent {
        data class CounterReached(
            val value: Int,
        ) : PlaceholderEvent
    }

    private val _uiState = MutableStateFlow(PlaceholderUiState())
    val uiState: StateFlow<PlaceholderUiState> = _uiState.asStateFlow()

    private val _events = Channel<PlaceholderEvent>(Channel.BUFFERED)
    val events: Flow<PlaceholderEvent> = _events.receiveAsFlow()

    /**
     * State mutation via `update { copy }` plus a one-shot event emission when the
     * counter reaches a milestone — the exact shape screen stories copy.
     */
    fun incrementCounter() {
        _uiState.update { it.copy(counter = it.counter + 1, errorMessage = null) }
        val current = _uiState.value.counter
        if (current % MILESTONE_STEP == 0) {
            _events.trySend(PlaceholderEvent.CounterReached(current))
        }
    }

    /**
     * Failure-path demo: the error-UiState state (CONF-009). Screen stories set
     * `errorMessage` from a caught repository failure instead.
     */
    fun simulateFailure() {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = SIMULATED_ERROR_MESSAGE,
            )
        }
    }

    /**
     * Retry demo: clears the error, flips `isLoading`, and performs the (simulated)
     * suspend repository call inside `viewModelScope`, then resolves back to idle.
     */
    fun retry() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            // Real screens call a suspend function on the repository boundary here.
            delay(RETRY_DELAY_MS)
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private companion object {
        const val MILESTONE_STEP = 3
        const val SIMULATED_ERROR_MESSAGE = "Error simulado (demo)"
        const val RETRY_DELAY_MS = 250L
    }
}
