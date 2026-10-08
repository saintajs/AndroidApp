/*
 * Plain JVM contract tests for RetrofitAuthService over OkHttp MockWebServer
 * (US-012, testing.yaml components_under_test "RetrofitAuthService (login
 * branches)", CA-06).
 *
 * Fixtures are copied VERBATIM from backend.yaml test_access_points — the
 * exact Spanish server copy must round-trip untouched. The HTTP stack is the
 * REAL one (ApiClient.create + AuthInterceptor + the kotlinx-serialization
 * converter) against a localhost-only MockWebServer; no real network.
 *
 * Branch coverage: 200 (token stored, TTL-only result), 401 (verbatim
 * non-enumerating copy), 422 (loc -> Spanish per-field copy, unmapped loc ->
 * generic fallback, never raw English msg), 429 (verbatim + Retry-After
 * digits), transport failure and 5xx (TransportError), GET /api/me 200/401
 * (401 clears the token and fails with SessionExpiredException — the
 * recovery signal).
 *
 * Token material never appears in assertion failure messages: token equality
 * is checked inside assertTrue with static messages, and result objects are
 * only compared via a boolean containment check (CONF-002 / INSTR-008). The
 * store seam is the in-memory fake (BR-006).
 */
package com.nexti.minisoporte.data.remote

import com.nexti.minisoporte.data.model.UserRole
import com.nexti.minisoporte.network.ApiClient
import com.nexti.minisoporte.security.InMemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RetrofitAuthServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var tokenStore: InMemoryTokenStore
    private lateinit var service: RetrofitAuthService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = InMemoryTokenStore()
        val retrofit = ApiClient.create(server.url("/").toString(), tokenStore, debugLogging = false)
        service = RetrofitAuthService(retrofit.create(AuthApi::class.java), tokenStore)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun login200StoresTheFixtureTokenAndReportsExpiryOnlySuccess() {
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS))

        val result = runBlocking { service.signIn(EMAIL, PASSWORD) }

        assertEquals(RemoteAuthResult.Success(expiresInSeconds = 28800L), result)
        assertTrue(
            "a 200 must store the fixture access token in the TokenStore",
            runBlocking { tokenStore.read() } == LOGIN_ACCESS_TOKEN,
        )
        assertNoTokenMaterial(result)
    }

    @Test
    fun login401YieldsTheExactGenericNonEnumeratingSpanishCopy() {
        server.enqueue(jsonResponse(401, LOGIN_401))

        val result = runBlocking { service.signIn(EMAIL, PASSWORD) } as RemoteAuthResult.InvalidCredentials

        assertEquals(
            "the 401 server detail must be copied verbatim",
            "Credenciales inválidas",
            result.message,
        )
        assertNoTokenMaterial(result)
    }

    @Test
    fun login422EmailLocMapsToSpanishEmailCopyWithNullPasswordError() {
        server.enqueue(jsonResponse(422, LOGIN_422_EMAIL))

        val result = runBlocking { service.signIn("not-an-email", PASSWORD) } as RemoteAuthResult.ValidationError

        assertEquals("Ingresa un correo electrónico válido", result.emailError)
        assertNull("no password issue was reported", result.passwordError)
        assertEquals("Revisa los datos ingresados e inténtalo de nuevo.", result.generalError)
        assertTrue(
            "the raw English server msg must never surface",
            !result.emailError!!.contains(RAW_EMAIL_MSG_FRAGMENT),
        )
    }

    @Test
    fun login422UnmappedLocFallsBackToGenericNeverRawEnglish() {
        server.enqueue(jsonResponse(422, LOGIN_422_UNMAPPED))

        val result = runBlocking { service.signIn(EMAIL, PASSWORD) } as RemoteAuthResult.ValidationError

        assertNull(result.emailError)
        assertNull(result.passwordError)
        assertEquals("Revisa los datos ingresados e inténtalo de nuevo.", result.generalError)
        assertTrue(
            "the raw English server msg must never surface",
            !result.generalError.contains(RAW_UNMAPPED_MSG_FRAGMENT),
        )
    }

    @Test
    fun login429WithRetryAfterHeaderSurfacesTheSeconds() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "42")
                .setBody(LOGIN_429),
        )

        val result = runBlocking { service.signIn(EMAIL, PASSWORD) } as RemoteAuthResult.RateLimited

        assertEquals(
            "the 429 server detail must be copied verbatim",
            "Demasiados intentos. Inténtelo de nuevo más tarde.",
            result.message,
        )
        assertEquals(42L, result.retryAfterSeconds)
        assertNoTokenMaterial(result)
    }

    @Test
    fun login429WithoutRetryAfterHeaderYieldsNullSeconds() {
        server.enqueue(jsonResponse(429, LOGIN_429))

        val result = runBlocking { service.signIn(EMAIL, PASSWORD) } as RemoteAuthResult.RateLimited

        assertEquals("Demasiados intentos. Inténtelo de nuevo más tarde.", result.message)
        assertNull("no Retry-After header means null seconds", result.retryAfterSeconds)
    }

    @Test
    fun transportFailureYieldsRetryableTransportError() {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.shutdown()
        val deadRetrofit = ApiClient.create(deadUrl, tokenStore, debugLogging = false)
        val deadService = RetrofitAuthService(deadRetrofit.create(AuthApi::class.java), tokenStore)

        val result = runBlocking { deadService.signIn(EMAIL, PASSWORD) }

        assertTrue(
            "a transport failure must map to TransportError, never a crash",
            result is RemoteAuthResult.TransportError,
        )
        assertNoTokenMaterial(result)
    }

    @Test
    fun server5xxYieldsRetryableTransportError() {
        server.enqueue(jsonResponse(500, SERVER_500))

        val result = runBlocking { service.signIn(EMAIL, PASSWORD) }

        assertTrue(
            "an unexpected 5xx must map to TransportError, never a crash",
            result is RemoteAuthResult.TransportError,
        )
    }

    @Test
    fun fetchProfile200MapsTheMeFixtureIntoTheDomainProfile() {
        server.enqueue(jsonResponse(200, ME_SUCCESS))

        val profile = runBlocking { service.fetchProfile() }.getOrThrow()

        assertEquals("0b8f6c1e-5a4d-4c3b-9f2a-7d1e8b6a5c40", profile.id)
        assertEquals("employee@example.com", profile.email)
        assertEquals(UserRole.EMPLOYEE, profile.role)
        assertTrue("the fixture profile is active", profile.active)
        assertEquals("2026-10-01T09:00:00Z", profile.createdAt)
    }

    @Test
    fun fetchProfile401ClearsTheTokenAndSignalsSessionExpiredByType() {
        server.enqueue(jsonResponse(401, ME_401))
        runBlocking { tokenStore.save(LOGIN_ACCESS_TOKEN) }

        val profileResult = runBlocking { service.fetchProfile() }

        assertTrue("a 401 on /api/me must be a failure", profileResult.isFailure)
        val error = profileResult.exceptionOrNull()
        assertTrue(
            "the 401 recovery signal must be SessionExpiredException (asserted by TYPE)",
            error is SessionExpiredException,
        )
        assertTrue(
            "a 401 on /api/me must clear the stored token",
            runBlocking { tokenStore.read() } == null,
        )
        assertEquals("the failed profile call must never be retried silently", 1, server.requestCount)
    }

    @Test
    fun noResultBranchEverCarriesTokenMaterial() {
        server.enqueue(jsonResponse(200, LOGIN_SUCCESS))
        server.enqueue(jsonResponse(401, LOGIN_401))
        server.enqueue(jsonResponse(429, LOGIN_429))

        val results =
            runBlocking {
                listOf(
                    service.signIn(EMAIL, PASSWORD),
                    service.signIn(EMAIL, PASSWORD),
                    service.signIn(EMAIL, PASSWORD),
                )
            }

        assertEquals(3, results.size)
        results.forEach { result -> assertNoTokenMaterial(result) }
    }

    private fun jsonResponse(
        code: Int,
        body: String,
    ): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun assertNoTokenMaterial(result: RemoteAuthResult) {
        assertTrue(
            "no result object may carry token material",
            !result.toString().contains(LOGIN_ACCESS_TOKEN),
        )
    }

    private companion object {
        const val EMAIL = "employee@example.com"
        const val PASSWORD = "employee-password"

        // backend.yaml test_access_points.login / me — copied VERBATIM
        // (multi-line raw strings keep the JSON values identical; JSON
        // whitespace between tokens is insignificant).
        const val LOGIN_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.<claims>.<sig>"
        val LOGIN_SUCCESS =
            """
            {"access_token": "eyJhbGciOiJIUzI1NiJ9.<claims>.<sig>", "token_type": "bearer",
                "expires_in_seconds": 28800}
            """.trimIndent()
        const val LOGIN_401 =
            """{"detail": "Credenciales inválidas"}"""
        const val LOGIN_422_EMAIL =
            """{"detail": [{"loc": ["body", "email"], "msg": "value is not a valid email address...", "type": "value_error"}]}"""
        const val LOGIN_422_UNMAPPED =
            """{"detail": [{"loc": ["body", "unknown_field"], "msg": "Field required", "type": "missing"}]}"""
        const val LOGIN_429 =
            """{"detail": "Demasiados intentos. Inténtelo de nuevo más tarde."}"""
        val ME_SUCCESS =
            """
            {"id": "0b8f6c1e-5a4d-4c3b-9f2a-7d1e8b6a5c40", "email": "employee@example.com",
                "role": "employee", "active": true, "created_at": "2026-10-01T09:00:00Z"}
            """.trimIndent()
        const val ME_401 =
            """{"detail": "No autenticado"}"""
        const val SERVER_500 =
            """{"detail": "Error interno del servidor"}"""
        const val RAW_EMAIL_MSG_FRAGMENT = "value is not a valid email address"
        const val RAW_UNMAPPED_MSG_FRAGMENT = "Field required"
    }
}
