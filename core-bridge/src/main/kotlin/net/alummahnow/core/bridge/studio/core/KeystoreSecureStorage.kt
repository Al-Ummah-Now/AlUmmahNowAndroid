package net.alummahnow.core.bridge.studio.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.alummahnow.core.AunException
import net.alummahnow.core.SecureStorage

/**
 * aun-core's secret store on Android: values are AES-256-GCM encrypted with a non-exportable
 * Android Keystore key and kept in private SharedPreferences. Tokens never leave the device's
 * keystore-protected storage, and app backup is off (`allowBackup=false`).
 */
class KeystoreSecureStorage(context: Context, private val name: String = "aun_secure") : SecureStorage {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val lock = Any()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return gen.generateKey()
    }

    override fun get(key: String): ByteArray? = synchronized(lock) {
        val stored = prefs.getString(key, null) ?: return null
        try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
        } catch (e: java.security.GeneralSecurityException) {
            // The keystore key was lost (e.g. device restore): the secret is unreadable; drop it.
            prefs.edit().remove(key).apply()
            throw AunException.Storage("secure value unreadable: ${e.javaClass.simpleName}").apply { initCause(e) }
        } catch (e: IllegalArgumentException) {
            prefs.edit().remove(key).apply()
            throw AunException.Storage("secure value corrupt").apply { initCause(e) }
        }
    }

    override fun set(key: String, value: ByteArray) = synchronized(lock) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(value)
            if (!prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).commit()) {
                throw AunException.Storage("could not save")
            }
        } catch (e: java.security.GeneralSecurityException) {
            throw AunException.Platform("keystore: ${e.javaClass.simpleName}").apply { initCause(e) }
        }
    }

    override fun delete(key: String) = synchronized(lock) {
        if (!prefs.edit().remove(key).commit()) throw AunException.Storage("could not delete")
    }

    override fun list(prefix: String): List<String> = synchronized(lock) { prefs.all.keys.filter { it.startsWith(prefix) }.sorted() }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "aun-core-secure-storage"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
