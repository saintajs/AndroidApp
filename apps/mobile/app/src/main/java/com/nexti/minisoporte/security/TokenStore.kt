/*
 * Session-token storage seam (design.md Decision 5, CA-06/CA-07/CONF-002/BR-006).
 *
 * This is the ONLY way any code in this change touches token material: a port
 * with save/read/delete. The in-memory fake lives in [InMemoryTokenStore];
 * US-012 replaces the implementation with a platform secure-storage-backed
 * store WITHOUT changing this signature. No Android imports here, no logging of
 * token material anywhere (CONF-002).
 */
package com.nexti.minisoporte.security

/**
 * Port for session-token storage. US-012 replaces the in-memory fake with a platform secure-storage-backed impl.
 */
interface TokenStore {
    suspend fun save(token: String)

    /** Returns the stored token, or `null` when no session is present. */
    suspend fun read(): String?

    /** Removes the stored token, if any; [read] returns `null` afterwards. */
    suspend fun delete()
}
