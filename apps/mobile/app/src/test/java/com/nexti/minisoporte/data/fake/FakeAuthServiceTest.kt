/*
 * Plain JVM unit tests for the fake authentication seam
 * (design.md Decision 7, spec "Fake authentication accepting exactly
 * admin/admin" — CA-06/BR-006).
 *
 * The rejection is asserted to be generic: the same AuthResult.InvalidCredentials
 * shape for every wrong pair, no reason/detail field, and no partial state
 * written to the TokenStore. No credential or token material is printed.
 */
package com.nexti.minisoporte.data.fake

import com.nexti.minisoporte.data.model.UserRole
import com.nexti.minisoporte.security.InMemoryTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAuthServiceTest {
    @Test
    fun correctDemoCredentialsSignInAndStoreToken() =
        runBlocking {
            val store = InMemoryTokenStore()
            val service = FakeAuthService(store)

            val result = service.signIn(VALID_USERNAME, VALID_PASSWORD)

            val session = (result as AuthResult.Authenticated).session
            assertEquals(EXPECTED_TOKEN, session.token)
            assertEquals(FakeSeed.DEMO_EMPLOYEE_ID, session.user.id)
            assertEquals("empleado@minisoporte.es", session.user.email)
            assertEquals(UserRole.EMPLOYEE, session.user.role)
            assertTrue(session.user.active)
            assertEquals(EXPECTED_TOKEN, store.read())
        }

    @Test
    fun everyOtherPairIsGenericallyRejected() =
        runBlocking {
            val wrongPairs =
                listOf(
                    VALID_USERNAME to "wrong-password",
                    "root" to VALID_PASSWORD,
                    "Admin" to VALID_PASSWORD,
                    "admin@example.com" to VALID_PASSWORD,
                    "" to "",
                )
            for ((username, password) in wrongPairs) {
                val store = InMemoryTokenStore()
                val service = FakeAuthService(store)

                val result = service.signIn(username, password)

                assertEquals(AuthResult.InvalidCredentials, result)
                assertNull(store.read())
            }
        }

    @Test
    fun signOutClearsStoredToken() =
        runBlocking {
            val store = InMemoryTokenStore()
            val service = FakeAuthService(store)
            service.signIn(VALID_USERNAME, VALID_PASSWORD)

            service.signOut()

            assertNull(store.read())
        }

    @Test
    fun authResultHasExactlyTheTwoDocumentedShapes() {
        val permittedShapeNames =
            (AuthResult::class.java.permittedSubclasses ?: emptyArray())
                .map { it.simpleName }
                .sorted()
        assertEquals(
            listOf("Authenticated", "InvalidCredentials"),
            permittedShapeNames,
        )
        val rejectionFieldNames =
            AuthResult.InvalidCredentials::class.java.declaredFields
                .map { it.name }
        assertFalse(rejectionFieldNames.contains("reason"))
    }

    private companion object {
        const val VALID_USERNAME = "admin"
        const val VALID_PASSWORD = "admin"

        /** Fixed token issued by the fake — asserted only via assertEquals. */
        const val EXPECTED_TOKEN = "fake-session-token-admin"
    }
}
