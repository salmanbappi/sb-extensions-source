package eu.kanade.tachiyomi.animeextension.en.animesogo

import android.util.Base64
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AnimeSogoRC4 {

    fun encodeVrf(animeId: String): String {
        var vrf = animeId
        ORDER.forEach { item ->
            when (item.second) {
                "exchange" -> vrf = exchange(vrf, item.third)
                "rc4" -> vrf = rc4Encrypt(item.third[0], vrf)
                "reverse" -> vrf = vrf.reversed()
                "base64" -> vrf = Base64.encodeToString(vrf.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
            }
        }
        return URLEncoder.encode(vrf, "utf-8")
    }

    private fun rc4Encrypt(key: String, input: String): String {
        val rc4Key = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.ENCRYPT_MODE, rc4Key)
        val output = cipher.doFinal(input.toByteArray(Charsets.ISO_8859_1))
        return Base64.encodeToString(output, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun exchange(input: String, keys: List<String>): String {
        val key1 = keys[0]
        val key2 = keys[1]
        return input.map { i ->
            val idx = key1.indexOf(i)
            if (idx != -1) key2[idx] else i
        }.joinToString("")
    }

    private val EXCHANGE_KEY_1 = listOf("AP6GeR8H0lwUz1", "UAz8Gwl10P6ReH")
    private const val KEY_1 = "ItFKjuWokn4ZpB"
    private const val KEY_2 = "fOyt97QWFB3"
    private val EXCHANGE_KEY_2 = listOf("1majSlPQd2M5", "da1l2jSmP5QM")
    private val EXCHANGE_KEY_3 = listOf("CPYvHj09Au3", "0jHA9CPYu3v")
    private const val KEY_3 = "736y1uTJpBLUX"

    private val ORDER = listOf(
        Triple(1, "exchange", EXCHANGE_KEY_1),
        Triple(2, "rc4", listOf(KEY_1)),
        Triple(3, "rc4", listOf(KEY_2)),
        Triple(4, "exchange", EXCHANGE_KEY_2),
        Triple(5, "exchange", EXCHANGE_KEY_3),
        Triple(6, "reverse", emptyList()),
        Triple(7, "rc4", listOf(KEY_3)),
        Triple(8, "base64", emptyList()),
    )
}
