package com.nexti.minisoporte.data.repository

import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentHistoryEntry
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus

/*
 * The repository Port for the incident workflow (design.md Decision 4).
 *
 * Every method mirrors 1:1 the future REST contract so US-012's
 * `RetrofitIncidentRepository` implements this interface unchanged:
 *  - [listIncidents]  → GET /api/incidents?status=pending|in_progress|resolved|all
 *                       (owner-scoped, response order created_at DESC)
 *  - [createIncident] → POST /api/incidents (server assigns id/status/created_at;
 *                       status is always `pending` — the client never sends it)
 *  - [getIncident]    → GET /api/incidents/{id} (200 or 404; missing == foreign,
 *                       same safe non-leaking outcome — never enumerate)
 *  - [getHistory]     → the `history` array of GET /api/incidents/{id}
 *                       (ASC by changed_at)
 *
 * Pure Kotlin: no Android, no networking imports. All calls are suspend so
 * concrete adapters (in-memory fake today, Retrofit later) may be async.
 */
interface IncidentRepository {
    /**
     * Lists the caller's incidents, newest first (createdAt DESC).
     *
     * [status] filters by [IncidentStatus]; `null` means "Todas" (no filter —
     * every status is returned). Owner-scoped: only incidents whose
     * `ownerUserId` equals [ownerId] are ever visible.
     */
    suspend fun listIncidents(
        ownerId: String,
        status: IncidentStatus?,
    ): List<Incident>

    /**
     * Creates a new incident for [ownerId]. The repository assigns the id,
     * forces status [IncidentStatus.PENDING] (create-as-Pending — the client
     * never sends status or owner fields) and stamps createdAt; returns the
     * stored [Incident] exactly as persisted.
     */
    suspend fun createIncident(
        ownerId: String,
        title: String,
        description: String,
        category: IncidentCategory,
        priority: IncidentPriority,
    ): Incident

    /**
     * Owner-scoped detail lookup. Returns the [Incident] (with its history)
     * when it exists and belongs to [ownerId]; `null` when the id is missing
     * OR owned by another user — non-enumerating, mirroring the safe 404
     * (`Incidencia no encontrada`) of the future endpoint.
     */
    suspend fun getIncident(
        ownerId: String,
        incidentId: String,
    ): Incident?

    /**
     * Returns the status-history of an owner-scoped incident, ASC by
     * changedAt. Empty list when the incident is missing or foreign —
     * same non-leaking behaviour as [getIncident].
     */
    suspend fun getHistory(
        ownerId: String,
        incidentId: String,
    ): List<IncidentHistoryEntry>
}
