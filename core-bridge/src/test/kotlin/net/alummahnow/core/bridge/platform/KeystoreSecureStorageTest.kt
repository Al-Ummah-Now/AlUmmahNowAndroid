package net.alummahnow.core.bridge.platform

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.GeneralSecurityException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KeystoreSecureStorageTest {
    /** Robolectric has no AndroidKeyStore; a reversible fake proves the storage plumbing around it. */
    private class XorCrypto : KeystoreSecureStorage.Crypto {
        var broken = false
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray): ByteArray {
            if (broken) throw GeneralSecurityException("key gone")
            return seal(sealed)
        }
    }

    private val crypto = XorCrypto()
    private val storage = KeystoreSecureStorage(ApplicationProvider.getApplicationContext(), crypto, "test_secure")

    @Test fun `values round-trip, list by prefix and delete`() {
        storage.set("auth.tokens", "سلام".toByteArray())
        storage.set("auth.other", byteArrayOf(1))
        storage.set("sync.cursor", byteArrayOf(2))
        assertArrayEquals("سلام".toByteArray(), storage.get("auth.tokens"))
        assertEquals(listOf("auth.other", "auth.tokens"), storage.list("auth."))
        storage.delete("auth.tokens")
        assertNull(storage.get("auth.tokens"))
    }

    @Test fun `a value that can no longer be decrypted reads as absent and is dropped`() {
        storage.set("auth.tokens", byteArrayOf(1, 2, 3))
        crypto.broken = true
        assertNull(storage.get("auth.tokens"))
        assertEquals(emptyList<String>(), storage.list("auth."))
    }
}
