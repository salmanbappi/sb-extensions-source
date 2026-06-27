package keiyoushi.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoTest {

    @Test
    fun testRc4ThrowsOnEmptyKey() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            rc4(ByteArray(0), "data".toByteArray())
        }
        assertEquals("RC4 key must not be empty", ex.message)
    }

    @Test
    fun testRc4ThrowsOnKeyExceeding256Bytes() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            rc4(ByteArray(257), "data".toByteArray())
        }
        assertEquals("RC4 key must not exceed 256 bytes, got 257", ex.message)
    }

    @Test
    fun testRc4ThrowsOnNegativeSkip() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            rc4(ByteArray(16), "data".toByteArray(), skip = -1)
        }
        assertEquals("RC4 skip must be non-negative, got -1", ex.message)
    }

    @Test
    fun testRc4EncryptionAndDecryption() {
        val key = "secret_key".toByteArray()
        val data = "hello world".toByteArray()

        val encrypted = rc4(key, data)
        val decrypted = rc4(key, encrypted)

        assertArrayEquals(data, decrypted)
    }

    @Test
    fun testRc4WithSkip() {
        val key = "secret_key".toByteArray()
        val data = "hello world".toByteArray()

        val encrypted = rc4(key, data, skip = 256)
        val decrypted = rc4(key, encrypted, skip = 256)

        assertArrayEquals(data, decrypted)
    }
}
