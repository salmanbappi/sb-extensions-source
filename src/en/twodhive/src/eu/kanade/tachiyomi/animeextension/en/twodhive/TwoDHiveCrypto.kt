package eu.kanade.tachiyomi.animeextension.en.twodhive

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TwoDHiveCrypto {
    private val random = SecureRandom()
    private const val GCM_TAG_LENGTH = 128

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
}
