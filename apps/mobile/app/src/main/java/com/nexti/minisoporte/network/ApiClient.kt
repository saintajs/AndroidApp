/*
 * OkHttp/Retrofit transport factory (design.md Decision 3, CA-04/INSTR-008).
 *
 * Single place the app builds its HTTP stack: explicit timeouts, the
 * [AuthInterceptor] as the ONLY bearer-attach point, and debug body logging
 * with the Authorization header redacted so token material never reaches any
 * log. Callers inject the [TokenStore] — no singleton lookups here.
 */
package com.nexti.minisoporte.network

import com.nexti.minisoporte.security.TokenStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the shared [OkHttpClient] and Retrofit instances for the real-backend path.
 *
 * Strict JSON: unknown server keys fail visibly ([Json.ignoreUnknownKeys] = false)
 * so wire drift is never silently swallowed; [Json.encodeDefaults] = true keeps
 * request bodies complete.
 */
object ApiClient {
    private val json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }

    /**
     * Builds the OkHttp client with explicit connect/read/write timeouts and the
     * [AuthInterceptor] installed.
     *
     * When [debugLogging] is true a body-level [HttpLoggingInterceptor] is added
     * AFTER the auth interceptor, with the Authorization header redacted so the
     * token value never reaches logs (CA-04 / INSTR-008).
     */
    fun okHttpClient(
        tokenStore: TokenStore,
        debugLogging: Boolean = false,
    ): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(AuthInterceptor(tokenStore))
        if (debugLogging) {
            val logging =
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    redactHeader("Authorization")
                }
            builder.addInterceptor(logging)
        }
        return builder.build()
    }

    /**
     * Creates the Retrofit instance for [baseUrl] (the BuildConfig base URL per
     * build type) over [okHttpClient], with the kotlinx-serialization converter.
     */
    fun create(
        baseUrl: String,
        tokenStore: TokenStore,
        debugLogging: Boolean = false,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient(tokenStore, debugLogging))
            .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()
}
