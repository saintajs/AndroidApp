package com.nexti.minisoporte.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nexti.minisoporte.data.remote.AppServices
import com.nexti.minisoporte.ui.detail.IncidentDetailScreen
import com.nexti.minisoporte.ui.detail.IncidentDetailViewModel
import com.nexti.minisoporte.ui.incidents.IncidentListScreen
import com.nexti.minisoporte.ui.incidents.IncidentListViewModel
import com.nexti.minisoporte.ui.newincident.NewIncidentScreen
import com.nexti.minisoporte.ui.newincident.NewIncidentViewModel
import com.nexti.minisoporte.ui.signin.SignInScreen
import com.nexti.minisoporte.ui.signin.SignInViewModel

/**
 * The app's single navigation graph (CA-04, design.md Decision 3): four destinations
 * for S-01..S-04, starting at S-01 Iniciar sesión. The S-01, S-02, S-03 and S-04
 * entries render the real [SignInScreen], [IncidentListScreen], [NewIncidentScreen]
 * and [IncidentDetailScreen].
 *
 * EXTENSION CONTRACT for screen stories US-006..US-009: swap exactly ONE entry's
 * `PlaceholderScreen()` line for your real screen (e.g. `SignInScreen()` in the
 * S-01 entry). Never touch the route model, the other entries or this file's wiring.
 */
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    // One shared token store for the whole graph (US-012 single injection
    // point): AppServices picks the mock InMemoryTokenStore (default,
    // byte-identical) or the platform secure-storage-backed store.
    val context = LocalContext.current
    val tokenStore = remember { AppServices.tokenStore(context) }
    NavHost(
        navController = navController,
        startDestination = Destination.SignIn.ROUTE,
    ) {
        // S-01 — Iniciar sesión
        composable(Destination.SignIn.ROUTE) {
            val viewModel: SignInViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                            initializer {
                                SignInViewModel(AppServices.fakeAuthService(tokenStore))
                            }
                        },
                )
            SignInScreen(
                viewModel = viewModel,
                onNavigateToS02 = { navController.navigate(Destination.Incidents.ROUTE) },
            )
        }

        // S-02 — Mis incidencias
        composable(Destination.Incidents.ROUTE) {
            // Real path: the repository reports session expiry (401) by
            // flipping this flag from its background callback; the flip is
            // observed on Main below, which performs the SAME navigation as
            // the sign-out callback. Mock path: the fake repository never
            // invokes the callback — byte-identical.
            var sessionExpired by remember { mutableStateOf(false) }
            LaunchedEffect(sessionExpired) {
                if (sessionExpired) {
                    navController.navigate(Destination.SignIn.ROUTE) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            val viewModel: IncidentListViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                            initializer {
                                IncidentListViewModel(
                                    AppServices.incidentRepository(
                                        tokenStore,
                                        onSessionExpired = { sessionExpired = true },
                                    ),
                                    tokenStore,
                                )
                            }
                        },
                )
            IncidentListScreen(
                viewModel = viewModel,
                onNavigateToNewIncident = { navController.navigate(Destination.NewIncident.ROUTE) },
                onNavigateToIncidentDetail = { id ->
                    navController.navigate(Destination.IncidentDetail(id).route)
                },
                onNavigateToSignIn = {
                    navController.navigate(Destination.SignIn.ROUTE) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // S-03 — Nueva incidencia
        composable(Destination.NewIncident.ROUTE) {
            val viewModel: NewIncidentViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                            initializer {
                                NewIncidentViewModel(AppServices.incidentRepository(tokenStore))
                            }
                        },
                )
            NewIncidentScreen(
                viewModel = viewModel,
                // S-02 Mis incidencias sits directly underneath S-03 in the back
                // stack (the S-02 FAB navigated here), so popping back returns to
                // the S-02 route — its LaunchedEffect reloads the list and shows
                // the new Pendiente card first (BR-019).
                onNavigateBackToS02 = { navController.popBackStack() },
            )
        }

        // S-04 — Detalle de incidencia (typed incidentId path argument)
        composable(
            route = Destination.IncidentDetail.ROUTE,
            arguments =
                listOf(
                    navArgument(Destination.IncidentDetail.ARG_INCIDENT_ID) {
                        type = NavType.StringType
                    },
                ),
        ) { backStackEntry ->
            // incidentId is delivered as a StringType argument parsed from the route
            // pattern by navigation-compose; S-04 reads it via
            // backStackEntry.arguments?.getString(Destination.IncidentDetail.ARG_INCIDENT_ID).
            val incidentId =
                backStackEntry.arguments
                    ?.getString(Destination.IncidentDetail.ARG_INCIDENT_ID)
                    .orEmpty()
            val viewModel: IncidentDetailViewModel =
                viewModel(
                    factory =
                        viewModelFactory {
                            initializer {
                                IncidentDetailViewModel(AppServices.incidentRepository(tokenStore))
                            }
                        },
                )
            IncidentDetailScreen(
                incidentId = incidentId,
                viewModel = viewModel,
                // S-02 Mis incidencias sits directly underneath S-04 in the back
                // stack (the S-02 card tap navigated here), so popping back returns
                // to the S-02 route — its LaunchedEffect reloads the list scrolled
                // to top (frontend.yaml action_flows).
                onBackToS02 = { navController.popBackStack() },
            )
        }
    }
}
