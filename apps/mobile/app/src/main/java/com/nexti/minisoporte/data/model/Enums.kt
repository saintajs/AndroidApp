package com.nexti.minisoporte.data.model

/*
 * Canonical domain enums for the incident workflow (group-decisions.md "Canonical names").
 *
 * Each entry carries:
 *  - [wire]: the exact stored/JSON value exchanged with the API (snake_case English,
 *    never hyphenated, never Spanish) — e.g. `in_progress`, never `in-progress`.
 *  - [label]: the Spanish display text. UI copy only — it MUST NOT be persisted,
 *    logged or sent over the wire; US-012's DTO mapping serializes [wire] exclusively.
 *
 * These are the ONLY status/category/priority/role names allowed in data code.
 * Per-screen enums are forbidden (group-decisions.md).
 */

/**
 * Lifecycle status of an incident.
 *
 * Wire values (exact, snake_case): `pending`, `in_progress`, `resolved`.
 * Spanish labels (UI only): Pendiente, En progreso, Resuelta.
 */
enum class IncidentStatus(
    val wire: String,
    val label: String,
) {
    PENDING(
        wire = "pending",
        label = "Pendiente",
    ),
    IN_PROGRESS(
        wire = "in_progress",
        label = "En progreso",
    ),
    RESOLVED(
        wire = "resolved",
        label = "Resuelta",
    ),
    ;

    companion object {
        /**
         * Maps a wire value (stored/JSON, e.g. from a DTO field) to its enum entry.
         * Returns `null` for any value outside `pending`/`in_progress`/`resolved` —
         * chosen over throwing so callers (today the fake repository, later the
         * US-012 DTO mapper) decide their own failure handling.
         */
        fun fromWire(value: String): IncidentStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Category of an incident.
 *
 * Wire values (exact): `systems`, `equipment`, `access`, `other`.
 * Spanish labels (UI only): Sistemas, Equipos, Acceso, Otro.
 */
enum class IncidentCategory(
    val wire: String,
    val label: String,
) {
    SYSTEMS(
        wire = "systems",
        label = "Sistemas",
    ),
    EQUIPMENT(
        wire = "equipment",
        label = "Equipos",
    ),
    ACCESS(
        wire = "access",
        label = "Acceso",
    ),
    OTHER(
        wire = "other",
        label = "Otro",
    ),
    ;

    companion object {
        /**
         * Maps a wire value to its enum entry; returns `null` when unknown
         * (same contract as [IncidentStatus.fromWire]).
         */
        fun fromWire(value: String): IncidentCategory? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Priority of an incident.
 *
 * Wire values (exact): `low`, `medium`, `high`.
 * Spanish labels (UI only): Baja, Media, Alta.
 */
enum class IncidentPriority(
    val wire: String,
    val label: String,
) {
    LOW(
        wire = "low",
        label = "Baja",
    ),
    MEDIUM(
        wire = "medium",
        label = "Media",
    ),
    HIGH(
        wire = "high",
        label = "Alta",
    ),
    ;

    companion object {
        /**
         * Maps a wire value to its enum entry; returns `null` when unknown
         * (same contract as [IncidentStatus.fromWire]).
         */
        fun fromWire(value: String): IncidentPriority? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Role of an application user.
 *
 * Wire values (exact): `employee`, `support`. (`admin`/`user` are forbidden names.)
 * Spanish labels (UI only): Empleado, Soporte.
 */
enum class UserRole(
    val wire: String,
    val label: String,
) {
    EMPLOYEE(
        wire = "employee",
        label = "Empleado",
    ),
    SUPPORT(
        wire = "support",
        label = "Soporte",
    ),
    ;

    companion object {
        /**
         * Maps a wire value to its enum entry; returns `null` when unknown
         * (same contract as [IncidentStatus.fromWire]).
         */
        fun fromWire(value: String): UserRole? = entries.firstOrNull { it.wire == value }
    }
}
