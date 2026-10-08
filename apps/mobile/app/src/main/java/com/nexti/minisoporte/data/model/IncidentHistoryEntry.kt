package com.nexti.minisoporte.data.model

/**
 * One status transition in an incident's timeline — mirrors the
 * `incident_history` rows the API returns inside `GET /api/incidents/{id}`
 * (ordered ASC by `changed_at`).
 *
 * Pure Kotlin: no Android, no networking, no serialization imports.
 *
 * Conventions (group-decisions.md cross-cutting rules):
 *  - [id], [incidentId], [changedByUserId] are opaque UUID strings.
 *  - [previousStatus] is `null` only for the creation transition
 *    (incident created directly as [IncidentStatus.PENDING]).
 *  - [changedAt] is an ISO-8601 UTC string with `Z` suffix; a plain String
 *    keeps the model free of any date library.
 */
data class IncidentHistoryEntry(
    val id: String,
    val incidentId: String,
    val previousStatus: IncidentStatus?,
    val newStatus: IncidentStatus,
    val changedByUserId: String,
    val changedByEmail: String,
    val changedAt: String,
    val comment: String? = null,
)
