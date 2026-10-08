/*
 * Plain JVM unit tests for the in-memory TokenStore fake
 * (design.md Decision 7, spec "In-memory TokenStore fake with safe token
 * handling" / "Token lifecycle in plain JVM tests").
 *
 * No token material is ever printed or embedded in a failure message: the
 * assertions below are plain assertEquals/assertNull calls (CONF-002).
 */
package com.nexti.minisoporte.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryTokenStoreTest {
    @Test
    fun readOnFreshStoreReturnsNull() =
        runBlocking {
            val store = InMemoryTokenStore()
            assertNull(store.read())
        }

    @Test
    fun saveThenReadRoundTrips() =
        runBlocking {
            val store = InMemoryTokenStore()
            store.save(TEST_TOKEN)
            assertEquals(TEST_TOKEN, store.read())
        }

    @Test
    fun deleteLeavesNothingReadable() =
        runBlocking {
            val store = InMemoryTokenStore()
            store.save(TEST_TOKEN)
            store.delete()
            assertNull(store.read())
        }

    @Test
    fun deleteOnEmptyStoreIsANoOp() =
        runBlocking {
            val store = InMemoryTokenStore()
            store.delete()
            assertNull(store.read())
        }

    @Test
    fun reSaveAfterDeleteRoundTripsAgain() =
        runBlocking {
            val store = InMemoryTokenStore()
            store.save(TEST_TOKEN)
            store.delete()
            store.save(TEST_TOKEN)
            assertEquals(TEST_TOKEN, store.read())
        }

    private companion object {
        const val TEST_TOKEN = "test-session-token"
    }
}
