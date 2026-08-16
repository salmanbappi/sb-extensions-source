"""
Deobfuscator Agent
Specialized engine for reverse engineering Dean Edwards packer, PlayerJS payloads,
AES/RC4 ciphers, string rotation tables, and synthesizing Kotlin deobfuscation helpers.
"""

import base64
import hashlib
import re
from dataclasses import dataclass
from typing import List, Optional, Tuple


@dataclass
class DeobfuscationResult:
    engine: str
    original: str
    deobfuscated: str
    success: bool
    generated_kotlin: Optional[str] = None
    metadata: Optional[dict] = None


class DeanEdwardsSolver:
    """Unpacks Dean Edwards p.a.c.k.e.r JavaScript obfuscation."""

    @staticmethod
    def unpack(packed_js: str) -> str:
        pattern = r"\}\s*\(\s*'(.*?)'\s*,\s*(\d+|0x[0-9a-fA-F]+)\s*,\s*(\d+|0x[0-9a-fA-F]+)\s*,\s*'(.*?)'\.split\('\|'\)"
        match = re.search(pattern, packed_js, re.DOTALL)
        if not match:
            pattern_alt = r"\}\s*\(\s*(['\"].*?['\"])\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['\"].*?['\"])\.split\('\|'\)"
            match = re.search(pattern_alt, packed_js, re.DOTALL)
            if not match:
                return packed_js

        payload, radix_str, count_str, symtab_str = match.groups()
        if payload.startswith("'") and payload.endswith("'"):
            payload = payload[1:-1]
        elif payload.startswith('"') and payload.endswith('"'):
            payload = payload[1:-1]

        radix = int(radix_str, 16) if str(radix_str).startswith("0x") else int(radix_str)
        count = int(count_str, 16) if str(count_str).startswith("0x") else int(count_str)
        symtab = symtab_str.split("|")

        def baseN(num: int, b: int) -> str:
            chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!#$%&()*+,-./:;<=>?@[\\]^_`{|}~"
            if num == 0:
                return chars[0]
            res = []
            while num:
                res.append(chars[num % b])
                num //= b
            return "".join(reversed(res))

        lookup = {}
        for i in range(count):
            key = baseN(i, radix)
            lookup[key] = symtab[i] if i < len(symtab) and symtab[i] else key

        def replace_token(m):
            token = m.group(0)
            return lookup.get(token, token)

        unpacked = re.sub(r"\b[0-9a-zA-Z]+\b", replace_token, payload)
        if "eval(function(p,a,c,k,e,r" in unpacked:
            return DeanEdwardsSolver.unpack(unpacked)
        return unpacked


class PlayerJsSolver:
    """Decodes PlayerJS encoded video and subtitle playlist representations."""

    PREFIXES = ("#0", "#1", "#2", "//_//", "//", "==")

    @classmethod
    def decode(cls, encoded: str) -> str:
        cleaned = encoded.strip()
        for prefix in cls.PREFIXES:
            if cleaned.startswith(prefix):
                cleaned = cleaned[len(prefix):]

        # 1. Direct Base64 decode
        try:
            pad = len(cleaned) % 4
            padded = cleaned + ("=" * (4 - pad) if pad else "")
            dec = base64.b64decode(padded).decode("utf-8", errors="ignore")
            if "http" in dec or ".m3u8" in dec or ".mp4" in dec or "[" in dec:
                return dec
        except Exception:
            pass

        # 2. Reverse Base64 decode
        try:
            rev = cleaned[::-1]
            pad = len(rev) % 4
            padded = rev + ("=" * (4 - pad) if pad else "")
            dec = base64.b64decode(padded).decode("utf-8", errors="ignore")
            if "http" in dec or ".m3u8" in dec or ".mp4" in dec or "[" in dec:
                return dec
        except Exception:
            pass

        return cleaned


class CipherSolver:
    """Implements cryptographic solvers for AES-CBC (OpenSSL EVP), RC4, XOR, and ROT."""

    @staticmethod
    def evp_bytes_to_key(password: bytes, salt: bytes, key_len: int = 32, iv_len: int = 16) -> Tuple[bytes, bytes]:
        """Derives key and IV using OpenSSL MD5 EVP_BytesToKey."""
        dtot = b""
        d = b""
        while len(dtot) < (key_len + iv_len):
            d = hashlib.md5(d + password + salt).digest()
            dtot += d
        return dtot[:key_len], dtot[key_len:key_len + iv_len]

    @staticmethod
    def rc4_crypt(data: bytes, key: bytes) -> bytes:
        """Symmetric RC4 encryption/decryption (KSA & PRGA)."""
        s = list(range(256))
        j = 0
        for i in range(256):
            j = (j + s[i] + key[i % len(key)]) % 256
            s[i], s[j] = s[j], s[i]

        i = j = 0
        out = bytearray()
        for char in data:
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            s[i], s[j] = s[j], s[i]
            out.append(char ^ s[(s[i] + s[j]) % 256])
        return bytes(out)

    @classmethod
    def decrypt_rc4(cls, ciphertext_b64_or_hex: str, key: str) -> Optional[str]:
        """Decrypts RC4 ciphertext given a password/key."""
        key_bytes = key.encode("utf-8")
        raw_bytes = None

        # Try base64
        try:
            raw_bytes = base64.b64decode(ciphertext_b64_or_hex)
        except Exception:
            pass

        # Try hex
        if not raw_bytes:
            try:
                raw_bytes = bytes.fromhex(ciphertext_b64_or_hex)
            except Exception:
                raw_bytes = ciphertext_b64_or_hex.encode("latin1")

        decrypted = cls.rc4_crypt(raw_bytes, key_bytes)
        try:
            return decrypted.decode("utf-8")
        except UnicodeDecodeError:
            return decrypted.decode("latin1", errors="ignore")

    @classmethod
    def decrypt_aes_openssl(cls, ciphertext_b64: str, password: str) -> Optional[str]:
        """Decrypts CryptoJS AES Salted__ payloads in pure Python."""
        try:
            raw = base64.b64decode(ciphertext_b64)
            if not raw.startswith(b"Salted__"):
                return None
            salt = raw[8:16]
            encrypted = raw[16:]
            key, iv = cls.evp_bytes_to_key(password.encode("utf-8"), salt, 32, 16)

            # Try using PyCryptodome or cryptography if available
            try:
                from Crypto.Cipher import AES
                cipher = AES.new(key, AES.MODE_CBC, iv)
                decrypted = cipher.decrypt(encrypted)
                pad_len = decrypted[-1]
                if 1 <= pad_len <= 16:
                    decrypted = decrypted[:-pad_len]
                return decrypted.decode("utf-8", errors="ignore")
            except ImportError:
                # Return derivation parameters if library missing
                return f"[Decrypted Params: key={key.hex()}, iv={iv.hex()}]"
        except Exception as e:
            return f"Error: {e}"

    @staticmethod
    def rot13(text: str) -> str:
        """Performs ROT13 substitution on text."""
        result = []
        for c in text:
            if "a" <= c <= "z":
                result.append(chr((ord(c) - ord("a") + 13) % 26 + ord("a")))
            elif "A" <= c <= "Z":
                result.append(chr((ord(c) - ord("A") + 13) % 26 + ord("A")))
            else:
                result.append(c)
        return "".join(result)

    @staticmethod
    def rotate_string_table(items: List[str], shift: int) -> List[str]:
        """Rotates an obfuscated string array by an integer offset."""
        if not items:
            return items
        n = shift % len(items)
        return items[n:] + items[:n]


class KotlinDeobfuscatorGenerator:
    """Generates production-grade, API v16 compliant Kotlin helper routines."""

    @staticmethod
    def generate_playerjs_helper() -> str:
        return """
    private fun decodePlayerJs(encoded: String): String {
        var clean = encoded.trim()
        val prefixes = arrayOf("#0", "#1", "#2", "//_//", "//", "==")
        for (prefix in prefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.substring(prefix.length)
            }
        }
        return try {
            val decoded = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                val rev = clean.reversed()
                val decoded = android.util.Base64.decode(rev, android.util.Base64.DEFAULT)
                String(decoded, Charsets.UTF_8)
            } catch (e2: Exception) {
                clean
            }
        }
    }
""".strip()

    @staticmethod
    def generate_rc4_helper() -> str:
        return """
    private fun decryptRc4(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }
        var i = 0
        j = 0
        val out = ByteArray(ciphertext.size)
        for (k in ciphertext.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
            out[k] = (ciphertext[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }
""".strip()

    @staticmethod
    def generate_aes_cryptojs_helper() -> str:
        return """
    private fun decryptCryptoJsAes(ciphertextB64: String, password: String): String? {
        return try {
            val raw = android.util.Base64.decode(ciphertextB64, android.util.Base64.DEFAULT)
            if (!raw.copyOfRange(0, 8).contentEquals("Salted__".toByteArray())) return null
            val salt = raw.copyOfRange(8, 16)
            val cipherData = raw.copyOfRange(16, raw.size)

            val passBytes = password.toByteArray(Charsets.UTF_8)
            val md5 = java.security.MessageDigest.getInstance("MD5")
            var dtot = ByteArray(0)
            var d = ByteArray(0)
            while (dtot.size < 48) {
                md5.reset()
                d = md5.digest(d + passBytes + salt)
                dtot += d
            }
            val key = dtot.copyOfRange(0, 32)
            val iv = dtot.copyOfRange(32, 48)

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            String(cipher.doFinal(cipherData), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
""".strip()


class DeobfuscatorAgent:
    """Coordinates detection, unpacking, cipher resolution, and Kotlin helper synthesis."""

    def __init__(self):
        self.packer = DeanEdwardsSolver()
        self.playerjs = PlayerJsSolver()
        self.cipher = CipherSolver()
        self.kotlin_gen = KotlinDeobfuscatorGenerator()

    def solve(self, content: str, key: Optional[str] = None) -> DeobfuscationResult:
        """Automatically attempts all solvers against the input payload."""
        clean = content.strip()

        # 1. Dean Edwards
        if "eval(function(p,a,c,k,e,r" in clean:
            unpacked = self.packer.unpack(clean)
            return DeobfuscationResult(
                engine="DeanEdwards",
                original=clean,
                deobfuscated=unpacked,
                success=True,
                generated_kotlin="// Dean Edwards unpacked during build or use JsUnpacker.unpack(js)",
            )

        # 2. PlayerJS
        if clean.startswith(PlayerJsSolver.PREFIXES):
            decoded = self.playerjs.decode(clean)
            return DeobfuscationResult(
                engine="PlayerJs",
                original=clean,
                deobfuscated=decoded,
                success=(decoded != clean),
                generated_kotlin=self.kotlin_gen.generate_playerjs_helper(),
            )

        # 3. CryptoJS AES
        if "Salted__" in clean or (len(clean) > 20 and clean.startswith("U2FsdGVkX1")):
            if key:
                decrypted = self.cipher.decrypt_aes_openssl(clean, key)
                return DeobfuscationResult(
                    engine="CryptoJS-AES",
                    original=clean,
                    deobfuscated=decrypted or clean,
                    success=bool(decrypted and not decrypted.startswith("Error")),
                    generated_kotlin=self.kotlin_gen.generate_aes_cryptojs_helper(),
                )

        # 4. RC4 / Base64
        if key:
            rc4_dec = self.cipher.decrypt_rc4(clean, key)
            if rc4_dec and ("http" in rc4_dec or "{" in rc4_dec or ".m3u8" in rc4_dec):
                return DeobfuscationResult(
                    engine="RC4",
                    original=clean,
                    deobfuscated=rc4_dec,
                    success=True,
                    generated_kotlin=self.kotlin_gen.generate_rc4_helper(),
                )

        # 5. Base64 fallback
        try:
            pad = len(clean) % 4
            b64_clean = clean + ("=" * (4 - pad) if pad else "")
            b64_dec = base64.b64decode(b64_clean).decode("utf-8")
            if "http" in b64_dec or "{" in b64_dec:
                return DeobfuscationResult(
                    engine="Base64",
                    original=clean,
                    deobfuscated=b64_dec,
                    success=True,
                )
        except Exception:
            pass

        return DeobfuscationResult(
            engine="Unknown",
            original=clean,
            deobfuscated=clean,
            success=False,
        )
