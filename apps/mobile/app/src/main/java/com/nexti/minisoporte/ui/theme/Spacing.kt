package com.nexti.minisoporte.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Approved spacing tokens — 8dp base grid, verbatim from config.yaml
 * `design_tokens.spacing-layout-tokens` ("space-1 = 8px, space-2 = 16px,
 * space-3 = 24px, space-4 = 32px").
 *
 * Composables reference these named values instead of hard-coding dp
 * (design.md Decision 2, INSTR-012). Half-steps for sub-grid tweaks are
 * intentionally absent: snap to the grid.
 */
object Spacing {
    /** space-1: icon gaps, intra-group padding. */
    val space1 = 8.dp

    /** space-2: card internal padding, standard element spacing. */
    val space2 = 16.dp

    /** space-3: section separation. */
    val space3 = 24.dp

    /** space-4: screen-level separation. */
    val space4 = 32.dp
}
