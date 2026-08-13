#!/usr/bin/env python3
"""
Extractor Testing & Obfuscation Solver Tool for Aniyomi Extensions

Standalone Python 3 script to test video extractor logic, JS unpackers,
PlayerJS payload decoders, and stream obfuscation solvers.
"""

import argparse
import base64
import json
import re
import sys
import urllib.parse
import urllib.request

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.5",
}


# ==============================================================================
# JS UNPACKER (Dean Edwards Algorithm)
# ==============================================================================
class JsUnpacker:
    @staticmethod
    def detect(source: str) -> bool:
        return bool(re.search(r"eval\(function\(p,a,c,k,e,[rd]?", source, re.IGNORE_CASE))

    @staticmethod
    def unpack(source: str) -> str:
        """Unpacks Dean Edwards packed JavaScript code (radix 2..95, multi-pass)."""
        packed_pattern = re.compile(
            r"\}\s*\('(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)",
            re.DOTALL | re.IGNORECASE
        )

        current_source = source
        for _ in range(5):  # Max 5 recursive passes
            matches = list(packed_pattern.finditer(current_source))
            if not matches:
                break

            unpacked_blocks = []
            for match in matches:
                payload, radix_str, count_str, symtab_str = match.groups()
                radix = int(radix_str)
                count = int(count_str)
                symtab = symtab_str.split('|')

                if len(symtab) < count:
                    symtab.extend([''] * (count - len(symtab)))

                def unbase(val_str, base):
                    alpha_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    alpha_95 = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
                    if 2 <= base <= 36:
                        try:
                            return int(val_str, base)
                        except ValueError:
                            return 0
                    charset = alpha_62 if base <= 62 else alpha_95
                    char_map = {c: i for i, c in enumerate(charset[:base])}
                    res = 0
                    for i, char in enumerate(reversed(val_str)):
                        res += (base ** i) * char_map.get(char, 0)
                    return res

                def replace_word(m):
                    word = m.group(0)
                    idx = unbase(word, radix)
                    if idx < len(symtab) and symtab[idx]:
                        return symtab[idx]
                    return word

                unpacked = re.sub(r"\b\w+\b", replace_word, payload)
                unpacked = unpacked.replace(r"\'", "'").replace(r'\"', '"')
                unpacked_blocks.append(unpacked)

            current_source = "\n".join(unpacked_blocks)

        return current_source


# ==============================================================================
# PLAYERJS PAYLOAD DECODER
# ==============================================================================
class PlayerJsDecoder:
    DEFAULT_TRASH = ["//", "_", "bk0", "bk1", "bk2", "bk3", "bk4", "=0", "=1", "=2", "=3", "=4"]

    @classmethod
    def decode_payload(cls, payload: str, trash_list: list = None) -> str:
        if trash_list is None:
            trash_list = cls.DEFAULT_TRASH

        str_val = payload.strip()
        if not str_val:
            return ""

        if str_val.startswith("#2") or str_val.startswith("#"):
            str_val = str_val[2:] if str_val.startswith("#2") else str_val[1:]
            for trash in trash_list:
                if trash:
                    str_val = str_val.replace(trash, "")

            try:
                pad = len(str_val) % 4
                if pad > 0:
                    str_val += "=" * (4 - pad)
                decoded = base64.b64decode(str_val).decode('utf-8', errors='ignore')
                if "http" in decoded or "//" in decoded or "." in decoded:
                    return decoded
            except Exception:
                pass

        return str_val

    @classmethod
    def decode_playlist(cls, payload: str, trash_list: list = None) -> list:
        results = []
        parts = payload.split(",")
        current_label = None
        current_payload = []

        for part in parts:
            p = part.strip()
            if p.startswith("["):
                if current_payload:
                    url = cls.decode_payload(",".join(current_payload), trash_list)
                    if url:
                        results.append({"label": current_label, "url": url})
                    current_payload = []
                label_end = p.find("]")
                if label_end != -1:
                    current_label = p[1:label_end]
                    p = p[label_end + 1:]
            current_payload.append(p)

        if current_payload:
            url = cls.decode_payload(",".join(current_payload), trash_list)
            if url:
                results.append({"label": current_label, "url": url})

        return results


# ==============================================================================
# STREAM OBFUSCATION SOLVER
# ==============================================================================
class StreamObfuscationSolver:
    @staticmethod
    def reverse_string(input_str: str) -> str:
        return input_str[::-1]

    @staticmethod
    def xor_decode(encoded_str: str, key: str) -> str:
        if not key:
            return encoded_str
        return "".join(chr(ord(c) ^ ord(key[i % len(key)])) for i, c in enumerate(encoded_str))

    @staticmethod
    def rot_shift(input_str: str, shift: int = 13) -> str:
        res = []
        for c in input_str:
            if 'a' <= c <= 'z':
                res.append(chr((ord(c) - ord('a') + shift) % 26 + ord('a')))
            elif 'A' <= c <= 'Z':
                res.append(chr((ord(c) - ord('A') + shift) % 26 + ord('A')))
            else:
                res.append(c)
        return "".join(res)

    @staticmethod
    def extract_stream_urls(text: str) -> list:
        regex = r"(https?://[^\s\"'<>\\]+\.(?:m3u8|mp4)[^\s\"'<>\\]*)"
        return list(set(re.findall(regex, text)))


def fetch_html(url, headers=None, referer=None):
    if headers is None:
        headers = HEADERS.copy()
    if referer:
        headers['Referer'] = referer

    print(f"[INFO] Fetching: {url}")
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.read().decode('utf-8', errors='ignore')
    except Exception as e:
        print(f"[ERROR] Failed to fetch {url}: {e}")
        return ""


def extract_filemoon(html, url):
    print("[INFO] Running FileMoon extraction logic...")
    if JsUnpacker.detect(html):
        print("[INFO] JSUnpacker detected packed JS in FileMoon. Unpacking...")
        html = JsUnpacker.unpack(html)

    file_match = re.search(r'file:\s*["\'](https?://[^"\']+\.m3u8[^"\']*)["\']', html)
    if file_match:
        return {"hls_url": file_match.group(1)}

    urls = StreamObfuscationSolver.extract_stream_urls(html)
    if urls:
        return {"hls_url": urls[0]}

    print("[ERROR] FileMoon m3u8 not found.")
    return None


def extract_mixdrop(html, url):
    print("[INFO] Running MixDrop extraction logic...")
    if JsUnpacker.detect(html):
        print("[INFO] JSUnpacker detected packed JS in MixDrop. Unpacking...")
        html = JsUnpacker.unpack(html)

    wurl_match = re.search(r"MDCore\.wurl\s*=\s*['\"]([^'\"]+)['\"]", html)
    if wurl_match:
        wurl = wurl_match.group(1)
        if wurl.startswith("//"):
            wurl = "https:" + wurl
        return {"video_url": wurl}

    print("[ERROR] MixDrop video URL not found.")
    return None


def extract_playerjs(html, url):
    print("[INFO] Running PlayerJS extraction logic...")
    file_match = re.search(r'file:\s*["\']([^"\']+)["\']', html)
    if not file_match:
        print("[ERROR] PlayerJS 'file' key not found.")
        return None

    raw_payload = file_match.group(1)
    if "[" in raw_payload and "]" in raw_payload:
        playlist = PlayerJsDecoder.decode_playlist(raw_payload)
        return {"type": "playlist", "items": playlist}
    else:
        decoded_url = PlayerJsDecoder.decode_payload(raw_payload)
        return {"type": "single", "url": decoded_url}


def extract_doodstream(html, url):
    print("[INFO] Running Doodstream extraction logic...")
    md5_match = re.search(r"(/pass_md5/[^']*)", html)
    if not md5_match:
        print("[ERROR] MD5 pass URL not found.")
        return None

    pass_url = urllib.parse.urljoin(url, md5_match.group(1))
    token_match = re.search(r"token=([^&']*)", html)
    token = token_match.group(1) if token_match else "unknown"
    return {"pass_url": pass_url, "token": token}


def extract_streamtape(html, url):
    print("[INFO] Running StreamTape extraction logic...")
    robotlink_match = re.search(r"document\.getElementById\('robotlink'\)\.innerHTML\s*=\s*'([^']+)'", html)
    if robotlink_match:
        return {"stream_url_part": robotlink_match.group(1)}
    return None


def extract_vidsrc(html, url):
    print("[INFO] Running VidSrc extraction logic...")
    iframe_match = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', html)
    if iframe_match:
        return {"iframe_src": iframe_match.group(1)}
    return None


def auto_detect_provider(url):
    url_lower = url.lower()
    if "dood" in url_lower: return "doodstream"
    if "streamtape" in url_lower or "strcloud" in url_lower: return "streamtape"
    if "filemoon" in url_lower: return "filemoon"
    if "mixdrop" in url_lower or "mixeno" in url_lower: return "mixdrop"
    if "vidsrc" in url_lower: return "vidsrc"
    if "playerjs" in url_lower: return "playerjs"
    return None


def extract(html, url, provider):
    if provider == "doodstream": return extract_doodstream(html, url)
    if provider == "streamtape": return extract_streamtape(html, url)
    if provider == "filemoon": return extract_filemoon(html, url)
    if provider == "mixdrop": return extract_mixdrop(html, url)
    if provider == "vidsrc": return extract_vidsrc(html, url)
    if provider == "playerjs": return extract_playerjs(html, url)
    print(f"[ERROR] Unsupported provider: {provider}")
    return None


def main():
    parser = argparse.ArgumentParser(description="Extractor Testing Tool")
    parser.add_argument("url", nargs="?", help="URL of the embed to test")
    parser.add_argument("--file", help="Path to local HTML file to parse")
    parser.add_argument("--provider", help="Force provider (doodstream, streamtape, filemoon, mixdrop, vidsrc, playerjs)")
    parser.add_argument("--test-all", action="store_true", help="Run basic sanity tests")

    args = parser.parse_args()

    if args.test_all:
        print("\n=== Running JSUnpacker Test ===")
        packed_sample = "eval(function(p,a,c,k,e,d){e=function(c){return c};if(!''.replace(/^/,String)){while(c--)d[c]=k[c]||c;k=[function(e){return d[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0.1 = \"2\";',3,3,'MDCore|wurl|//example.com/video.mp4'.split('|'),0,{}))"
        unpacked = JsUnpacker.unpack(packed_sample)
        print(f"Unpacked Result: {unpacked}")

        print("\n=== Running PlayerJS Decoder Test ===")
        playerjs_sample = "[1080p]#2bk0aHR0cHM6Ly9leGFtcGxlLmNvbS92aWRlb18xMDgwcC5tM3U4,[720p]#2bk0aHR0cHM6Ly9leGFtcGxlLmNvbS92aWRlb183MjBwLm0zdTg="
        decoded_pjs = PlayerJsDecoder.decode_playlist(playerjs_sample)
        print(f"PlayerJS Decoded Result: {json.dumps(decoded_pjs, indent=2)}")

        print("\n=== Running Stream Obfuscation Solver Test ===")
        rev_sample = StreamObfuscationSolver.reverse_string("83u3m.0801_oediv/moc.elpmaxe//:sptth")
        xor_sample = StreamObfuscationSolver.xor_decode("\x08\x14\x14\x10\x73\x6f\x6f", "KEY")
        rot_sample = StreamObfuscationSolver.rot_shift("uggcf://rknzcyr.pbz", 13)
        print(f"Reversed: {rev_sample}")
        print(f"XOR Decoded: {xor_sample}")
        print(f"ROT13 Shift: {rot_sample}")
        sys.exit(0)

    if not args.url and not args.file:
        parser.print_help()
        sys.exit(1)

    if args.file:
        with open(args.file, 'r', encoding='utf-8') as f:
            html = f.read()
        url = args.url or "file://" + args.file
    else:
        url = args.url
        html = fetch_html(url)

    provider = args.provider or auto_detect_provider(url)
    if not provider:
        print("[ERROR] Could not auto-detect provider. Please use --provider.")
        sys.exit(1)

    result = extract(html, url, provider)
    print("\n--- Extraction Result ---")
    print(json.dumps(result, indent=2) if result else "Failed to extract data.")


if __name__ == "__main__":
    main()
