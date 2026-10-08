package com.nexti.minisoporte.data.remote.mappers

import com.nexti.minisoporte.data.model.IncidentHistoryEntry
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.remote.dto.IncidentHistoryEntryDto

/*
 * Mapper between IncidentHistoryEntryDto (wire) and the IncidentHistoryEntry
 * domain model (CA-03: domain models never appear in request/response types).
 *
 * Fail-fast policy: unknown wire status values (previous or new) throw
 * IllegalArgumentException — never silently mislabeled.
 */

/**
 * Maps one timeline entry of GET /api/incidents/{id}. Wire gaps filled here:
 * [IncidentHistoryEntryDto.previous_status] stays `null` for the creation
 * entry (preserved); changedByEmail is NOT on the wire and maps to `""`;
 * comment is NOT on the wire and maps to `null`. [changed_at] must be ISO 8601
 * UTC with Z suffix.
 */
fun IncidentHistoryEntryDto.toDomain(): IncidentHistoryEntry {
    requireIso8601Utc(changed_at)
    val mappedNewStatus =
        IncidentStatus.fromWire(new_status)
            ?: throw IllegalArgumentException("Unknown wire value: <new_status=$new_status>")
    val mappedPreviousStatus =
        previous_status?.let { previousWire ->
            IncidentStatus.fromWire(previousWire)
                ?: throw IllegalArgumentException("Unknown wire value: <previous_status=$previousWire>")
        }
    return IncidentHistoryEntry(
        id = id,
        incidentId = incident_id,
        previousStatus = mappedPreviousStatus,
        newStatus = mappedNewStatus,
        changedByUserId = changed_by,
        changedByEmail = "",
        changedAt = changed_at,
        comment = null,
    )
}
