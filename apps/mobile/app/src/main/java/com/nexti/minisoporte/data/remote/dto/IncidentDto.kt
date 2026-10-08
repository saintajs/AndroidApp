package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact incident item (backend.yaml `dtos.IncidentDto`).
 * [description] defaults to `null` because list items (GET /api/incidents)
 * omit the field while create/detail responses include it — a null default
 * tolerates both shapes. [category]/[priority]/[status] carry the English
 * snake_case wire keys; enum mapping happens in the mapper layer (CA-03).
 * [created_at]/[updated_at] are ISO 8601 UTC strings with `Z` suffix.
 */
@Serializable
data class IncidentDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String,
    val priority: String,
    val status: String,
    val created_at: String,
    val updated_at: String,
)
