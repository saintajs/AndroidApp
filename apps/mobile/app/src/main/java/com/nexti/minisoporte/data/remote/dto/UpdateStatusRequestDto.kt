package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact request body for PATCH /api/incidents/{id}/status (backend.yaml
 * `update-incident-status`) — the body carries only {"new_status"}.
 * [new_status] is the English wire key (`pending`/`in_progress`/`resolved`).
 */
@Serializable
data class UpdateStatusRequestDto(
    val new_status: String,
)
