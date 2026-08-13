package eu.kanade.tachiyomi.lib.unpacker

import android.util.Base64

object StreamObfuscationSolver {

    /**
     * Reverses a string (e.g. `83u3m.oediv/moc.site//:sptth` -> `https://site.com/video.m3u8`).
     */
    fun reverseString(input: String): String = input.reversed()

    /**
     * Decodes an XOR obfuscated string with a repeating key.
     */
    fun xorDecode(encoded: String, key: String): String {
        if (key.isEmpty()) return encoded
        val sb = StringBuilder()
        for (i in encoded.indices) {
            val c = encoded[i].code xor key[i % key.length].code
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /**
     * Decodes ROT-N character shifts (default ROT13).
     */
    fun rotShift(input: String, shift: Int = 13): String {
        val sb = StringBuilder()
        for (c in input) {
            sb.append(
                when (c) {
                    in 'a'..'z' -> ((c - 'a' + shift + 26) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> ((c - 'A' + shift + 26) % 26 + 'A'.code).toChar()
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    /**
     * Converts integer char code lists (e.g. "104,116,116,112,58,47,47...") into text.
     */
    fun parseCharCodes(input: String, delimiter: String = ","): String = input.split(delimiter).mapNotNull { codeStr ->
        codeStr.trim().toIntOrNull()?.toChar()?.toString()
    }.joinToString("")

    /**
     * Decodes Base64 payloads with automatic padding repair and URL-safe character mapping.
     */
    fun decodeBase64(input: String): String? {
        val clean = input.trim().replace("-", "+").replace("_", "/")
        val pad = clean.length % 4
        val padded = if (pad > 0) clean + "=".repeat(4 - pad) else clean
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Extracts .m3u8 and .mp4 URLs directly from un-parsed script or HTML string blocks.
     */
    fun extractStreamUrls(text: String): List<String> {
        val regex = Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*)""")
        return regex.findAll(text).map { it.value }.distinct().toList()
    }
}
