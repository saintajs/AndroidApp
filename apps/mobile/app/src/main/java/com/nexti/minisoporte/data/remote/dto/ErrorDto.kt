package com.nexti.minisoporte.data.remote.dto

import kotlinx.serialization.Serializable

/*
 * Error bodies for every non-2xx branch EXCEPT 422 (backend.yaml rules):
 * the shape is always {"detail": "<mensaje>"} and the server detail string is
 * copied verbatim for 401/403/404/409/429.
 */
@Serializable
data class ErrorDto(
    val detail: String,
)

/*
 * FastAPI's default 422 body is NOT [ErrorDto]-shaped — `detail` is an array
 * of structured field descriptors. These mirrors keep the client able to
 * decode 422 bodies and map `loc` to Spanish per-field copy.
 */
@Serializable
data class ValidationIssueDto(
    val loc: List<String>,
    val msg: String,
    val type: String,
)

@Serializable
data class ValidationErrorDto(
    val detail: List<ValidationIssueDto>,
)
