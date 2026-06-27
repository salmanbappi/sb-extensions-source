package keiyoushi.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFailsWith

class CryptoTest {

    @Test
    fun testDecodeHex() {
        val hexString = "48656c6c6f" // "Hello" in hex
        val expectedBytes = byteArrayOf(72, 101, 108, 108, 111)
        assertArrayEquals(expectedBytes, hexString.decodeHex())

        val upperHexString = "48656C6C6F"
        assertArrayEquals(expectedBytes, upperHexString.decodeHex())

        val emptyHexString = ""
        assertArrayEquals(byteArrayOf(), emptyHexString.decodeHex())
    }

    @Test
    fun testDecodeHexOddLength() {
        val oddHexString = "486"
        val exception = assertFailsWith<IllegalArgumentException> {
            oddHexString.decodeHex()
        }
        assertEquals("Unexpected hex string: 486", exception.message)
    }

    @Test
    fun testDecodeHexInvalidChars() {
        val invalidHexString = "486g"
        val exception = assertFailsWith<IllegalArgumentException> {
            invalidHexString.decodeHex()
        }
        assertEquals("Unexpected hex digit: g", exception.message)
    }

    @Test
    fun testDecodeHexToString() {
        val hexString = "48656c6c6f"
        assertEquals("Hello", hexString.decodeHexToString())
    }

    @Test
    fun testByteArrayToHex() {
        val bytes = byteArrayOf(72, 101, 108, 108, 111)
        assertEquals("48656c6c6f", bytes.toHex())
    }

    @Test
    fun testStringToHex() {
        val str = "Hello"
        assertEquals("48656c6c6f", str.toHex())
    }

    @Test
    fun testRc4EncryptionAndDecryption() {
        val key = "Secret".toByteArray()
        val plaintext = "Attack at dawn".toByteArray()

        val ciphertext = rc4(key, plaintext)
        val decryptedText = rc4(key, ciphertext)

        assertArrayEquals(plaintext, decryptedText)
    }

    @Test
    fun testRc4WithKnownVector() {
        // Known RC4 test vector
        // Key: "Key" (4b6579)
        // Plaintext: "Plaintext" (506c61696e74657874)
        // Ciphertext: "bbf316e8d940af0ad3"
        val key = "Key".toByteArray()
        val plaintext = "Plaintext".toByteArray()
        val expectedCiphertext = "bbf316e8d940af0ad3".decodeHex()

        val ciphertext = rc4(key, plaintext)
        assertArrayEquals(expectedCiphertext, ciphertext)
    }

    @Test
    fun testRc4EmptyKey() {
        val emptyKey = byteArrayOf()
        val data = "data".toByteArray()
        val exception = assertFailsWith<IllegalArgumentException> {
            rc4(emptyKey, data)
        }
        assertEquals("RC4 key must not be empty", exception.message)
    }

    @Test
    fun testRc4TooLongKey() {
        val tooLongKey = ByteArray(257) { it.toByte() }
        val data = "data".toByteArray()
        val exception = assertFailsWith<IllegalArgumentException> {
            rc4(tooLongKey, data)
        }
        assertEquals("RC4 key must not exceed 256 bytes, got 257", exception.message)
    }

    @Test
    fun testRc4WithSkip() {
        val key = "Key".toByteArray()
        val plaintext = "Plaintext".toByteArray()

        // Let's generate 5 bytes of ciphertext using standard RC4
        val fullCiphertext = rc4(key, plaintext)

        // If we skip the first 2 bytes, we should get the rest of the ciphertext
        val expectedPartialCiphertext = fullCiphertext.sliceArray(2 until fullCiphertext.size)

        // Note: rc4 function as implemented processes `skip` bytes from keystream but then takes
        // data of size `data.size` and outputs ciphertext.
        // Wait, the way rc4 is implemented, it skips `skip` bytes of keystream, and THEN encrypts the provided data.

        // So let's test it differently.
        // We have full plaintext. We encrypt the first 2 bytes, and encrypt the rest with skip=2.
        val part1 = plaintext.sliceArray(0 until 2)
        val part2 = plaintext.sliceArray(2 until plaintext.size)

        val cipherPart1 = rc4(key, part1)
        val cipherPart2 = rc4(key, part2, skip = 2)

        val combinedCiphertext = cipherPart1 + cipherPart2
        assertArrayEquals(fullCiphertext, combinedCiphertext)
    }

    @Test
    fun testRc4NegativeSkip() {
        val key = "Key".toByteArray()
        val data = "data".toByteArray()
        val exception = assertFailsWith<IllegalArgumentException> {
            rc4(key, data, skip = -1)
        }
        assertEquals("RC4 skip must be non-negative, got -1", exception.message)
    }

    @Test
    fun `decodeHex throws exception for odd length string`() {
        assertFailsWith<IllegalArgumentException> {
            "abc".decodeHex()
        }
    }
}
