package com.nexti.minisoporte.data.model

/**
 * The signed-in user's profile — mirrors the `GET /api/me` payload fields
 * `id` / `email` / `role` / `active` / `created_at` (snake_case on the wire,
 * camelCase here; US-012's DTO layer performs the mapping).
 *
 * Pure Kotlin: no Android, no networking, no serialization imports.
 *
 * Conventions (group-decisions.md cross-cutting rules):
 *  - [id] is an opaque UUID string — never parsed for meaning.
 *  - [createdAt] is an ISO-8601 UTC string with `Z` suffix; a plain String
 *    keeps the model free of any date library.
 *  - No token material ever lives on this model (CONF-002).
 */
data class UserProfile(
    val id: String,
    val email: String,
    val role: UserRole,
    val active: Boolean,
    val createdAt: String,
)
