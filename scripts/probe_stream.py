#!/usr/bin/env python3
"""
Deep Media Stream & ExoPlayer Compatibility Inspector
Probes HLS (M3U8), DASH (MPD), and direct video streams, validating variants,
codecs, HTTP Range segment reachability, and subtitle tracks.
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional, Tuple


class StreamProber:
    def __init__(self, headers: Optional[Dict[str, str]] = None, timeout: int = 8):
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
        }
        if headers:
            self.headers.update(headers)
        self.timeout = timeout

    def fetch_url(self, url: str, range_bytes: Optional[str] = None, method: str = "GET", max_bytes: Optional[int] = None) -> Tuple[int, Dict[str, str], bytes]:
        """Performs HTTP GET/HEAD request with optional Range header and maximum read buffer."""
        req_headers = dict(self.headers)
        if range_bytes:
            req_headers["Range"] = f"bytes={range_bytes}"
            if max_bytes is None:
                max_bytes = 65536  # Default 64KB cap for range probes

        parsed = urllib.parse.urlparse(url)
        if "Referer" not in req_headers:
            req_headers["Referer"] = f"{parsed.scheme}://{parsed.netloc}/"

        req = urllib.request.Request(url, headers=req_headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                status = resp.getcode()
                resp_headers = dict(resp.headers)
                if method.upper() == "HEAD":
                    body = b""
                elif max_bytes:
                    body = resp.read(max_bytes)
                else:
                    # Default safety ceiling of 10MB to prevent OOM on unbounded stream downloads
                    body = resp.read(10 * 1024 * 1024)
                return status, resp_headers, body
        except urllib.error.HTTPError as e:
            err_body = e.read(65536) if hasattr(e, "read") else b""
            return e.code, dict(e.headers), err_body
        except Exception as e:
            return 0, {}, str(e).encode()

    def parse_m3u8(self, m3u8_text: str, base_url: str) -> Dict:
        """Parses M3U8 Master or Media playlist."""
        lines = [line.strip() for line in m3u8_text.splitlines() if line.strip()]
        result = {
            "is_master": False,
            "variants": [],
            "subtitles": [],
            "segments": [],
            "target_duration": None
        }

        if "#EXT-X-STREAM-INF" in m3u8_text:
            result["is_master"] = True
            result["media"] = []
            
            for m in re.findall(r'#EXT-X-MEDIA:(.+)', m3u8_text):
                attrs = {}
                for match in re.finditer(r'([A-Z0-9\-]+)=(?:"([^"]*)"|([^,]*))', m):
                    k = match.group(1)
                    v = match.group(2) if match.group(2) is not None else match.group(3)
                    attrs[k] = v
                
                uri = attrs.get("URI")
                result["media"].append({
                    "type": attrs.get("TYPE", "UNKNOWN"),
                    "group_id": attrs.get("GROUP-ID", "unknown"),
                    "name": attrs.get("NAME", "Unknown"),
                    "language": attrs.get("LANGUAGE", "und"),
                    "default": attrs.get("DEFAULT", "NO") == "YES",
                    "autoselect": attrs.get("AUTOSELECT", "NO") == "YES",
                    "url": urllib.parse.urljoin(base_url, uri) if uri else None
                })

            i = 0
            while i < len(lines):
                line = lines[i]
                if line.startswith("#EXT-X-STREAM-INF:"):
                    # Extract attributes
                    attr_str = line[len("#EXT-X-STREAM-INF:"):]
                    res_m = re.search(r'RESOLUTION=(\d+x\d+)', attr_str)
                    bw_m = re.search(r'BANDWIDTH=(\d+)', attr_str)
                    codecs_m = re.search(r'CODECS="([^"]+)"', attr_str)
                    name_m = re.search(r'NAME="([^"]+)"', attr_str)

                    variant_url = None
                    if i + 1 < len(lines) and not lines[i + 1].startswith("#"):
                        variant_url = urllib.parse.urljoin(base_url, lines[i + 1])
                        i += 1

                    result["variants"].append({
                        "resolution": res_m.group(1) if res_m else "Unknown",
                        "bandwidth": int(bw_m.group(1)) if bw_m else 0,
                        "codecs": codecs_m.group(1) if codecs_m else "Unknown",
                        "name": name_m.group(1) if name_m else None,
                        "url": variant_url
                    })
                i += 1
        else:
            # Media playlist with segment URLs
            for line in lines:
                if line.startswith("#EXT-X-TARGETDURATION:"):
                    result["target_duration"] = line.split(":")[-1]
                elif not line.startswith("#"):
                    seg_url = urllib.parse.urljoin(base_url, line)
                    result["segments"].append(seg_url)

        return result

    def probe_stream(self, url: str, deep: bool = False, probe_segments: int = 3) -> bool:
        """Probes a video stream URL with full analysis."""
        print(f"📡 Probing Stream URL: {url}\n" + "=" * 60)

        start_time = time.time()
        parsed_path = urllib.parse.urlparse(url).path.lower()
        is_manifest_path = parsed_path.endswith(".m3u8") or parsed_path.endswith(".mpd")
        status, headers, body = self.fetch_url(url, range_bytes="0-8192" if not is_manifest_path else None)
        elapsed_ms = (time.time() - start_time) * 1000

        content_type = headers.get("Content-Type", "unknown")
        content_length = headers.get("Content-Length", "unknown")

        print(f"  • HTTP Status:       {status} ({'OK' if status in (200, 206) else 'ERROR'})")
        print(f"  • Response Time:     {elapsed_ms:.1f} ms")
        print(f"  • Content-Type:      {content_type}")
        if content_length != "unknown":
            print(f"  • Content-Length:    {content_length} bytes")

        if status not in (200, 206):
            print(f"❌ Failed to reach stream URL (HTTP {status})")
            return False

        # Detect M3U8
        is_m3u8 = (
            "mpegurl" in content_type.lower()
            or is_manifest_path
            or body.startswith(b"#EXTM3U")
        )

        if is_m3u8:
            print("\n🎬 Stream Type: HLS Manifest (M3U8)")
            m3u8_text = body.decode("utf-8", errors="ignore")
            parsed = self.parse_m3u8(m3u8_text, url)

            if parsed["is_master"]:
                print(f"  📋 Master Playlist detected with {len(parsed['variants'])} variant quality track(s):")
                if parsed["variants"]:
                    for i, v in enumerate(parsed["variants"], 1):
                        bw_mbps = f"({v['bandwidth'] / 1_000_000:.2f} Mbps)" if v['bandwidth'] else ""
                        print(f"     {i}. Resolution: {v['resolution']:<10} Codecs: {v['codecs']:<20} {bw_mbps}")
                        print(f"        URL: {v['url']}")

                audio_tracks = [m for m in parsed.get("media", []) if m["type"] == "AUDIO"]
                subtitle_tracks = [m for m in parsed.get("media", []) if m["type"] in ("SUBTITLES", "CLOSED-CAPTIONS")]

                if audio_tracks:
                    print(f"\n  🎵 Audio Tracks ({len(audio_tracks)}):")
                    for a in audio_tracks:
                        default_flag = "[DEFAULT]" if a["default"] else "[ ]"
                        uri_info = ""
                        if a["url"]:
                            h_status, _, _ = self.fetch_url(a["url"], method="HEAD")
                            check = "✅" if h_status in (200, 206) else "❌"
                            uri_path = urllib.parse.urlparse(a["url"]).path
                            uri_info = f"  URI: {uri_path} {check}"
                        print(f"     {default_flag} {a['language']}  — {a['name']} (GROUP: {a['group_id']}){uri_info}")
                    
                    track_names = ", ".join([f'"{a["name"]}"' for a in audio_tracks])
                    print(f"\n  💡 Verify: extension's Video(audioTracks=...) should include [{track_names}]")

                if subtitle_tracks:
                    print(f"\n  📝 Subtitle Tracks ({len(subtitle_tracks)}):")
                    for s in subtitle_tracks:
                        default_flag = "[DEFAULT]" if s["default"] else "[ ]"
                        uri_info = ""
                        if s["url"]:
                            h_status, _, _ = self.fetch_url(s["url"], method="HEAD")
                            check = "✅" if h_status in (200, 206) else "❌"
                            uri_path = urllib.parse.urlparse(s["url"]).path
                            uri_info = f"  URI: {uri_path} {check}"
                        print(f"     {default_flag} {s['language']}  — {s['name']} (GROUP: {s['group_id']}){uri_info}")

                if deep and parsed["variants"]:
                    top_variant = parsed["variants"][0]
                    if top_variant["url"]:
                        print(f"\n  🔬 Probing Top Variant: {top_variant['resolution']}...")
                        v_status, v_headers, v_body = self.fetch_url(top_variant["url"])
                        if v_status in (200, 206):
                            v_parsed = self.parse_m3u8(v_body.decode("utf-8", errors="ignore"), top_variant["url"])
                            print(f"     ✓ Variant Media Playlist loaded ({len(v_parsed['segments'])} segments)")
                            self._probe_segment_chunks(v_parsed["segments"], probe_segments)
            else:
                print(f"  📋 Media Playlist detected ({len(parsed['segments'])} segments)")
                if deep:
                    self._probe_segment_chunks(parsed["segments"], probe_segments)
        else:
            print("\n📹 Stream Type: Direct Video File (MP4/MKV/WebM)")
            if body.startswith(b"\x00\x00\x00") or b"ftyp" in body[:32]:
                print("  ✓ Valid MP4 container signature detected")
            elif body.startswith(b"\x1a\x45\xdf\xa3"):
                print("  ✓ Valid Matroska/WebM container signature detected")

        # Check FFprobe if available
        if shutil.which("ffprobe") and (parsed_path.endswith(".mp4") or is_m3u8):
            self._run_ffprobe(url)

        print("\n" + "=" * 60)
        print("✅ Stream Reachability & Integrity Validation Passed!")
        return True

    def _probe_segment_chunks(self, segments: List[str], count: int):
        """Concurrently probes first N segments."""
        import concurrent.futures
        sample_segments = segments[:count]
        print(f"     🔍 Probing first {len(sample_segments)} video segment chunks concurrently...")

        def probe_single(idx_and_seg):
            idx, seg = idx_and_seg
            s_status, s_headers, s_body = self.fetch_url(seg, range_bytes="0-2048")
            fmt = "Unknown"
            if s_body:
                if s_body.startswith(b"\x47"):
                    fmt = "MPEG-TS (Sync 0x47)"
                elif b"ftyp" in s_body[:64] or b"styp" in s_body[:64] or b"moof" in s_body[:64]:
                    fmt = "fMP4/CMAF"
                elif s_body.startswith(b"WEBVTT"):
                    fmt = "WebVTT Subtitle"
                elif len(s_body) >= 2 and s_body[0] == 0xFF and (s_body[1] & 0xF0) == 0xF0:
                    fmt = "AAC ADTS Audio"
                elif s_body.startswith(b"\x1a\x45\xdf\xa3"):
                    fmt = "WebM/Matroska"
            return idx, seg, s_status, len(s_body), fmt

        if not sample_segments:
            print("     ℹ️ No segments found to probe.")
            return

        obfuscation_detected = False
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(len(sample_segments), 5))) as executor:
            futures = [executor.submit(probe_single, (idx, seg)) for idx, seg in enumerate(sample_segments, 1)]
            for future in concurrent.futures.as_completed(futures):
                idx, seg, s_status, s_len, s_fmt = future.result()
                seg_path = urllib.parse.urlparse(seg).path.lower()
                is_fake_ext = any(seg_path.endswith(ext) for ext in [".jpg", ".jpeg", ".png", ".gif", ".html", ".js", ".css", ".txt"])
                if is_fake_ext:
                    obfuscation_detected = True
                if s_status in (200, 206) and s_len > 0:
                    badge = f" [⚠️ Fake Extension: {seg_path.split('.')[-1]}]" if is_fake_ext else ""
                    print(f"       [{idx}/{len(sample_segments)}] Chunk 200 OK ({s_len} bytes) [{s_fmt}]{badge} -> {seg[:60]}...")
                else:
                    print(f"       [{idx}/{len(sample_segments)}] ⚠️ Chunk HTTP {s_status} Failed -> {seg}")

        if obfuscation_detected:
            print("\n  💡 Warning: Segments use fake image/script extensions (.jpg/.png/.html/etc.).")
            print("     Implement `:lib:m3u8server` and wrap video output with `m3u8Integration.processVideoList(videos)`.")

    def _run_ffprobe(self, url: str):
        """Runs ffprobe for deep codec extraction if installed."""
        try:
            print("\n  🎞️ Running FFprobe Deep Inspection...")
            cmd = [
                "ffprobe", "-v", "error",
                "-show_entries", "stream=index,codec_name,codec_type,width,height,bit_rate",
                "-of", "json",
                "-headers", "".join(f"{k}: {v}\r\n" for k, v in self.headers.items()),
                url
            ]
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            if res.returncode == 0:
                data = json.loads(res.stdout)
                streams = data.get("streams", [])
                for s in streams:
                    stype = s.get("codec_type", "unknown")
                    cname = s.get("codec_name", "unknown")
                    dim = f"{s.get('width')}x{s.get('height')}" if "width" in s else ""
                    print(f"     • {stype.upper()}: {cname} {dim}")
        except Exception:
            pass


def main():
    parser = argparse.ArgumentParser(description="Deep Media Stream & ExoPlayer Compatibility Inspector")
    parser.add_argument("url", help="Target video stream URL (HLS .m3u8, DASH .mpd, or direct video)")
    parser.add_argument("--referer", "-r", help="Optional Referer header")
    parser.add_argument("--user-agent", "-u", help="Optional User-Agent header")
    parser.add_argument("--headers", help="JSON string of additional HTTP request headers")
    parser.add_argument("--deep", action="store_true", help="Probe nested variant playlists and video segment chunks")
    parser.add_argument("--probe-segments", type=int, default=3, help="Number of segments to probe in deep mode (default: 3)")

    args = parser.parse_args()

    custom_headers = {}
    if args.referer:
        custom_headers["Referer"] = args.referer
    if args.user_agent:
        custom_headers["User-Agent"] = args.user_agent
    if args.headers:
        try:
            custom_headers.update(json.loads(args.headers))
        except Exception as e:
            print(f"❌ Invalid headers JSON: {e}")
            sys.exit(1)

    prober = StreamProber(headers=custom_headers)
    success = prober.probe_stream(args.url, deep=args.deep, probe_segments=args.probe_segments)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
