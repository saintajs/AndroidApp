package com.nexti.minisoporte.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Approved shape tokens: 8dp corner radius everywhere except pills/chips/badges
// (see [PillShape]) and the FAB (circle — `CircleShape`, already provided by Compose).
//
// Feature code reads shapes via `MaterialTheme.shapes.*` or [PillShape] — never a
// hard-coded radius (design.md Decision 2, CA-03 / INSTR-012).

// --- Standard radius -------------------------------------------------------

/** Standard 8dp corner radius applied to every Material component size bucket. */
private val Corner = RoundedCornerShape(8.dp)

/** Material 3 shape scale — all buckets use the approved 8dp corner radius. */
val MiniSoporteShapes =
    Shapes(
        extraSmall = Corner,
        small = Corner,
        medium = Corner,
        large = Corner,
        extraLarge = Corner,
    )

/** Full pill shape (999px equivalent) for filter chips, status badges and pills. */
val PillShape = RoundedCornerShape(percent = 50)
