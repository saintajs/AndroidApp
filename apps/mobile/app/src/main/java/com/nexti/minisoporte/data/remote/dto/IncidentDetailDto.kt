package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact 200 response for GET /api/incidents/{id} (backend.yaml
 * `incident-detail`) — a flat detail object whose [history] array arrives
 * ASC by changed_at, server-side ordered; rendered as-is.
 */
@Serializable
data class IncidentDetailDto(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val priority: String,
    val status: String,
    val created_at: String,
    val updated_at: String,
    val history: List<IncidentHistoryEntryDto>,
)
