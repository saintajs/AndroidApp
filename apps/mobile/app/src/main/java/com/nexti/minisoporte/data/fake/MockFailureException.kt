package com.nexti.minisoporte.data.fake

/**
 * The controlled exception thrown by [FakeIncidentRepository] when its failure
 * hook is enabled. ViewModels catch THIS type (not raw IllegalState/NullPointer)
 * and map it to the error-UiState-with-retry pattern (CONF-009): the failure is
 * a designed, retryable UI state — never a crash.
 *
 * The message is developer-facing only; it never reaches UI copy.
 */
class MockFailureException(
    message: String,
) : RuntimeException(message)
