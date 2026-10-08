package com.nexti.minisoporte.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * REFERENCE PATTERN screen for S-01..S-04 (CA-10, design.md Decision 6) — pairs with
 * [PlaceholderViewModel] and renders NO screen content: just the themed background
 * (plus a progress indicator while the UDF state says loading). Screen stories DELETE
 * or replace this composable and collect their own ViewModel exactly as shown here:
 * `uiState` via `collectAsStateWithLifecycle()`, one-shot events consumed once inside
 * `LaunchedEffect(Unit)` (consume-on-collect).
 */
@Composable
fun PlaceholderScreen(viewModel: PlaceholderViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // One-shot events: consumed exactly once, here — never stored in uiState.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PlaceholderViewModel.PlaceholderEvent.CounterReached -> {
                    // Event consumed. Screen stories navigate (navController.navigate)
                    // or show feedback at exactly this point.
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .clickable { viewModel.incrementCounter() },
        contentAlignment = Alignment.Center,
    ) {
        if (uiState.isLoading) {
            LinearProgressIndicator()
        }
    }
}
