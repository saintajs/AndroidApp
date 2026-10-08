package com.nexti.minisoporte.data.remote

import com.nexti.minisoporte.data.remote.dto.CreateIncidentRequestDto
import com.nexti.minisoporte.data.remote.dto.IncidentDetailDto
import com.nexti.minisoporte.data.remote.dto.IncidentDto
import com.nexti.minisoporte.data.remote.dto.StatusUpdateResponseDto
import com.nexti.minisoporte.data.remote.dto.UpdateStatusRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/*
 * Retrofit mirror of the incident REST contract (backend.yaml endpoints
 * list-incidents / create-incident / incident-detail / update-incident-status).
 *
 * Exactly FOUR operations — 1:1 with the IncidentRepository Port (CA-02):
 *  - [listIncidents]  → GET /api/incidents?status=… (owner-scoped, created_at DESC)
 *  - [createIncident] → POST /api/incidents (server assigns id/status/timestamps)
 *  - [getIncident]    → GET /api/incidents/{id} (detail incl. history ASC)
 *  - [updateStatus]   → PATCH /api/incidents/{id}/status (support-only)
 *
 * Every call is suspend and returns retrofit2.Response so non-2xx outcomes
 * (401/404/409/429) stay reachable as code() values — no exception guessing
 * in the adapter layer. [status] carries the English wire key
 * (pending | in_progress | resolved) or `all` for an explicit unfiltered
 * request; `null` OMITS the query param (the server treats missing as all).
 * Domain models never appear here — DTOs only (CA-03).
 */
interface IncidentApi {
    @GET("api/incidents")
    suspend fun listIncidents(
        @Query("status") status: String?,
    ): Response<List<IncidentDto>>

    @POST("api/incidents")
    suspend fun createIncident(
        @Body body: CreateIncidentRequestDto,
    ): Response<IncidentDto>

    @GET("api/incidents/{id}")
    suspend fun getIncident(
        @Path("id") incidentId: String,
    ): Response<IncidentDetailDto>

    @PATCH("api/incidents/{id}/status")
    suspend fun updateStatus(
        @Path("id") incidentId: String,
        @Body body: UpdateStatusRequestDto,
    ): Response<StatusUpdateResponseDto>
}
