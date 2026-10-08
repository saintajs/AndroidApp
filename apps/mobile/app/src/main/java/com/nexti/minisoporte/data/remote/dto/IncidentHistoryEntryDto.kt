package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact history entry (backend.yaml `dtos.IncidentHistoryEntryDto`).
 * [previous_status] is `null` for the creation entry; [changed_at] is ISO 8601
 * UTC. [new_status] carries the English wire key — enum mapping lives in the
 * mapper layer (CA-03).
 */
@Serializable
data class IncidentHistoryEntryDto(
    val id: String,
    val incident_id: String,
    val previous_status: String? = null,
    val new_status: String,
    val changed_by: String,
    val changed_at: String,
)
