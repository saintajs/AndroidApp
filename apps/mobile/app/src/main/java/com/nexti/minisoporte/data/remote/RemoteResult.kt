package com.nexti.minisoporte.data.remote

import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentHistoryEntry

/*
 * Sealed result types for the richer (non-interface) paths of
 * [RetrofitIncidentRepository] — one branch per backend.yaml error fixture
 * (CA-09): a real 401 surfaces as the dedicated SessionExpired recovery
 * signal (never a crash), 404 on detail is the safe non-leaking no-access
 * outcome (missing == foreign, indistinguishable), 409 on transition is
 * retryable error-state DATA, and 403/429/5xx/transport collapse into
 * RetryableError.
 */

/**
 * Outcome of the richer detail GET (`getIncidentDetail`). `history` inside
 * [Found] is the server's ASC-by-changed_at order, preserved as-is.
 */
sealed interface IncidentDetailResult {
    data class Found(
        val incident: Incident,
    ) : IncidentDetailResult

    /** 404 — missing == foreign, indistinguishable (safe no-access). */
    data object NoAccess : IncidentDetailResult

    /** Real 401 — the dedicated recovery signal (token deleted, navigate to S-01). */
    data object SessionExpired : IncidentDetailResult

    /** 429/5xx/transport — retry later, never a crash. */
    data object RetryableError : IncidentDetailResult
}

/**
 * Outcome of the richer status transition (`updateStatus`). [Success] carries
 * the updated incident plus the accepted `latest_history` entry.
 */
sealed interface StatusUpdateResult {
    data class Success(
        val incident: Incident,
        val latestHistory: IncidentHistoryEntry,
    ) : StatusUpdateResult

    /** 404 — missing == foreign, indistinguishable (safe no-access). */
    data object NoAccess : StatusUpdateResult

    /** Real 401 — the dedicated recovery signal (token deleted, navigate to S-01). */
    data object SessionExpired : StatusUpdateResult

    /** 409 — invalid transition, surfaced as retryable error-state data (never a crash). */
    data object InvalidTransition : StatusUpdateResult

    /** 403/429/5xx/transport — retry later, never a crash. */
    data object RetryableError : StatusUpdateResult
}

/**
 * Outcome of the richer create (`createIncidentResult`). The plain
 * `IncidentRepository.createIncident` interface method has no error channel
 * and delegates here; on every non-201 branch it falls back to its documented
 * synthesized uncreated incident (id="", timestamps="") — it never throws.
 */
sealed interface CreateResult {
    data class Success(
        val incident: Incident,
    ) : CreateResult

    /** Real 401 — the dedicated recovery signal (token deleted, navigate to S-01). */
    data object SessionExpired : CreateResult

    /** 422/429/5xx/transport — retry later, never a crash. */
    data object RetryableError : CreateResult
}
