package com.nexti.minisoporte.data.model

/**
 * A support incident — the domain model mirroring the future
 * `GET /api/incidents` / `GET /api/incidents/{id}` payloads (full incident +
 * `history` ordered ASC by `changed_at`).
 *
 * Pure Kotlin: no Android, no networking, no serialization imports — US-012's
 * DTO layer maps wire JSON to this model at the repository boundary.
 *
 * Conventions (group-decisions.md cross-cutting rules):
 *  - [id], [ownerUserId] are opaque UUID strings — never parsed for meaning.
 *  - [createdAt]/[resolvedAt] are ISO-8601 UTC strings with `Z` suffix
 *    (`2026-01-15T10:30:00Z`); a plain String keeps the model free of any
 *    date library. [resolvedAt] is `null` while the incident is not resolved.
 */
data class Incident(
    val id: String,
    val title: String,
    val description: String,
    val category: IncidentCategory,
    val priority: IncidentPriority,
    val status: IncidentStatus,
    val ownerUserId: String,
    val ownerEmail: String,
    val createdAt: String,
    val resolvedAt: String? = null,
    val history: List<IncidentHistoryEntry> = emptyList(),
)
