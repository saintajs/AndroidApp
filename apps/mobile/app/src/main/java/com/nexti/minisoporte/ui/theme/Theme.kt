package com.nexti.minisoporte.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

// The app theme — a SINGLE light Material 3 color scheme built from the approved
// tokens. No dark branch and no dynamic color by design (design.md Decision 2):
// the brand palette is the only approved palette, light-only.
//
// Raw hex values exist ONLY in Color.kt (CA-03 / BR-036); this file maps them onto
// Material scheme roles. Status-badge and priority-pill hexes are NOT Material scheme
// roles — later stories read those named constants directly from Color.kt.

// --- Color scheme ----------------------------------------------------------

/** Approved light color scheme. Single source of truth for all Material roles. */
private val LightColorScheme =
    lightColorScheme(
        primary = Primary,
        onPrimary = TextOnPrimary,
        primaryContainer = PrimaryHover, // pressed/selected control state
        onPrimaryContainer = TextOnPrimary,
        inversePrimary = AccentSoft,
        secondary = Secondary, // FAB background, brand accent
        onSecondary = TextPrimary,
        secondaryContainer = AccentSoft, // demo hint box
        onSecondaryContainer = TextPrimary,
        tertiary = PrimaryHover,
        onTertiary = TextOnPrimary,
        background = Background,
        onBackground = TextPrimary,
        surface = Surface,
        onSurface = TextPrimary,
        surfaceVariant = Background,
        onSurfaceVariant = TextMuted, // subtitles, metadata, hints, counters
        surfaceTint = Primary,
        inverseSurface = TextPrimary,
        inverseOnSurface = Surface,
        error = Danger,
        onError = TextOnPrimary,
        errorContainer = DangerSoft,
        onErrorContainer = Danger,
        outline = Border, // field outlines, card borders, dividers
        outlineVariant = Border,
        scrim = TextPrimary,
    )

/** MiniSoporte theme wrapper. All feature code consumes tokens exclusively through
 * `MaterialTheme.colorScheme` / `MaterialTheme.typography` / `MaterialTheme.shapes`
 * (plus the [Spacing] grid object and Color.kt badge/pill constants).
 */
@Composable
fun MiniSoporteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = MiniSoporteTypography,
        shapes = MiniSoporteShapes,
        content = content,
    )
}

/** Proves the theme renders with approved tokens; compiles as part of `check`. */
@Preview(showBackground = true)
@Composable
private fun ThemePreview() {
    MiniSoporteTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.space2),
                verticalArrangement = Arrangement.spacedBy(Spacing.space1),
            ) {
                Text(
                    text = "Título de pantalla",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Texto secundario",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
