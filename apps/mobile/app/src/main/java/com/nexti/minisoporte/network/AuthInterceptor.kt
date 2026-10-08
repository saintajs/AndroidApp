/*
 * Bearer-token attach interceptor (design.md Decision 3, CA-04/INSTR-008).
 *
 * The SINGLE place the app attaches `Authorization: Bearer <token>`. Token
 * material is never logged here, and the class holds no Android imports —
 * pure OkHttp plus the [com.nexti.minisoporte.security.TokenStore] seam.
 */
package com.nexti.minisoporte.network

import com.nexti.minisoporte.security.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the session token from [tokenStore] to every outgoing request.
 *
 * When no token is stored the request proceeds WITHOUT the Authorization
 * header — never `Bearer null`; the server's 401 responses drive the
 * recovery path, so this fail-open at transport level is intentional.
 */
class AuthInterceptor(
    private val tokenStore: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // TokenStore methods are suspend; OkHttp interceptors run blocking on
        // OkHttp's own dispatcher threads (never Main), so runBlocking is the
        // correct bridge here and cannot block the UI thread.
        val token = runBlocking { tokenStore.read() }
        val request =
            if (token != null) {
                chain
                    .request()
                    .newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
        return chain.proceed(request)
    }
}
