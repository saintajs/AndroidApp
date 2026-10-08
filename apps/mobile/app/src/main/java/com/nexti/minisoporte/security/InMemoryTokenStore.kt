/*
 * Deterministic in-memory [TokenStore] fake (design.md Decision 5).
 *
 * Pure JVM: no Android imports, no logging, no randomness — save stores,
 * read returns the stored token or null, delete removes it. Deliberately
 * process-local: US-012 swaps in the platform secure-storage-backed implementation.
 */
package com.nexti.minisoporte.security

/**
 * The deterministic in-memory fake used by every mock-first story and test.
 * Holds at most one token — the current session's.
 */
class InMemoryTokenStore : TokenStore {
    private var token: String? = null

    override suspend fun save(token: String) {
        this.token = token
    }

    override suspend fun read(): String? = token

    override suspend fun delete() {
        token = null
    }
}
