package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact request body for POST /api/incidents (backend.yaml
 * `create-incident`). [category]/[priority] must carry the English wire keys,
 * never Spanish labels; status/owner are assigned server-side.
 */
@Serializable
data class CreateIncidentRequestDto(
    val title: String,
    val description: String,
    val category: String,
    val priority: String,
)
