package eu.kanade.tachiyomi.animeextension.all.anikoto

import android.util.Base64

object AnikotoRC4 {
    private const val KEY = "simple-hash"

    fun rc4(key: String, input: String): String {
        val s = IntArray(256) { it }
        var a = 0
        for (n in 0 until 256) {
            a = (s[n] + a + key[n % key.length].code) % 256
            val tmp = s[n]
            s[n] = s[a]
            s[a] = tmp
        }
        val out = StringBuilder(input.length)
        var n2 = 0
        var a2 = 0
        for (r in 0 until input.length) {
            n2 = (n2 + 1) % 256
            a2 = (s[n2] + a2) % 256
            val tmp2 = s[n2]
            s[n2] = s[a2]
            s[a2] = tmp2
            val k = s[(s[n2] + s[a2]) % 256]
            out.append((input[r].code xor k).toChar())
        }
        return out.toString()
    }

    fun encodeVrf(animeId: String): String {
        val encrypted = rc4(KEY, animeId)
        val bytes = encrypted.toByteArray(Charsets.ISO_8859_1)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
