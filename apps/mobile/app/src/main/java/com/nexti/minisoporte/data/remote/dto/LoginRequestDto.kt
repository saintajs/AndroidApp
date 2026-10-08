package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Wire-exact request body for POST /api/auth/login (backend.yaml `login`).
 * Snake_case JSON fields with no aliasing layer (group-decisions.md rule) —
 * property names match the wire verbatim.
 */
@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)
