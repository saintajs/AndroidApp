/*
 * Plain JVM unit tests for the DTO<->domain mappers (US-012, testing.yaml
 * components_under_test "data/remote/mappers").
 *
 * Asserts BEHAVIOUR from the spec, not implementation details:
 *  - wire-key<->enum round-trip identity for every entry of the domain enums
 *    (the English wire keys are the ONLY wire vocabulary — never the Spanish
 *    labels);
 *  - ISO 8601 UTC Z-suffix shape validation (fail-fast on malformed stamps);
 *  - null previous_status preserved on the creation timeline entry;
 *  - unknown wire enum values throw IllegalArgumentException (fail-fast —
 *    never silently mislabeled);
 *  - wire gaps filled exactly as documented (description null -> "", owner
 *    fields -> "", changedByEmail -> "", comment -> null, resolvedAt -> null).
 *
 * No network, no Android, no token material — pure JVM per testing.yaml rules
 * (BR-006: the in-memory TokenStore fake stays the only seam tests touch).
 */
package com.nexti.minisoporte.data.remote.mappers

import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.data.model.UserProfile
import com.nexti.minisoporte.data.model.UserRole
import com.nexti.minisoporte.data.remote.dto.IncidentDetailDto
import com.nexti.minisoporte.data.remote.dto.IncidentDto
import com.nexti.minisoporte.data.remote.dto.IncidentHistoryEntryDto
import com.nexti.minisoporte.data.remote.dto.StatusUpdateResponseDto
import com.nexti.minisoporte.data.remote.dto.UserProfileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentMappersTest {
    /*
     * Wire-key <-> enum round-trip identity (testing.yaml critical_cases #1).
     */

    @Test
    fun incidentStatusWireKeysRoundTripEveryEntry() {
        IncidentStatus.entries.forEach { entry ->
            assertEquals(entry, IncidentStatus.fromWire(entry.wire))
        }
    }

    @Test
    fun incidentStatusWireVocabularyIsExactlyTheContractedThree() {
        assertEquals(
            setOf("pending", "in_progress", "resolved"),
            IncidentStatus.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun incidentCategoryWireKeysRoundTripEveryEntry() {
        IncidentCategory.entries.forEach { entry ->
            assertEquals(entry, IncidentCategory.fromWire(entry.wire))
        }
    }

    @Test
    fun incidentCategoryWireVocabularyIsExactlyTheContractedFour() {
        assertEquals(
            setOf("systems", "equipment", "access", "other"),
            IncidentCategory.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun incidentPriorityWireKeysRoundTripEveryEntry() {
        IncidentPriority.entries.forEach { entry ->
            assertEquals(entry, IncidentPriority.fromWire(entry.wire))
        }
    }

    @Test
    fun incidentPriorityWireVocabularyIsExactlyTheContractedThree() {
        assertEquals(
            setOf("low", "medium", "high"),
            IncidentPriority.entries.map { it.wire }.toSet(),
        )
    }

    /*
     * IncidentDto.toDomain: wire gaps + fail-fast unknown enum values.
     */

    @Test
    fun incidentDtoNullDescriptionMapsToEmptyString() {
        val incident = incidentDto(description = null).toDomain()

        assertEquals("", incident.description)
    }

    @Test
    fun incidentDtoPresentDescriptionIsPreserved() {
        val incident = incidentDto(description = "La impresora no enciende").toDomain()

        assertEquals("La impresora no enciende", incident.description)
    }

    @Test
    fun incidentDtoOwnerFieldsAreNotOnTheWireAndMapToEmpty() {
        val incident = incidentDto().toDomain()

        assertEquals("", incident.ownerUserId)
        assertEquals("", incident.ownerEmail)
        assertNull(incident.resolvedAt)
    }

    @Test
    fun incidentDtoUnknownStatusFailsFast() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                incidentDto(status = "archived").toDomain()
            }

        assertTrue(exception.message!!.contains("status"))
    }

    @Test
    fun incidentDtoUnknownCategoryFailsFast() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                incidentDto(category = "hardware").toDomain()
            }

        assertTrue(exception.message!!.contains("category"))
    }

    @Test
    fun incidentDtoUnknownPriorityFailsFast() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                incidentDto(priority = "urgent").toDomain()
            }

        assertTrue(exception.message!!.contains("priority"))
    }

    @Test
    fun incidentDtoMalformedCreatedAtFailsFast() {
        assertThrows(IllegalArgumentException::class.java) {
            incidentDto(created_at = "2026-10-05 10:15:00").toDomain()
        }
    }

    /*
     * requireIso8601Utc: the wire timestamp shape contract.
     */

    @Test
    fun requireIso8601UtcAcceptsPlainZSuffixForm() {
        requireIso8601Utc("2026-10-05T10:15:00Z")
    }

    @Test
    fun requireIso8601UtcAcceptsFractionalSecondsForm() {
        requireIso8601Utc("2026-10-05T10:15:00.123456Z")
    }

    @Test
    fun requireIso8601UtcRejectsMissingZSuffix() {
        assertThrows(IllegalArgumentException::class.java) {
            requireIso8601Utc("2026-10-05T10:15:00")
        }
    }

    @Test
    fun requireIso8601UtcRejectsDateOnlyStamp() {
        assertThrows(IllegalArgumentException::class.java) {
            requireIso8601Utc("2026-10-05")
        }
    }

    @Test
    fun requireIso8601UtcRejectsNonIsoGarbage() {
        assertThrows(IllegalArgumentException::class.java) {
            requireIso8601Utc("ayer por la tarde")
        }
    }

    @Test
    fun requireIso8601UtcIsAShapeCheckOnlySoCalendarImpossibleValuesPass() {
        // Documented regex contract: the validator guarantees the SHAPE only —
        // regex-valid but calendar-impossible values are not its concern.
        requireIso8601Utc("2026-13-99T99:99:99Z")
    }

    /*
     * IncidentDetailDto.toDomain: history ASC as received + creation entry.
     */

    @Test
    fun incidentDetailHistoryIsPreservedInReceivedAscOrder() {
        val detail = incidentDetailDto()

        val incident = detail.toDomain()

        assertEquals(
            listOf(OLDEST_STAMP, NEWEST_STAMP),
            incident.history.map { it.changedAt },
        )
    }

    @Test
    fun incidentDetailCreationEntryKeepsNullPreviousStatus() {
        val incident = incidentDetailDto().toDomain()

        val creation = incident.history.first()
        assertNull(creation.previousStatus)
        assertEquals(IncidentStatus.PENDING, creation.newStatus)
    }

    @Test
    fun incidentDetailTransitionEntryMapsPreviousAndNewStatus() {
        val incident = incidentDetailDto().toDomain()

        val transition = incident.history.last()
        assertEquals(IncidentStatus.PENDING, transition.previousStatus)
        assertEquals(IncidentStatus.IN_PROGRESS, transition.newStatus)
    }

    @Test
    fun incidentDetailHistoryEntriesFillWireGapsWithEmptyEmailAndNullComment() {
        val incident = incidentDetailDto().toDomain()

        incident.history.forEach { entry ->
            assertEquals("", entry.changedByEmail)
            assertNull(entry.comment)
        }
    }

    @Test
    fun incidentDetailUnknownNewStatusFailsFast() {
        val detail =
            incidentDetailDto(
                history =
                    listOf(
                        historyEntryDto(id = "h-1", new_status = "cancelled"),
                    ),
            )

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                detail.toDomain()
            }

        assertTrue(exception.message!!.contains("new_status"))
    }

    @Test
    fun incidentDetailUnknownPreviousStatusFailsFast() {
        val detail =
            incidentDetailDto(
                history =
                    listOf(
                        historyEntryDto(
                            id = "h-1",
                            previous_status = "archived",
                            new_status = "in_progress",
                        ),
                    ),
            )

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                detail.toDomain()
            }

        assertTrue(exception.message!!.contains("previous_status"))
    }

    /*
     * Request builders: the wire carries the English keys, never the Spanish labels.
     */

    @Test
    fun createIncidentRequestEmitsEnglishWireKeysForEveryCategory() {
        IncidentCategory.entries.forEach { category ->
            val request =
                createIncidentRequestDtoOf(
                    title = "Teclado roto",
                    description = "La tecla A no responde",
                    category = category,
                    priority = IncidentPriority.MEDIUM,
                )

            assertEquals(category.wire, request.category)
        }
    }

    @Test
    fun createIncidentRequestEmitsEnglishWireKeysForEveryPriority() {
        IncidentPriority.entries.forEach { priority ->
            val request =
                createIncidentRequestDtoOf(
                    title = "Teclado roto",
                    description = "La tecla A no responde",
                    category = IncidentCategory.EQUIPMENT,
                    priority = priority,
                )

            assertEquals(priority.wire, request.priority)
        }
    }

    @Test
    fun createIncidentRequestNeverCarriesSpanishLabels() {
        val request =
            createIncidentRequestDtoOf(
                title = "Teclado roto",
                description = "La tecla A no responde",
                category = IncidentCategory.SYSTEMS,
                priority = IncidentPriority.HIGH,
            )

        val spanishLabels = setOf("Sistemas", "Equipos", "Acceso", "Otro", "Baja", "Media", "Alta")
        assertTrue(spanishLabels.none { it == request.category || it == request.priority })
    }

    @Test
    fun updateStatusRequestEmitsEnglishWireKeyForEveryStatus() {
        IncidentStatus.entries.forEach { status ->
            val request = updateStatusRequestDtoOf(status)

            assertEquals(status.wire, request.new_status)
        }
    }

    @Test
    fun updateStatusRequestNeverCarriesSpanishLabels() {
        val request = updateStatusRequestDtoOf(IncidentStatus.IN_PROGRESS)

        assertTrue(request.new_status !in setOf("Pendiente", "En progreso", "Resuelta"))
    }

    /*
     * StatusUpdateResponseDto.toDomainPair.
     */

    @Test
    fun statusUpdateResponseMapsToIncidentPlusLatestHistoryPair() {
        val response = statusUpdateResponseDto()

        val (incident, latestHistory) = response.toDomainPair()

        assertEquals("inc-1", incident.id)
        assertEquals(IncidentStatus.IN_PROGRESS, incident.status)
        assertEquals("h-2", latestHistory.id)
        assertEquals(IncidentStatus.IN_PROGRESS, latestHistory.newStatus)
    }

    /*
     * UserProfile mapping: role wire key both directions, fail-fast unknown.
     */

    @Test
    fun userProfileDtoMapsEmployeeRole() {
        val profile = userProfileDto(role = "employee").toDomain()

        assertEquals(UserRole.EMPLOYEE, profile.role)
        assertEquals("u-1", profile.id)
        assertEquals("empleado@minisoporte.test", profile.email)
        assertTrue(profile.active)
        assertEquals(CREATED_STAMP, profile.createdAt)
    }

    @Test
    fun userProfileDtoMapsSupportRole() {
        val profile = userProfileDto(role = "support").toDomain()

        assertEquals(UserRole.SUPPORT, profile.role)
    }

    @Test
    fun userProfileDtoUnknownRoleFailsFast() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                userProfileDto(role = "admin").toDomain()
            }

        assertTrue(exception.message!!.contains("role"))
    }

    @Test
    fun userProfileToDtoEmitsTheRoleWireKey() {
        UserRole.entries.forEach { role ->
            val dto = userProfile(role = role).toDto()

            assertEquals(role.wire, dto.role)
        }
    }

    @Test
    fun userProfileToDtoEmitsSnakeCaseWireFieldValues() {
        val dto =
            userProfile(role = UserRole.EMPLOYEE).toDto()

        assertEquals("u-1", dto.id)
        assertEquals("empleado@minisoporte.test", dto.email)
        assertTrue(dto.active)
        assertEquals(CREATED_STAMP, dto.created_at)
    }

    /*
     * Fixtures.
     */

    private fun incidentDto(
        description: String? = "La impresora no enciende",
        category: String = "equipment",
        priority: String = "medium",
        status: String = "pending",
        created_at: String = CREATED_STAMP,
    ) = IncidentDto(
        id = "inc-1",
        title = "Impresora no responde",
        description = description,
        category = category,
        priority = priority,
        status = status,
        created_at = created_at,
        updated_at = created_at,
    )

    private fun historyEntryDto(
        id: String,
        previous_status: String? = null,
        new_status: String,
        changed_at: String = OLDEST_STAMP,
    ) = IncidentHistoryEntryDto(
        id = id,
        incident_id = "inc-1",
        previous_status = previous_status,
        new_status = new_status,
        changed_by = "00000000-0000-0000-0000-000000000001",
        changed_at = changed_at,
    )

    private fun incidentDetailDto(
        history: List<IncidentHistoryEntryDto> =
            listOf(
                historyEntryDto(id = "h-1", previous_status = null, new_status = "pending", changed_at = OLDEST_STAMP),
                historyEntryDto(
                    id = "h-2",
                    previous_status = "pending",
                    new_status = "in_progress",
                    changed_at = NEWEST_STAMP,
                ),
            ),
    ) = IncidentDetailDto(
        id = "inc-1",
        title = "Impresora no responde",
        description = "La impresora no enciende",
        category = "equipment",
        priority = "medium",
        status = "in_progress",
        created_at = CREATED_STAMP,
        updated_at = NEWEST_STAMP,
        history = history,
    )

    private fun statusUpdateResponseDto() =
        StatusUpdateResponseDto(
            incident =
                IncidentDto(
                    id = "inc-1",
                    title = "Impresora no responde",
                    description = "La impresora no enciende",
                    category = "equipment",
                    priority = "medium",
                    status = "in_progress",
                    created_at = CREATED_STAMP,
                    updated_at = NEWEST_STAMP,
                ),
            latest_history =
                historyEntryDto(
                    id = "h-2",
                    previous_status = "pending",
                    new_status = "in_progress",
                    changed_at = NEWEST_STAMP,
                ),
        )

    private fun userProfileDto(role: String) =
        UserProfileDto(
            id = "u-1",
            email = "empleado@minisoporte.test",
            role = role,
            active = true,
            created_at = CREATED_STAMP,
        )

    private fun userProfile(role: UserRole) =
        UserProfile(
            id = "u-1",
            email = "empleado@minisoporte.test",
            role = role,
            active = true,
            createdAt = CREATED_STAMP,
        )

    private companion object {
        const val CREATED_STAMP = "2026-10-05T10:15:00Z"
        const val OLDEST_STAMP = "2026-10-05T10:15:00Z"
        const val NEWEST_STAMP = "2026-10-05T11:30:00Z"
    }
}
