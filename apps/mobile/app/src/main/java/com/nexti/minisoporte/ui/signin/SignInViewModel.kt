/*
 * S-01 sign-in ViewModel (US-006, design.md Decisions 1-4).
 *
 * Validates required fields (BR-001), submits against the FakeAuthService seam
 * (US-005, accepted unchanged), and resolves to either a consumed one-shot
 * navigation event or a generic inline failure (BR-006). Pure UDF per the
 * PlaceholderViewModel reference pattern; mock purity (BR-041) — no network
 * stack imports, and token material never enters UiState nor any log
 * (CONF-002). The fake persists the token itself on success, so this class
 * never touches TokenStore directly.
 */
package com.nexti.minisoporte.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexti.minisoporte.data.fake.AuthResult
import com.nexti.minisoporte.data.fake.FakeAuthService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SignInViewModel(
    private val authService: FakeAuthService,
) : ViewModel() {
    /**
     * Immutable UI state rendered by [SignInScreen]. Holds only what the user
     * typed plus the error/lock flags — never the session token (CONF-002).
     */
    data class SignInUiState(
        val email: String = "",
        val password: String = "",
        val emailError: String? = null,
        val passwordError: String? = null,
        val isSubmitting: Boolean = false,
        val generalError: String? = null,
    )

    /**
     * One-shot events: consumed exactly once by the screen inside
     * `LaunchedEffect(Unit)` — never re-delivered on recomposition.
     */
    sealed interface SignInEvent {
        data object NavigateToS02 : SignInEvent
    }

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    private val _events = Channel<SignInEvent>(Channel.BUFFERED)
    val events: Flow<SignInEvent> = _events.receiveAsFlow()

    /** Updates the typed email and clears ONLY that field's error. */
    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, emailError = null) }
    }

    /** Updates the typed password and clears ONLY that field's error. */
    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, passwordError = null) }
    }

    /**
     * BR-001 first: blank email/password set ONLY the offending field errors
     * and never reach the auth seam (no isSubmitting, no generalError change).
     * With both fields present, flips the submit lock, clears a stale generic
     * error, and calls the fake in [viewModelScope]: success emits the
     * consumed [SignInEvent.NavigateToS02]; any rejected pair resolves to the
     * generic [SignInUiState.generalError] with both typed values preserved
     * and no field errors. Re-entrant submits while in flight are ignored
     * (BR-003).
     */
    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting) return

        val emailError = EMAIL_REQUIRED_ERROR.takeIf { current.email.isBlank() }
        val passwordError = PASSWORD_REQUIRED_ERROR.takeIf { current.password.isBlank() }
        if (emailError != null || passwordError != null) {
            _uiState.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, generalError = null) }
        viewModelScope.launch {
            when (val result = authService.signIn(current.email, current.password)) {
                is AuthResult.Authenticated -> {
                    // The fake already saved the token via TokenStore; the token
                    // itself stays out of UiState and out of logs (CONF-002).
                    _events.send(SignInEvent.NavigateToS02)
                    _uiState.update { it.copy(isSubmitting = false) }
                }

                AuthResult.InvalidCredentials ->
                    _uiState.update {
                        it.copy(
                            emailError = null,
                            passwordError = null,
                            isSubmitting = false,
                            generalError = GENERIC_ERROR,
                        )
                    }
            }
        }
    }

    /**
     * Drains any pending one-shot navigation event. The screen consumes events
     * by collecting [events] (receiveAsFlow delivers each exactly once), so
     * this is only a belt-and-braces reset for re-entry paths.
     */
    fun consumeNavigationEvent() {
        while (_events.tryReceive().isSuccess) {
            // Intentionally empty: draining means the event is dropped.
        }
    }

    private companion object {
        const val EMAIL_REQUIRED_ERROR = "Ingresa tu correo electrónico"
        const val PASSWORD_REQUIRED_ERROR = "Ingresa tu contraseña"
        const val GENERIC_ERROR = "Credenciales inválidas. Verifica tus datos e inténtalo de nuevo."
    }
}
