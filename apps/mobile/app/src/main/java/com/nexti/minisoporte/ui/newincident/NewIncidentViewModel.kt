/*
 * S-03 new-incident ViewModel (US-008, design.md Decisions 1-5).
 *
 * Owns the local validation of the creation form (BR-013..016), the live
 * character-counter inputs, the duplicate-submission lock and the submission
 * through the [IncidentRepository] seam (accepted unchanged, backed by the
 * in-memory FakeIncidentRepository). The ViewModel never sends status or
 * owner fields in the create call (INSTR-002, BR-011) — the fake assigns
 * Pendiente, the fresh mock id and the initial history entry. Debounce-free
 * per-field validation runs live ONLY once the field has been touched (first
 * change or a submit attempt) so mid-typing states never flash errors
 * (design.md Risk 3). Mock purity (BR-041): no network-stack imports, and no
 * token material ever enters UiState, events nor any log (CONF-002).
 */
package com.nexti.minisoporte.ui.newincident

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexti.minisoporte.data.fake.FakeSeed
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.repository.IncidentRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NewIncidentViewModel(
    private val incidentRepository: IncidentRepository,
    private val ownerId: String = FakeSeed.DEMO_EMPLOYEE_ID,
) : ViewModel() {
    /**
     * Immutable UI state rendered by the new-incident screen. Counters are
     * derived in the UI from [title]/[description] length — never stored.
     */
    data class NewIncidentUiState(
        val title: String = "",
        val description: String = "",
        val category: IncidentCategory? = null,
        val priority: IncidentPriority? = null,
        val titleError: String? = null,
        val descriptionError: String? = null,
        val categoryError: String? = null,
        val priorityError: String? = null,
        val isSubmitting: Boolean = false,
    )

    /**
     * One-shot events: consumed exactly once by the screen inside
     * `LaunchedEffect(Unit)` — never re-delivered on recomposition.
     */
    sealed interface NewIncidentEvent {
        data object NavigateBackToS02 : NewIncidentEvent
    }

    private val _uiState = MutableStateFlow(NewIncidentUiState())
    val uiState: StateFlow<NewIncidentUiState> = _uiState.asStateFlow()

    private val _events = Channel<NewIncidentEvent>(Channel.BUFFERED)
    val events: Flow<NewIncidentEvent> = _events.receiveAsFlow()

    // Text fields re-validate live only after being touched (first change
    // or a submit attempt). Main-thread only — no cross-coroutine sync needed.
    private var titleTouched: Boolean = false
    private var descriptionTouched: Boolean = false

    /**
     * Updates the typed title (the live counter derives from its length) and
     * re-validates the field live — the field is touched by this very change,
     * so its error clears the moment the value becomes valid and reappears
     * the moment it leaves the BR-013 range.
     */
    fun onTitleChange(title: String) {
        titleTouched = true
        _uiState.update {
            it.copy(
                title = title,
                titleError = titleErrorOf(title),
            )
        }
    }

    /**
     * Updates the typed description (the live counter derives from its
     * length) and re-validates the field live, same touch rule as
     * [onTitleChange] against the BR-014 range.
     */
    fun onDescriptionChange(description: String) {
        descriptionTouched = true
        _uiState.update {
            it.copy(
                description = description,
                descriptionError = descriptionErrorOf(description),
            )
        }
    }

    /** Selects the category and clears ONLY the category group's error (BR-015). */
    fun onCategorySelect(category: IncidentCategory) {
        _uiState.update { it.copy(category = category, categoryError = null) }
    }

    /** Selects the priority and clears ONLY the priority group's error (BR-016). */
    fun onPrioritySelect(priority: IncidentPriority) {
        _uiState.update { it.copy(priority = priority, priorityError = null) }
    }

    /**
     * The duplicate-submission lock first: when [NewIncidentUiState.isSubmitting]
     * is already true the tap is ignored, so repeated taps reach the
     * repository exactly once. Then full validation: on ANY failure only the
     * offending per-field Spanish errors are set and the repository is never
     * called (INSTR-011, BR-018). On success the lock flips, the create runs
     * in [viewModelScope] with ONLY title/description/category/priority plus
     * the owner id (never status — INSTR-002/BR-011), and the consumed
     * [NewIncidentEvent.NavigateBackToS02] is emitted. The lock holds only
     * until the creation resolves — it releases on BOTH outcomes (any
     * repository exception, e.g. the fake's
     * [com.nexti.minisoporte.data.fake.MockFailureException] hook, is
     * swallowed — never a crash (CONF-009)).
     */
    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting) return

        // A submit attempt touches both text fields: from now on every
        // keystroke re-validates its field live.
        titleTouched = true
        descriptionTouched = true

        val titleError = titleErrorOf(current.title)
        val descriptionError = descriptionErrorOf(current.description)
        val categoryError = CATEGORY_ERROR.takeIf { current.category == null }
        val priorityError = PRIORITY_ERROR.takeIf { current.priority == null }
        val hasErrors =
            titleError != null ||
                descriptionError != null ||
                categoryError != null ||
                priorityError != null
        if (hasErrors) {
            _uiState.update {
                it.copy(
                    titleError = titleError,
                    descriptionError = descriptionError,
                    categoryError = categoryError,
                    priorityError = priorityError,
                )
            }
            return
        }

        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                incidentRepository.createIncident(
                    ownerId = ownerId,
                    title = current.title,
                    description = current.description,
                    category = requireNotNull(current.category),
                    priority = requireNotNull(current.priority),
                )
                _events.send(NewIncidentEvent.NavigateBackToS02)
            } catch (exception: Exception) {
                // Swallowed: never a crash (CONF-009).
            } finally {
                // The lock holds only until the creation resolves — success
                // or failure — so a retry is possible once it completes.
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    /**
     * Drains any pending one-shot navigation event. The screen consumes
     * events by collecting [events] (receiveAsFlow delivers each exactly
     * once), so this is only a belt-and-braces reset for re-entry paths.
     */
    fun consumeNavigationEvent() {
        while (_events.tryReceive().isSuccess) {
            // Intentionally empty: draining means the event is dropped.
        }
    }

    /** BR-013: the title must be 5..100 characters once trimmed. */
    private fun titleErrorOf(title: String): String? = TITLE_ERROR.takeIf { title.trim().length !in TITLE_MIN_LENGTH..TITLE_MAX_LENGTH }

    /** BR-014: the description must be 10..1000 characters once trimmed. */
    private fun descriptionErrorOf(description: String): String? =
        DESCRIPTION_ERROR.takeIf {
            description.trim().length !in DESCRIPTION_MIN_LENGTH..DESCRIPTION_MAX_LENGTH
        }

    private companion object {
        const val TITLE_MIN_LENGTH = 5
        const val TITLE_MAX_LENGTH = 100
        const val DESCRIPTION_MIN_LENGTH = 10
        const val DESCRIPTION_MAX_LENGTH = 1000
        const val TITLE_ERROR = "El título debe tener entre 5 y 100 caracteres"
        const val DESCRIPTION_ERROR = "La descripción debe tener entre 10 y 1000 caracteres"
        const val CATEGORY_ERROR = "Selecciona una categoría"
        const val PRIORITY_ERROR = "Selecciona una prioridad"
    }
}
