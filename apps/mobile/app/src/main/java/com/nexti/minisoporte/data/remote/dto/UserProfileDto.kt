package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact 200 response for GET /api/me (backend.yaml `me`).
 * [role] carries the English wire key (`employee` | `support`); mapping to the
 * UserRole enum happens in the mapper layer, never here (CA-03).
 */
@Serializable
data class UserProfileDto(
    val id: String,
    val email: String,
    val role: String,
    val active: Boolean,
    val created_at: String,
)
