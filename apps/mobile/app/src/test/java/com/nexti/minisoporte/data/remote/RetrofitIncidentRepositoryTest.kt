/*
 * Plain JVM contract tests for RetrofitIncidentRepository over OkHttp
 * MockWebServer (US-012, testing.yaml components_under_test
 * "RetrofitIncidentRepository (REST branches)", CA-09).
 *
 * Fixtures are copied VERBATIM from backend.yaml test_access_points. The
 * HTTP stack is the REAL one (ApiClient.create + AuthInterceptor + the
 * kotlinx-serialization converter) against a localhost-only MockWebServer;
 * no real network.
 *
 * Covered branches: 200 list/detail/update/create (mappings, wire keys,
 * query-param forwarding), 404 detail (missing == foreign, indistinguishable),
 * 409 transition (error-state data, never a throw), 403/429/5xx/transport
 * on detail and update (RetryableError, never a crash), the REAL 401 recovery path on every
 * protected call (token cleared, onSessionExpired fired EXACTLY ONCE, no
 * silent retry — server.requestCount stays 1), and create-as-Pending.
 *
 * Token material never appears in assertion failure messages: token presence
 * is checked inside assertTrue with static messages (CONF-002 / INSTR-008).
 * The store seam is the in-memory fake (BR-006).
 */
package com.nexti.minisoporte.data.remote

import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
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
import java.util.concurrent.atomic.AtomicInteger

class RetrofitIncidentRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tokenStore: InMemoryTokenStore
    private lateinit var repository: RetrofitIncidentRepository
    private lateinit var expirySignals: AtomicInteger

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = InMemoryTokenStore()
        expirySignals = AtomicInteger(0)
        val retrofit = ApiClient.create(server.url("/").toString(), tokenStore, debugLogging = false)
        repository =
            RetrofitIncidentRepository(
                incidentApi = retrofit.create(IncidentApi::class.java),
                tokenStore = tokenStore,
                onSessionExpired = { expirySignals.incrementAndGet() },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listIncidentsMapsTheFixtureArrayInResponseOrderWithListWireGapsFilled() {
        server.enqueue(jsonResponse(200, LIST_SUCCESS))

        val incidents = runBlocking { repository.listIncidents(ownerId = OWNER_ID, status = null) }

        assertEquals(1, incidents.size)
        val incident = incidents[0]
        assertEquals("1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81", incident.id)
        assertEquals("Impresora no responde", incident.title)
        assertEquals("absent list-item description maps to empty string", "", incident.description)
        assertEquals(IncidentCategory.EQUIPMENT, incident.category)
        assertEquals(IncidentPriority.MEDIUM, incident.priority)
        assertEquals(IncidentStatus.PENDING, incident.status)
        assertEquals("2026-10-05T10:15:00Z", incident.createdAt)
        assertNull("the wire carries no resolved_at field", incident.resolvedAt)

        val recorded = server.takeRequest()
        assertEquals("/api/incidents", recorded.path)
        assertNull("no filter must omit the status query param", recorded.requestUrl!!.queryParameter("status"))
    }

    @Test
    fun listIncidentsForwardsTheStatusFilterAsTheEnglishWireKey() {
        server.enqueue(jsonResponse(200, LIST_SUCCESS))

        runBlocking { repository.listIncidents(ownerId = OWNER_ID, status = IncidentStatus.PENDING) }

        assertEquals("pending", server.takeRequest().requestUrl!!.queryParameter("status"))
    }

    @Test
    fun detailSuccessMapsHistoryAscWithNullPreviousStatusOnTheCreationEntry() {
        server.enqueue(jsonResponse(200, DETAIL_SUCCESS))

        val result = runBlocking { repository.getIncidentDetail(DETAIL_ID) }

        val incident = (result as IncidentDetailResult.Found).incident
        assertEquals(DETAIL_ID, incident.id)
        assertEquals("La impresora de planta no responde desde ayer.", incident.description)
        assertEquals(IncidentStatus.PENDING, incident.status)
        assertEquals(1, incident.history.size)
        val entry = incident.history[0]
        assertNull("the creation entry keeps previous_status null", entry.previousStatus)
        assertEquals(IncidentStatus.PENDING, entry.newStatus)
        assertEquals("9c4d2e7b-8f1a-4b6c-a3d5-2e7f9b1c4d60", entry.id)
        assertEquals(DETAIL_ID, entry.incidentId)
        assertEquals("0b8f6c1e-5a4d-4c3b-9f2a-7d1e8b6a5c40", entry.changedByUserId)
        assertEquals("owner-scoped wire has no changed-by email", "", entry.changedByEmail)
        assertEquals("2026-10-05T10:15:00Z", entry.changedAt)
    }

    @Test
    fun detail404IsTheSameSafeNoAccessOutcomeForMissingAndForeignIds() {
        server.enqueue(jsonResponse(404, DETAIL_404))
        // Byte-identical fixture for the foreign-id case — the backend contract
        // makes missing and foreign indistinguishable.
        server.enqueue(jsonResponse(404, DETAIL_404))

        val missing = runBlocking { repository.getIncidentDetail(DETAIL_ID) }
        val foreign = runBlocking { repository.getIncidentDetail(DETAIL_ID) }

        assertEquals(IncidentDetailResult.NoAccess, missing)
        assertEquals(IncidentDetailResult.NoAccess, foreign)
        assertEquals("missing and foreign 404s are indistinguishable", missing, foreign)
        assertEquals("a 404 is not a session expiry", 0, expirySignals.get())
    }

    @Test
    fun updateStatusSuccessCarriesTheUpdatedIncidentAndAcceptedLatestHistory() {
        server.enqueue(jsonResponse(200, UPDATE_SUCCESS))

        val result = runBlocking { repository.updateStatus(DETAIL_ID, IncidentStatus.IN_PROGRESS) }

        val success = result as StatusUpdateResult.Success
        assertEquals(DETAIL_ID, success.incident.id)
        assertEquals(IncidentStatus.IN_PROGRESS, success.incident.status)
        assertEquals(IncidentStatus.PENDING, success.latestHistory.previousStatus)
        assertEquals(IncidentStatus.IN_PROGRESS, success.latestHistory.newStatus)
        assertEquals("4f7b1c8d-2a9e-4d3f-b6c0-8e5a1d7f3b92", success.latestHistory.id)

        val recorded = server.takeRequest()
        assertEquals("/api/incidents/$DETAIL_ID/status", recorded.path)
        assertTrue(
            "the PATCH body must carry only the English wire key for new_status",
            recorded.body.readUtf8().contains("\"new_status\":\"in_progress\""),
        )
    }

    @Test
    fun updateStatus409SurfacesInvalidTransitionAsDataNeverAnException() {
        server.enqueue(jsonResponse(409, UPDATE_409))

        val result = runBlocking { repository.updateStatus(DETAIL_ID, IncidentStatus.RESOLVED) }

        assertEquals(StatusUpdateResult.InvalidTransition, result)
    }

    @Test
    fun updateStatus404IsNoAccessAnd403IsRetryableError() {
        server.enqueue(jsonResponse(404, UPDATE_404))
        server.enqueue(jsonResponse(403, UPDATE_403))

        val notFound = runBlocking { repository.updateStatus(DETAIL_ID, IncidentStatus.IN_PROGRESS) }
        val forbidden = runBlocking { repository.updateStatus(DETAIL_ID, IncidentStatus.IN_PROGRESS) }

        assertEquals(StatusUpdateResult.NoAccess, notFound)
        assertEquals("403 collapses into the retryable error state", StatusUpdateResult.RetryableError, forbidden)
        assertEquals("neither failure is a session expiry", 0, expirySignals.get())
    }

    @Test
    fun real401OnDetailClearsTheTokenSignalsOnceAndNeverRetriesSilently() {
        server.enqueue(jsonResponse(401, PROTECTED_401))
        runBlocking { tokenStore.save(TEST_TOKEN) }

        val result = runBlocking { repository.getIncidentDetail(DETAIL_ID) }

        assertEquals(IncidentDetailResult.SessionExpired, result)
        assertTrue(
            "a real 401 must clear the stored token",
            runBlocking { tokenStore.read() } == null,
        )
        assertEquals("the recovery signal must fire exactly once", 1, expirySignals.get())
        assertEquals("the failed call must never be retried silently", 1, server.requestCount)
    }

    @Test
    fun updateStatus401AlsoClearsTheTokenAndSignalsOnceWithoutRetry() {
        server.enqueue(jsonResponse(401, PROTECTED_401))
        runBlocking { tokenStore.save(TEST_TOKEN) }

        val result = runBlocking { repository.updateStatus(DETAIL_ID, IncidentStatus.IN_PROGRESS) }

        assertEquals(StatusUpdateResult.SessionExpired, result)
        assertTrue(
            "a real 401 must clear the stored token",
            runBlocking { tokenStore.read() } == null,
        )
        assertEquals("the recovery signal must fire exactly once", 1, expirySignals.get())
        assertEquals("the failed call must never be retried silently", 1, server.requestCount)
    }

    @Test
    fun list401ReturnsAnEmptyListWithTheRecoverySideEffects() {
        server.enqueue(jsonResponse(401, PROTECTED_401))
        runBlocking { tokenStore.save(TEST_TOKEN) }

        val incidents = runBlocking { repository.listIncidents(ownerId = OWNER_ID, status = null) }

        assertTrue("the channel-less list path degrades to emptyList on 401", incidents.isEmpty())
        assertTrue(
            "a real 401 must clear the stored token",
            runBlocking { tokenStore.read() } == null,
        )
        assertEquals("the recovery signal must fire exactly once", 1, expirySignals.get())
        assertEquals("the failed call must never be retried silently", 1, server.requestCount)
    }

    @Test
    fun transportFailureMapsDetailAndUpdateStatusToRetryableErrorWithoutEscaping() {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.shutdown()
        val deadRetrofit = ApiClient.create(deadUrl, tokenStore, debugLogging = false)
        val deadRepository =
            RetrofitIncidentRepository(
                incidentApi = deadRetrofit.create(IncidentApi::class.java),
                tokenStore = tokenStore,
                onSessionExpired = { expirySignals.incrementAndGet() },
            )
        runBlocking { tokenStore.save(TEST_TOKEN) }

        val detail = runBlocking { deadRepository.getIncidentDetail(DETAIL_ID) }
        val updated = runBlocking { deadRepository.updateStatus(DETAIL_ID, IncidentStatus.IN_PROGRESS) }

        assertEquals("transport failure is a retryable error, never a crash", IncidentDetailResult.RetryableError, detail)
        assertEquals(
            "transport failure is a retryable error, never a crash",
            StatusUpdateResult.RetryableError,
            updated,
        )
        assertEquals("a transport failure is not a session expiry", 0, expirySignals.get())
    }

    @Test
    fun server5xxOnUpdateStatusMapsToRetryableError() {
        server.enqueue(jsonResponse(500, SERVER_500))

        val updated = runBlocking { repository.updateStatus(DETAIL_ID, IncidentStatus.IN_PROGRESS) }

        assertEquals("a 5xx is retryable, never a crash", StatusUpdateResult.RetryableError, updated)
    }

    @Test
    fun server5xxOnDetailYieldsRetryableError() {
        server.enqueue(jsonResponse(500, SERVER_500))

        val detail = runBlocking { repository.getIncidentDetail(DETAIL_ID) }

        assertEquals("a 5xx is retryable, never a safe no-access", IncidentDetailResult.RetryableError, detail)
        assertEquals("a 5xx is not a session expiry", 0, expirySignals.get())
    }

    @Test
    fun server429And403OnDetailAlsoYieldRetryableError() {
        server.enqueue(jsonResponse(429, SERVER_429))
        server.enqueue(jsonResponse(403, UPDATE_403))

        val throttled = runBlocking { repository.getIncidentDetail(DETAIL_ID) }
        val forbidden = runBlocking { repository.getIncidentDetail(DETAIL_ID) }

        assertEquals("429 collapses into the retryable error state", IncidentDetailResult.RetryableError, throttled)
        assertEquals("403 collapses into the retryable error state", IncidentDetailResult.RetryableError, forbidden)
        assertEquals("neither failure is a session expiry", 0, expirySignals.get())
    }

    @Test
    fun createIncident201ReturnsTheCreatedPendingIncidentWithDescriptionPreserved() {
        server.enqueue(jsonResponse(201, CREATE_SUCCESS))

        val result =
            runBlocking {
                repository.createIncidentResult(
                    title = "Impresora no responde",
                    description = CREATE_DESCRIPTION,
                    category = IncidentCategory.EQUIPMENT,
                    priority = IncidentPriority.MEDIUM,
                )
            }

        val incident = (result as CreateResult.Success).incident
        assertEquals("1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81", incident.id)
        assertEquals("create-as-Pending", IncidentStatus.PENDING, incident.status)
        assertEquals(CREATE_DESCRIPTION, incident.description)
        assertEquals(IncidentCategory.EQUIPMENT, incident.category)
        assertEquals(IncidentPriority.MEDIUM, incident.priority)
        assertEquals("2026-10-05T10:15:00Z", incident.createdAt)

        val recorded = server.takeRequest()
        assertEquals("/api/incidents", recorded.path)
        val sentBody = recorded.body.readUtf8()
        assertTrue(
            "create must cross the wire with the English category key",
            sentBody.contains("\"category\":\"equipment\""),
        )
        assertTrue(
            "create must cross the wire with the English priority key",
            sentBody.contains("\"priority\":\"medium\""),
        )
    }

    private fun jsonResponse(
        code: Int,
        body: String,
    ): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private companion object {
        const val TEST_TOKEN = "test-session-token-abc123"
        const val OWNER_ID = "ignored-owner-the-jwt-owner-scopes-server-side"
        const val DETAIL_ID = "1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81"
        const val CREATE_DESCRIPTION = "La impresora de planta no responde desde ayer."

        // backend.yaml test_access_points — copied VERBATIM (multi-line raw
        // strings keep the JSON values identical; JSON whitespace between
        // tokens is insignificant).
        const val PROTECTED_401 =
            """{"detail": "No autenticado"}"""
        val LIST_SUCCESS =
            """
            [{"id": "1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81", "title": "Impresora no responde",
                "category": "equipment", "priority": "medium", "status": "pending",
                "created_at": "2026-10-05T10:15:00Z", "updated_at": "2026-10-05T10:15:00Z"}]
            """.trimIndent()
        val DETAIL_SUCCESS =
            """
            {"id": "1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81", "title": "Impresora no responde",
                "description": "La impresora de planta no responde desde ayer.", "category": "equipment",
                "priority": "medium", "status": "pending", "created_at": "2026-10-05T10:15:00Z",
                "updated_at": "2026-10-05T10:15:00Z",
                "history": [{"id": "9c4d2e7b-8f1a-4b6c-a3d5-2e7f9b1c4d60",
                    "incident_id": "1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81", "previous_status": null,
                    "new_status": "pending", "changed_by": "0b8f6c1e-5a4d-4c3b-9f2a-7d1e8b6a5c40",
                    "changed_at": "2026-10-05T10:15:00Z"}]}
            """.trimIndent()
        const val DETAIL_404 =
            """{"detail": "Incidencia no encontrada"}"""
        val UPDATE_SUCCESS =
            """
            {"incident": {"id": "1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81", "title": "Impresora no responde",
                "description": "La impresora de planta no responde desde ayer.", "category": "equipment",
                "priority": "medium", "status": "in_progress", "created_at": "2026-10-05T10:15:00Z",
                "updated_at": "2026-10-05T11:00:00Z"},
                "latest_history": {"id": "4f7b1c8d-2a9e-4d3f-b6c0-8e5a1d7f3b92",
                    "incident_id": "1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81", "previous_status": "pending",
                    "new_status": "in_progress", "changed_by": "c2d4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f",
                    "changed_at": "2026-10-05T11:00:00Z"}}
            """.trimIndent()
        const val UPDATE_403 =
            """{"detail": "No autorizado"}"""
        const val UPDATE_404 =
            """{"detail": "Incidencia no encontrada"}"""
        const val UPDATE_409 =
            """{"detail": "Transición de estado no válida"}"""
        val CREATE_SUCCESS =
            """
            {"id": "1e6c9a2f-3b7d-4e8f-9c0a-5d2b7e4f6a81", "title": "Impresora no responde",
                "description": "La impresora de planta no responde desde ayer.", "category": "equipment",
                "priority": "medium", "status": "pending", "created_at": "2026-10-05T10:15:00Z",
                "updated_at": "2026-10-05T10:15:00Z"}
            """.trimIndent()
        const val SERVER_500 =
            """{"detail": "Error interno del servidor"}"""
        const val SERVER_429 =
            """{"detail": "Demasiadas peticiones"}"""
    }
}
