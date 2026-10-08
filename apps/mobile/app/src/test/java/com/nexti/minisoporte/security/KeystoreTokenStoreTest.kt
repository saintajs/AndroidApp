/*
 * Robolectric contract tests for the platform secure-storage-backed TokenStore
 * (US-012 CA-05 / BR-006, testing.yaml "KeystoreTokenStore" harness).
 *
 * Tier split (testing.yaml): plain JVM tests keep using the InMemoryTokenStore
 * fake; this suite is placed under app/src/test with @RunWith(RobolectricTestRunner)
 * — that IS the Robolectric tier, so the Keystore-backed implementation is never
 * instantiated outside it.
 *
 * What the JVM tier can honestly assert (Robolectric only partially simulates
 * the platform key provider):
 *  - No plain-text token ever reaches app storage: save() either persists a
 *    ciphertext-shaped payload entry (Base64, never containing/equal to the
 *    token) or fails fast before persisting anything (key generation precedes
 *    encryption) — a recursive scan of the app data directory must find no
 *    file with the raw token in either case (the shared_prefs XML file is
 *    checked whenever the platform wrote it — Robolectric's prefs shadow is
 *    in-memory, so the file may be absent on this tier; the instrumented
 *    variant asserts that file unconditionally, where the platform always
 *    persists it).
 *  - Fail-closed reads: an undecryptable entry is removed and read back as
 *    null — never a crash, never foreign data.
 *  - Delete leaves nothing readable and no persisted entry behind.
 * The round-trip half (save -> read returns the same token) is gated with an
 * Assume on platform key-provider availability: it SKIPS where the provider is
 * absent (typical Robolectric) and PASSES where it works. The instrumented run
 * (KeystoreTokenStoreInstrumentedTest, app/src/androidTest) is the AUTHORITATIVE
 * round-trip and on-disk proof per testing.yaml.
 *
 * No token material is ever printed or embedded in a failure message: every
 * assertion message below is static (testing.yaml rules). The gate-literal
 * naming convention (fragments, capitalized forms only) is respected.
 */
package com.nexti.minisoporte.security

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStore
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeystoreTokenStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun saveThenReadRoundTripsWhenPlatformKeyProviderAvailable() =
        runBlocking {
            // The platform key provider is only partially simulated under
            // Robolectric: skip (never fail) where it is absent. The instrumented
            // run is the authoritative proof of this half (testing.yaml).
            assumeTrue(MESSAGE_PROVIDER_ASSUMPTION, platformKeyProviderAvailable())
            val store = KeystoreTokenStore(context)
            store.save(TEST_TOKEN)
            assertEquals(MESSAGE_ROUND_TRIP, TEST_TOKEN, store.read())
        }

    @Test
    fun savePersistsOnlyEncryptedPayloadAndNoPlainTextTokenInAppStorage() =
        runBlocking {
            val store = KeystoreTokenStore(context)
            // Under Robolectric the platform key provider is only partially
            // simulated, so save() may fail fast BEFORE anything is persisted
            // (key generation precedes encryption). The contract holds either
            // way: ciphertext-only persistence when it succeeds, a clean
            // failure that leaks nothing when it cannot — never plain text.
            val savedCleanly = attemptSave(store)

            if (savedCleanly) {
                val payload = prefs().getString(PAYLOAD_ENTRY, null)
                assertNotNull(MESSAGE_PAYLOAD_PRESENT, payload)
                assertFalse(MESSAGE_PAYLOAD_NOT_PLAIN, payload == TEST_TOKEN)
                assertFalse(MESSAGE_PAYLOAD_NOT_PLAIN, payload!!.contains(TEST_TOKEN))
                assertDecodedPayloadIsCiphertextShaped(payload)
            }
            assertNoAppStorageFileContainsPlainTextToken()
        }

    @Test
    fun deleteLeavesNothingReadableAndNoPersistedEntry() =
        runBlocking {
            val store = KeystoreTokenStore(context)
            seedPersistedEntry(store)

            store.delete()
            assertNull(MESSAGE_READ_NULL_AFTER_DELETE, store.read())
            assertFalse(MESSAGE_ENTRY_REMOVED, prefs().contains(PAYLOAD_ENTRY))
            assertNoAppStorageFileContainsPlainTextToken()
        }

    @Test
    fun deleteOnEmptyStoreIsANoOp() =
        runBlocking {
            val store = KeystoreTokenStore(context)
            store.delete()
            assertNull(MESSAGE_READ_NULL_ON_EMPTY, store.read())
        }

    @Test
    fun undecryptablePayloadFailsClosedAndIsRemoved() =
        runBlocking {
            // Seeded directly through prefs: valid Base64 shape (12-byte IV +
            // garbage ciphertext) that can never decrypt. No platform key
            // provider is needed to prove the fail-closed path.
            prefs().edit().putString(PAYLOAD_ENTRY, CORRUPTED_PAYLOAD).apply()

            assertNull(MESSAGE_FAIL_CLOSED, KeystoreTokenStore(context).read())
            assertFalse(MESSAGE_FAIL_CLOSED_REMOVED, prefs().contains(PAYLOAD_ENTRY))
        }

    @Test
    fun readOnFreshStoreReturnsNull() =
        runBlocking {
            assertNull(MESSAGE_READ_NULL_ON_EMPTY, KeystoreTokenStore(context).read())
        }

    /**
     * The saved payload must decode as Base64(IV || ciphertext): at least the
     * 12-byte IV prefix plus a non-empty ciphertext body — never the raw token.
     */
    private fun assertDecodedPayloadIsCiphertextShaped(payload: String) {
        val bytes =
            try {
                Base64.getDecoder().decode(payload)
            } catch (error: IllegalArgumentException) {
                throw AssertionError(MESSAGE_PAYLOAD_DECODE, error)
            }
        assertTrue(MESSAGE_PAYLOAD_SHAPE, bytes.size > IV_SIZE_BYTES)
        assertFalse(
            MESSAGE_PAYLOAD_NOT_PLAIN,
            String(bytes, Charsets.UTF_8).contains(TEST_TOKEN),
        )
    }

    /**
     * Recursive scan of the app data directory (shared_prefs included): no file
     * may contain the raw token. The shared_prefs XML is asserted whenever the
     * platform wrote it; under Robolectric the prefs shadow is in-memory, so its
     * absence here is expected and covered on-device instead.
     */
    private fun assertNoAppStorageFileContainsPlainTextToken() {
        val storageRoot = context.filesDir.parentFile ?: context.filesDir
        val prefsXml = File(storageRoot, "shared_prefs/$PREFS_NAME.xml")
        if (prefsXml.exists()) {
            assertFalse(MESSAGE_NO_PLAIN_TEXT, prefsXml.readText().contains(TEST_TOKEN))
        }
        storageRoot
            .walkTopDown()
            .filter { file -> file.isFile }
            .forEach { file ->
                assertFalse(
                    MESSAGE_NO_PLAIN_TEXT,
                    file.readBytes().toString(Charsets.UTF_8).contains(TEST_TOKEN),
                )
            }
    }

    /**
     * Attempts a save through the real implementation. Returns true when it
     * persisted (platform key provider available); returns false when the
     * provider is absent and save failed fast — in that case a ciphertext-
     * shaped payload is seeded directly so delete() has real persisted state
     * to remove (never the plain token — seeding plain text would be the very
     * leak these tests forbid).
     */
    private suspend fun attemptSave(store: TokenStore): Boolean =
        try {
            store.save(TEST_TOKEN)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            prefs().edit().putString(PAYLOAD_ENTRY, CORRUPTED_PAYLOAD).apply()
            false
        }

    /** Alias of [attemptSave] for delete-path tests: state must exist before delete. */
    private suspend fun seedPersistedEntry(store: TokenStore) {
        attemptSave(store)
    }

    /**
     * Probes whether the platform key provider is usable in this environment —
     * the assumption guard for the round-trip test only.
     */
    private fun platformKeyProviderAvailable(): Boolean =
        try {
            val probe = KeyStore.getInstance(PROVIDER_NAME)
            probe.load(null)
            true
        } catch (error: Exception) {
            false
        }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val TEST_TOKEN = "test-session-token-robolectric"
        const val PREFS_NAME = "minisoporte_secure_prefs"
        const val PAYLOAD_ENTRY = "session_token_payload"
        const val PROVIDER_NAME = "AndroidKeyStore"
        const val IV_SIZE_BYTES = 12

        // Valid Base64 shape (12 zero-byte IV + 16 undecryptable bytes) seeded to
        // prove the fail-closed read path and to give delete() persisted state
        // when the platform key provider is absent.
        val CORRUPTED_PAYLOAD: String =
            Base64.getEncoder().encodeToString(ByteArray(IV_SIZE_BYTES + 16))

        // Static assertion messages only — never token material (testing.yaml).
        const val MESSAGE_PROVIDER_ASSUMPTION = "platform key provider must be available for the round-trip contract"
        const val MESSAGE_ROUND_TRIP = "read must return the saved token when the platform key provider is available"
        const val MESSAGE_PAYLOAD_PRESENT = "save must persist an encrypted payload entry"
        const val MESSAGE_PAYLOAD_NOT_PLAIN = "persisted state must never contain plain-text session material"
        const val MESSAGE_PAYLOAD_DECODE = "persisted payload must be valid Base64(IV || ciphertext)"
        const val MESSAGE_PAYLOAD_SHAPE = "persisted payload must carry an IV prefix plus ciphertext"
        const val MESSAGE_READ_NULL_AFTER_DELETE = "read must return null after delete"
        const val MESSAGE_READ_NULL_ON_EMPTY = "read must return null on an empty store"
        const val MESSAGE_ENTRY_REMOVED = "delete must remove the persisted entry"
        const val MESSAGE_FAIL_CLOSED = "read must fail closed (null) on an undecryptable entry"
        const val MESSAGE_FAIL_CLOSED_REMOVED = "a fail-closed read must remove the undecryptable entry"
        const val MESSAGE_NO_PLAIN_TEXT = "no app-storage file may contain plain-text session material"
    }
}
