package eu.kanade.tachiyomi.animeextension.all.sankanime

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SankanimeCrypto {
    private const val SALT_KEY = "0b68fdee2cbb00afd5f8609690ea9202b390b22179e96072102b63505c459345"
    private const val API_AUTH_KEY = "3e8bb81b4086ca84ac94e2ccb5dfba78b23976ec1a372c5c1d003ef2e9a2cc5d"
    private const val API_SIGNATURE = "8d392a3e87d39cb9f899ad1308e2e74e3402c3984e12e2ddb38d2235dd9abf42"
    private const val API_SESSION = "ca72532da101957b0a943dd7ea5ed54d4ff1f462ee7fecbdb8e5e616552d2be5"

    private val secureRandom = SecureRandom()
    private val hexChars = "0123456789abcdef".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val result = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            result[i * 2] = hexChars[v ushr 4]
            result[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(result)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(input.toByteArray(Charsets.UTF_8)))
    }

    private fun hmacSha256(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        return bytesToHex(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }

    private fun kt(salt: String, nonce: String, session: String): String {
        return listOf(
            nonce.substring(16),
            salt.substring(0, 16),
            session.substring(16),
            nonce.substring(0, 16),
            salt.substring(16),
            session.substring(0, 16),
        ).joinToString(":")
    }

    fun makeRequestHeader(method: String, fullPath: String, bodyStr: String = ""): Pair<String, String> {
        val nonceBytes = ByteArray(16)
        secureRandom.nextBytes(nonceBytes)
        val nonce = bytesToHex(nonceBytes)

        val m = kt(SALT_KEY, nonce, API_SESSION)
        val p = sha256(m).substring(0, 16)

        val saltDyn = hmacSha256(SALT_KEY, "$p:$nonce")
        val authDyn = hmacSha256(API_AUTH_KEY, "$p:$SALT_KEY")
        val sigDyn = hmacSha256(API_SIGNATURE, "$p:$API_SESSION")
        val sessionDyn = hmacSha256(API_SESSION, "$p:$API_AUTH_KEY")

        val parts = fullPath.split("?")
        val b = parts[0].trimEnd('/')
        val y = parts.drop(1)
        val c = y.joinToString("?")
        val w = if (c.isNotEmpty()) "$b?$c" else b

        var v = listOf(method.uppercase(), w, p, nonce, sessionDyn, saltDyn).joinToString(":")
        if (bodyStr.isNotEmpty()) {
            v += ":$bodyStr"
        }

        val j = hmacSha256(sigDyn, v)
        val s = hmacSha256(saltDyn, j)
        val n = hmacSha256(authDyn, s)
        val signature = hmacSha256(sessionDyn, n)

        val pSt = sha256("$p:$nonce:$saltDyn")
        val sb = StringBuilder(authDyn.length * 2)
        for (i in authDyn.indices) {
            val xorVal = authDyn[i].code xor pSt[i and 63].code
            sb.append(String.format("%02x", xorVal))
        }
        val stVal = sb.toString()

        val reqS = "$stVal|$nonce|$signature"
        return Pair(reqS, nonce)
    }

    fun decryptPayload(rawJsonStr: String, reqNonce: String): String {
        if (!rawJsonStr.contains("_encsanka") && !rawJsonStr.contains("_encs")) {
            return rawJsonStr
        }

        val f = kt(SALT_KEY, reqNonce, API_SESSION)
        val w = sha256(f).substring(0, 16)
        val h = hmacSha256(API_SESSION, "$w:$API_AUTH_KEY")
        val g = hmacSha256(SALT_KEY, "$w:$reqNonce")

        val jsonElement = try {
            Json.parseToJsonElement(rawJsonStr).jsonObject
        } catch (_: Exception) {
            return rawJsonStr
        }

        val encSankaa = jsonElement["_encsankaa"]?.jsonPrimitive?.contentOrNull
        if (encSankaa != null) {
            val isK = jsonElement["_k"]?.jsonPrimitive?.booleanOrNull == true
            var enc = encSankaa.replace("-", "+").replace("_", "/")
            val rem = enc.length % 4
            if (rem > 0) {
                enc += "=".repeat(4 - rem)
            }
            val raw = Base64.decode(enc, Base64.DEFAULT)
            if (isK) {
                val keyM = sha256("$w:$h:$g").toByteArray(Charsets.UTF_8)
                for (i in raw.indices) {
                    raw[i] = (raw[i].toInt() xor keyM[i and 63].toInt()).toByte()
                }
            }
            return decompressDeflate(raw)
        }

        val encSanka = jsonElement["_encsanka"]?.jsonPrimitive?.contentOrNull
        if (encSanka != null) {
            val isK = jsonElement["_k"]?.jsonPrimitive?.booleanOrNull == true
            var enc = encSanka.replace("-", "+").replace("_", "/")
            val rem = enc.length % 4
            if (rem > 0) {
                enc += "=".repeat(4 - rem)
            }
            val raw = Base64.decode(enc, Base64.DEFAULT)
            if (isK) {
                val keyM = sha256("$w:$h:$g").toByteArray(Charsets.UTF_8)
                for (i in raw.indices) {
                    raw[i] = (raw[i].toInt() xor keyM[i and 63].toInt()).toByte()
                }
            }
            return decompressGzip(raw)
        }

        val encS = jsonElement["_encs"]?.jsonPrimitive?.contentOrNull
        if (encS != null) {
            var enc = encS.replace("-", "+").replace("_", "/")
            val rem = enc.length % 4
            if (rem > 0) {
                enc += "=".repeat(4 - rem)
            }
            val raw = Base64.decode(enc, Base64.DEFAULT)
            val keyM = sha256("$w:$reqNonce:$h").toByteArray(Charsets.UTF_8)
            for (i in raw.indices) {
                raw[i] = (raw[i].toInt() xor keyM[i and 63].toInt()).toByte()
            }
            return String(raw, Charsets.UTF_8)
        }

        return rawJsonStr
    }

    private fun decompressDeflate(data: ByteArray): String {
        return try {
            val inflater = Inflater(true)
            inflater.setInput(data)
            val outputStream = ByteArrayOutputStream(data.size * 2)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                outputStream.write(buffer, 0, count)
            }
            inflater.end()
            outputStream.toString("UTF-8")
        } catch (_: Exception) {
            val inflater = Inflater(false)
            inflater.setInput(data)
            val outputStream = ByteArrayOutputStream(data.size * 2)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                outputStream.write(buffer, 0, count)
            }
            inflater.end()
            outputStream.toString("UTF-8")
        }
    }

    private fun decompressGzip(data: ByteArray): String {
        GZIPInputStream(ByteArrayInputStream(data)).use { gzipStream ->
            return gzipStream.bufferedReader(Charsets.UTF_8).readText()
        }
    }
}
