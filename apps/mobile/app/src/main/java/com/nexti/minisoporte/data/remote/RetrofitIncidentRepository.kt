package com.nexti.minisoporte.data.remote

import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentHistoryEntry
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.remote.mappers.createIncidentRequestDtoOf
import com.nexti.minisoporte.data.remote.mappers.toDomain
import com.nexti.minisoporte.data.remote.mappers.toDomainPair
import com.nexti.minisoporte.data.remote.mappers.updateStatusRequestDtoOf
import com.nexti.minisoporte.data.repository.IncidentRepository
import com.nexti.minisoporte.security.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/*
 * Retrofit adapter implementing the US-005 [IncidentRepository] Port over
 * [IncidentApi] (design.md Decision 6, backend.yaml services.RetrofitIncidentRepository).
 *
 * OWNER SCOPING: the interface's `ownerId` parameters are deliberately IGNORED
 * here — the API owner-scopes every call via the JWT attached by
 * [com.nexti.minisoporte.network.AuthInterceptor], and the client never sends
 * owner fields (mass-assignment defense, backend create-incident rule). The
 * parameters exist only because the Port signature is shared with the fake.
 *
 * ERROR BRANCHES (CA-09): every call maps HTTP outcomes to domain results —
 * 401 → token deleted via [TokenStore] + the [onSessionExpired] recovery
 * signal surfaced EXACTLY ONCE (no automatic retry of the failed call — no
 * refresh tokens exist, design.md Risks); 404 on detail → the same safe
 * no-access outcome for missing AND foreign ids (indistinguishable, never
 * enumerate); 409 on transition → retryable error-state data. Everything else
 * (403/422/429/5xx/transport/IOException/timeouts, even mapper failures) is
 * RetryableError — never a crash (CONF-009).
 *
 * THE INTERFACE METHODS HAVE NO ERROR CHANNEL, so they delegate to the richer
 * methods below and degrade deterministically:
 *  - [listIncidents]   → any failure after signalling returns emptyList().
 *  - [createIncident]  → 201 returns the created Incident; ANY non-201 branch
 *    returns the documented synthesized UNCREATED incident (id = "",
 *    status PENDING, createdAt/updatedAt = "") — nothing is fabricated beyond
 *    this placeholder, the real branch lives in [createIncidentResult].
 *  - [getIncident]     → Found.incident or null.
 *  - [getHistory]      → Found.incident.history (ASC as received) or emptyList().
 *
 * Mock-path ViewModels keep using the fake repository; the real path uses the
 * richer methods where the branch information matters.
 */
class RetrofitIncidentRepository(
    private val incidentApi: IncidentApi,
    private val tokenStore: TokenStore,
    val onSessionExpired: (suspend () -> Unit)? = null,
) : IncidentRepository {
    override suspend fun listIncidents(
        ownerId: String,
        status: IncidentStatus?,
    ): List<Incident> =
        networkCatching {
            val response = incidentApi.listIncidents(status?.wire)
            if (response.code() == 401) {
                onUnauthorized()
                emptyList()
            } else if (!response.isSuccessful) {
                // Any other HTTP failure: empty list, crash-free.
                emptyList()
            } else {
                val body = response.body()
                // Render the response order AS-IS (created_at DESC server-side).
                body?.map { it.toDomain() } ?: emptyList()
            }
        }.getOrElse { emptyList() }

    override suspend fun createIncident(
        ownerId: String,
        title: String,
        description: String,
        category: IncidentCategory,
        priority: IncidentPriority,
    ): Incident =
        when (val result = createIncidentResult(title, description, category, priority)) {
            is CreateResult.Success -> result.incident
            // Bound fallback for the channel-less interface method — the real
            // branch (SessionExpired vs RetryableError) is already signalled.
            CreateResult.SessionExpired, CreateResult.RetryableError ->
                uncreatedIncidentOf(title, description, category, priority)
        }

    override suspend fun getIncident(
        ownerId: String,
        incidentId: String,
    ): Incident? =
        when (val result = getIncidentDetail(incidentId)) {
            is IncidentDetailResult.Found -> result.incident
            else -> null
        }

    override suspend fun getHistory(
        ownerId: String,
        incidentId: String,
    ): List<IncidentHistoryEntry> =
        when (val result = getIncidentDetail(incidentId)) {
            is IncidentDetailResult.Found -> result.incident.history
            else -> emptyList()
        }

    /**
     * Richer list is NOT offered (backend.yaml lists no separate list result):
     * the interface method IS the list path. Detail, however, gets one richer
     * entry point — [getIncident] and [getHistory] each call the API directly
     * through this method (one detail GET feeds both; no caching needed).
     */
    suspend fun getIncidentDetail(incidentId: String): IncidentDetailResult =
        networkCatching {
            val response = incidentApi.getIncident(incidentId)
            when {
                response.code() == 401 -> {
                    onUnauthorized()
                    IncidentDetailResult.SessionExpired
                }
                // 404 — missing == foreign, indistinguishable (safe no-access).
                response.code() == 404 -> IncidentDetailResult.NoAccess
                // Any other non-2xx (403/422/429/5xx) — retry later, never a crash.
                !response.isSuccessful -> IncidentDetailResult.RetryableError
                else -> {
                    val detail = response.body()
                    if (detail == null) {
                        IncidentDetailResult.RetryableError
                    } else {
                        // history preserved ASC exactly as the server ordered it.
                        IncidentDetailResult.Found(detail.toDomain())
                    }
                }
            }
        }.getOrElse { IncidentDetailResult.RetryableError }

    /**
     * Richer create: the real branch information (201 vs 401 vs anything else)
     * the channel-less interface method cannot carry.
     */
    suspend fun createIncidentResult(
        title: String,
        description: String,
        category: IncidentCategory,
        priority: IncidentPriority,
    ): CreateResult =
        networkCatching {
            val response = incidentApi.createIncident(createIncidentRequestDtoOf(title, description, category, priority))
            when {
                response.code() == 401 -> {
                    onUnauthorized()
                    CreateResult.SessionExpired
                }
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        CreateResult.RetryableError
                    } else {
                        CreateResult.Success(body.toDomain())
                    }
                }
                // 422/429/5xx — retry later, never a crash.
                else -> CreateResult.RetryableError
            }
        }.getOrElse { CreateResult.RetryableError }

    /**
     * Richer status transition (PATCH …/status): 409 surfaces as
     * [StatusUpdateResult.InvalidTransition] — retryable error-state DATA,
     * never a crash; [Success] carries the updated incident plus the accepted
     * latest history entry from the same response.
     */
    suspend fun updateStatus(
        incidentId: String,
        newStatus: IncidentStatus,
    ): StatusUpdateResult =
        networkCatching {
            val response = incidentApi.updateStatus(incidentId, updateStatusRequestDtoOf(newStatus))
            when (response.code()) {
                401 -> {
                    onUnauthorized()
                    StatusUpdateResult.SessionExpired
                }
                404 -> StatusUpdateResult.NoAccess
                409 -> StatusUpdateResult.InvalidTransition
                else -> {
                    val body = if (response.isSuccessful) response.body() else null
                    if (body == null) {
                        StatusUpdateResult.RetryableError
                    } else {
                        val (incident, latestHistory) = body.toDomainPair()
                        StatusUpdateResult.Success(incident, latestHistory)
                    }
                }
            }
        }.getOrElse { StatusUpdateResult.RetryableError }

    /**
     * The 401 recovery path (binding): delete the stored token, then surface
     * the dedicated recovery signal EXACTLY ONCE — the failed call is never
     * retried automatically (no refresh tokens exist; design.md Risks).
     * Never throws on 401.
     */
    private fun onUnauthorized() {
        runBlocking {
            // TokenStore methods are suspend; Retrofit suspends on IO dispatchers
            // (never Main), so runBlocking is the correct bridge — same pattern
            // as AuthInterceptor.
            runCatching { tokenStore.delete() }
            // A throwing navigation callback must not crash the caller either.
            onSessionExpired?.let { callback ->
                runCatching { callback() }
            }
        }
    }

    /**
     * The documented UNCREATED placeholder for the channel-less interface
     * create path: the caller's request fields, status PENDING (create-as-
     * Pending), no id and empty timestamps — explicitly NOT presented as a
     * stored incident. The real branch lives in [createIncidentResult].
     */
    private fun uncreatedIncidentOf(
        title: String,
        description: String,
        category: IncidentCategory,
        priority: IncidentPriority,
    ): Incident =
        Incident(
            id = "",
            title = title,
            description = description,
            category = category,
            priority = priority,
            status = IncidentStatus.PENDING,
            ownerUserId = "",
            ownerEmail = "",
            createdAt = "",
            resolvedAt = null,
        )
}

/**
 * [runCatching] that rethrows [CancellationException] so coroutine cancellation
 * keeps propagating (plain runCatching would swallow it) while every other
 * failure — IOException/timeouts, HTTP-layer errors, mapper
 * IllegalArgumentException — becomes [Result.failure] → RetryableError
 * (CONF-009: never a crash).
 */
private inline fun <T> networkCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
