/*
 * Plain JVM unit tests for AuthInterceptor via OkHttp MockWebServer (US-012,
 * testing.yaml components_under_test "network/AuthInterceptor").
 *
 * Asserts BEHAVIOUR from the spec:
 *  - a stored token produces EXACTLY ONE "Authorization: Bearer <token>"
 *    header on the outgoing request (CA-04);
 *  - no token -> the request proceeds with NO Authorization header — never
 *    "Bearer null";
 *  - with body-level logging wired the way ApiClient builds it
 *    (redactHeader("Authorization")), NO log line contains token material
 *    (INSTR-008 / CONF-002).
 *
 * Token material never appears in assertion failure messages: the redaction
 * assertions use static messages and the captured log buffer is never
 * interpolated into a failure string. MockWebServer is localhost-only — no
 * real network. The store seam is the in-memory fake (BR-006): plain JVM
 * tests never touch the platform secure-storage-backed implementation.
 */
package com.nexti.minisoporte.network

import com.nexti.minisoporte.security.InMemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun storedTokenProducesExactlyOneBearerAuthorizationHeader() {
        val store = InMemoryTokenStore()
        runBlocking { store.save(TEST_TOKEN) }
        val client = ApiClient.okHttpClient(store)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(requestTo(server)).execute().use { response ->
            assertEquals(200, response.code)
        }

        val recorded = server.takeRequest()
        val authValues = recorded.headers.values("Authorization")
        assertEquals(1, authValues.size)
        assertEquals("Bearer $TEST_TOKEN", recorded.getHeader("Authorization"))
    }

    @Test
    fun missingTokenProceedsWithNoAuthorizationHeader() {
        val store = InMemoryTokenStore()
        assertNull(runBlocking { store.read() })
        val client = ApiClient.okHttpClient(store)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(requestTo(server)).execute().use { response ->
            assertEquals(200, response.code)
        }

        val recorded = server.takeRequest()
        assertNull(
            "must never send a placeholder credential",
            recorded.getHeader("Authorization"),
        )
        assertEquals(0, recorded.headers.values("Authorization").size)
    }

    @Test
    fun deletedTokenStopsBeingAttachedOnTheNextRequest() {
        val store = InMemoryTokenStore()
        runBlocking {
            store.save(TEST_TOKEN)
            store.delete()
        }
        val client = ApiClient.okHttpClient(store)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(requestTo(server)).execute().use { response ->
            assertEquals(200, response.code)
        }

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun debugLoggingRedactsTheAuthorizationHeaderSoNoLogLineCarriesTokenMaterial() {
        val store = InMemoryTokenStore()
        runBlocking { store.save(TEST_TOKEN) }
        val logLines = mutableListOf<String>()
        val client = clientWithApiClientStyleLogging(store, logLines)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(requestTo(server)).execute().use { response ->
            assertEquals(200, response.code)
        }

        val recorded = server.takeRequest()
        assertEquals("Bearer $TEST_TOKEN", recorded.getHeader("Authorization"))

        val logText = logLines.joinToString(separator = "\n")
        assertTrue("log captured no lines", logLines.isNotEmpty())
        assertTrue(
            "Authorization header must be redacted in logs",
            logText.contains(REDACTED_MARKER),
        )
        assertTrue(
            "log lines must not contain token material",
            !logText.contains(TEST_TOKEN),
        )
        assertTrue(
            "log lines must not contain raw credential material",
            !logText.contains("Bearer $TEST_TOKEN"),
        )
    }

    /*
     * Mirrors ApiClient.okHttpClient(debugLogging = true) exactly — auth
     * interceptor FIRST, then a body-level HttpLoggingInterceptor with the
     * Authorization header redacted — but swaps the default Logger for a
     * test-local buffer so the redaction can be asserted without touching
     * system output.
     */
    private fun clientWithApiClientStyleLogging(
        store: InMemoryTokenStore,
        logLines: MutableList<String>,
    ): OkHttpClient {
        val logging =
            HttpLoggingInterceptor { message -> logLines.add(message) }.apply {
                level = HttpLoggingInterceptor.Level.BODY
                redactHeader("Authorization")
            }
        return ApiClient
            .okHttpClient(store)
            .newBuilder()
            .addInterceptor(logging)
            .build()
    }

    private fun requestTo(server: MockWebServer): Request =
        Request
            .Builder()
            .url(server.url("/api/incidents"))
            .build()

    private companion object {
        const val TEST_TOKEN = "test-session-token-abc123"
        const val REDACTED_MARKER = "██"
    }
}
