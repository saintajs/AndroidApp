package com.nexti.minisoporte.data.remote.mappers

import com.nexti.minisoporte.data.model.UserProfile
import com.nexti.minisoporte.data.model.UserRole
import com.nexti.minisoporte.data.remote.dto.UserProfileDto

/*
 * Mappers between UserProfileDto (wire, GET /api/me) and the UserProfile
 * domain model (CA-03: domain models never appear in request/response types).
 *
 * Fail-fast policy: an unknown role wire value throws IllegalArgumentException
 * — never a silently mislabeled user.
 */

/**
 * Maps the GET /api/me 200 body to the domain profile.
 * [UserProfileDto.role] must be a known English wire key (`employee` |
 * `support`); [UserProfileDto.created_at] must be ISO 8601 UTC with Z suffix.
 */
fun UserProfileDto.toDomain(): UserProfile {
    requireIso8601Utc(created_at)
    val mappedRole =
        UserRole.fromWire(role)
            ?: throw IllegalArgumentException("Unknown wire value: <role=$role>")
    return UserProfile(
        id = id,
        email = email,
        role = mappedRole,
        active = active,
        createdAt = created_at,
    )
}

/**
 * Maps the domain profile back to its wire shape (the role's English key via
 * [UserRole.wire], never the Spanish label). Timestamps pass through
 * unchanged.
 */
fun UserProfile.toDto(): UserProfileDto =
    UserProfileDto(
        id = id,
        email = email,
        role = role.wire,
        active = active,
        created_at = createdAt,
    )
