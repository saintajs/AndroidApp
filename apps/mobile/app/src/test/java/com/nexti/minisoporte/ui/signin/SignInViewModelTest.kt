/*
 * Plain JVM unit tests for the S-01 sign-in ViewModel (design.md Decision 6,
 * testing.yaml critical_cases). Drives the real in-memory fakes
 * (FakeAuthService + InMemoryTokenStore) under virtual time — no mocks, no
 * Robolectric, no network. Asserts BR-001 required-field validation never
 * reaches the auth seam, the BR-003 submit lock while the fake is in flight,
 * the token round-trip through the TokenStore on success, the BR-006 generic
 * failure for every other pair, and CONF-002 token purity of every emitted
 * UiState snapshot.
 */
package com.nexti.minisoporte.ui.signin

import com.nexti.minisoporte.data.fake.FakeAuthService
import com.nexti.minisoporte.security.InMemoryTokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // BR-001: blank email only — the exact per-field error, no lock, no auth.
    @Test
    fun submitWithBlankEmailShowsOnlyEmailErrorAndNeverCallsAuth() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = InMemoryTokenStore()
            val viewModel = SignInViewModel(FakeAuthService(store))
            val states = mutableListOf<SignInViewModel.SignInUiState>()
            val events = mutableListOf<SignInViewModel.SignInEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.toList(states)
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            viewModel.onPasswordChange(DEMO_PASSWORD)

            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(EMAIL_REQUIRED_ERROR, state.emailError)
            assertNull(state.passwordError)
            assertNull(state.generalError)
            assertFalse(state.isSubmitting)
            assertTrue("no snapshot may show the submit lock", states.none { it.isSubmitting })
            assertTrue("no navigation event may be emitted", events.isEmpty())
            assertNull("TokenStore must stay empty without a successful auth call", store.read())
            assertNoTokenMaterialIn(states)
        }

    // BR-001: blank password only — the email error stays clear.
    @Test
    fun submitWithBlankPasswordShowsOnlyPasswordErrorAndNeverCallsAuth() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = InMemoryTokenStore()
            val viewModel = SignInViewModel(FakeAuthService(store))
            val states = mutableListOf<SignInViewModel.SignInUiState>()
            val events = mutableListOf<SignInViewModel.SignInEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.toList(states)
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            viewModel.onEmailChange(DEMO_USERNAME)

            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(PASSWORD_REQUIRED_ERROR, state.passwordError)
            assertNull(state.emailError)
            assertNull(state.generalError)
            assertFalse(state.isSubmitting)
            assertTrue(states.none { it.isSubmitting })
            assertTrue(events.isEmpty())
            assertNull(store.read())
            assertNoTokenMaterialIn(states)
        }

    // BR-001: both blank — both offending fields flagged, auth seam untouched.
    @Test
    fun submitWithBothFieldsBlankShowsBothFieldErrorsAndNeverCallsAuth() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = InMemoryTokenStore()
            val viewModel = SignInViewModel(FakeAuthService(store))
            val states = mutableListOf<SignInViewModel.SignInUiState>()
            val events = mutableListOf<SignInViewModel.SignInEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.toList(states)
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(EMAIL_REQUIRED_ERROR, state.emailError)
            assertEquals(PASSWORD_REQUIRED_ERROR, state.passwordError)
            assertNull(state.generalError)
            assertFalse(state.isSubmitting)
            assertTrue(states.none { it.isSubmitting })
            assertTrue(events.isEmpty())
            assertNull(store.read())
            assertNoTokenMaterialIn(states)
        }

    // admin/admin: the lock is observed in flight, the fake saves the token
    // through the TokenStore, and exactly ONE NavigateToS02 is emitted.
    @Test
    fun adminAdminSubmitLocksStoresTokenAndEmitsSingleNavigationEvent() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = InMemoryTokenStore()
            val viewModel = SignInViewModel(FakeAuthService(store))
            val states = mutableListOf<SignInViewModel.SignInUiState>()
            val events = mutableListOf<SignInViewModel.SignInEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.toList(states)
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            viewModel.onEmailChange(DEMO_USERNAME)
            viewModel.onPasswordChange(DEMO_PASSWORD)

            viewModel.submit()

            assertTrue(
                "isSubmitting must be true while the auth call is in flight",
                viewModel.uiState.value.isSubmitting,
            )
            advanceUntilIdle()

            assertEquals(EXPECTED_TOKEN, store.read())
            assertEquals(
                "exactly one NavigateToS02 event",
                listOf(SignInViewModel.SignInEvent.NavigateToS02),
                events,
            )
            assertFalse(viewModel.uiState.value.isSubmitting)
            assertTrue("the lock was observed in flight", states.any { it.isSubmitting })
            assertNull(viewModel.uiState.value.generalError)
            assertNoTokenMaterialIn(states)
        }

    // BR-006: every wrong pair gets the SAME generic message, typed values
    // preserved, no field errors, no navigation, no token in the store.
    @Test
    fun rejectedPairsShowGenericErrorPreserveValuesAndNeverNavigate() =
        runTest(mainDispatcherRule.testDispatcher) {
            val rejectedPairs =
                listOf(
                    DEMO_USERNAME to "wrong-password",
                    "unknown" to "unknown",
                    "unknown" to DEMO_PASSWORD,
                )
            for ((email, password) in rejectedPairs) {
                val store = InMemoryTokenStore()
                val viewModel = SignInViewModel(FakeAuthService(store))
                val states = mutableListOf<SignInViewModel.SignInUiState>()
                val events = mutableListOf<SignInViewModel.SignInEvent>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.toList(states)
                }
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.events.toList(events)
                }
                viewModel.onEmailChange(email)
                viewModel.onPasswordChange(password)

                viewModel.submit()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                assertEquals(GENERIC_ERROR, state.generalError)
                assertEquals("typed email preserved", email, state.email)
                assertEquals("typed password preserved", password, state.password)
                assertNull(state.emailError)
                assertNull(state.passwordError)
                assertFalse(state.isSubmitting)
                assertTrue("no navigation event on failure", events.isEmpty())
                assertNull("failed auth must not store a token", store.read())
                assertNoTokenMaterialIn(states)
            }
        }

    // CONF-002: no emitted UiState snapshot — success or failure journey —
    // may carry the token in any of its string properties.
    @Test
    fun noUiStateSnapshotEverCarriesTokenMaterial() =
        runTest(mainDispatcherRule.testDispatcher) {
            val successStore = InMemoryTokenStore()
            val successViewModel = SignInViewModel(FakeAuthService(successStore))
            val successStates = mutableListOf<SignInViewModel.SignInUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                successViewModel.uiState.toList(successStates)
            }
            successViewModel.onEmailChange(DEMO_USERNAME)
            successViewModel.onPasswordChange(DEMO_PASSWORD)
            successViewModel.submit()
            advanceUntilIdle()
            assertEquals(EXPECTED_TOKEN, successStore.read())

            val failureStore = InMemoryTokenStore()
            val failureViewModel = SignInViewModel(FakeAuthService(failureStore))
            val failureStates = mutableListOf<SignInViewModel.SignInUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                failureViewModel.uiState.toList(failureStates)
            }
            failureViewModel.onEmailChange(DEMO_USERNAME)
            failureViewModel.onPasswordChange("wrong-password")
            failureViewModel.submit()
            advanceUntilIdle()

            assertFalse(successStates.isEmpty())
            assertFalse(failureStates.isEmpty())
            assertNoTokenMaterialIn(successStates)
            assertNoTokenMaterialIn(failureStates)
        }

    // BR-003: a second submit while the lock is held is ignored — one auth
    // call, one token, one navigation event.
    @Test
    fun secondSubmitWhileSubmittingIsIgnored() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = InMemoryTokenStore()
            val viewModel = SignInViewModel(FakeAuthService(store))
            val events = mutableListOf<SignInViewModel.SignInEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            viewModel.onEmailChange(DEMO_USERNAME)
            viewModel.onPasswordChange(DEMO_PASSWORD)

            viewModel.submit()
            assertTrue(viewModel.uiState.value.isSubmitting)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("exactly one event despite double submit", 1, events.size)
            assertEquals(SignInViewModel.SignInEvent.NavigateToS02, events.single())
            assertEquals(EXPECTED_TOKEN, store.read())
            assertFalse(viewModel.uiState.value.isSubmitting)
        }

    // Per-field error clearing: typing into a field clears ONLY that field's
    // error; the other one stays until its own field changes.
    @Test
    fun typingIntoAFieldClearsOnlyThatFieldsError() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = SignInViewModel(FakeAuthService(InMemoryTokenStore()))

            viewModel.submit()
            assertEquals(EMAIL_REQUIRED_ERROR, viewModel.uiState.value.emailError)
            assertEquals(PASSWORD_REQUIRED_ERROR, viewModel.uiState.value.passwordError)

            viewModel.onEmailChange(DEMO_USERNAME)

            var state = viewModel.uiState.value
            assertNull("email typing clears the email error", state.emailError)
            assertEquals("password error survives email typing", PASSWORD_REQUIRED_ERROR, state.passwordError)

            viewModel.onPasswordChange(DEMO_PASSWORD)

            state = viewModel.uiState.value
            assertNull(state.emailError)
            assertNull(state.passwordError)
            assertFalse(state.isSubmitting)
        }

    /**
     * CONF-002: no string property of any observed snapshot may equal or
     * contain the fake token — asserted field-wise, not on the whole object.
     */
    private fun assertNoTokenMaterialIn(states: List<SignInViewModel.SignInUiState>) {
        for (state in states) {
            val textFields =
                listOf(
                    state.email,
                    state.password,
                    state.emailError,
                    state.passwordError,
                    state.generalError,
                )
            for (text in textFields) {
                if (text != null) {
                    assertFalse("UiState must never carry token material", text.contains(EXPECTED_TOKEN))
                }
            }
            assertFalse(state.toString().contains(EXPECTED_TOKEN))
        }
    }

    private companion object {
        const val DEMO_USERNAME = "admin"
        const val DEMO_PASSWORD = "admin"
        const val EMAIL_REQUIRED_ERROR = "Ingresa tu correo electrónico"
        const val PASSWORD_REQUIRED_ERROR = "Ingresa tu contraseña"
        const val GENERIC_ERROR = "Credenciales inválidas. Verifica tus datos e inténtalo de nuevo."

        /** Fixed token issued by the fake — asserted only via assertEquals. */
        const val EXPECTED_TOKEN = "fake-session-token-admin"
    }
}
