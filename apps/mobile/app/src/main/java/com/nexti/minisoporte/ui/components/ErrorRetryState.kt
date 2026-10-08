/*
 * Shared error + retry state (US-007, frontend.yaml `components.ErrorRetryState`).
 *
 * Spanish error message (centered) above an outline retry button: white
 * surface background, primary (#1E3A2A via `colorScheme.primary`) text and
 * 1px border, 8dp radius, min-height 44dp. All values through the theme
 * (INSTR-012); the message text itself is rendered straight from the
 * ViewModel state. Reusable unchanged by US-009.
 */
package com.nexti.minisoporte.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexti.minisoporte.ui.theme.Spacing

// --- Spec constants (INSTR-012 allow-list: 1px hairline border, 44dp retry
// minimum height). All other values come from the theme. ---

/** 1px hairline border for the outline retry button. */
private val Hairline = 1.dp

/** Minimum height of the outline retry button (frontend.yaml retry spec). */
private val RetryMinHeight = 44.dp

// --- Test tags (frontend.yaml rule: s<screen>_<element>) ---

private const val TAG_RETRY = "s02_error_retry"

/**
 * Error state: centered Spanish [message] (bodyMedium) above the outline
 * retry button ([retryLabel], default "Reintentar") carrying the
 * `s02_error_retry` test tag. [onRetry] reloads from the repository seam.
 */
@Composable
fun ErrorRetryState(
    message: String,
    retryLabel: String = "Reintentar",
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = onRetry,
            shape = MaterialTheme.shapes.small,
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            border = BorderStroke(Hairline, MaterialTheme.colorScheme.primary),
            modifier =
                Modifier
                    .padding(top = Spacing.space2)
                    .heightIn(min = RetryMinHeight)
                    .testTag(TAG_RETRY),
        ) {
            Text(text = retryLabel)
        }
    }
}
