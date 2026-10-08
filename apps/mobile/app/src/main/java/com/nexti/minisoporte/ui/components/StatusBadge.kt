/*
 * Shared status badge (US-007, frontend.yaml `components.StatusBadge`).
 *
 * Pairs the Spanish status label with its approved background color — the
 * label is ALWAYS rendered together with its background (INSTR-012: never
 * color alone). Badge colors are the named constants in ui/theme/Color.kt,
 * NOT MaterialTheme scheme roles; feature code reads them exclusively from
 * there — never a raw hex (CA-03 / BR-036). Reusable unchanged by US-009.
 */
package com.nexti.minisoporte.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.ui.theme.BadgeTextStyle
import com.nexti.minisoporte.ui.theme.PillShape
import com.nexti.minisoporte.ui.theme.Spacing
import com.nexti.minisoporte.ui.theme.StatusInProgressBg
import com.nexti.minisoporte.ui.theme.StatusInProgressFg
import com.nexti.minisoporte.ui.theme.StatusPendingBg
import com.nexti.minisoporte.ui.theme.StatusPendingFg
import com.nexti.minisoporte.ui.theme.StatusResolvedBg
import com.nexti.minisoporte.ui.theme.StatusResolvedFg

/**
 * Pill badge pairing the Spanish status label ([IncidentStatus.label]) with
 * its approved background/text color pair — Pendiente #7A5B10 on #FBF1D4,
 * En progreso #2A4E79 on #E1EAF6, Resuelta #1E5B36 on #DFEFE3 (colors via
 * the ui/theme named constants). Badge typography is [BadgeTextStyle]
 * (12sp / 700). Shows ONLY the label on its paired background — never color
 * alone (INSTR-012).
 */
@Composable
fun StatusBadge(
    status: IncidentStatus,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        when (status) {
            IncidentStatus.PENDING -> StatusPendingBg
            IncidentStatus.IN_PROGRESS -> StatusInProgressBg
            IncidentStatus.RESOLVED -> StatusResolvedBg
        }
    val contentColor =
        when (status) {
            IncidentStatus.PENDING -> StatusPendingFg
            IncidentStatus.IN_PROGRESS -> StatusInProgressFg
            IncidentStatus.RESOLVED -> StatusResolvedFg
        }

    Text(
        text = status.label,
        style = BadgeTextStyle,
        color = contentColor,
        textAlign = TextAlign.Center,
        modifier =
            modifier
                .clip(PillShape)
                .background(containerColor)
                .padding(horizontal = Spacing.space2, vertical = Spacing.space1),
    )
}
