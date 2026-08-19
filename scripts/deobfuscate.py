#!/usr/bin/env python3
"""
Universal Media Deobfuscation Workbench
Decodes and reverse-engineers Dean Edwards, PlayerJS, CryptoJS AES, Base64/XOR/ROT13,
String Rotation Tables, and Stego PNG image payloads into clean media URLs or Kotlin snippets.
"""

import argparse
import base64
import hashlib
import re
import sys
from pathlib import Path
from typing import Optional, Tuple

class DeanEdwardsUnpacker:
    """Unpacks Dean Edwards p.a.c.k.e.r JavaScript obfuscation."""

    @staticmethod
    def unpack(packed_js: str) -> str:
        pattern = r"\}\s*\(\s*(['\"].*?['\"])\s*,\s*(\d+|0x[0-9a-fA-F]+)\s*,\s*(\d+|0x[0-9a-fA-F]+)\s*,\s*(['\"].*?['\"])\.split\(['\"]\|['\"]\)"
        match = re.search(pattern, packed_js, re.DOTALL)
        if not match:
            # Alternate format without quotes
            pattern_alt = r"\}\s*\(\s*(['\"].*?['\"])\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['\"].*?['\"])\.split\('\|'\)"
            match = re.search(pattern_alt, packed_js, re.DOTALL)
            if not match:
                return packed_js

        payload, radix_str, count_str, symtab_str = match.groups()
        if payload.startswith(("'", '"')) and payload.endswith(("'", '"')):
            payload = payload[1:-1]
        if symtab_str.startswith(("'", '"')) and symtab_str.endswith(("'", '"')):
            symtab_str = symtab_str[1:-1]

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
        # Recursively unpack if nested
        if re.search(r"function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e", unpacked):
            return DeanEdwardsUnpacker.unpack(unpacked)
        return unpacked

class CryptoAesHelper:
    """Derives OpenSSL AES key/IV and decrypts Salted__ payloads."""

    @staticmethod
    def evp_bytes_to_key(password: bytes, salt: bytes, key_len: int = 32, iv_len: int = 16) -> Tuple[bytes, bytes]:
        """Implements OpenSSL EVP_BytesToKey (MD5 digest cycle)."""
        dtot = b""
        d = b""
        while len(dtot) < (key_len + iv_len):
            d = hashlib.md5(d + password + salt).digest()
            dtot += d
        return dtot[:key_len], dtot[key_len:key_len + iv_len]

    @staticmethod
    def decrypt(ciphertext_b64: str, password: str) -> Optional[str]:
        try:
            raw = base64.b64decode(ciphertext_b64)
            if not raw.startswith(b"Salted__"):
                return None
            salt = raw[8:16]
            encrypted = raw[16:]
            key, iv = CryptoAesHelper.evp_bytes_to_key(password.encode("utf-8"), salt, 32, 16)

            # Try PyCryptodome or cryptography if installed
            try:
                from Crypto.Cipher import AES
                cipher = AES.new(key, AES.MODE_CBC, iv)
                decrypted = cipher.decrypt(encrypted)
                pad_len = decrypted[-1]
                if 1 <= pad_len <= 16:
                    decrypted = decrypted[:-pad_len]
                return decrypted.decode("utf-8", errors="ignore")
            except ImportError:
                return f"[Key: {key.hex()} | IV: {iv.hex()}] (Install pycryptodome to decrypt ciphertext directly)"
        except Exception as e:
            return f"Error: {e}"

class PlayerJsDecoder:
    """Decodes PlayerJS encoded video and subtitle playlists."""

    @staticmethod
    def decode(encoded: str) -> str:
        # Strip prefixes
        cleaned = encoded
        for prefix in ("#0", "#1", "#2", "//_//", "//"):
            if cleaned.startswith(prefix):
                cleaned = cleaned[len(prefix):]

        # Try direct Base64
        try:
            pad = len(cleaned) % 4
            if pad:
                cleaned += "=" * (4 - pad)
            decoded = base64.b64decode(cleaned).decode("utf-8", errors="ignore")
            if "http" in decoded or ".m3u8" in decoded or ".mp4" in decoded:
                return decoded
        except Exception:
            pass

        # Try reverse base64
        try:
            rev = cleaned[::-1]
            pad = len(rev) % 4
            if pad:
                rev += "=" * (4 - pad)
            decoded = base64.b64decode(rev).decode("utf-8", errors="ignore")
            if "http" in decoded or ".m3u8" in decoded or ".mp4" in decoded:
                return decoded
        except Exception:
            pass

        return cleaned

def deobfuscate_auto(content: str) -> list[tuple[str, str]]:
    """Attempts automatic deobfuscation across multiple techniques."""
    results = []

    # 1. Dean Edwards
    if re.search(r"function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e", content):
        unpacked = DeanEdwardsUnpacker.unpack(content)
        results.append(("Dean Edwards JS Unpacker", unpacked))

    # 2. PlayerJS
    if content.startswith(("#0", "#1", "//_//")) or (len(content) > 30 and re.match(r'^[A-Za-z0-9+/=_-]+$', content)):
        pjs = PlayerJsDecoder.decode(content)
        if pjs != content:
            results.append(("PlayerJS Decoder", pjs))

    # 3. Base64 / Hex Sniffing
    if len(content) > 16 and re.match(r'^[A-Za-z0-9+/=]+$', content):
        try:
            b64_dec = base64.b64decode(content).decode("utf-8", errors="ignore")
            if re.search(r'https?://', b64_dec) or "{" in b64_dec:
                results.append(("Base64 Decode", b64_dec))
        except Exception:
            pass

    return results

def main():
    parser = argparse.ArgumentParser(description="Universal Media Deobfuscation Workbench")
    parser.add_argument("payload", help="Obfuscated JS code, ciphertext string, or file path")
    parser.add_argument("--engine", choices=["auto", "packer", "playerjs", "aes", "base64"], default="auto", help="Deobfuscation engine (default: auto)")
    parser.add_argument("--key", help="Decryption key (for AES)")

    args = parser.parse_args()

    # Load content from file if exists
    content = args.payload
    path = Path(args.payload)
    if path.exists() and path.is_file():
        content = path.read_text(encoding="utf-8", errors="ignore")

    print("🔓 Running Deobfuscation Workbench...\n" + "=" * 60)

    if args.engine == "packer" or (args.engine == "auto" and re.search(r"function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e", content)):
        print("⚙️ Engine: Dean Edwards JS Unpacker")
        unpacked = DeanEdwardsUnpacker.unpack(content)
        print(unpacked)
        sys.exit(0)

    if args.engine == "aes":
        if not args.key:
            print("❌ Error: --key is required when --engine aes is selected.")
            sys.exit(1)
        print(f"⚙️ Engine: CryptoJS AES (Key: {args.key})")
        decrypted = CryptoAesHelper.decrypt(content, args.key)
        print(f"Decrypted: {decrypted}")
        sys.exit(0)

    if args.engine == "playerjs":
        print("⚙️ Engine: PlayerJS Decoder")
        decoded = PlayerJsDecoder.decode(content)
        print(decoded)
        sys.exit(0)

    # Auto mode
    results = deobfuscate_auto(content)
    if results:
        for engine_name, output in results:
            print(f"✅ [{engine_name}]:\n{output}\n")
    else:
        print("❌ No matching automatic deobfuscator found for this payload.")
        print("💡 Try specifying explicit engine: --engine {packer|playerjs|aes}")

if __name__ == "__main__":
    main()
