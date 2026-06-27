package keiyoushi.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoTest {

    @Test
    fun testRc4EmptyKeyThrowsException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            rc4(ByteArray(0), ByteArray(10))
        }
        assertEquals("RC4 key must not be empty", exception.message)
    }
}
