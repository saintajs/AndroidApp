package com.nexti.minisoporte.data.remote

import android.content.Context
import com.nexti.minisoporte.BuildConfig
import com.nexti.minisoporte.data.fake.FakeAuthService
import com.nexti.minisoporte.data.fake.FakeIncidentRepository
import com.nexti.minisoporte.data.repository.IncidentRepository
import com.nexti.minisoporte.network.ApiClient
import com.nexti.minisoporte.security.InMemoryTokenStore
import com.nexti.minisoporte.security.KeystoreTokenStore
import com.nexti.minisoporte.security.TokenStore
import retrofit2.Retrofit

/**
 * The single injection point of the composition root (US-012): ONE switch over
 * [BuildConfig.USE_MOCK_BACKEND] selects the mock-path fakes (the default,
 * byte-identical behavior) or the real Retrofit/OkHttp adapters
 * ([RetrofitIncidentRepository]). ViewModels never read the flag themselves —
 * the composition root asks this factory for every dependency (CA-08/BR-042).
 *
 * S-01 placeholder (US-006 seam is frozen): SignInViewModel takes
 * [FakeAuthService] concretely, so the S-01 entry keeps [fakeAuthService] in
 * BOTH paths. In real mode the fake auth service is still backed by the same
 * platform secure-storage-backed [TokenStore] the network stack uses, so the
 * saved session token is real. [authService] exposes [RetrofitAuthService] as
 * the real auth seam for the contract/test level and future wiring.
 */
object AppServices {
    /** True when this build is wired to the real backend (USE_MOCK_BACKEND=false). */
    fun isRealBackend(): Boolean = BuildConfig.USE_MOCK_BACKEND.not()

    /**
     * Mock path: [InMemoryTokenStore] (in-process, exactly as before). Real
     * path: platform secure-storage-backed store rooted in the given [context].
     */
    fun tokenStore(context: Context): TokenStore =
        if (BuildConfig.USE_MOCK_BACKEND) {
            InMemoryTokenStore()
        } else {
            KeystoreTokenStore(context)
        }

    /**
     * The S-01 auth service in BOTH paths — see the placeholder note in the
     * class KDoc (US-006 seam takes the fake concretely).
     */
    fun fakeAuthService(tokenStore: TokenStore): FakeAuthService = FakeAuthService(tokenStore)

    /**
     * The real auth seam (login/me/sign-out over [AuthApi]). Not consumed by
     * the composition root yet (S-01 keeps the fake) — it exists for the
     * contract/test level and future wiring.
     */
    fun authService(tokenStore: TokenStore): RetrofitAuthService =
        RetrofitAuthService(
            authApi = sharedRetrofit(tokenStore).create(AuthApi::class.java),
            tokenStore = tokenStore,
        )

    /**
     * Mock path: a FRESH [FakeIncidentRepository] per call (the composition
     * root calls this per screen entry, preserving today's fresh-per-entry
     * semantics). Real path: [RetrofitIncidentRepository] over the shared
     * Retrofit, owner-scoped by the JWT and reporting session expiry (401)
     * through [onSessionExpired].
     */
    fun incidentRepository(
        tokenStore: TokenStore,
        onSessionExpired: (suspend () -> Unit)? = null,
    ): IncidentRepository =
        if (BuildConfig.USE_MOCK_BACKEND) {
            FakeIncidentRepository()
        } else {
            RetrofitIncidentRepository(
                incidentApi = sharedRetrofit(tokenStore).create(IncidentApi::class.java),
                tokenStore = tokenStore,
                onSessionExpired = onSessionExpired,
            )
        }

    @Volatile
    private var sharedRetrofitInstance: Retrofit? = null

    /**
     * The Retrofit instance is built ONCE per app process on the real path so
     * the OkHttp client and its connection pool are shared by every screen
     * entry (the mock path never reaches this). The first caller's
     * [TokenStore] is captured: on the real path every store instance is
     * rooted in the same encrypted shared preferences file, so the captured
     * instance reads/writes the same session token.
     */
    private fun sharedRetrofit(tokenStore: TokenStore): Retrofit =
        sharedRetrofitInstance ?: synchronized(this) {
            sharedRetrofitInstance ?: ApiClient
                .create(
                    baseUrl = BuildConfig.BASE_URL,
                    tokenStore = tokenStore,
                    debugLogging = BuildConfig.DEBUG,
                ).also { sharedRetrofitInstance = it }
        }
}
