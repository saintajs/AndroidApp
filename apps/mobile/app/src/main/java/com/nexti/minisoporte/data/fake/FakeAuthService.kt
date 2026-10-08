/*
 * Fake authentication seam (design.md Decision 5, CA-06/CA-07/CONF-002/BR-006).
 *
 * Accepts exactly username `admin` / password `admin` (case-sensitive) and
 * issues a fixed, deterministic session token stored via [TokenStore]; every
 * other pair gets the SAME generic outcome — no hint whether the username
 * exists (BR-006). Pure Kotlin: no Android imports, no randomness, and no
 * logging of credentials or token material anywhere (CONF-002). The parallel
 * task's FakeSeed is intentionally NOT imported here — private constants
 * below mirror the pinned demo-employee values.
 */
package com.nexti.minisoporte.data.fake

import com.nexti.minisoporte.data.model.UserProfile
import com.nexti.minisoporte.data.model.UserRole
import com.nexti.minisoporte.security.TokenStore

/**
 * Outcome of a sign-in attempt. Exactly two shapes: success with a [Session],
 * or [InvalidCredentials] for EVERY wrong pair (generic, non-enumerating).
 */
sealed interface AuthResult {
    data class Authenticated(
        val session: Session,
    ) : AuthResult

    data object InvalidCredentials : AuthResult
}

/**
 * An authenticated session: the fake token plus the signed-in profile.
 * Held ONLY in memory — never logged, never placed in any UiState (CONF-002).
 */
data class Session(
    val token: String,
    val user: UserProfile,
)

/**
 * Mock-first auth service backing the sign-in/sign-out journey with zero
 * backend. [signIn] issues a FIXED token so tests can assert it round-trips
 * through the [TokenStore]; [signOut] clears the store.
 */
class FakeAuthService(
    private val tokenStore: TokenStore,
) {
    /**
     * Accepts exactly `admin`/`admin` (case-sensitive); stores the issued
     * token via [TokenStore.save] and returns [AuthResult.Authenticated].
     * Any other pair — unknown user, wrong password, empty strings, case
     * variations — returns [AuthResult.InvalidCredentials] without throwing.
     */
    suspend fun signIn(
        username: String,
        password: String,
    ): AuthResult {
        if (username != VALID_USERNAME || password != VALID_PASSWORD) {
            return AuthResult.InvalidCredentials
        }
        tokenStore.save(FAKE_SESSION_TOKEN)
        val session =
            Session(
                token = FAKE_SESSION_TOKEN,
                user = DEMO_EMPLOYEE,
            )
        return AuthResult.Authenticated(session)
    }

    /** Clears the current session via [TokenStore.delete]. */
    suspend fun signOut() {
        tokenStore.delete()
    }

    private companion object {
        const val VALID_USERNAME = "admin"
        const val VALID_PASSWORD = "admin"

        /** Fixed, deterministic token — no randomness, safe to assert in tests. */
        const val FAKE_SESSION_TOKEN = "fake-session-token-admin"

        // Mirrors FakeSeed.DEMO_EMPLOYEE (private copy to avoid the parallel-build import race).
        val DEMO_EMPLOYEE =
            UserProfile(
                id = "00000000-0000-0000-0000-0000000000a1",
                email = "empleado@minisoporte.es",
                role = UserRole.EMPLOYEE,
                active = true,
                createdAt = "2026-01-15T09:00:00Z",
            )
    }
}
