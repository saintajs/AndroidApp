/*
 * Shared incident card (US-007, frontend.yaml `components.IncidentCard`).
 *
 * Compact card built from an [Incident] WITHOUT its description: title,
 * category pill, priority pill, [StatusBadge] and the `Creada el <date>`
 * caption. Card surface / 1px border / 8dp radius / 16dp internal padding
 * come from the theme (surface, outline, shapes.small, Spacing.space2);
 * priority pill colors are the ui/theme named constants — never raw hex
 * (INSTR-012 / CA-03). The whole card is clickable with a 48dp-min touch
 * target. Reusable unchanged by US-009.
 */
package com.nexti.minisoporte.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.ui.theme.PillShape
import com.nexti.minisoporte.ui.theme.PriorityHighBorder
import com.nexti.minisoporte.ui.theme.PriorityHighText
import com.nexti.minisoporte.ui.theme.PriorityLowBorder
import com.nexti.minisoporte.ui.theme.PriorityLowText
import com.nexti.minisoporte.ui.theme.PriorityMediumBorder
import com.nexti.minisoporte.ui.theme.PriorityMediumText
import com.nexti.minisoporte.ui.theme.Spacing

// --- Spec constants (INSTR-012 allow-list: 1px hairline border, 48dp
// minimum touch target). All other values come from the theme. ---

/** Minimum interactive touch target for the whole clickable card (BR-036). */
private val MinTouchTarget = 48.dp

/** 1px hairline border for the card and the outline pills. */
private val Hairline = 1.dp

// --- ui_copy (frontend.yaml, verbatim) ---

private const val CREATED_ON_PREFIX = "Creada el "

// --- Test tags (frontend.yaml rule: s<screen>_<element>) ---

private const val TAG_CARD_PREFIX = "s02_incident_card_"

/**
 * Compact incident card: title, category pill, priority pill, status badge
 * and `Creada el <date>` caption — never the description (list-item rule).
 * Whole-card click target navigating to S-04 via [onClick]; carries the
 * `s02_incident_card_<id>` test tag on the root card.
 */
@Composable
fun IncidentCard(
    incident: Incident,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .testTag(TAG_CARD_PREFIX + incident.id),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(Hairline, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(Spacing.space2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = incident.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(status = incident.status)
            }
            Row(
                modifier = Modifier.padding(top = Spacing.space1),
                horizontalArrangement = Arrangement.spacedBy(Spacing.space1),
            ) {
                CategoryPill(category = incident.category)
                PriorityPill(priority = incident.priority)
            }
            Text(
                text = CREATED_ON_PREFIX + incident.createdAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.space1),
            )
        }
    }
}

/** Neutral outline pill for the category label (muted text, 1px outline border). */
@Composable
private fun CategoryPill(
    category: IncidentCategory,
    modifier: Modifier = Modifier,
) {
    OutlinePill(
        text = category.label,
        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        borderColor = MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}

/**
 * Outline pill for the priority label: Alta / Media / Baja, each with its
 * approved border/text pair from the ui/theme named constants (INSTR-012).
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

    OutlinePill(
        text = priority.label,
        textColor = textColor,
        borderColor = borderColor,
        modifier = modifier,
    )
}

/** Outline pill: transparent background, 1px border, pill shape, labelMedium text. */
@Composable
private fun OutlinePill(
    text: String,
    textColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
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
