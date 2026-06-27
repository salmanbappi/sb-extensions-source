package keiyoushi.utils

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CryptoTest {
    @Test
    fun `decodeHex throws exception for odd length string`() {
        assertFailsWith<IllegalArgumentException> {
            "abc".decodeHex()
        }
    }
}
