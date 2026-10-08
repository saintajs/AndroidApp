package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact 200 response for POST /api/auth/login (backend.yaml `login`).
 * Property names match the JSON fields verbatim (no @SerialName, no naming
 * scheme) — token material lives here only transiently and is never logged.
 */
@Serializable
data class LoginResponseDto(
    val access_token: String,
    val token_type: String,
    val expires_in_seconds: Long,
)
