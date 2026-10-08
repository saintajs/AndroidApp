package com.nexti.minisoporte.data.remote.mappers

/*
 * ISO 8601 UTC timestamp shape check shared by the DTO->domain mappers.
 *
 * The wire contract (backend.yaml rules) sends dates as ISO 8601 UTC strings
 * with `Z` suffix (e.g. `2026-10-05T10:15:00Z`). Domain models keep dates as
 * plain Strings — no date library, no reformatting — so this validator is the
 * only wire-shape guarantee: a malformed stamp fails fast with an
 * IllegalArgumentException instead of poisoning the domain with a value the
 * UI cannot render deterministically.
 */

private val ISO_8601_UTC_REGEX = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")

/**
 * Validates that [value] matches the wire date shape (ISO 8601 UTC, `Z`
 * suffix, optional fractional seconds). Throws IllegalArgumentException on a
 * malformed stamp. Timestamps pass through the mappers unchanged.
 */
internal fun requireIso8601Utc(value: String) {
    require(ISO_8601_UTC_REGEX.matches(value)) {
        "Malformed ISO 8601 UTC timestamp: <$value>"
    }
}
