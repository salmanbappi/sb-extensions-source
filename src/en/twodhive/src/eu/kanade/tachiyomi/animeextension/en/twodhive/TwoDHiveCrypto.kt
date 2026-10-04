package eu.kanade.tachiyomi.animeextension.en.twodhive

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.stream.IntStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TwoDHiveCrypto {
    private val random = SecureRandom()
    private const val GCM_TAG_LENGTH = 128

    private const val MEGAPLAY_KEY = "i?LMTAx0Q6,:}50U"
    private const val MEGAPLAY_IV = "W0;27ToaUpl_P%'c"

    // ======================== BabaStream AES-GCM ========================
    fun encryptAesGcm(keyBase64: String, plaintext: String): String {
        val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
        val iv = ByteArray(12).apply { random.nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptAesGcm(keyBase64: String, encryptedBase64: String): String {
        val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
        val dataBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
        if (dataBytes.size <= 12) return ""
        val iv = dataBytes.copyOfRange(0, 12)
        val cipherText = dataBytes.copyOfRange(12, dataBytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = cipher.doFinal(cipherText)
        return String(decrypted, Charsets.UTF_8)
    }

    // ======================== MegaPlay AES-CBC ========================
    fun decryptMegaPlay(enc: String): String? = runCatching {
        val normalized = enc.replace('-', '+').replace('_', '/')
        val rem = normalized.length % 4
        val padded = if (rem > 0) normalized + "=".repeat(4 - rem) else normalized
        val data = Base64.decode(padded, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keyBytes = MEGAPLAY_KEY.toByteArray(Charsets.UTF_8).copyOf(32)
        val ivBytes = MEGAPLAY_IV.toByteArray(Charsets.UTF_8)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val decrypted = cipher.doFinal(data)
        String(decrypted, Charsets.UTF_8)
    }.getOrNull()

    // ======================== Cap (PoW) Solver ========================
    fun fnvPrng(seed: String, targetLen: Int): String {
        var t = 2166136261L
        for (i in seed.indices) {
            t = t xor seed[i].code.toLong()
            t = (t + (t shl 1) + (t shl 4) + (t shl 7) + (t shl 8) + (t shl 24)) and 0xFFFFFFFFL
        }
        var i = t.toInt()
        val sb = StringBuilder()
        while (sb.length < targetLen) {
            i = i xor (i shl 13)
            i = i xor (i ushr 17)
            i = i xor (i shl 5)
            val u = i.toLong() and 0xFFFFFFFFL
            var hex = java.lang.Long.toHexString(u)
            while (hex.length < 8) hex = "0$hex"
            sb.append(hex)
        }
        return sb.substring(0, targetLen)
    }

    private fun writeInt(v: Int, b: ByteArray, offset: Int): Int {
        if (v == 0) {
            b[offset] = '0'.code.toByte()
            return 1
        }
        var len = 0
        var temp = v
        while (temp > 0) {
            len++
            temp /= 10
        }
        var curr = v
        for (i in len - 1 downTo 0) {
            b[offset + i] = ('0'.code + (curr % 10)).toByte()
            curr /= 10
        }
        return len
    }

    fun solvePow(salt: String, target: String): Int {
        val targetBits = 4 * target.length
        val fullBytes = targetBits / 8
        val remBits = targetBits % 8
        val remMask = if (remBits > 0) (0xFF shl (8 - remBits)) and 0xFF else 0

        val paddedTarget = if (target.length % 2 == 0) target else "${target}0"
        val targetBytes = ByteArray(paddedTarget.length / 2)
        for (i in targetBytes.indices) {
            targetBytes[i] = paddedTarget.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }

        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val inputBuf = ByteArray(saltBytes.size + 16)
        System.arraycopy(saltBytes, 0, inputBuf, 0, saltBytes.size)

        var nonce = 0
        while (true) {
            val len = writeInt(nonce, inputBuf, saltBytes.size)
            md.update(inputBuf, 0, saltBytes.size + len)
            val digest = md.digest()

            var match = true
            for (i in 0 until fullBytes) {
                if (digest[i] != targetBytes[i]) {
                    match = false
                    break
                }
            }
            if (match && remBits > 0) {
                if ((digest[fullBytes].toInt() and remMask) != (targetBytes[fullBytes].toInt() and remMask)) {
                    match = false
                }
            }
            if (match) {
                return nonce
            }
            nonce++
        }
    }

    fun solveCapChallenges(token: String, count: Int, sLen: Int, dLen: Int): List<Int> {
        val solutions = IntArray(count)
        IntStream.rangeClosed(1, count).parallel().forEach { idx ->
            val salt = fnvPrng("$token$idx", sLen)
            val target = fnvPrng("${token}${idx}d", dLen)
            solutions[idx - 1] = solvePow(salt, target)
        }
        return solutions.toList()
    }
}
