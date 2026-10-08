package com.nexti.minisoporte.ui.navigation

/**
 * Typed route model for the app's four destinations (CA-04, design.md Decision 3).
 *
 * Each destination pins its route pattern as a `ROUTE` constant so tests (and the
 * NavHost wiring) can assert patterns without instantiating the destination. Screen
 * stories US-006..US-009 consume these constants unchanged — ownership per
 * group-decisions.md: this file belongs to US-005, every other story swaps only its
 * own one-line entry inside `AppNavHost`.
 */
sealed interface Destination {
    val route: String

    /**
     * S-01 — Iniciar sesión. Start destination of the graph.
     */
    data object SignIn : Destination {
        const val ROUTE = "signin"

        override val route: String = ROUTE
    }

    /**
     * S-02 — Mis incidencias.
     */
    data object Incidents : Destination {
        const val ROUTE = "incidents"

        override val route: String = ROUTE
    }

    /**
     * S-03 — Nueva incidencia.
     */
    data object NewIncident : Destination {
        const val ROUTE = "new_incident"

        override val route: String = ROUTE
    }

    /**
     * S-04 — Detalle de incidencia for the incident identified by [incidentId].
     */
    data class IncidentDetail(
        val incidentId: String,
    ) : Destination {
        companion object {
            const val ROUTE = "incident_detail/{incidentId}"
            const val ARG_INCIDENT_ID = "incidentId"
        }

        override val route: String
            get() = "incident_detail/$incidentId"
    }
}
