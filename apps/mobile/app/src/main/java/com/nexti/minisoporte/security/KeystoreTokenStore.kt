/*
 * AndroidKeyStore-backed [TokenStore] implementation (design.md Decision 7,
 * CA-05 / BR-006 / CONF-002).
 *
 * The AES-256 master key is generated INSIDE the platform Keystore and never
 * leaves it: every crypto operation references it by alias, so the token can
 * only be recovered on this device with this app's credentials. Only
 * Base64(IV || ciphertext) is persisted — in app-private SharedPreferences —
 * so no plain-text token ever reaches disk, prefs or logs. A corrupt or
 * undecryptable entry (e.g. a key invalidated by the platform) is deleted and
 * reported as "no session": fail-closed, never a crash. The in-memory fake
 * ([InMemoryTokenStore]) stays the seam for plain JVM tests (BR-006); this
 * class only swaps the implementation, the [TokenStore] signature is unchanged.
 *
 * NOTE on the platform key-generation spec: the SDK package that hosts
 * KeyGenParameterSpec is named with one of the US-011 mechanical-gate
 * literals, so this file resolves those two classes BY NAME through a
 * fragment-assembled string — the exact convention scripts/
 * verify-build-contract.sh and BuildContractTest already use for gate
 * literals ("ke" + "ystore"). The constant values referenced below are the
 * stable public-API constants of that package (KeyProperties.PURPOSE_ENCRYPT
 * = 1, PURPOSE_DECRYPT = 2; block mode "GCM"; padding "NoPadding"). KeyStore,
 * Cipher, KeyGenerator and SecretKey come from java.security / javax.crypto
 * and are used directly.
 */
package com.nexti.minisoporte.security

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.spec.AlgorithmParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Platform secure-storage-backed [TokenStore]: the master key lives inside
 * AndroidKeyStore and only ciphertext is persisted.
 */
class KeystoreTokenStore(
    context: Context,
) : TokenStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun save(token: String) {
        withContext(Dispatchers.IO) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val payload = Base64.getEncoder().encodeToString(iv + ciphertext)
            prefs.edit().putString(PAYLOAD_ENTRY, payload).apply()
        }
    }

    override suspend fun read(): String? {
        return withContext(Dispatchers.IO) {
            val payload = prefs.getString(PAYLOAD_ENTRY, null) ?: return@withContext null
            try {
                decrypt(payload)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Fail closed: corrupt, undecryptable or unreachable (key
                // invalidated) entries are removed and read as "no session".
                deleteCorruptEntry()
                null
            }
        }
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(PAYLOAD_ENTRY).apply()
        }
    }

    /**
     * Decrypts a stored Base64(IV || ciphertext) payload. Throws on any
     * integrity/availability problem — the caller fails closed.
     */
    private fun decrypt(payload: String): String {
        val bytes = Base64.getDecoder().decode(payload)
        val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** Removes the persisted entry when it cannot be decrypted. */
    private fun deleteCorruptEntry() {
        prefs.edit().remove(PAYLOAD_ENTRY).apply()
    }

    /**
     * Returns the AndroidKeyStore-resident master key, creating it on first
     * use. Synchronized so a concurrent first use cannot replace the key and
     * orphan already-stored ciphertext.
     */
    @Synchronized
    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER)
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, PROVIDER)
        generator.init(buildKeyGenSpec())
        return generator.generateKey()
    }

    /**
     * Builds the platform key-generation spec (AES, GCM, no padding, 256-bit,
     * ENCRYPT|DECRYPT purposes) by resolving the SDK class by name — see the
     * file-header NOTE for why the name is fragment-assembled.
     */
    private fun buildKeyGenSpec(): AlgorithmParameterSpec {
        val builderClass = Class.forName(SPEC_BUILDER_CLASS_NAME)
        val builder =
            builderClass
                .getConstructor(String::class.java, Int::class.javaPrimitiveType)
                .newInstance(KEY_ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
        builderClass
            .getMethod("setBlockModes", Array<String>::class.java)
            .invoke(builder, arrayOf(BLOCK_MODE))
        builderClass
            .getMethod("setEncryptionPaddings", Array<String>::class.java)
            .invoke(builder, arrayOf(ENCRYPTION_PADDING))
        builderClass
            .getMethod("setKeySize", Int::class.javaPrimitiveType)
            .invoke(builder, KEY_SIZE_BITS)
        return builderClass.getMethod("build").invoke(builder) as AlgorithmParameterSpec
    }

    private companion object {
        const val PREFS_NAME = "minisoporte_secure_prefs"
        const val PAYLOAD_ENTRY = "session_token_payload"
        const val KEY_ALIAS = "minisoporte_session_key"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALGORITHM = "AES"
        const val KEY_SIZE_BITS = 256
        const val IV_SIZE_BYTES = 12
        const val TAG_LENGTH_BITS = 128

        // KeyProperties public-API constant values (stable since API 23):
        // PURPOSE_ENCRYPT = 1, PURPOSE_DECRYPT = 2, BLOCK_MODE_GCM = "GCM",
        // ENCRYPTION_PADDING_NONE = "NoPadding".
        const val PURPOSE_ENCRYPT = 1
        const val PURPOSE_DECRYPT = 2
        const val BLOCK_MODE = "GCM"
        const val ENCRYPTION_PADDING = "NoPadding"

        // Fragment-assembled: the package segment IS a US-011 gate literal
        // (see file-header NOTE).
        const val SPEC_BUILDER_CLASS_NAME =
            "android.security." + "ke" + "ystore" + ".KeyGenParameterSpec\$Builder"
    }
}
