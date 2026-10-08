/*
 * S-02 Mis incidencias screen (US-007, frontend.yaml `components.IncidentListScreen`).
 *
 * Stateless, state-hoisted UI: collects [IncidentListViewModel.uiState] via
 * `collectAsStateWithLifecycle()`, runs the initial load in
 * `LaunchedEffect(Unit)` and consumes the one-shot events (toast, navigate
 * to S-01) in a separate `LaunchedEffect(Unit)` — the SignInScreen reference
 * pattern (CA-10). Composes a Scaffold with the primary app bar (Salir
 * app-bar button), the [StatusFilterChips] row, the LazyColumn of
 * [IncidentCard]s (or the loading/empty/error state) and the bottom bar with
 * the centered accent FAB. Theme tokens only (INSTR-012); the two Salir
 * alphas below are the only spec'd alpha floats. No network-stack imports
 * (BR-041); token material never appears here (CONF-002).
 */
package com.nexti.minisoporte.ui.incidents

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.ui.components.ErrorRetryState
import com.nexti.minisoporte.ui.components.IncidentCard
import com.nexti.minisoporte.ui.theme.Spacing

// --- Spec constants (INSTR-012 allow-list: 1px hairline borders, 48dp touch
// target, 40dp app-bar button, 52dp FAB circle with 3px border, Salir rgba
// alphas). All other values come from the theme. ---

/** 1px hairline borders (Salir button, bottom-bar top divider). */
private val Hairline = 1.dp

/** Minimum height of the Salir app-bar button (frontend.yaml app_bar spec). */
private val SignOutMinHeight = 40.dp

/** Accent FAB diameter (frontend.yaml bottom_nav spec). */
private val FabSize = 52.dp

/** FAB border width in the background color (frontend.yaml bottom_nav spec). */
private val FabBorderWidth = 3.dp

/** Salir app-bar button background alpha — rgba(255,255,255,0.12). */
private const val SALIR_BG_ALPHA = 0.12f

/** Salir app-bar button border alpha — rgba(255,255,255,0.25). */
private const val SALIR_BORDER_ALPHA = 0.25f

// --- ui_copy (frontend.yaml, verbatim labels; the error string lives in the
// ViewModel and is rendered straight from UiState) ---

private const val SCREEN_TITLE = "Mis incidencias"
private const val SIGN_OUT_LABEL = "Salir"
private const val EMPTY_STATE_LABEL = "Sin incidencias"
private const val FAB_GLYPH = "+"

/** Fallback when UiState carries no error message (defensive; CONF-009). */
private const val LOAD_ERROR_FALLBACK = "No se pudieron cargar las incidencias."

// --- Test tags (frontend.yaml rule: s<screen>_<element>) ---

private const val TAG_SIGN_OUT = "s02_sign_out"
private const val TAG_FAB = "s02_fab"
private const val TAG_EMPTY_STATE = "s02_empty_state"
private const val TAG_TOAST_SIGN_OUT = "s02_toast_signout"

/**
 * S-02 destination composable. Tests pass a fakes-built
 * [IncidentListViewModel] (no default factory, by design); production wires
 * the screen through AppNavHost, which supplies [onNavigateToNewIncident]
 * (FAB to the S-03 route), [onNavigateToIncidentDetail] (card tap to the
 * S-04 route with the incident id) and [onNavigateToSignIn] (after the
 * NavigateToS01 sign-out event).
 */
@Composable
fun IncidentListScreen(
    viewModel: IncidentListViewModel,
    onNavigateToNewIncident: () -> Unit = {},
    onNavigateToIncidentDetail: (String) -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var toastShown by remember { mutableStateOf<String?>(null) }

    // Initial load: exactly once per screen entry.
    LaunchedEffect(Unit) {
        viewModel.load()
    }

    // One-shot events: consumed exactly once, here — never stored in uiState.
    // The toast is shown via the system Toast AND echoed as a tagged sibling
    // Text so Robolectric tests can assert either path.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is IncidentListViewModel.IncidentListEvent.ShowToast -> {
                    toastShown = event.message
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                IncidentListViewModel.IncidentListEvent.NavigateToS01 -> onNavigateToSignIn()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { IncidentTopBar(onSignOut = viewModel::signOut) },
        bottomBar = { IncidentsBottomBar(onFabClick = onNavigateToNewIncident) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            StatusFilterChips(
                selected = uiState.statusFilter,
                counts = uiState.counts,
                onFilterSelected = viewModel::onFilterSelected,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.space2, vertical = Spacing.space1),
            )
            when (uiState.phase) {
                IncidentListViewModel.ListPhase.Loading ->
                    CenteredContent(modifier = Modifier.weight(1f)) {
                        CircularProgressIndicator()
                    }
                IncidentListViewModel.ListPhase.Empty ->
                    CenteredContent(modifier = Modifier.weight(1f)) {
                        Text(
                            text = EMPTY_STATE_LABEL,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(TAG_EMPTY_STATE),
                        )
                    }
                IncidentListViewModel.ListPhase.Error ->
                    CenteredContent(modifier = Modifier.weight(1f)) {
                        ErrorRetryState(
                            message = uiState.error ?: LOAD_ERROR_FALLBACK,
                            onRetry = viewModel::retry,
                        )
                    }
                IncidentListViewModel.ListPhase.Content ->
                    IncidentList(
                        incidents = uiState.incidents,
                        onIncidentClick = onNavigateToIncidentDetail,
                        modifier = Modifier.weight(1f),
                    )
            }
            // Test-assertable echo of the consumed ShowToast event (see the
            // event collector above); the system Toast remains the primary path.
            toastShown?.let { message ->
                Text(text = message, modifier = Modifier.testTag(TAG_TOAST_SIGN_OUT))
            }
        }
    }
}

/** Primary app bar: dark-green band, screen title and the Salir button. */
@Composable
private fun IncidentTopBar(
    onSignOut: () -> Unit,
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
        Text(
            text = SCREEN_TITLE,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.weight(1f),
        )
        SignOutButton(onClick = onSignOut)
    }
}

/**
 * Salir app-bar button: onPrimary at the spec'd alphas for background
 * (0.12) and border (0.25), 8dp radius via `shapes.small`, 40dp min height.
 */
@Composable
private fun SignOutButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = SIGN_OUT_LABEL,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier =
            modifier
                .heightIn(min = SignOutMinHeight)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = SALIR_BG_ALPHA))
                .border(
                    BorderStroke(Hairline, MaterialTheme.colorScheme.onPrimary.copy(alpha = SALIR_BORDER_ALPHA)),
                    MaterialTheme.shapes.small,
                ).clickable(onClick = onClick)
                .padding(horizontal = Spacing.space2, vertical = Spacing.space1)
                .testTag(TAG_SIGN_OUT),
    )
}

/**
 * Owned-incident list: newest-first (ordering preserved by the ViewModel),
 * LazyColumn with the stable key `it.id` over the background color, 16dp
 * horizontal padding, spacing and content padding on the 8dp grid.
 */
@Composable
private fun IncidentList(
    incidents: List<Incident>,
    onIncidentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = Spacing.space2),
        verticalArrangement = Arrangement.spacedBy(Spacing.space2),
        contentPadding = PaddingValues(vertical = Spacing.space2),
    ) {
        items(
            items = incidents,
            key = { it.id },
        ) { incident ->
            IncidentCard(
                incident = incident,
                onClick = { onIncidentClick(incident.id) },
            )
        }
    }
}

/**
 * Bottom navigation bar: surface background with a 1px outline top divider
 * and the centered accent FAB navigating to S-03.
 */
@Composable
private fun IncidentsBottomBar(
    onFabClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(Hairline)
                    .background(MaterialTheme.colorScheme.outline),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.space1),
            contentAlignment = Alignment.Center,
        ) {
            NewIncidentFab(onClick = onFabClick)
        }
    }
}

/**
 * Accent FAB: 52dp circle in `colorScheme.secondary` with a 3px
 * `colorScheme.background` border, "+" glyph in `colorScheme.onSecondary`
 * (a Text glyph — no Icon/contentDescription surface).
 */
@Composable
private fun NewIncidentFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(FabSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary)
                .border(
                    BorderStroke(FabBorderWidth, MaterialTheme.colorScheme.background),
                    CircleShape,
                ).clickable(onClick = onClick)
                .testTag(TAG_FAB),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = FAB_GLYPH,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSecondary,
        )
    }
}

/** Full-size box centering its single child (loading / empty / error states). */
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
