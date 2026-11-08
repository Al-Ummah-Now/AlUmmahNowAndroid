package net.alummahnow.core.bridge.platform

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.alummahnow.core.AunException
import net.alummahnow.core.SecureStorage
import net.alummahnow.core.bridge.AunLog

/**
 * The core's [SecureStorage] adapter on Android: values are sealed with AES-256-GCM under a non-exportable
 * Android Keystore key and kept in private SharedPreferences (excluded from backups by the app's rules).
 * A value that can no longer be decrypted (key wiped, restore to a new device) reads as absent.
 */
class KeystoreSecureStorage(
    context: Context,
    private val crypto: Crypto = KeystoreCrypto(),
    fileName: String = "aun_secure"
) : SecureStorage {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    /** Seals and opens byte arrays. The Keystore-backed default is swapped out in JVM tests. */
    interface Crypto {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    override fun get(key: String): ByteArray? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            crypto.open(Base64.decode(stored, Base64.NO_WRAP))
        } catch (e: GeneralSecurityException) {
            AunLog.w(TAG, "unreadable secure value dropped", "key" to key, "error" to e.javaClass.simpleName)
            prefs.edit().remove(key).apply()
            null
        } catch (e: IllegalArgumentException) {
            AunLog.w(TAG, "corrupt secure value dropped", "key" to key, "error" to e.javaClass.simpleName)
            prefs.edit().remove(key).apply()
            null
        }
    }

    override fun set(key: String, value: ByteArray) {
        val sealed = try {
            crypto.seal(value)
        } catch (e: GeneralSecurityException) {
            throw AunException.Storage("secure storage: ${e.javaClass.simpleName}").apply { initCause(e) }
        }
        if (!prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
            throw AunException.Storage("secure storage: write failed")
        }
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).commit()
    }

    override fun list(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }.sorted()

    private companion object {
        const val TAG = "secure-storage"
    }
}

/** AES-256-GCM with a random 12-byte IV prepended to the ciphertext. */
class KeystoreCrypto(private val alias: String = "aun_secure_storage") : KeystoreSecureStorage.Crypto {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed value too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
