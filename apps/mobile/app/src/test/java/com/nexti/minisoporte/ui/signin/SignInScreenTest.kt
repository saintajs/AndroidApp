/*
 * Robolectric-backed Compose component tests for the S-01 sign-in screen
 * (design.md Decision 6, testing.yaml critical_cases — "SignInScreen
 * composables with s01_* tags"). Renders the REAL SignInScreen on the real
 * in-memory fakes (FakeAuthService + InMemoryTokenStore) — no mocks, no
 * network; fully offline inside ./gradlew check (CA-06). All lookups go
 * through the s01_* test tags (or exact supporting/error texts); colors are
 * never asserted (testing.yaml rules).
 *
 * Dispatchers: createComposeRule() owns Dispatchers.Main (UnconfinedTestDispatcher)
 * and shares the test thread with Robolectric, so the ViewModel's viewModelScope
 * coroutines resolve eagerly — the fake needs no virtual-time advance. The only
 * non-eager suspension is the BR-003 submit lock, observed by gating
 * TokenStore.save() on a CompletableDeferred (GatedTokenStore below). Every
 * state transition is flushed with composeTestRule.waitForIdle() — no sleeps.
 */
package com.nexti.minisoporte.ui.signin

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nexti.minisoporte.data.fake.FakeAuthService
import com.nexti.minisoporte.security.InMemoryTokenStore
import com.nexti.minisoporte.security.TokenStore
import com.nexti.minisoporte.ui.navigation.Destination
import com.nexti.minisoporte.ui.theme.MiniSoporteTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// h960dp: Robolectric's default window (320x470px) puts the submit button and
// demo hint BELOW the fold of the scrollable S-01 column, so injected touch
// clicks would miss them; a tall viewport keeps the whole screen on-window.
@Config(sdk = [36], qualifiers = "h960dp")
class SignInScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // CA-04: the demo hint box renders the pinned demo credentials verbatim.
    // The tagged Surface does NOT merge descendants, so the text is read from
    // its tagged-subtree child node.
    @Test
    fun demoHintBoxRendersExactDemoCredentials() {
        setContentWith(createViewModel())

        val hintBox = composeTestRule.onNodeWithTag(TAG_DEMO_CREDENTIALS).fetchSemanticsNode()
        val hintBoxTexts =
            hintBox.children.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        assertTrue(
            "demo hint box must render exactly '$DEMO_HINT'",
            hintBoxTexts.singleOrNull()?.text == DEMO_HINT,
        )
    }

    // BR-001 through the screen: empty submit flags BOTH fields with their
    // Spanish supporting texts, keeps the button enabled for retry, and never
    // shows the generic error.
    @Test
    fun emptySubmitShowsPerFieldErrorsKeepsButtonEnabledAndNoGenericError() {
        setContentWith(createViewModel())

        composeTestRule.onNodeWithTag(TAG_SUBMIT_BUTTON).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(EMAIL_REQUIRED_ERROR).assertExists()
        composeTestRule.onNodeWithText(PASSWORD_REQUIRED_ERROR).assertExists()
        composeTestRule.onNodeWithTag(TAG_SUBMIT_BUTTON).assertIsEnabled()
        composeTestRule.onNodeWithTag(TAG_ERROR_GENERIC).assertDoesNotExist()
    }

    // BR-003 through the screen: with the token save gated, the fake is
    // "in flight" after the click — the button is DISABLED; once the gate is
    // released and the fake resolves, the button is enabled again.
    @Test
    fun submitLockDisablesButtonWhileInFlightThenReenablesAfterFakeResolves() {
        val store = GatedTokenStore()
        setContentWith(SignInViewModel(FakeAuthService(store)))
        typeCredentials(DEMO_USERNAME, DEMO_PASSWORD)
        val submit = composeTestRule.onNodeWithTag(TAG_SUBMIT_BUTTON)
        submit.assertIsEnabled()

        submit.performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SUBMIT_BUTTON).assertIsNotEnabled()

        store.release()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SUBMIT_BUTTON).assertIsEnabled()
        composeTestRule.onNodeWithTag(TAG_ERROR_GENERIC).assertDoesNotExist()
    }

    // BR-006 through the screen: a rejected pair renders the single generic
    // error (no per-field errors) and BOTH typed values remain in the fields.
    @Test
    fun rejectedCredentialsShowGenericErrorAndPreserveTypedValues() {
        setContentWith(createViewModel())
        typeCredentials(DEMO_USERNAME, REJECTED_PASSWORD)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SUBMIT_BUTTON).performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag(TAG_ERROR_GENERIC)
            .assertExists()
            .assertTextContains(GENERIC_ERROR)
        composeTestRule.onNodeWithTag(TAG_EMAIL_INPUT).assertTextContains(DEMO_USERNAME)
        composeTestRule.onNodeWithTag(TAG_PASSWORD_INPUT).assertTextContains(REJECTED_PASSWORD)
        composeTestRule.onNodeWithText(EMAIL_REQUIRED_ERROR).assertDoesNotExist()
        composeTestRule.onNodeWithText(PASSWORD_REQUIRED_ERROR).assertDoesNotExist()
    }

    // Journey 1 through a REAL NavHost (production wiring shape): start on
    // S-01, sign in with admin/admin, arrive at the S-02 destination marker.
    @Test
    fun adminAdminSuccessNavigatesToS02Destination() {
        val viewModel = createViewModel()
        composeTestRule.setContent {
            MiniSoporteTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = Destination.SignIn.ROUTE,
                ) {
                    composable(Destination.SignIn.ROUTE) {
                        SignInScreen(
                            viewModel = viewModel,
                            onNavigateToS02 = {
                                navController.navigate(Destination.Incidents.ROUTE)
                            },
                        )
                    }
                    composable(Destination.Incidents.ROUTE) {
                        Text(text = S02_MARKER)
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        typeCredentials(DEMO_USERNAME, DEMO_PASSWORD)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SUBMIT_BUTTON).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(S02_MARKER).assertExists()
        composeTestRule.onNodeWithTag(TAG_ERROR_GENERIC).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TAG_EMAIL_INPUT).assertDoesNotExist()
    }

    /** Builds the screen under the app theme with the fakes-built ViewModel. */
    private fun setContentWith(viewModel: SignInViewModel) {
        composeTestRule.setContent {
            MiniSoporteTheme {
                SignInScreen(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun createViewModel() = SignInViewModel(FakeAuthService(InMemoryTokenStore()))

    private fun typeCredentials(
        email: String,
        password: String,
    ) {
        composeTestRule.onNodeWithTag(TAG_EMAIL_INPUT).performTextInput(email)
        composeTestRule.onNodeWithTag(TAG_PASSWORD_INPUT).performTextInput(password)
        composeTestRule.waitForIdle()
    }

    private companion object {
        const val DEMO_USERNAME = "admin"
        const val DEMO_PASSWORD = "admin"
        const val REJECTED_PASSWORD = "wrongpass"
        const val TAG_EMAIL_INPUT = "s01_email_input"
        const val TAG_PASSWORD_INPUT = "s01_password_input"
        const val TAG_SUBMIT_BUTTON = "s01_submit_button"
        const val TAG_DEMO_CREDENTIALS = "s01_demo_credentials"
        const val TAG_ERROR_GENERIC = "s01_error_generic"
        const val DEMO_HINT = "Demo: admin / admin"
        const val EMAIL_REQUIRED_ERROR = "Ingresa tu correo electrónico"
        const val PASSWORD_REQUIRED_ERROR = "Ingresa tu contraseña"
        const val GENERIC_ERROR = "Credenciales inválidas. Verifica tus datos e inténtalo de nuevo."

        /** Marker text of the test-local S-02 destination — plain text, no tag. */
        const val S02_MARKER = "Mis incidencias (S-02)"
    }
}

/**
 * TokenStore fake whose save() suspends until released: gates the successful
 * fake auth mid-flight so the BR-003 submit lock (isSubmitting) is observable
 * in the Compose tree. Otherwise identical to InMemoryTokenStore.
 */
private class GatedTokenStore : TokenStore {
    private val delegate = InMemoryTokenStore()
    private val gate = CompletableDeferred<Unit>()

    override suspend fun save(token: String) {
        gate.await()
        delegate.save(token)
    }

    override suspend fun read(): String? = delegate.read()

    override suspend fun delete() = delegate.delete()

    /** Resumes the gated save — the fake auth completes deterministically. */
    fun release() {
        gate.complete(Unit)
    }
}
