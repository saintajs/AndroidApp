/*
 * Plain JVM unit tests for the typed route model S-01..S-04 (design.md Decision 7,
 * spec "Single-Activity Compose host with typed routes S-01..S-04").
 *
 * CA-04: every destination pins its route pattern as a ROUTE constant so the graph
 * stays resolvable before any screen story lands — the tests below assert the exact
 * patterns, the argument name, the instance route resolution for S-04, and that each
 * singleton's `route` property equals its ROUTE constant.
 */
package com.nexti.minisoporte.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationRoutesTest {
    @Test
    fun signInRouteIsPinnedToS01() {
        assertEquals("signin", Destination.SignIn.ROUTE)
    }

    @Test
    fun incidentsRouteIsPinnedToS02() {
        assertEquals("incidents", Destination.Incidents.ROUTE)
    }

    @Test
    fun newIncidentRouteIsPinnedToS03() {
        assertEquals("new_incident", Destination.NewIncident.ROUTE)
    }

    @Test
    fun incidentDetailRouteIsPinnedToS04WithPlaceholder() {
        assertEquals("incident_detail/{incidentId}", Destination.IncidentDetail.ROUTE)
    }

    @Test
    fun incidentDetailArgumentNameIsPinned() {
        assertEquals("incidentId", Destination.IncidentDetail.ARG_INCIDENT_ID)
    }

    @Test
    fun incidentDetailInstanceResolvesItsRouteWithTheActualId() {
        assertEquals(
            "incident_detail/abc",
            Destination.IncidentDetail("abc").route,
        )
    }

    @Test
    fun incidentDetailPlaceholderMatchesTheDeclaredArgumentName() {
        val pattern = Destination.IncidentDetail.ROUTE
        val argument = Destination.IncidentDetail.ARG_INCIDENT_ID
        assertTrue(
            "ROUTE placeholder must use ARG_INCIDENT_ID: $pattern",
            pattern.contains("{$argument}"),
        )
    }

    @Test
    fun everySingletonRoutePropertyEqualsItsRouteConstant() {
        assertEquals(Destination.SignIn.ROUTE, Destination.SignIn.route)
        assertEquals(Destination.Incidents.ROUTE, Destination.Incidents.route)
        assertEquals(Destination.NewIncident.ROUTE, Destination.NewIncident.route)
    }

    @Test
    fun allFourDestinationsAreDistinctTypedInstancesOfTheModel() {
        val destinations =
            listOf<Destination>(
                Destination.SignIn,
                Destination.Incidents,
                Destination.NewIncident,
                Destination.IncidentDetail("abc"),
            )
        assertEquals(4, destinations.size)
        assertEquals(
            listOf("signin", "incidents", "new_incident", "incident_detail/abc"),
            destinations.map { it.route },
        )
    }
}
