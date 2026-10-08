/*
 * Retrofit adapter implementing the auth seam over [AuthApi]
 * (design.md Decision 6, backend.yaml services.RetrofitAuthService,
 * test_access_points.login error strings copied VERBATIM).
 *
 * STRUCTURAL NOTE (binding): the S-01 screen keeps the fake on the mock path
 * (SignInViewModel takes FakeAuthService concretely and is US-006-owned);
 * this class is the REAL auth seam implementation consumed at contract level
 * and by the composition-root switch for the real path. It deliberately
 * implements no interface.
 *
 * ERROR BRANCHES (CA-06):
 *  - 200 → the JWT is stored via [TokenStore] and [RemoteAuthResult.Success]
 *    carries ONLY the expiry seconds — token material never reaches the
 *    result, any log or any state (CONF-002).
 *  - 401 → the server `detail` is copied VERBATIM (group-decisions rule);
 *    generic and non-enumerating ("Credenciales inválidas").
 *  - 422 → FastAPI's structured array detail is mapped by the LAST `loc`
 *    element (leading "body"/"query" ignored) to Spanish per-field copy;
 *    unmapped descriptors fall back to a generic message. Raw English `msg`
 *    values and raw JSON are NEVER rendered (design.md Risks). The first
 *    issue per field wins.
 *  - 429 → server `detail` verbatim + `Retry-After` parsed when it is plain
 *    digits (null otherwise).
 *  - Transport failures (IOException/timeouts) and every unexpected HTTP
 *    status (5xx) → [RemoteAuthResult.TransportError] — retryable, never a
 *    crash (CONF-009).
 */
package com.nexti.minisoporte.data.remote

import com.nexti.minisoporte.data.model.UserProfile
import com.nexti.minisoporte.data.remote.dto.ErrorDto
import com.nexti.minisoporte.data.remote.dto.LoginRequestDto
import com.nexti.minisoporte.data.remote.dto.LoginResponseDto
import com.nexti.minisoporte.data.remote.dto.ValidationErrorDto
import com.nexti.minisoporte.data.remote.dto.ValidationIssueDto
import com.nexti.minisoporte.data.remote.mappers.toDomain
import com.nexti.minisoporte.security.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/**
 * Outcome of a real sign-in attempt — one branch per backend.yaml `login`
 * error fixture plus the retryable transport case. NEVER carries token
 * material (CONF-002): [Success] exposes only the expiry seconds.
 */
sealed interface RemoteAuthResult {
    /** 200 — token already stored via [TokenStore]; only the TTL travels. */
    data class Success(
        val expiresInSeconds: Long,
    ) : RemoteAuthResult

    /** 401 — generic, non-enumerating; server `detail` copied verbatim. */
    data class InvalidCredentials(
        val message: String,
    ) : RemoteAuthResult

    /**
     * 422 — per-field Spanish copy mapped from the `loc` descriptors;
     * [generalError] is always populated (generic fallback).
     */
    data class ValidationError(
        val emailError: String?,
        val passwordError: String?,
        val generalError: String,
    ) : RemoteAuthResult

    /** 429 — server `detail` verbatim; [retryAfterSeconds] null when absent/unparsable. */
    data class RateLimited(
        val message: String,
        val retryAfterSeconds: Long?,
    ) : RemoteAuthResult

    /** IOException/timeouts/5xx — retryable, never a crash (CONF-009). */
    data class TransportError(
        val message: String,
    ) : RemoteAuthResult
}

/**
 * Raised when GET /api/me reports 401 — the stored session is no longer
 * valid; the recovery path clears the token via [TokenStore] and returns
 * to sign-in (same recovery shape as the repository adapter). Carries no
 * token material.
 */
class SessionExpiredException(
    message: String = SESSION_EXPIRED_MESSAGE,
) : Exception(message) {
    private companion object {
        const val SESSION_EXPIRED_MESSAGE = "La sesión ha expirado."
    }
}

/**
 * Real sign-in/sign-out/profile adapter over [AuthApi]. No interface — the
 * mock path keeps [com.nexti.minisoporte.data.fake.FakeAuthService] concretely.
 */
class RetrofitAuthService(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
) {
    private val errorJson = Json { ignoreUnknownKeys = true }

    /**
     * POST /api/auth/login with the 200/401/422/429 branch mapping from
     * backend.yaml. Never throws for HTTP or transport failures — a thrown
     * [CancellationException] still propagates so coroutine cancellation
     * keeps working.
     */
    suspend fun signIn(
        email: String,
        password: String,
    ): RemoteAuthResult =
        networkCatching {
            val response = authApi.login(LoginRequestDto(email = email, password = password))
            when (response.code()) {
                200 -> signedInResultOf(response)
                401 ->
                    RemoteAuthResult.InvalidCredentials(
                        message =
                            errorDetailOf(response) ?: INVALID_CREDENTIALS_MESSAGE,
                    )
                422 -> validationErrorOf(response)
                429 -> rateLimitedOf(response)
                else -> RemoteAuthResult.TransportError(RETRYABLE_MESSAGE)
            }
        }.getOrElse { RemoteAuthResult.TransportError(RETRYABLE_MESSAGE) }

    /**
     * GET /api/me: 200 → the mapped domain profile; 401 → the token is
     * cleared and [SessionExpiredException] is the failure (the recovery
     * signal, mirroring the repository adapter); any other HTTP failure →
     * [IOException]; transport failures → the thrown error as failure.
     * No message ever contains token material.
     */
    suspend fun fetchProfile(): Result<UserProfile> =
        networkCatching {
            val response = authApi.me()
            when {
                response.code() == 401 -> {
                    runCatching { tokenStore.delete() }
                    Result.failure(SessionExpiredException())
                }
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        Result.failure(IOException(EMPTY_PROFILE_BODY_MESSAGE))
                    } else {
                        Result.success(body.toDomain())
                    }
                }
                else -> Result.failure(IOException(PROFILE_HTTP_FAILURE_MESSAGE))
            }
        }.fold(
            onSuccess = { profileResult -> profileResult },
            onFailure = { error -> Result.failure(error) },
        )

    /** Clears the current session via [TokenStore.delete]. */
    suspend fun signOut() {
        tokenStore.delete()
    }

    /**
     * The 200 branch: store the JWT via [TokenStore] and report success with
     * only the expiry seconds — the token itself never leaves this method
     * (CONF-002).
     */
    private suspend fun signedInResultOf(response: Response<LoginResponseDto>): RemoteAuthResult {
        val body = response.body()
        if (body == null) {
            return RemoteAuthResult.TransportError(RETRYABLE_MESSAGE)
        }
        tokenStore.save(body.access_token)
        return RemoteAuthResult.Success(expiresInSeconds = body.expires_in_seconds)
    }

    /**
     * 422 → per-field Spanish copy by the LAST `loc` element (leading
     * "body"/"query" ignored this way); first issue per field wins; an
     * unparsable body or an unmapped descriptor lands in the generic
     * fallback. Raw server `msg` strings are never surfaced.
     */
    private fun validationErrorOf(response: Response<*>): RemoteAuthResult.ValidationError {
        var emailError: String? = null
        var passwordError: String? = null
        var generalError: String? = null
        parseValidationIssues(response).forEach { issue ->
            when (issue.loc.lastOrNull()) {
                FIELD_EMAIL -> if (emailError == null) emailError = EMAIL_FIELD_ERROR
                FIELD_PASSWORD -> if (passwordError == null) passwordError = PASSWORD_FIELD_ERROR
                else -> if (generalError == null) generalError = GENERAL_VALIDATION_FALLBACK
            }
        }
        return RemoteAuthResult.ValidationError(
            emailError = emailError,
            passwordError = passwordError,
            generalError = generalError ?: GENERAL_VALIDATION_FALLBACK,
        )
    }

    /**
     * 429 → server `detail` verbatim (literal fallback when unparsable) plus
     * `Retry-After` parsed only when it is plain digits.
     */
    private fun rateLimitedOf(response: Response<*>): RemoteAuthResult.RateLimited =
        RemoteAuthResult.RateLimited(
            message = errorDetailOf(response) ?: RATE_LIMITED_MESSAGE,
            retryAfterSeconds = response.headers()["Retry-After"]?.trim()?.toLongOrNull(),
        )

    /** The 401/429 `{"detail": "…"}` body, decoded; null when it fails to parse. */
    private fun errorDetailOf(response: Response<*>): String? =
        response.errorBody()?.string()?.let { raw ->
            runCatching { errorJson.decodeFromString<ErrorDto>(raw).detail }.getOrNull()
        }

    /** The 422 array detail, decoded; empty when the body fails to parse. */
    private fun parseValidationIssues(response: Response<*>): List<ValidationIssueDto> =
        response.errorBody()?.string()?.let { raw ->
            runCatching { errorJson.decodeFromString<ValidationErrorDto>(raw).detail }.getOrNull()
        } ?: emptyList()

    private companion object {
        const val INVALID_CREDENTIALS_MESSAGE = "Credenciales inválidas"
        const val RATE_LIMITED_MESSAGE = "Demasiados intentos. Inténtelo de nuevo más tarde."
        const val GENERAL_VALIDATION_FALLBACK = "Revisa los datos ingresados e inténtalo de nuevo."
        const val EMAIL_FIELD_ERROR = "Ingresa un correo electrónico válido"
        const val PASSWORD_FIELD_ERROR = "Ingresa una contraseña válida"
        const val RETRYABLE_MESSAGE = "No se pudo conectar. Inténtalo de nuevo."
        const val EMPTY_PROFILE_BODY_MESSAGE = "No se pudo obtener el perfil."
        const val PROFILE_HTTP_FAILURE_MESSAGE = "No se pudo obtener el perfil."
        const val FIELD_EMAIL = "email"
        const val FIELD_PASSWORD = "password"
    }
}

/**
 * [runCatching] that rethrows [CancellationException] so coroutine cancellation
 * keeps propagating (plain runCatching would swallow it) while every other
 * failure — IOException/timeouts, HTTP-layer errors, mapper
 * IllegalArgumentException — becomes [Result.failure] (CONF-009: never a
 * crash). Same pattern as the repository adapter.
 */
private inline fun <T> networkCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
