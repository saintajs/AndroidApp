package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact 200 response for PATCH /api/incidents/{id}/status (backend.yaml
 * `update-incident-status`): the updated incident plus the accepted history
 * change ([latest_history]).
 */
@Serializable
data class StatusUpdateResponseDto(
    val incident: IncidentDto,
    val latest_history: IncidentHistoryEntryDto,
)
