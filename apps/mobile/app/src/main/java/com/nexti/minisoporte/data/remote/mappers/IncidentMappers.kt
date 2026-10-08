package com.nexti.minisoporte.data.remote.mappers

import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentHistoryEntry
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.remote.dto.CreateIncidentRequestDto
import com.nexti.minisoporte.data.remote.dto.IncidentDetailDto
import com.nexti.minisoporte.data.remote.dto.IncidentDto
import com.nexti.minisoporte.data.remote.dto.StatusUpdateResponseDto
import com.nexti.minisoporte.data.remote.dto.UpdateStatusRequestDto

/*
 * Mappers between the incident DTOs (wire) and the Incident / IncidentHistoryEntry
 * domain models (CA-03: domain models never appear in request/response types —
 * they only feed mapper parameters that build String-carrying DTOs).
 *
 * Fail-fast policy: an unknown wire enum value throws IllegalArgumentException
 * (message names field and value) — never a silently mislabeled status,
 * category or priority. Timestamps pass through unchanged but must match the
 * ISO 8601 UTC Z-suffix shape.
 */

/**
 * Maps a list item / create-response incident (GET /api/incidents,
 * POST /api/incidents 201) to the domain model.
 *
 * Wire gaps filled here: [IncidentDto.description] is absent in list items and
 * maps to `""`; ownerUserId/ownerEmail are owner-scoped server-side and NOT on
 * the wire, so they map to `""` (the domain fields stay for the fake path).
 * [Incident.resolvedAt] stays `null` — the wire carries no resolved_at field.
 */
fun IncidentDto.toDomain(): Incident {
    requireIso8601Utc(created_at)
    requireIso8601Utc(updated_at)
    val mappedCategory =
        IncidentCategory.fromWire(category)
            ?: throw IllegalArgumentException("Unknown wire value: <category=$category>")
    val mappedPriority =
        IncidentPriority.fromWire(priority)
            ?: throw IllegalArgumentException("Unknown wire value: <priority=$priority>")
    val mappedStatus =
        IncidentStatus.fromWire(status)
            ?: throw IllegalArgumentException("Unknown wire value: <status=$status>")
    return Incident(
        id = id,
        title = title,
        description = description ?: "",
        category = mappedCategory,
        priority = mappedPriority,
        status = mappedStatus,
        ownerUserId = "",
        ownerEmail = "",
        createdAt = created_at,
        resolvedAt = null,
    )
}

/**
 * Maps the GET /api/incidents/{id} 200 flat detail object to the domain model.
 * [IncidentDetailDto.history] arrives ASC by changed_at (server-ordered) and
 * is preserved as-is.
 */
fun IncidentDetailDto.toDomain(): Incident {
    val mappedBase =
        IncidentDto(
            id = id,
            title = title,
            description = description,
            category = category,
            priority = priority,
            status = status,
            created_at = created_at,
            updated_at = updated_at,
        ).toDomain()
    return mappedBase.copy(
        history = history.map { it.toDomain() },
    )
}

/**
 * Builds the POST /api/incidents request body. The domain enums only feed the
 * mapper — the DTO crosses the wire with their English keys ([IncidentCategory.wire]
 * / [IncidentPriority.wire]), never the Spanish labels.
 */
fun createIncidentRequestDtoOf(
    title: String,
    description: String,
    category: IncidentCategory,
    priority: IncidentPriority,
): CreateIncidentRequestDto =
    CreateIncidentRequestDto(
        title = title,
        description = description,
        category = category.wire,
        priority = priority.wire,
    )

/**
 * Builds the PATCH /api/incidents/{id}/status request body — the wire carries
 * only `new_status` as the target status's English key.
 */
fun updateStatusRequestDtoOf(status: IncidentStatus): UpdateStatusRequestDto =
    UpdateStatusRequestDto(
        new_status = status.wire,
    )

/**
 * Maps the PATCH /api/incidents/{id}/status 200 body to the pair the
 * repository seam consumes: the updated incident plus the accepted history
 * change (the domain types, never invented ones). Throws on any unknown wire
 * enum in either half.
 */
fun StatusUpdateResponseDto.toDomainPair(): Pair<Incident, IncidentHistoryEntry> =
    Pair(
        first = incident.toDomain(),
        second = latest_history.toDomain(),
    )
