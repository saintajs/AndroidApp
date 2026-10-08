/*
 * Instrumented (androidTest) contract tests for the platform secure-storage-
 * backed TokenStore — the AUTHORITATIVE verification of CA-05 per testing.yaml
 * ("instrumented tests remain the authoritative verification").
 *
 * Runs on a real device/emulator where the platform key provider is fully
 * available, so the FULL contract is asserted without assumptions:
 *  1. Round-trip: save -> read returns the same token.
 *  2. Delete: read() is null afterwards and the persisted entry is gone.
 *  3. No plain-text token anywhere in app storage: the shared_prefs XML file
 *     (which the platform truly persists) never contains the raw token, and a
 *     recursive scan of the app data directory finds no file with it. Prefs
 *     writes use apply() (asynchronous flush), so the on-disk assertions wait
 *     bounded for the XML file to reach the expected state — no fixed sleeps.
 *
 * The JVM-tier mirror (KeystoreTokenStoreTest, Robolectric) covers the same
 * contract minus the provider-gated round-trip, which only this tier can prove.
 *
 * No token material is ever printed or embedded in a failure message: every
 * assertion message below is static (testing.yaml rules). The gate-literal
 * naming convention (fragments, capitalized forms only) is respected.
 */
package com.nexti.minisoporte.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun saveThenReadRoundTripsViaPlatformBackedStorage() =
        runBlocking {
            val store = KeystoreTokenStore(context)
            store.save(TEST_TOKEN)
            assertEquals(MESSAGE_ROUND_TRIP, TEST_TOKEN, store.read())
        }

    @Test
    fun savePersistsOnlyEncryptedPayloadAndPrefsXmlHasNoPlainTextToken() =
        runBlocking {
            val store = KeystoreTokenStore(context)
            store.save(TEST_TOKEN)

            val payload = prefs().getString(PAYLOAD_ENTRY, null)
            assertNotNull(MESSAGE_PAYLOAD_PRESENT, payload)
            assertFalse(MESSAGE_PAYLOAD_NOT_PLAIN, payload == TEST_TOKEN)
            assertFalse(MESSAGE_PAYLOAD_NOT_PLAIN, payload!!.contains(TEST_TOKEN))

            // The platform persists prefs to disk: the XML file must exist and
            // never carry the raw token (bounded wait for the async flush).
            val prefsXml = awaitPrefsXml(POLL_TIMEOUT_MS)
            assertNotNull(MESSAGE_PREFS_XML_PRESENT, prefsXml)
            assertFalse(MESSAGE_NO_PLAIN_TEXT, prefsXml!!.readText().contains(TEST_TOKEN))
            assertNoAppStorageFileContainsPlainTextToken()
        }

    @Test
    fun deleteLeavesNothingReadableAndPrefsXmlClean() =
        runBlocking {
            val store = KeystoreTokenStore(context)
            store.save(TEST_TOKEN)

            store.delete()
            assertNull(MESSAGE_READ_NULL_AFTER_DELETE, store.read())
            assertFalse(MESSAGE_ENTRY_REMOVED, prefs().contains(PAYLOAD_ENTRY))

            // Bounded wait until the flushed XML no longer carries the entry;
            // fail with a static message if the entry ever survives.
            val prefsXml = awaitPrefsXml(POLL_TIMEOUT_MS)
            if (prefsXml != null) {
                awaitPayloadEntryRemoved(prefsXml)
                val text = prefsXml.readText()
                assertFalse(MESSAGE_ENTRY_REMOVED, text.contains(PAYLOAD_ENTRY))
                assertFalse(MESSAGE_NO_PLAIN_TEXT, text.contains(TEST_TOKEN))
            }
            assertNoAppStorageFileContainsPlainTextToken()
        }

    /** Bounded poll for the prefs XML file to appear on disk (apply() is async). */
    private fun awaitPrefsXml(timeoutMs: Long): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var file = prefsXmlFile()
        while (!file.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            file = prefsXmlFile()
        }
        return if (file.exists()) file else null
    }

    /** Bounded poll until the flushed XML no longer carries the payload entry. */
    private fun awaitPayloadEntryRemoved(prefsXml: File) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && prefsXml.readText().contains(PAYLOAD_ENTRY)) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun prefsXmlFile(): File {
        val storageRoot = context.filesDir.parentFile ?: context.filesDir
        return File(storageRoot, "shared_prefs/$PREFS_NAME.xml")
    }

    /**
     * Recursive scan of the app data directory (shared_prefs included): no file
     * may contain the raw token.
     */
    private fun assertNoAppStorageFileContainsPlainTextToken() {
        val storageRoot = context.filesDir.parentFile ?: context.filesDir
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

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val TEST_TOKEN = "test-session-token-instrumented"
        const val PREFS_NAME = "minisoporte_secure_prefs"
        const val PAYLOAD_ENTRY = "session_token_payload"
        const val POLL_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L

        // Static assertion messages only — never token material (testing.yaml).
        const val MESSAGE_ROUND_TRIP = "read must return the saved token via platform-backed storage"
        const val MESSAGE_PAYLOAD_PRESENT = "save must persist an encrypted payload entry"
        const val MESSAGE_PAYLOAD_NOT_PLAIN = "persisted state must never contain plain-text session material"
        const val MESSAGE_PREFS_XML_PRESENT = "the platform must persist the prefs XML file on device"
        const val MESSAGE_READ_NULL_AFTER_DELETE = "read must return null after delete"
        const val MESSAGE_ENTRY_REMOVED = "delete must remove the persisted entry"
        const val MESSAGE_NO_PLAIN_TEXT = "no app-storage file may contain plain-text session material"
    }
}
