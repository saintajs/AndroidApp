/*
 * S-03 Nueva incidencia screen (US-008, frontend.yaml `components`).
 *
 * Stateless, state-hoisted UI: collects [NewIncidentViewModel.uiState] via
 * `collectAsStateWithLifecycle()` and consumes the one-shot
 * [NewIncidentViewModel.NewIncidentEvent.NavigateBackToS02] inside
 * `LaunchedEffect(Unit)` — the SignInScreen reference pattern (CA-10) —
 * showing the confirmation toast over S-02 after the back navigation.
 * Renders the primary app bar with the Volver button, the form card
 * (Título with live counter, Categoría group, Prioridad group, Descripción
 * with live counter, Pendiente hint) and the Guardar incidencia button.
 * Theme tokens only (INSTR-012); no network-stack imports (BR-041); token
 * material never appears here (CONF-002).
 */
package com.nexti.minisoporte.ui.newincident

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.ui.theme.Spacing

// --- Spec constants (INSTR-012 allow-list: 1px hairline borders, 48dp touch
// targets, 40dp app-bar button, choice-option 10px/14px padding, Volver rgba
// alphas). All other values come from the theme. ---

/** 1px hairline borders (form card, option outlines, Pendiente hint). */
private val Hairline = 1.dp

/** Minimum interactive touch target (BR-036; supersedes the 40dp visual spec). */
private val MinTouchTarget = 48.dp

/** Minimum height of the Volver app-bar button (frontend.yaml app_bar spec). */
private val AppBarButtonMinHeight = 40.dp

/** Choice-option horizontal padding (frontend.yaml choice_group_option spec). */
private val OptionPaddingHorizontal = 14.dp

/** Choice-option vertical padding (frontend.yaml choice_group_option spec). */
private val OptionPaddingVertical = 10.dp

/** Volver app-bar button background alpha — rgba(255,255,255,0.12). */
private const val BACK_BG_ALPHA = 0.12f

/** Volver app-bar button border alpha — rgba(255,255,255,0.25). */
private const val BACK_BORDER_ALPHA = 0.25f

// --- ui_copy (frontend.yaml, verbatim; error strings live in the ViewModel
// and are rendered straight from UiState) ---

private const val SCREEN_TITLE = "Nueva incidencia"
private const val BACK_LABEL = "Volver"
private const val TITLE_LABEL = "Título"
private const val TITLE_PLACEHOLDER = "Describe brevemente el problema"
private const val DESCRIPTION_LABEL = "Descripción"
private const val DESCRIPTION_PLACEHOLDER = "Explica el problema con el mayor detalle posible"
private const val PENDIENTE_HINT = "La incidencia se creará en estado Pendiente"
private const val SUBMIT_LABEL = "Guardar incidencia"
private const val SUBMIT_LABEL_IN_FLIGHT = "Guardando…"
private const val SUCCESS_TOAST = "Incidencia creada correctamente"
private const val TITLE_COUNTER_MAX = 100
private const val DESCRIPTION_COUNTER_MAX = 1000

// --- Test tags (frontend.yaml rule: s<screen>_<element>) ---

private const val TAG_BACK = "s03_back"
private const val TAG_TITLE_INPUT = "s03_title_input"
private const val TAG_COUNTER_TITLE = "s03_counter_title"
private const val TAG_ERROR_TITLE = "s03_error_title"
private const val TAG_CATEGORY_OPTION_PREFIX = "s03_category_option_"
private const val TAG_ERROR_CATEGORY = "s03_error_category"
private const val TAG_PRIORITY_OPTION_PREFIX = "s03_priority_option_"
private const val TAG_ERROR_PRIORITY = "s03_error_priority"
private const val TAG_DESCRIPTION_INPUT = "s03_description_input"
private const val TAG_COUNTER_DESCRIPTION = "s03_counter_description"
private const val TAG_ERROR_DESCRIPTION = "s03_error_description"
private const val TAG_PENDIENTE_HINT = "s03_pendiente_hint"
private const val TAG_SUBMIT = "s03_submit"

/** One selectable option of a single-select choice group. */
private data class ChoiceOptionData<T>(
    val value: T,
    val label: String,
    val tag: String,
)

private val CategoryOptions: List<ChoiceOptionData<IncidentCategory>> =
    IncidentCategory.entries.map { category ->
        ChoiceOptionData(
            value = category,
            label = category.label,
            tag = TAG_CATEGORY_OPTION_PREFIX + category.wire,
        )
    }

private val PriorityOptions: List<ChoiceOptionData<IncidentPriority>> =
    IncidentPriority.entries.map { priority ->
        ChoiceOptionData(
            value = priority,
            label = priority.label,
            tag = TAG_PRIORITY_OPTION_PREFIX + priority.wire,
        )
    }

/**
 * S-03 destination composable. Tests pass a fakes-built
 * [NewIncidentViewModel] (no default factory, by design); production wires
 * the screen through AppNavHost, which supplies [onNavigateBackToS02] to pop
 * back to the existing S-02 destination after a successful creation.
 */
@Composable
fun NewIncidentScreen(
    viewModel: NewIncidentViewModel,
    onNavigateBackToS02: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // One-shot events: consumed exactly once, here — never stored in uiState.
    // The toast is shown after the back navigation so it renders over S-02.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                NewIncidentViewModel.NewIncidentEvent.NavigateBackToS02 -> {
                    onNavigateBackToS02()
                    Toast.makeText(context, SUCCESS_TOAST, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()),
    ) {
        NewIncidentTopBar(onBack = onNavigateBackToS02)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.space2, vertical = Spacing.space2),
            verticalArrangement = Arrangement.spacedBy(Spacing.space2),
        ) {
            NewIncidentFormCard(
                uiState = uiState,
                onTitleChange = viewModel::onTitleChange,
                onDescriptionChange = viewModel::onDescriptionChange,
                onCategorySelect = viewModel::onCategorySelect,
                onPrioritySelect = viewModel::onPrioritySelect,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::submit,
                enabled = !uiState.isSubmitting,
                shape = MaterialTheme.shapes.small,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .testTag(TAG_SUBMIT),
            ) {
                Text(text = if (uiState.isSubmitting) SUBMIT_LABEL_IN_FLIGHT else SUBMIT_LABEL)
            }
        }
    }
}

/** Primary app bar: dark-green band, Volver button on the leading side, title. */
@Composable
private fun NewIncidentTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Spacing.space2, vertical = Spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onClick = onBack)
        Text(
            text = SCREEN_TITLE,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = Spacing.space2),
        )
    }
}

/**
 * Volver app-bar button: onPrimary at the spec'd alphas for background
 * (0.12) and border (0.25), 8dp radius via `shapes.small`, 40dp min height.
 */
@Composable
private fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = BACK_LABEL,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier =
            modifier
                .heightIn(min = AppBarButtonMinHeight)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = BACK_BG_ALPHA))
                .border(
                    BorderStroke(Hairline, MaterialTheme.colorScheme.onPrimary.copy(alpha = BACK_BORDER_ALPHA)),
                    MaterialTheme.shapes.small,
                ).clickable(onClick = onClick)
                .padding(horizontal = Spacing.space2, vertical = Spacing.space1)
                .testTag(TAG_BACK),
    )
}

/**
 * Form card (surface, 1px outline border, 8dp radius, 16dp internal padding)
 * holding, in order: the Título field with counter, the Categoría group, the
 * Prioridad group, the Descripción field with counter and the Pendiente hint.
 */
@Composable
private fun NewIncidentFormCard(
    uiState: NewIncidentViewModel.NewIncidentUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCategorySelect: (IncidentCategory) -> Unit,
    onPrioritySelect: (IncidentPriority) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(Hairline, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.space2),
            verticalArrangement = Arrangement.spacedBy(Spacing.space2),
        ) {
            TitleField(
                value = uiState.title,
                error = uiState.titleError,
                onTitleChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
            )
            CategoryChoiceGroup(
                selected = uiState.category,
                error = uiState.categoryError,
                onOptionSelect = onCategorySelect,
                modifier = Modifier.fillMaxWidth(),
            )
            PriorityChoiceGroup(
                selected = uiState.priority,
                error = uiState.priorityError,
                onOptionSelect = onPrioritySelect,
                modifier = Modifier.fillMaxWidth(),
            )
            DescriptionField(
                value = uiState.description,
                error = uiState.descriptionError,
                onDescriptionChange = onDescriptionChange,
                modifier = Modifier.fillMaxWidth(),
            )
            PendienteHint(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Outlined "Título" field with its live "n / 100" counter and error text. */
@Composable
private fun TitleField(
    value: String,
    error: String?,
    onTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FieldLabel(text = TITLE_LABEL)
        OutlinedTextField(
            value = value,
            onValueChange = onTitleChange,
            placeholder = { Text(text = TITLE_PLACEHOLDER) },
            isError = error != null,
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .testTag(TAG_TITLE_INPUT),
        )
        CounterText(
            text = "${value.length} / $TITLE_COUNTER_MAX",
            tag = TAG_COUNTER_TITLE,
            modifier = Modifier.padding(top = Spacing.space1),
        )
        error?.let { message ->
            FieldErrorText(
                message = message,
                tag = TAG_ERROR_TITLE,
                modifier = Modifier.padding(top = Spacing.space1),
            )
        }
    }
}

/** Outlined "Descripción" field with its live "n / 1000" counter and error text. */
@Composable
private fun DescriptionField(
    value: String,
    error: String?,
    onDescriptionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FieldLabel(text = DESCRIPTION_LABEL)
        OutlinedTextField(
            value = value,
            onValueChange = onDescriptionChange,
            placeholder = { Text(text = DESCRIPTION_PLACEHOLDER) },
            isError = error != null,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .testTag(TAG_DESCRIPTION_INPUT),
        )
        CounterText(
            text = "${value.length} / $DESCRIPTION_COUNTER_MAX",
            tag = TAG_COUNTER_DESCRIPTION,
            modifier = Modifier.padding(top = Spacing.space1),
        )
        error?.let { message ->
            FieldErrorText(
                message = message,
                tag = TAG_ERROR_DESCRIPTION,
                modifier = Modifier.padding(top = Spacing.space1),
            )
        }
    }
}

/** Single-select "Categoría" choice group (Sistemas/Equipos/Acceso/Otro). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChoiceGroup(
    selected: IncidentCategory?,
    error: String?,
    onOptionSelect: (IncidentCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoiceGroup(
        label = "Categoría",
        options = CategoryOptions,
        selected = selected,
        error = error,
        errorTag = TAG_ERROR_CATEGORY,
        onOptionSelect = onOptionSelect,
        modifier = modifier,
    )
}

/** Single-select "Prioridad" choice group (Baja/Media/Alta). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityChoiceGroup(
    selected: IncidentPriority?,
    error: String?,
    onOptionSelect: (IncidentPriority) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoiceGroup(
        label = "Prioridad",
        options = PriorityOptions,
        selected = selected,
        error = error,
        errorTag = TAG_ERROR_PRIORITY,
        onOptionSelect = onOptionSelect,
        modifier = modifier,
    )
}

/**
 * Generic single-select choice group: label, wrapping row of
 * [ChoiceOption]s and — when present — the group's error text below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(
    label: String,
    options: List<ChoiceOptionData<T>>,
    selected: T?,
    error: String?,
    errorTag: String,
    onOptionSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FieldLabel(text = label)
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.space1),
            horizontalArrangement = Arrangement.spacedBy(Spacing.space1),
            verticalArrangement = Arrangement.spacedBy(Spacing.space1),
        ) {
            options.forEach { option ->
                ChoiceOption(
                    data = option,
                    selected = option.value == selected,
                    onClick = { onOptionSelect(option.value) },
                )
            }
        }
        error?.let { message ->
            FieldErrorText(
                message = message,
                tag = errorTag,
                modifier = Modifier.padding(top = Spacing.space1),
            )
        }
    }
}

/**
 * One choice option: unselected = surface + 1px outline border + onSurface
 * text; selected = primary + onPrimary text. 8dp radius via `shapes.small`
 * and a 48dp minimum touch target (BR-036).
 */
@Composable
private fun <T> ChoiceOption(
    data: ChoiceOptionData<T>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .heightIn(min = MinTouchTarget)
                .testTag(data.tag),
        shape = MaterialTheme.shapes.small,
        color =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            },
        border =
            BorderStroke(
                Hairline,
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            ),
    ) {
        Text(
            text = data.label,
            style = MaterialTheme.typography.titleSmall,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier =
                Modifier.padding(
                    horizontal = OptionPaddingHorizontal,
                    vertical = OptionPaddingVertical,
                ),
        )
    }
}

/** Always-visible Pendiente hint box (accent-soft + accent border). */
@Composable
private fun PendienteHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag(TAG_PENDIENTE_HINT),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(Hairline, MaterialTheme.colorScheme.secondary),
    ) {
        Text(
            text = PENDIENTE_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.space2, vertical = Spacing.space1),
        )
    }
}

/** Field/group label (13sp/600 label token). */
@Composable
private fun FieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** Live character counter ("n / 100" / "n / 1000"), muted caption, end-aligned. */
@Composable
private fun CounterText(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(tag),
    )
}

/** Field error text in the theme error color, liveRegion for TalkBack. */
@Composable
private fun FieldErrorText(
    message: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(tag)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
