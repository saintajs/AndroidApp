/*
 * S-01 sign-in screen (US-006, design.md Decisions 1 & 5).
 *
 * Stateless, state-hoisted UI: collects [SignInViewModel.uiState] via
 * `collectAsStateWithLifecycle()` and consumes the one-shot navigation event
 * inside `LaunchedEffect(Unit)` exactly like the PlaceholderScreen reference
 * pattern (CA-10). Renders the hero band, the centered login card and the demo
 * hint box using ONLY MaterialTheme tokens (INSTR-012 — no raw hex or magic
 * dp in feature code). No network stack imports (BR-041); token material never
 * appears here (CONF-002).
 */
package com.nexti.minisoporte.ui.signin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexti.minisoporte.ui.theme.Spacing

// --- Spec constants (INSTR-012 allow-list: 1px borders, 48dp touch targets,
// 480px card cap, subtitle copy alpha). All other values come from the theme. ---

/** Minimum interactive touch target (BR-036). */
private val MinTouchTarget = 48.dp

/** 1px hairline borders for the card and the demo hint box. */
private val Hairline = 1.dp

/** Login card maximum width, centered per the approved mockup. */
private val CardMaxWidth = 480.dp

/** Brand mark circle diameter inside the hero band. */
private val BrandMarkSize = 48.dp

/** Opacity of the hero subtitle over the primary band. */
private const val HERO_SUBTITLE_ALPHA = 0.7f

// --- ui_copy (frontend.yaml, verbatim labels only — error strings live in the
// ViewModel and are rendered straight from UiState) ---

private const val SCREEN_TITLE = "Iniciar sesión"
private const val HERO_SUBTITLE = "Accede con tus credenciales de empleado"
private const val BRAND_INITIALS = "MS"
private const val EMAIL_LABEL = "Correo electrónico"
private const val PASSWORD_LABEL = "Contraseña"
private const val SUBMIT_LABEL = "Iniciar sesión"
private const val DEMO_HINT = "Demo: admin / admin"

// --- Test tags (frontend.yaml rule: s<screen>_<element>) ---

private const val TAG_EMAIL_INPUT = "s01_email_input"
private const val TAG_PASSWORD_INPUT = "s01_password_input"
private const val TAG_SUBMIT_BUTTON = "s01_submit_button"
private const val TAG_DEMO_CREDENTIALS = "s01_demo_credentials"
private const val TAG_ERROR_GENERIC = "s01_error_generic"

/**
 * S-01 destination composable. Tests pass a fakes-built [SignInViewModel]
 * (no default factory, by design); production wires the screen through
 * AppNavHost, which supplies [onNavigateToS02] to route to the existing S-02
 * destination on a successful sign-in.
 */
@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    onNavigateToS02: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // One-shot events: consumed exactly once, here — never stored in uiState.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                SignInViewModel.SignInEvent.NavigateToS02 -> onNavigateToS02()
            }
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SignInHero()

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.space3, vertical = Spacing.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SignInLoginCard(
                uiState = uiState,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onSubmit = viewModel::submit,
                modifier = Modifier.fillMaxWidth(),
            )
            DemoHintBox(modifier = Modifier.fillMaxWidth().padding(top = Spacing.space2))
        }
    }
}

/** Hero band: primary background, brand mark, screen title and subtitle. */
@Composable
private fun SignInHero(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = Spacing.space3, vertical = Spacing.space4),
    ) {
        Box(
            modifier =
                Modifier
                    .size(BrandMarkSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = BRAND_INITIALS,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            text = SCREEN_TITLE,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(top = Spacing.space2),
        )
        Text(
            text = HERO_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = HERO_SUBTITLE_ALPHA),
            modifier = Modifier.padding(top = Spacing.space1),
        )
    }
}

/**
 * Centered login card (surface, 1px outline border, 8dp radius, max 480dp)
 * holding both outlined fields, the submit button and — when present — the
 * generic inline error below the button.
 */
@Composable
private fun SignInLoginCard(
    uiState: SignInViewModel.SignInUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = CardMaxWidth),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(Hairline, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.space2),
            verticalArrangement = Arrangement.spacedBy(Spacing.space2),
        ) {
            EmailField(
                value = uiState.email,
                error = uiState.emailError,
                onEmailChange = onEmailChange,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                value = uiState.password,
                error = uiState.passwordError,
                onPasswordChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSubmit,
                enabled = !uiState.isSubmitting,
                shape = MaterialTheme.shapes.small,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .testTag(TAG_SUBMIT_BUTTON),
            ) {
                Text(text = SUBMIT_LABEL)
            }
            uiState.generalError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(TAG_ERROR_GENERIC)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Outlined "Correo electrónico" field; required-field error via supporting text. */
@Composable
private fun EmailField(
    value: String,
    error: String?,
    onEmailChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onEmailChange,
        label = { Text(text = EMAIL_LABEL) },
        isError = error != null,
        supportingText = {
            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier =
            modifier
                .heightIn(min = MinTouchTarget)
                .testTag(TAG_EMAIL_INPUT),
    )
}

/** Outlined "Contraseña" field with masked input and its own supporting text. */
@Composable
private fun PasswordField(
    value: String,
    error: String?,
    onPasswordChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onPasswordChange,
        label = { Text(text = PASSWORD_LABEL) },
        isError = error != null,
        supportingText = {
            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier =
            modifier
                .heightIn(min = MinTouchTarget)
                .testTag(TAG_PASSWORD_INPUT),
    )
}

/** Always-visible demo hint box below the login card (accent-soft + accent border). */
@Composable
private fun DemoHintBox(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = CardMaxWidth).testTag(TAG_DEMO_CREDENTIALS),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(Hairline, MaterialTheme.colorScheme.secondary),
    ) {
        Text(
            text = DEMO_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.space2, vertical = Spacing.space1),
        )
    }
}
