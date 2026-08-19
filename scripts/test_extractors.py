#!/usr/bin/env python3
"""
Extractor Testing & Obfuscation Solver Tool for Aniyomi Extensions

Standalone Python 3 script to test video extractor logic, JS unpackers,
PlayerJS payload decoders, and stream obfuscation solvers.
"""

import argparse
import base64
import json
from pathlib import Path
import re
import sys
import urllib.parse
import urllib.request

REPO_ROOT = Path(__file__).resolve().parent.parent

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
            r"\}\s*\(\s*['\"](.*?)['\"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['\"](.*?)['\"]\s*\.split\(\s*['\"]\|['\"]\s*\)",
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

        # Match prefixes like #0, #1, #2, #3, #, //_//
        str_val = re.sub(r"^(?:#\d?|//_//)", "", str_val)

        for trash in trash_list:
            if trash and trash != "_":
                str_val = str_val.replace(trash, "")

        # Handle standard and URL-safe base64
        str_val = str_val.replace("-", "+").replace("_", "/")
        pad = len(str_val) % 4
        if pad > 0:
            str_val += "=" * (4 - pad)

        try:
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


def extract_voe(html, url):
    print("[INFO] Running VOE extraction logic...")
    m = re.search(r"['\"](?:hls|file)['\"]\s*:\s*['\"](https?://[^'\"]+\.m3u8[^'\"]*)['\"]", html)
    if m:
        return {"hls_url": m.group(1)}
    m_b64 = re.search(r"prompt\(.*?['\"]([A-Za-z0-9+/=]{20,})['\"]", html)
    if m_b64:
        try:
            decoded = base64.b64decode(m_b64.group(1)).decode('utf-8')
            return {"hls_url": decoded}
        except Exception:
            pass
    urls = StreamObfuscationSolver.extract_stream_urls(html)
    return {"hls_url": urls[0]} if urls else None


def extract_vidmoly(html, url):
    print("[INFO] Running Vidmoly extraction logic...")
    m = re.search(r'file\s*:\s*["\'](https?://[^"\']+\.m3u8[^"\']*)["\']', html)
    if m:
        return {"hls_url": m.group(1)}
    urls = StreamObfuscationSolver.extract_stream_urls(html)
    return {"hls_url": urls[0]} if urls else None


def extract_streamwish(html, url):
    print("[INFO] Running StreamWish extraction logic...")
    if JsUnpacker.detect(html):
        html = JsUnpacker.unpack(html)
    m = re.search(r'file\s*:\s*["\'](https?://[^"\']+\.m3u8[^"\']*)["\']', html)
    if m:
        return {"hls_url": m.group(1)}
    urls = StreamObfuscationSolver.extract_stream_urls(html)
    return {"hls_url": urls[0]} if urls else None


def extract_vidguard(html, url):
    print("[INFO] Running VidGuard extraction logic...")
    if "svg" in html or "stream" in html:
        urls = StreamObfuscationSolver.extract_stream_urls(html)
        if urls:
            return {"hls_url": urls[0]}
    return {"status": "requires svg unpacker / resolver"}


def extract_lulu(html, url):
    print("[INFO] Running LuluStream extraction logic...")
    if JsUnpacker.detect(html):
        html = JsUnpacker.unpack(html)
    urls = StreamObfuscationSolver.extract_stream_urls(html)
    return {"hls_url": urls[0]} if urls else None


def extract_megacloud_flixcloud(html, url):
    print("[INFO] Running MegaCloud / FlixCloud extraction logic...")
    urls = StreamObfuscationSolver.extract_stream_urls(html)
    if urls:
        return {"hls_url": urls[0]}
    embed_match = re.search(r'["\'](https?://[^"\']+(?:embed|player)[^"\']*)["\']', html)
    return {"embed_url": embed_match.group(1)} if embed_match else {"status": "requires webview / source key decryption"}


def extract_byse(html, url):
    print("[INFO] Running Byse (bysetayico) extraction logic...")
    file_id = url.split("/e/")[-1].split("?")[0].split("#")[0].strip("/")
    if not file_id:
        return {"status": "failed to extract file_id"}
    host = url.split("/e/")[0] if "http" in url.split("/e/")[0] else "https://bysetayico.com"
    api_url = f"{host}/api/videos/{file_id}"
    try:
        req = urllib.request.Request(api_url, headers={
            "User-Agent": HEADERS["User-Agent"],
            "Referer": f"{host}/e/{file_id}",
            "Accept": "application/json, text/plain, */*"
        })
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        pb = data.get("playback", {})
        version = int(pb.get("version", 1))
        iv_str = pb.get("iv", "")
        payload_str = pb.get("payload", "")
        key_parts = pb.get("key_parts", [])
        if key_parts and iv_str and payload_str:
            def b64url_dec(s):
                s = s.replace('-', '+').replace('_', '/')
                while len(s) % 4 != 0: s += '='
                return base64.b64decode(s)
            part1 = b64url_dec(key_parts[version - 1])
            part2 = b64url_dec(key_parts[31 - version - 1])
            key = part1 + part2
            iv = b64url_dec(iv_str)
            raw = b64url_dec(payload_str)
            try:
                from Crypto.Cipher import AES
                tag = raw[-16:]
                ciphertext = raw[:-16]
                cipher = AES.new(key, AES.MODE_GCM, nonce=iv)
                dec = cipher.decrypt_and_verify(ciphertext, tag)
                media_data = json.loads(dec.decode("utf-8"))
                sources = media_data.get("sources", [])
                if sources:
                    return {"hls_url": sources[0].get("url"), "all_sources": sources}
            except Exception as e:
                return {"status": f"AES-GCM decryption error: {e}"}
    except Exception as e:
        return {"status": f"API request error: {e}"}
    return None


def extract_buzzheavier(html, url):
    print("[INFO] Running Buzzheavier extraction logic...")
    m = re.search(r'["\'](https?://[^"\']+\.mp4[^"\']*)["\']', html)
    if m:
        return {"video_url": m.group(1)}
    urls = StreamObfuscationSolver.extract_stream_urls(html)
    return {"video_url": urls[0]} if urls else None


def extract_vidzee(html, url):
    print("[INFO] Running Vidzee extraction logic...")
    m = re.search(r'/(?:embed/)?(movie|tv)/([0-9]+)(?:/([0-9]+)/([0-9]+))?', url)
    if not m:
        return {"status": "Could not parse Vidzee URL"}
    mtype, mid, season, ep = m.group(1), m.group(2), m.group(3) or "1", m.group(4) or "1"
    subpath = f"movie/{mid}" if mtype == "movie" else f"tv/{mid}/{season}/{ep}"

    import subprocess
    for server in ['dcloud', 'tik', 'ipcloud']:
        api_url = f"https://core.vidzee.wtf/streams/{subpath}?s={server}&e=1"
        try:
            req = urllib.request.Request(api_url, headers={
                "User-Agent": HEADERS["User-Agent"],
                "Referer": "https://player.vidzee.wtf/",
                "Origin": "https://player.vidzee.wtf"
            })
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            if "c" in data:
                payload = data["c"]
                wasm_path = REPO_ROOT / "scratch/vidzee.wasm"
                if not wasm_path.exists():
                    try:
                        req_js = urllib.request.Request("https://player.vidzee.wtf/assets/streams-BHpSC3gU.js", headers=HEADERS)
                        with urllib.request.urlopen(req_js, timeout=5) as resp_js:
                            rm = re.search(r'const R=\"([^\"]+)\"', resp_js.read().decode("utf-8"))
                            if rm:
                                wasm_path.parent.mkdir(parents=True, exist_ok=True)
                                wasm_path.write_bytes(base64.b64decode(rm.group(1)))
                    except Exception:
                        pass

                if wasm_path.exists():
                    node_script = """
                    const fs = require('fs');
                    const [wasmPath, payload, host] = process.argv.slice(1);
                    (async () => {
                        const wasmBuffer = fs.readFileSync(wasmPath);
                        const wasmModule = await WebAssembly.instantiate(wasmBuffer, { env: { abort() { throw Error('abort'); } } });
                        const Q = wasmModule.instance.exports;
                        const E = Q.memory;
                        function i(A) { if (A == null) return 0; const B = A.length, C = Q.__new(B << 1, 2) >>> 0, I = new Uint16Array(E.buffer); for (let G = 0; G < B; ++G) I[(C >>> 1) + G] = A.charCodeAt(G); return C; }
                        function e(A, B, C, I) { if (I == null) return 0; const G = I.length, y = Q.__pin(Q.__new(G << C, 1)) >>> 0, D = Q.__new(12, B) >>> 0; new DataView(E.buffer).setUint32(D + 0, y, !0); new DataView(E.buffer).setUint32(D + 4, y, !0); new DataView(E.buffer).setUint32(D + 8, G << C, !0); new A(E.buffer, y, G).set(I); Q.__unpin(y); return D; }
                        function F(A, B) { if (!B) return null; const view = new DataView(E.buffer); const ptr = view.getUint32(B + 4, !0); const len = view.getUint32(B + 8, !0) / A.BYTES_PER_ELEMENT; return new A(E.buffer, ptr, len).slice(); }
                        const payloadBytes = Buffer.from(payload, 'base64');
                        const A = e(Uint8Array, 6, 0, payloadBytes);
                        const B = i(host);
                        const resPtr = Q.decrypt(A, B) >>> 0;
                        const outBytes = F(Uint8Array, resPtr);
                        console.log(Buffer.from(outBytes).toString('utf8'));
                    })();
                    """
                    proc = subprocess.run(["node", "-e", node_script, str(wasm_path), payload, "player.vidzee.wtf"], capture_output=True, text=True, timeout=5)
                    if proc.returncode == 0 and proc.stdout.strip():
                        dec_data = json.loads(proc.stdout.strip())
                        return {"hls_url": dec_data.get("url"), "server": server, "raw": dec_data}
        except Exception:
            continue
    return None


def auto_detect_provider(url):
    url_lower = url.lower()
    if "dood" in url_lower or "ds2play" in url_lower: return "doodstream"
    if "streamtape" in url_lower or "strcloud" in url_lower: return "streamtape"
    if "bysetayico" in url_lower or "byse" in url_lower: return "byse"
    if "vidzee" in url_lower: return "vidzee"
    if "filemoon" in url_lower: return "filemoon"
    if "mixdrop" in url_lower or "mixeno" in url_lower: return "mixdrop"
    if "vidsrc" in url_lower: return "vidsrc"
    if "playerjs" in url_lower: return "playerjs"
    if "voe." in url_lower or "/e/" in url_lower and "voe" in url_lower: return "voe"
    if "vidmoly" in url_lower: return "vidmoly"
    if "streamwish" in url_lower or "wishembed" in url_lower or "swish" in url_lower or "hanerix" in url_lower: return "streamwish"
    if "vidguard" in url_lower or "vgfplay" in url_lower or "vembed" in url_lower: return "vidguard"
    if "lulu" in url_lower or "luluvdo" in url_lower: return "luluvdo"
    if "buzzheavier" in url_lower: return "buzzheavier"
    if "flixcloud" in url_lower or "megacloud" in url_lower or "rapid-cloud" in url_lower: return "megacloud"
    return None


def extract(html, url, provider):
    if provider == "doodstream": return extract_doodstream(html, url)
    if provider == "streamtape": return extract_streamtape(html, url)
    if provider == "byse": return extract_byse(html, url)
    if provider == "vidzee": return extract_vidzee(html, url)
    if provider == "filemoon": return extract_filemoon(html, url)
    if provider == "mixdrop": return extract_mixdrop(html, url)
    if provider == "vidsrc": return extract_vidsrc(html, url)
    if provider == "playerjs": return extract_playerjs(html, url)
    if provider == "voe": return extract_voe(html, url)
    if provider == "vidmoly": return extract_vidmoly(html, url)
    if provider == "streamwish": return extract_streamwish(html, url)
    if provider == "vidguard": return extract_vidguard(html, url)
    if provider == "luluvdo": return extract_lulu(html, url)
    if provider == "buzzheavier": return extract_buzzheavier(html, url)
    if provider == "megacloud": return extract_megacloud_flixcloud(html, url)
    print(f"[ERROR] Unsupported provider: {provider}")
    return None


def main():
    parser = argparse.ArgumentParser(description="Extractor Testing Tool")
    parser.add_argument("url", nargs="?", help="URL of the embed to test")
    parser.add_argument("--file", help="Path to local HTML file to parse")
    parser.add_argument("--provider", help="Force provider (doodstream, streamtape, filemoon, mixdrop, vidsrc, playerjs)")
    parser.add_argument("--test-all", action="store_true", help="Run basic sanity tests")
    parser.add_argument("--play", action="store_true", help="Automatically probe and run real FFmpeg playback decode simulation on extracted stream")
    parser.add_argument("--probe", action="store_true", help="Probe extracted stream reachability, codecs, and audio/subtitle tracks")

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

    if result and (args.play or args.probe):
        stream_url = None
        if isinstance(result, dict):
            stream_url = result.get("hls_url") or result.get("video_url") or result.get("file") or result.get("url")
            if not stream_url and result.get("sources"):
                first_src = result["sources"][0]
                stream_url = first_src.get("file") or first_src.get("url") if isinstance(first_src, dict) else str(first_src)
            elif not stream_url and result.get("all_sources"):
                first_src = result["all_sources"][0]
                stream_url = first_src.get("url") if isinstance(first_src, dict) else str(first_src)

        if stream_url:
            print("\n" + "=" * 60)
            print("🚀 Auto-Probing Extracted Stream...")
            try:
                from probe_stream import StreamProber
            except ImportError:
                import sys
                from pathlib import Path
                sys.path.insert(0, str(Path(__file__).parent))
                from probe_stream import StreamProber

            headers = {"Referer": url}
            prober = StreamProber(headers=headers)
            prober.probe_stream(stream_url, deep=args.probe, verify_play=args.play)
        else:
            print("\n⚠️ No direct stream URL found in result to probe.")


if __name__ == "__main__":
    main()
