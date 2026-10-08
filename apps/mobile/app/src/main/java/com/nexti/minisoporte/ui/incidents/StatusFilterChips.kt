/*
 * S-02 status filter chips (US-007, frontend.yaml `components.StatusFilterChips`).
 *
 * Stateless row of four pill chips — Todas (null filter), Pendiente, En
 * progreso, Resuelta — each pairing its Spanish label with the live count
 * derived client-side from the loaded owned list. Selected chip:
 * `colorScheme.primary` background, `colorScheme.onPrimary` text and a 1px
 * primary border; unselected: `colorScheme.surface` background,
 * `colorScheme.onSurface` text and a 1px outline border. Chip typography is
 * `labelMedium` (13.5sp / 600); every chip keeps a 48dp-min touch target
 * (BR-036). Theme tokens only — no raw hex or magic dp (INSTR-012).
 */
package com.nexti.minisoporte.ui.incidents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.ui.theme.PillShape
import com.nexti.minisoporte.ui.theme.Spacing

// --- Spec constants (INSTR-012 allow-list: 1px hairline border, 48dp touch
// target). All other values come from the theme. ---

/** Minimum interactive touch target for every chip (BR-036). */
private val MinTouchTarget = 48.dp

/** 1px hairline border on every chip. */
private val Hairline = 1.dp

// --- ui_copy (frontend.yaml, verbatim) ---

private const val LABEL_ALL = "Todas"

// --- Test tags (frontend.yaml rule: s<screen>_<element>) ---

private const val TAG_CHIP_ALL = "s02_filter_chip_todas"
private const val TAG_CHIP_PENDING = "s02_filter_chip_pendiente"
private const val TAG_CHIP_IN_PROGRESS = "s02_filter_chip_en_progreso"
private const val TAG_CHIP_RESOLVED = "s02_filter_chip_resuelta"

/**
 * Filter chip row for S-02: exactly one chip active at a time ([selected],
 * `null` = "Todas"), each rendering its label plus the live count taken from
 * [counts] (missing keys render 0). Taps are forwarded to
 * [onFilterSelected] with the chip's status (`null` for "Todas").
 */
@Composable
fun StatusFilterChips(
    selected: IncidentStatus?,
    counts: Map<IncidentStatus?, Int>,
    onFilterSelected: (IncidentStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.space1),
    ) {
        StatusChip(
            label = LABEL_ALL,
            count = counts[null] ?: 0,
            isSelected = selected == null,
            testTag = TAG_CHIP_ALL,
            onClick = { onFilterSelected(null) },
        )
        StatusChip(
            label = IncidentStatus.PENDING.label,
            count = counts[IncidentStatus.PENDING] ?: 0,
            isSelected = selected == IncidentStatus.PENDING,
            testTag = TAG_CHIP_PENDING,
            onClick = { onFilterSelected(IncidentStatus.PENDING) },
        )
        StatusChip(
            label = IncidentStatus.IN_PROGRESS.label,
            count = counts[IncidentStatus.IN_PROGRESS] ?: 0,
            isSelected = selected == IncidentStatus.IN_PROGRESS,
            testTag = TAG_CHIP_IN_PROGRESS,
            onClick = { onFilterSelected(IncidentStatus.IN_PROGRESS) },
        )
        StatusChip(
            label = IncidentStatus.RESOLVED.label,
            count = counts[IncidentStatus.RESOLVED] ?: 0,
            isSelected = selected == IncidentStatus.RESOLVED,
            testTag = TAG_CHIP_RESOLVED,
            onClick = { onFilterSelected(IncidentStatus.RESOLVED) },
        )
    }
}

/**
 * Single pill chip: [PillShape], 1px border (primary when selected, outline
 * when not), [MinTouchTarget] height and the `labelMedium` chip typography
 * for both the label and its live count.
 */
@Composable
private fun StatusChip(
    label: String,
    count: Int,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        }
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val borderColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }

    Surface(
        modifier =
            modifier
                .heightIn(min = MinTouchTarget)
                .clip(PillShape)
                .clickable(onClick = onClick)
                .testTag(testTag),
        shape = PillShape,
        color = containerColor,
        border = BorderStroke(Hairline, borderColor),
    ) {
        Row(
            modifier =
                Modifier.padding(horizontal = Spacing.space2, vertical = Spacing.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.space1),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}
