package keiyoushi.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CryptoTest {

    @Test
    fun testRc4EmptyKeyThrowsException() {
        val exception = assertFailsWith<IllegalArgumentException> {
            rc4(ByteArray(0), ByteArray(10))
        }
        assertEquals("RC4 key must not be empty", exception.message)
    }
}
