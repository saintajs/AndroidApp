/*
 * S-04 Detalle de incidencia screen (US-009, frontend.yaml
 * `components.IncidentDetailScreen`, design.md Decisions 2, 5 and 6).
 *
 * Stateless, state-hoisted UI: collects [IncidentDetailViewModel.uiState] via
 * `collectAsStateWithLifecycle()`, re-runs the owner-scoped load in
 * `LaunchedEffect(incidentId)` so every re-entry re-reads the in-memory store,
 * and consumes the one-shot [IncidentDetailViewModel.events] (NavigateBackToS02)
 * in a separate `LaunchedEffect(Unit)` — the IncidentListScreen reference
 * pattern. Renders the dark primary app bar with the 'Mis incidencias' back
 * action (48dp minimum target, BR-036) and, on the background color, the
 * detail card: ID caption, title + [StatusBadge] (label + color together,
 * INSTR-012), description block and the Categoría/Prioridad/Estado/Fecha de
 * creación label/value rows. Card surface / 1px border / 8dp radius / 16dp
 * internal padding come from the theme (surface, outline, shapes.small,
 * Spacing.space2); priority pill colors are the ui/theme named constants —
 * never raw hex (INSTR-012 / CA-03). Read-only: no edit affordance anywhere
 * (BR-012). No network-stack imports (BR-041); token material never appears
 * here (CONF-002).
 */
package com.nexti.minisoporte.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.ui.components.StatusBadge
import com.nexti.minisoporte.ui.theme.PillShape
import com.nexti.minisoporte.ui.theme.PriorityHighBorder
import com.nexti.minisoporte.ui.theme.PriorityHighText
import com.nexti.minisoporte.ui.theme.PriorityLowBorder
import com.nexti.minisoporte.ui.theme.PriorityLowText
import com.nexti.minisoporte.ui.theme.PriorityMediumBorder
import com.nexti.minisoporte.ui.theme.PriorityMediumText
import com.nexti.minisoporte.ui.theme.Spacing

// --- Spec constants (INSTR-012 allow-list: 1px hairline border, 48dp back
// touch target, back rgba alphas). All other values come from the theme. ---

/** 1px hairline borders (back button, detail card, priority pill, timeline dot). */
private val Hairline = 1.dp

/** Minimum touch target of the back app-bar action (BR-036). */
private val MinTouchTarget = 48.dp

/** Leading status dot of one timeline row (frontend.yaml timeline spec). */
private val TimelineDot = 12.dp

/** Corner radius of the dashed no-access box (`shapes.small` bucket). */
private val NoAccessCornerRadius = 8.dp

/** Dash/on-off pattern in px of the dashed no-access border. */
private val NoAccessDashPattern = floatArrayOf(12f, 8f)

/** Back action background alpha — rgba(255,255,255,0.12). */
private const val BACK_BG_ALPHA = 0.12f

/** Back action border alpha — rgba(255,255,255,0.25). */
private const val BACK_BORDER_ALPHA = 0.25f

// --- ui_copy (frontend.yaml, verbatim labels) ---

private const val BACK_LABEL = "Mis incidencias"

private const val TIMELINE_HEADING = "Historial de estados"

private const val NO_ACCESS_TITLE = "Incidencia no disponible"

private const val NO_ACCESS_HINT = "No se encontró la incidencia o no tienes acceso a ella."

/** TalkBack suffix appended when the row is the newest timeline entry. */
private const val NEWEST_DESCRIPTION_SUFFIX = ", último estado"

private const val DETAIL_LABEL_CATEGORY = "Categoría"
private const val DETAIL_LABEL_PRIORITY = "Prioridad"
private const val DETAIL_LABEL_STATUS = "Estado"
private const val DETAIL_LABEL_CREATED_AT = "Fecha de creación"

// --- Test tags (frontend.yaml rule: s<screen>_<element>) ---

private const val TAG_BACK = "s04_back"
private const val TAG_LOADING = "s04_loading"
private const val TAG_ID_CAPTION = "s04_id_caption"
private const val TAG_TITLE = "s04_title"
private const val TAG_DESCRIPTION = "s04_description"
private const val TAG_CATEGORY_VALUE = "s04_category_value"
private const val TAG_PRIORITY_VALUE = "s04_priority_value"
private const val TAG_STATUS_VALUE = "s04_status_value"
private const val TAG_CREATED_AT_VALUE = "s04_created_at_value"
private const val TAG_TIMELINE_HEADING = "s04_timeline_heading"
private const val TAG_TIMELINE_ITEM = "s04_timeline_item"
private const val TAG_NO_ACCESS = "s04_no_access"

/**
 * S-04 destination composable. Tests pass a fakes-built [IncidentDetailViewModel]
 * (no default factory, by design); production wires the screen through
 * AppNavHost, which reads the typed `incidentId` path argument from the back
 * stack entry and supplies [onBackToS02] (pop back to the S-02 route).
 */
@Composable
fun IncidentDetailScreen(
    incidentId: String,
    viewModel: IncidentDetailViewModel,
    onBackToS02: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Load keyed on the incident id: every re-entry re-reads the in-memory
    // store so the card reflects the current fake state (design.md Decision 2).
    LaunchedEffect(incidentId) {
        viewModel.load(incidentId)
    }

    // One-shot events: consumed exactly once, here — never stored in uiState.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                IncidentDetailEvent.NavigateBackToS02 -> onBackToS02()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DetailTopBar(onBack = viewModel::onBackToMisIncidencias) },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            when (val state = uiState) {
                IncidentDetailUiState.Loading ->
                    CenteredContent {
                        CircularProgressIndicator(modifier = Modifier.testTag(TAG_LOADING))
                    }
                is IncidentDetailUiState.Content ->
                    DetailCard(
                        incident = state.incident,
                        timeline = state.timeline,
                        modifier = Modifier.fillMaxSize(),
                    )
                IncidentDetailUiState.NoAccess ->
                    // Shared-placeholder style (dashed border + lock, no retry —
                    // nothing to retry when the id is missing or foreign);
                    // rendered locally because the shared ErrorRetryState API
                    // requires an onRetry action (group decisions: shared
                    // components are consumed unchanged).
                    CenteredContent {
                        NoAccessState(modifier = Modifier.padding(Spacing.space2))
                    }
            }
        }
    }
}

/**
 * Primary app bar: dark-green band whose only content is the 'Mis incidencias'
 * back action (frontend.yaml detail spec — no screen title).
 */
@Composable
private fun DetailTopBar(
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
    ) {
        BackButton(onClick = onBack)
    }
}

/**
 * 'Mis incidencias' back action: onPrimary at the spec'd alphas for background
 * (0.12) and border (0.25), 8dp radius via `shapes.small`, 48dp minimum touch
 * target (BR-036).
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
                .heightIn(min = MinTouchTarget)
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
 * Detail card on the background color with 16dp content padding: surface
 * #FFFFFF via `colorScheme.surface`, 1px outline border, 8dp radius
 * (`shapes.small`), 16dp internal padding. Below the card, the 'Historial de
 * estados' timeline renders [timeline] in list order — the ViewModel already
 * delivered it newest-first, the composable never reorders. Both sections are
 * vertically scrollable so everything stays reachable on small viewports.
 * Read-only — no edit affordance (BR-012).
 */
@Composable
private fun DetailCard(
    incident: Incident,
    timeline: List<TimelineEntry>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.space2),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(Hairline, MaterialTheme.colorScheme.outline),
        ) {
            Column(modifier = Modifier.padding(Spacing.space2)) {
                Text(
                    text = incident.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TAG_ID_CAPTION),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.space1),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.space1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = incident.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier
                                .weight(1f)
                                .testTag(TAG_TITLE),
                    )
                    StatusBadge(status = incident.status)
                }
                Text(
                    text = incident.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .padding(top = Spacing.space1)
                            .testTag(TAG_DESCRIPTION),
                )
                DetailRow(
                    label = DETAIL_LABEL_CATEGORY,
                    modifier = Modifier.padding(top = Spacing.space2),
                ) {
                    Text(
                        text = incident.category.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(TAG_CATEGORY_VALUE),
                    )
                }
                DetailRow(
                    label = DETAIL_LABEL_PRIORITY,
                    modifier = Modifier.padding(top = Spacing.space1),
                ) {
                    PriorityPill(
                        priority = incident.priority,
                        modifier = Modifier.testTag(TAG_PRIORITY_VALUE),
                    )
                }
                DetailRow(
                    label = DETAIL_LABEL_STATUS,
                    modifier = Modifier.padding(top = Spacing.space1),
                ) {
                    StatusBadge(
                        status = incident.status,
                        modifier = Modifier.testTag(TAG_STATUS_VALUE),
                    )
                }
                DetailRow(
                    label = DETAIL_LABEL_CREATED_AT,
                    modifier = Modifier.padding(top = Spacing.space1),
                ) {
                    Text(
                        text = incident.createdAt,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(TAG_CREATED_AT_VALUE),
                    )
                }
            }
        }
        Text(
            text = TIMELINE_HEADING,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .padding(top = Spacing.space3)
                    .testTag(TAG_TIMELINE_HEADING),
        )
        Column(
            modifier = Modifier.padding(top = Spacing.space1),
            verticalArrangement = Arrangement.spacedBy(Spacing.space1),
        ) {
            timeline.forEach { entry ->
                TimelineRow(entry = entry)
            }
        }
    }
}

/**
 * One detail label/value row: the muted 13sp/600 label followed by the value
 * slot (plain Text, priority pill or [StatusBadge]).
 */
@Composable
private fun DetailRow(
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value()
    }
}

/**
 * Outline pill for the priority label: Alta / Media / Baja, each with its
 * approved border/text pair from the ui/theme named constants (INSTR-012) —
 * mirrors IncidentCard's rendering without touching the shared component.
 */
@Composable
private fun PriorityPill(
    priority: IncidentPriority,
    modifier: Modifier = Modifier,
) {
    val borderColor =
        when (priority) {
            IncidentPriority.HIGH -> PriorityHighBorder
            IncidentPriority.MEDIUM -> PriorityMediumBorder
            IncidentPriority.LOW -> PriorityLowBorder
        }
    val textColor =
        when (priority) {
            IncidentPriority.HIGH -> PriorityHighText
            IncidentPriority.MEDIUM -> PriorityMediumText
            IncidentPriority.LOW -> PriorityLowText
        }

    Text(
        text = priority.label,
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        modifier =
            modifier
                .clip(PillShape)
                .background(Color.Transparent)
                .border(BorderStroke(Hairline, borderColor), PillShape)
                .padding(horizontal = Spacing.space2, vertical = Spacing.space1),
    )
}

/** Full-size box centering its single child (loading / no-access states). */
@Composable
private fun CenteredContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * One read-only timeline row (BR-012): leading status dot — filled
 * (`colorScheme.primary`) on the newest entry, hollow 1dp-outline otherwise —
 * plus the status line, timestamp and mock actor. TalkBack reads the row as
 * one unit via [timelineRowDescription] and announces changes politely.
 */
@Composable
private fun TimelineRow(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = timelineRowDescription(entry)
                    liveRegion = LiveRegionMode.Polite
                }.testTag(TAG_TIMELINE_ITEM)
                .padding(vertical = Spacing.space1),
        horizontalArrangement = Arrangement.spacedBy(Spacing.space1),
    ) {
        NewestDot(isNewest = entry.isNewest)
        Column {
            Text(
                text = entry.statusLine,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.changedAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.space1),
            )
            Text(
                text = entry.actor,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Leading 12dp circle of one timeline row: filled with `colorScheme.primary`
 * when [isNewest] (the FIRST rendered entry — INSTR-005), otherwise hollow
 * with a 1dp outline border and transparent fill.
 */
@Composable
private fun NewestDot(
    isNewest: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(TimelineDot)
                .clip(CircleShape)
                .background(
                    if (isNewest) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ).then(
                    if (isNewest) {
                        Modifier
                    } else {
                        Modifier.border(BorderStroke(Hairline, MaterialTheme.colorScheme.outline), CircleShape)
                    },
                ),
    )
}

/**
 * TalkBack description of one timeline row: status line, timestamp and actor
 * as one announcement, plus the ', último estado' suffix on the newest entry.
 */
private fun timelineRowDescription(entry: TimelineEntry): String =
    buildString {
        append(entry.statusLine)
        append(", ")
        append(entry.changedAt)
        append(", ")
        append(entry.actor)
        if (entry.isNewest) {
            append(NEWEST_DESCRIPTION_SUFFIX)
        }
    }

/**
 * Safe no-access state (BR-009/BR-010/BR-021): one dashed-border box in the
 * shared ErrorRetryState placeholder style — 1dp `colorScheme.outline` dashes
 * on an 8dp rounded rect, lock glyph, 'Incidencia no disponible' + hint —
 * centered, with NO retry button (nothing to retry). Missing and foreign ids
 * collapse into this exact same state; no distinguishing information exists.
 */
@Composable
private fun NoAccessState(modifier: Modifier = Modifier) {
    val outlineColor = MaterialTheme.colorScheme.outline
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(TAG_NO_ACCESS)
                .drawBehind {
                    val cornerRadius = NoAccessCornerRadius.toPx()
                    val path =
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(0f, 0f, size.width, size.height),
                                    cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                                ),
                            )
                        }
                    drawPath(
                        color = outlineColor,
                        path = path,
                        style =
                            Stroke(
                                width = Hairline.toPx(),
                                pathEffect = PathEffect.dashPathEffect(NoAccessDashPattern),
                            ),
                    )
                }.padding(Spacing.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = NO_ACCESS_TITLE,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.space1),
        )
        Text(
            text = NO_ACCESS_HINT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.space1),
        )
    }
}
