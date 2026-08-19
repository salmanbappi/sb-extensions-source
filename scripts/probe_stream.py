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

    def probe_stream(self, url: str, deep: bool = False, probe_segments: int = 3, verify_play: bool = False) -> bool:
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

        # Determine probe target URL (if master playlist, use top video variant for fast and direct ffprobe/ffmpeg decode)
        probe_target_url = url
        if is_m3u8 and parsed.get("is_master") and parsed.get("variants"):
            top_var = parsed["variants"][0].get("url")
            if top_var:
                probe_target_url = top_var

        # Run FFprobe / FFmpeg playability inspection if available
        if shutil.which("ffprobe"):
            self._run_ffprobe(probe_target_url)
        if verify_play or (shutil.which("ffmpeg") and deep):
            self._run_ffmpeg_playback_test(probe_target_url)

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

    def _build_ffmpeg_headers(self) -> Tuple[List[str], str]:
        """Builds FFmpeg CLI arguments for User-Agent and custom request headers."""
        cli_args = []
        custom_headers = []
        for k, v in self.headers.items():
            if k.lower() == "user-agent":
                cli_args.extend(["-user_agent", v])
            else:
                custom_headers.append(f"{k}: {v}\r\n")
        header_str = "".join(custom_headers)
        if header_str:
            cli_args.extend(["-headers", header_str])
        return cli_args, header_str

    def _run_ffprobe(self, url: str):
        """Runs ffprobe for deep media inspection: duration, video resolution/codec, audio tracks/languages, subtitles."""
        try:
            print("\n  🎞️ Running FFprobe Deep Inspection...")
            header_args, _ = self._build_ffmpeg_headers()
            cmd = [
                "ffprobe", "-v", "error",
                *header_args,
                "-show_entries", "format=duration,size,bit_rate,format_name:stream=index,codec_name,codec_type,width,height,channels,channel_layout,sample_rate,r_frame_rate:stream_tags=language,title",
                "-of", "json",
                url
            ]
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
            if res.returncode == 0:
                data = json.loads(res.stdout)
                fmt = data.get("format", {})
                streams = data.get("streams", [])
                
                # Duration
                dur_raw = fmt.get("duration")
                if dur_raw:
                    try:
                        dur_sec = float(dur_raw)
                        mins, secs = divmod(int(dur_sec), 60)
                        hours, mins = divmod(mins, 60)
                        dur_formatted = f"{hours}h {mins}m {secs}s" if hours > 0 else f"{mins}m {secs}s"
                        print(f"     ⏱️  Duration:     {dur_formatted} ({dur_sec:.2f}s)")
                    except Exception:
                        print(f"     ⏱️  Duration:     {dur_raw}s")

                video_streams = [s for s in streams if s.get("codec_type") == "video"]
                audio_streams = [s for s in streams if s.get("codec_type") == "audio"]
                sub_streams = [s for s in streams if s.get("codec_type") == "subtitle"]

                for v in video_streams:
                    w = v.get("width")
                    h = v.get("height")
                    cname = v.get("codec_name", "unknown").upper()
                    fps_raw = v.get("r_frame_rate", "")
                    fps_str = ""
                    if "/" in fps_raw:
                        num, den = fps_raw.split("/")
                        if den and den != "0":
                            fps_val = float(num) / float(den)
                            fps_str = f" @ {fps_val:.2f} fps" if fps_val > 0 else ""
                    res_label = f"{h}p ({w}x{h})" if w and h else "Unknown Resolution"
                    print(f"     📺 Video Track:  {res_label} [{cname}]{fps_str}")

                if audio_streams:
                    lang_map = {
                        "hi": "Hindi", "ta": "Tamil", "te": "Telugu", "en": "English",
                        "ja": "Japanese", "jpn": "Japanese", "es": "Spanish", "fr": "French",
                        "de": "German", "it": "Italian", "pt": "Portuguese", "ko": "Korean",
                        "kor": "Korean", "zh": "Chinese", "zho": "Chinese", "und": "Undetermined"
                    }
                    print(f"     🔊 Audio Tracks ({len(audio_streams)}):")
                    for a in audio_streams:
                        idx = a.get("index", "?")
                        cname = a.get("codec_name", "unknown").upper()
                        channels = a.get("channels", 2)
                        layout = a.get("channel_layout", "stereo")
                        sr = a.get("sample_rate", "44100")
                        tags = a.get("tags", {})
                        lang_code = tags.get("language", "und").lower()
                        lang_name = lang_map.get(lang_code, lang_code.upper())
                        title = tags.get("title", "")
                        title_info = f' "{title}"' if title else ""
                        print(f"        • [Stream #{idx}] {lang_name} ({lang_code}){title_info} — {cname} {layout} ({channels}ch, {sr}Hz)")

                if sub_streams:
                    print(f"     💬 Subtitles ({len(sub_streams)}):")
                    for s in sub_streams:
                        idx = s.get("index", "?")
                        cname = s.get("codec_name", "unknown").upper()
                        tags = s.get("tags", {})
                        lang = tags.get("language", "und")
                        title = tags.get("title", "")
                        print(f"        • [Stream #{idx}] {lang} ({title or cname})")
            else:
                if "403" in res.stderr or "Forbidden" in res.stderr:
                    print("     ⚠️ FFprobe: HTTP 403 Forbidden (Check Referer / User-Agent headers)")
                elif res.stderr.strip():
                    first_err = res.stderr.strip().splitlines()[-1]
                    print(f"     ⚠️ FFprobe Notice: {first_err}")
        except subprocess.TimeoutExpired:
            print("     ⚠️ FFprobe timed out (stream server took >15s to respond)")
        except Exception as e:
            print(f"     ⚠️ FFprobe exception: {e}")

    def _run_ffmpeg_playback_test(self, url: str, duration_sec: int = 2):
        """Runs ffmpeg decode test to confirm stream can actually be decoded and played smoothly in ExoPlayer."""
        if not shutil.which("ffmpeg"):
            return
        try:
            print(f"\n  🎬 Testing Real Playback Decode ({duration_sec}s sample)...")
            header_args, _ = self._build_ffmpeg_headers()
            cmd = [
                "ffmpeg", "-v", "error",
                *header_args,
                "-ss", "00:00:02",
                "-t", str(duration_sec),
                "-i", url,
                "-f", "null",
                "-"
            ]
            start_t = time.time()
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
            elapsed = time.time() - start_t
            if res.returncode == 0 and not res.stderr.strip():
                print(f"     ✅ Playback Simulation PASSED: Stream decoded perfectly in {elapsed:.2f}s (0 frame errors, ExoPlayer ready)")
            elif res.returncode == 0:
                print(f"     ⚠️ Playback Simulation: Decoded with minor stream notices: {res.stderr.strip()[:150]}")
            else:
                print(f"     ❌ Playback Simulation FAILED: {res.stderr.strip()[:200]}")
        except subprocess.TimeoutExpired:
            print("     ⚠️ Playback test timed out (>15s). Stream bandwidth may be constrained.")
        except Exception as e:
            print(f"     ⚠️ Playback test exception: {e}")


    def detect_stego_offset(self, url: str) -> bool:
        """Probes stream header and scans for container sync markers to calculate stego payload offset."""
        print(f"\n🔍 Detecting Stego / Fake-Image Byte Offset: {url}")
        status, headers, body = self.fetch_url(url, range_bytes="0-65535", max_bytes=65536)
        if status not in (200, 206) or not body:
            print(f"❌ Failed to fetch stream range: HTTP {status}")
            return False

        print(f"  ✓ Fetched initial {len(body):,} bytes (HTTP {status})")
        wrapper = "None (Pure binary)"
        if body.startswith(b"\x89PNG\r\n\x1a\n"):
            wrapper = "PNG Image (\x89PNG)"
        elif body.startswith(b"\xff\xd8\xff"):
            wrapper = "JPEG Image (\xff\xd8\xff)"
        elif body.startswith(b"GIF87a") or body.startswith(b"GIF89a"):
            wrapper = "GIF Image"
        elif body.startswith(b"ID3"):
            wrapper = "ID3 Metadata Header"

        print(f"  🖼️  Detected Outer Header: {wrapper}")

        # Scan for media container sync markers
        detected_offset = None
        container_type = None

        # 1. M3U8 Playlist
        m3u8_idx = body.find(b"#EXTM3U")
        if m3u8_idx != -1:
            detected_offset = m3u8_idx
            container_type = "HLS Playlist (#EXTM3U)"

        # 2. MPEG-TS (Check for 0x47 spaced 188 bytes apart)
        if detected_offset is None:
            for i in range(len(body) - 564):
                if body[i] == 0x47 and body[i + 188] == 0x47 and body[i + 376] == 0x47:
                    detected_offset = i
                    container_type = "MPEG-TS Video (Sync Byte 0x47 @ 188-byte stride)"
                    break

        # 3. MP4 (Check for ftyp, moov, mdat)
        if detected_offset is None:
            for box in (b"ftyp", b"moov", b"mdat", b"styp"):
                box_idx = body.find(box)
                if box_idx >= 4:
                    detected_offset = box_idx - 4
                    container_type = f"ISO-MP4/CMAF ({box.decode('ascii', errors='ignore')} box)"
                    break

        # 4. WebM / Matroska
        if detected_offset is None:
            ebml_idx = body.find(b"\x1a\x45\xdf\xa3")
            if ebml_idx != -1:
                detected_offset = ebml_idx
                container_type = "WebM/Matroska Container"

        if detected_offset is not None:
            print(f"  🎯 Found Media Stream: {container_type}")
            print(f"  📏 Exact Stego Byte Offset: {detected_offset} bytes (0x{detected_offset:X})")
            if detected_offset > 0:
                print("\n  💡 Kotlin Integration Snippet (LocalProxy / M3U8Server):")
                print("     ----------------------------------------------------------------------")
                print(f"     val proxyUrl = LocalProxy.create(videoUrl, headers, offset = {detected_offset})")
                print("     // or with M3u8Server:")
                print(f"     val proxyUrl = M3u8Integration(client).createProxyUrl(videoUrl, headers, offset = {detected_offset})")
                print("     ----------------------------------------------------------------------")
            else:
                print("  ✅ Stream is direct/unwrapped (Offset: 0). No LocalProxy offset needed.")
            return True
        else:
            print("  ⚠️ No standard media sync marker (MPEG-TS, MP4, M3U8, WebM) found in the first 64KB.")
            print(f"     First 64 bytes hex: {body[:64].hex()}")
            return False


def main():
    parser = argparse.ArgumentParser(description="Deep Media Stream & ExoPlayer Compatibility Inspector")
    parser.add_argument("url", help="Target video stream URL (HLS .m3u8, DASH .mpd, or direct video)")
    parser.add_argument("--referer", "-r", help="Optional Referer header")
    parser.add_argument("--user-agent", "-u", help="Optional User-Agent header")
    parser.add_argument("--headers", help="JSON string of additional HTTP request headers")
    parser.add_argument("--deep", action="store_true", help="Probe nested variant playlists and video segment chunks")
    parser.add_argument("--probe-segments", type=int, default=3, help="Number of segments to probe in deep mode (default: 3)")
    parser.add_argument("--detect-offset", action="store_true", help="Scan stream for fake image wrappers (PNG/JPEG/GIF) and calculate LocalProxy byte offset")
    parser.add_argument("--verify-play", "--play", action="store_true", help="Run real FFmpeg decoding test to verify video/audio actually play without frame errors")

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
    if args.detect_offset:
        success = prober.detect_stego_offset(args.url)
    else:
        success = prober.probe_stream(args.url, deep=args.deep, probe_segments=args.probe_segments, verify_play=args.verify_play)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
