package com.nexti.minisoporte.data.remote

import com.nexti.minisoporte.data.remote.dto.LoginRequestDto
import com.nexti.minisoporte.data.remote.dto.LoginResponseDto
import com.nexti.minisoporte.data.remote.dto.UserProfileDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/*
 * Retrofit mirror of the auth REST contract (backend.yaml endpoints
 * login / me).
 *
 *  - [login] → POST /api/auth/login (200 token pair; 401/422/429 stay
 *    reachable as Response.code() — uniform failure, no enumeration)
 *  - [me]    → GET /api/me (requires Authorization: Bearer, attached by
 *    the AuthInterceptor)
 *
 * Both calls are suspend and return retrofit2.Response so non-2xx
 * outcomes are branch-mapped in the adapter layer, never guessed from
 * exceptions. Domain models never appear here — DTOs only (CA-03).
 */
interface AuthApi {
    @POST("api/auth/login")
    suspend fun login(
        @Body body: LoginRequestDto,
    ): Response<LoginResponseDto>

    @GET("api/me")
    suspend fun me(): Response<UserProfileDto>
}
