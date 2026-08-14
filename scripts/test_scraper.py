#!/usr/bin/env python3
"""
Advanced Scraper Testing Utility (test_scraper.py)
--------------------------------------------------
A lightweight local scraping verification and debugging tool for Aniyomi extension development.
Allows testing HTTP requests, CSS selectors, regex extractions, live video streams,
and media host discovery without compiling full Android APKs.

Usage:
  python3 scripts/test_scraper.py <url_or_module> [options]
  python3 scripts/test_scraper.py animestream --popular
  python3 scripts/test_scraper.py animestream --latest
  python3 scripts/test_scraper.py animestream --search "piece"
  python3 scripts/test_scraper.py https://example.com/anime/123 --selector "h1.title"
  python3 scripts/test_scraper.py https://example.com/episode/1 --media
  python3 scripts/test_scraper.py https://example.com/api/v1/search --json
  python3 scripts/test_scraper.py https://example.com/watch -i (Interactive REPL)
"""

import argparse
import http.cookiejar
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union


# ==============================================================================
# HTTP Client & Session Handler
# ==============================================================================

class ScraperSession:
    """Manages HTTP requests, custom headers, cookies, redirects, and SSL bypass."""

    def __init__(self, cookie_jar_path: Optional[str] = None, insecure: bool = False, raw_cookie: Optional[str] = None):
        self.cookie_jar = http.cookiejar.LWPCookieJar(cookie_jar_path) if cookie_jar_path else http.cookiejar.CookieJar()
        if cookie_jar_path and os.path.exists(cookie_jar_path):
            try:
                self.cookie_jar.load(ignore_discard=True, ignore_expires=True)
            except Exception:
                pass

        self.insecure = insecure
        self.raw_cookie = raw_cookie
        self.default_headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
            "DNT": "1",
            "Connection": "keep-alive",
            "Upgrade-Insecure-Requests": "1"
        }

        # Build SSL Context
        self.ssl_context = ssl.create_default_context()
        if self.insecure:
            self.ssl_context.check_hostname = False
            self.ssl_context.verify_mode = ssl.CERT_NONE

        self.cookie_handler = urllib.request.HTTPCookieProcessor(self.cookie_jar)
        self.opener = urllib.request.build_opener(
            self.cookie_handler,
            urllib.request.HTTPSHandler(context=self.ssl_context)
        )

    def fetch(self, url: str, method: str = "GET", headers: Optional[Dict[str, str]] = None,
              data: Optional[Union[str, bytes, dict]] = None, timeout: int = 20) -> Tuple[int, str, Dict[str, str], float]:
        """Performs HTTP request and returns (status_code, body_text, response_headers, duration_seconds)."""
        req_headers = dict(self.default_headers)
        if self.raw_cookie:
            req_headers["Cookie"] = self.raw_cookie
        if headers:
            req_headers.update(headers)

        payload_bytes = None
        if data:
            if isinstance(data, dict):
                payload_bytes = urllib.parse.urlencode(data).encode("utf-8")
                if "Content-Type" not in req_headers:
                    req_headers["Content-Type"] = "application/x-www-form-urlencoded"
            elif isinstance(data, str):
                payload_bytes = data.encode("utf-8")
            elif isinstance(data, bytes):
                payload_bytes = data

        req = urllib.request.Request(url, data=payload_bytes, headers=req_headers, method=method.upper())

        start_time = time.time()
        try:
            with self.opener.open(req, timeout=timeout) as response:
                duration = time.time() - start_time
                status = response.getcode()
                raw_bytes = response.read()

                # Handle gzip / deflate if needed
                encoding = response.info().get("Content-Encoding", "").lower()
                if "gzip" in encoding:
                    import gzip
                    raw_bytes = gzip.decompress(raw_bytes)
                elif "deflate" in encoding:
                    import zlib
                    raw_bytes = zlib.decompress(raw_bytes)

                charset = response.info().get_content_charset() or "utf-8"
                body = raw_bytes.decode(charset, errors="replace")
                resp_headers = {k: v for k, v in response.info().items()}
                return status, body, resp_headers, duration
        except urllib.error.HTTPError as e:
            duration = time.time() - start_time
            raw_bytes = e.read()
            body = raw_bytes.decode("utf-8", errors="replace")
            resp_headers = {k: v for k, v in e.headers.items()}
            return e.code, body, resp_headers, duration


# ==============================================================================
# HTML Parser (Tag, Class, ID selector simulator without external deps)
# ==============================================================================

class SimpleSelectorParser(HTMLParser):
    """Lightweight HTML element extractor matching basic tag/class/id selectors."""

    def __init__(self, tag_name: Optional[str] = None, class_name: Optional[str] = None, id_name: Optional[str] = None):
        super().__init__()
        self.target_tag = tag_name.lower() if tag_name else None
        self.target_class = class_name.lower() if class_name else None
        self.target_id = id_name.lower() if id_name else None
        self.matches = []
        self._current_match = None
        self._depth = 0

    def handle_starttag(self, tag, attrs):
        attr_dict = dict(attrs)
        tag_matched = not self.target_tag or tag.lower() == self.target_tag
        id_matched = not self.target_id or attr_dict.get("id", "").lower() == self.target_id
        class_matched = not self.target_class or self.target_class in attr_dict.get("class", "").lower().split()

        if tag_matched and id_matched and class_matched:
            self._current_match = {"tag": tag, "attrs": attr_dict, "text": ""}
            self._depth = 1
        elif self._current_match:
            self._depth += 1

    def handle_endtag(self, tag):
        if self._current_match:
            self._depth -= 1
            if self._depth == 0:
                self.matches.append(self._current_match)
                self._current_match = None

    def handle_data(self, data):
        if self._current_match:
            self._current_match["text"] += data


# ==============================================================================
# DevTools Headers Converter
# ==============================================================================

def parse_raw_devtools_headers(raw_text: str) -> Dict[str, str]:
    """Converts raw Chrome/Firefox DevTools network headers to a key-value dict."""
    headers = {}
    lines = raw_text.strip().splitlines()
    for line in lines:
        line = line.strip()
        if not line or line.startswith(":") or line.startswith("GET ") or line.startswith("POST "):
            continue
        if ":" in line:
            k, v = line.split(":", 1)
            k = k.strip()
            v = v.strip()
            if k.lower() not in ["content-length", "host"]:
                headers[k] = v
    return headers


# ==============================================================================
# Interactive REPL Session
# ==============================================================================

def run_interactive_repl(session: ScraperSession, url: str, headers: Dict[str, str]):
    """Launches an interactive scraping sandbox."""
    print(f"\n🔍 Initializing interactive scraper sandbox for: {url}")
    status, body, _, duration = session.fetch(url, headers=headers)
    print(f"✅ Loaded page (Status: {status}, Size: {len(body):,} bytes, Time: {duration*1000:.1f}ms)")
    print("=" * 60)
    print("Commands:")
    print("  select <css_selector>      - Test CSS selector (e.g. 'select div.title')")
    print("  regex <pattern>            - Test regex against body (e.g. 'regex /stream/(\\w+)')")
    print("  media                      - Sniff .m3u8, .mp4, and video iframe hosters")
    print("  json                       - View body formatted as JSON")
    print("  refetch                    - Re-fetch the page")
    print("  url <new_url>              - Switch target URL")
    print("  exit / quit                - Exit REPL\n")

    current_url = url
    current_body = body

    while True:
        try:
            line = input(f"scraper [{current_url[:40]}]> ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\nExiting.")
            break

        if not line:
            continue

        parts = line.split(" ", 1)
        cmd = parts[0].lower()
        arg = parts[1].strip() if len(parts) > 1 else ""

        if cmd in ["exit", "quit", "q"]:
            break
        elif cmd == "refetch":
            status, current_body, _, duration = session.fetch(current_url, headers=headers)
            print(f"✅ Refetched (Status: {status}, Size: {len(current_body):,} bytes, Time: {duration*1000:.1f}ms)")
        elif cmd == "url":
            if not arg:
                print("Usage: url <new_url>")
                continue
            current_url = arg
            status, current_body, _, duration = session.fetch(current_url, headers=headers)
            print(f"✅ Loaded (Status: {status}, Size: {len(current_body):,} bytes, Time: {duration*1000:.1f}ms)")
        elif cmd == "media":
            m3u8s = sorted(set(re.findall(r'https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*', current_body)))
            mp4s = sorted(set(re.findall(r'https?://[^\s"\'<>]+\.mp4[^\s"\'<>]*', current_body)))
            iframes = sorted(set(re.findall(r'<iframe[^>]+(?:src|data-src)=["\']([^"\']+)["\']', current_body, re.IGNORECASE)))
            print(f"📹 Found {len(m3u8s)} HLS stream(s), {len(mp4s)} MP4 stream(s), {len(iframes)} Iframe(s):")
            for u in m3u8s: print(f"  [HLS]    {u}")
            for u in mp4s: print(f"  [MP4]    {u}")
            for u in iframes: print(f"  [IFRAME] {u}")
        elif cmd == "json":
            try:
                data = json.loads(current_body)
                print(json.dumps(data, indent=2)[:2000])
            except Exception as e:
                print(f"❌ Failed to parse body as JSON: {e}")
        elif cmd == "regex":
            if not arg:
                print("Usage: regex <pattern>")
                continue
            try:
                matches = re.findall(arg, current_body)
                print(f"🎯 Pattern '{arg}' found {len(matches)} match(es):")
                for i, m in enumerate(matches[:15], 1):
                    print(f"  [{i:2d}] {m}")
            except Exception as e:
                print(f"❌ Regex Error: {e}")
        elif cmd == "select":
            if not arg:
                print("Usage: select <css_selector>")
                continue
            sel = arg
            tag, class_name, id_name = None, None, None
            if '#' in sel:
                parts = sel.split('#', 1)
                tag = parts[0] if parts[0] else None
                id_name = parts[1]
            elif '.' in sel:
                parts = sel.split('.', 1)
                tag = parts[0] if parts[0] else None
                class_name = parts[1]
            else:
                tag = sel

            parser_obj = SimpleSelectorParser(tag_name=tag, class_name=class_name, id_name=id_name)
            parser_obj.feed(current_body)
            print(f"🎯 Selector '{sel}' found {len(parser_obj.matches)} element(s):")
            for i, m in enumerate(parser_obj.matches[:10], 1):
                text = m['text'].strip().replace('\n', ' ')[:90]
                print(f"  [{i:2d}] <{m['tag']}> Text: '{text}' | Attrs: {m['attrs']}")
        else:
            print(f"Unknown command: '{cmd}'. Type 'help' for command list.")


# ==============================================================================
# Extension Auto-Resolution & Inspection Helpers
# ==============================================================================

REPO_ROOT = Path(__file__).resolve().parent.parent if "__file__" in globals() else Path.cwd()


def resolve_extension_target(repo_root: Path, target: Optional[str] = None, lang: Optional[str] = None, name: Optional[str] = None) -> Tuple[Optional[str], Optional[str]]:
    """Helper to resolve (lang, name) from target positional argument (e.g. 'animestream' or 'en/animestream')."""
    src_dir = repo_root / "src"

    if target and "/" in target:
        parts = target.split("/", 1)
        return parts[0], parts[1]

    if lang and "/" in lang:
        parts = lang.split("/", 1)
        return parts[0], parts[1]

    if name and "/" in name:
        parts = name.split("/", 1)
        return parts[0], parts[1]

    resolved_name = name or target
    resolved_lang = lang

    if resolved_name and src_dir.exists():
        if resolved_lang and (src_dir / resolved_lang / resolved_name).exists():
            return resolved_lang, resolved_name
        for lang_dir in sorted(src_dir.iterdir()):
            if lang_dir.is_dir() and (lang_dir / resolved_name).exists():
                return lang_dir.name, resolved_name

    return resolved_lang, resolved_name


def inspect_extension_module(repo_root: Path, lang: str, name: str) -> Tuple[Optional[str], Dict[str, str], Dict[str, str]]:
    """Extracts baseUrl, default headers, and endpoint request patterns from extension source files."""
    ext_dir = repo_root / "src" / lang / name
    if not ext_dir.exists():
        return None, {}, {}

    base_url = None
    extracted_headers = {}
    endpoints = {}

    for kt_file in ext_dir.rglob("*.kt"):
        content = kt_file.read_text(encoding="utf-8", errors="ignore")
        if not base_url:
            m_base = re.search(r'(?:baseUrl|defaultBaseUrl|PREF_DOMAIN_DEFAULT)\s*=\s*["\'](https?://[^"\']+)["\']', content)
            if not m_base:
                m_base = re.search(r'baseUrl\s*:\s*String.*?get\(\)\s*=\s*[^"\']*["\'](https?://[^"\']+)["\']', content, re.DOTALL)
            if m_base:
                base_url = m_base.group(1).rstrip("/")

        m_ua = re.search(r'["\']User-Agent["\']\s*,\s*["\']([^"\']+)["\']', content, re.IGNORECASE)
        if m_ua:
            extracted_headers["User-Agent"] = m_ua.group(1)

        m_ref = re.search(r'["\']Referer["\']\s*,\s*["\']([^"\']+)["\']', content, re.IGNORECASE)
        if m_ref:
            extracted_headers["Referer"] = m_ref.group(1)

        m_origin = re.search(r'["\']Origin["\']\s*,\s*["\']([^"\']+)["\']', content, re.IGNORECASE)
        if m_origin:
            extracted_headers["Origin"] = m_origin.group(1)

        # Extract popularAnimeRequest
        m_pop = re.search(r'fun\s+popularAnimeRequest\s*\([^)]*\)\s*:\s*Request\s*=\s*GET\s*\(\s*["\']([^"\']+)["\']', content)
        if not m_pop:
            m_pop = re.search(r'fun\s+popularAnimeRequest[^{]*\{[^}]*GET\s*\(\s*["\']([^"\']+)["\']', content)
        if m_pop:
            endpoints["popular"] = m_pop.group(1)

        # Extract latestUpdatesRequest
        m_latest = re.search(r'fun\s+latestUpdatesRequest\s*\([^)]*\)\s*:\s*Request\s*=\s*GET\s*\(\s*["\']([^"\']+)["\']', content)
        if not m_latest:
            m_latest = re.search(r'fun\s+latestUpdatesRequest[^{]*\{[^}]*GET\s*\(\s*["\']([^"\']+)["\']', content)
        if m_latest:
            endpoints["latest"] = m_latest.group(1)

        # Extract searchAnimeRequest base url
        m_search = re.search(r'fun\s+searchAnimeRequest[^{]*\{[^}]*["\']([^"\']*search[^"\']*)["\']', content)
        if m_search:
            endpoints["search"] = m_search.group(1)

    if base_url:
        if "Referer" not in extracted_headers:
            extracted_headers["Referer"] = f"{base_url}/"
        if "Origin" not in extracted_headers:
            extracted_headers["Origin"] = base_url

    return base_url, extracted_headers, endpoints


# ==============================================================================
# CLI Entrypoint
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="Lightweight local scraping & endpoint verification tool with media discovery and interactive REPL.",
        formatter_class=argparse.RawTextHelpFormatter
    )
    parser.add_argument("target", nargs="?", help="Target URL (e.g. 'https://example.com') or extension module name (e.g. 'animestream')")
    parser.add_argument("-u", "--url", help="Target URL to request (optional if target module/URL is passed)")
    parser.add_argument("-X", "--method", default="GET", help="HTTP Method (GET, POST, HEAD, OPTIONS)")
    parser.add_argument("-H", "--header", action="append", help="Custom header in 'Key: Value' format (can be specified multiple times)")
    parser.add_argument("--raw-headers", help="Raw DevTools request headers copied directly from Network tab")
    parser.add_argument("-d", "--data", help="POST body payload (raw string or JSON)")
    parser.add_argument("-c", "--cookie", help="Raw cookie string to pass in Cookie header")
    parser.add_argument("-b", "--cookie-jar", help="Path to cookie jar file for session persistence")
    parser.add_argument("-k", "--insecure", action="store_true", help="Allow insecure SSL connections (disable cert verification)")
    parser.add_argument("-i", "--interactive", action="store_true", help="Launch interactive selector, regex, and media sniffer REPL")
    parser.add_argument("--media", action="store_true", help="Scan body for .m3u8, .mp4, and video iframe hosters")
    parser.add_argument("--selector", help="CSS selector to test (e.g. 'div.title', '#player', 'a.item')")
    parser.add_argument("--regex", help="Regular expression pattern to evaluate against response body")
    parser.add_argument("--json", action="store_true", help="Format and print body as structured JSON")
    parser.add_argument("--benchmark", type=int, default=0, help="Run N consecutive requests to benchmark latency and throughput")

    # Extension endpoint shortcuts
    parser.add_argument("-P", "--popular", action="store_true", help="Test extension's popular anime endpoint")
    parser.add_argument("-L", "--latest", action="store_true", help="Test extension's latest updates endpoint")
    parser.add_argument("-S", "--search", help="Test extension's search endpoint with query string")
    parser.add_argument("--page", type=int, default=1, help="Page number to test (default: 1)")

    args = parser.parse_args()

    target_url = args.url
    injected_headers = {}
    endpoints = {}

    if args.target:
        if args.target.startswith("http://") or args.target.startswith("https://"):
            target_url = args.target
        else:
            lang, name = resolve_extension_target(REPO_ROOT, target=args.target)
            if lang and name:
                base_url, ext_headers, endpoints = inspect_extension_module(REPO_ROOT, lang, name)
                if base_url:
                    injected_headers.update(ext_headers)
                    print(f"🔍 Target extension module: src/{lang}/{name}")
                    print(f"🌐 Base URL: {base_url}")
                    if injected_headers:
                        print(f"🛡️  Auto-injected headers: {', '.join(injected_headers.keys())}")

                    # Resolve shortcut endpoints
                    if args.popular:
                        raw_pattern = endpoints.get("popular", f"{base_url}/api/v1/videos/popular?limit=20&page=$page")
                        target_url = raw_pattern.replace("$baseUrl", base_url).replace("$page", str(args.page))
                        print(f"🌟 Testing Popular Anime endpoint: {target_url}")
                    elif args.latest:
                        raw_pattern = endpoints.get("latest", f"{base_url}/api/v1/videos/new?limit=20&page=$page")
                        target_url = raw_pattern.replace("$baseUrl", base_url).replace("$page", str(args.page))
                        print(f"⚡ Testing Latest Updates endpoint: {target_url}")
                    elif args.search:
                        raw_pattern = endpoints.get("search", f"{base_url}/api/v1/search")
                        sep = "&" if "?" in raw_pattern else "?"
                        target_url = f"{raw_pattern.replace('$baseUrl', base_url)}{sep}query={urllib.parse.quote(args.search)}&page={args.page}"
                        print(f"🔎 Testing Search Anime endpoint ({args.search}): {target_url}")
                    elif not target_url:
                        target_url = base_url
                else:
                    print(f"⚠️ Extension src/{lang}/{name} found, but baseUrl could not be extracted.")
            else:
                print(f"❌ Could not resolve '{args.target}' as an extension module or URL.")

    if not target_url:
        parser.print_help()
        print("\n❌ Error: Please specify a target URL or extension module name.")
        sys.exit(1)

    session = ScraperSession(
        cookie_jar_path=args.cookie_jar,
        insecure=args.insecure,
        raw_cookie=args.cookie
    )

    headers = dict(injected_headers)
    if args.raw_headers:
        headers.update(parse_raw_devtools_headers(args.raw_headers))
    if args.header:
        for h in args.header:
            if ":" in h:
                k, v = h.split(":", 1)
                headers[k.strip()] = v.strip()

    if args.interactive:
        run_interactive_repl(session, target_url, headers)
        return

    # Standard Execution
    print(f"🚀 Sending {args.method} request to: {target_url}")
    try:
        status, body, resp_headers, elapsed = session.fetch(
            url=target_url,
            method=args.method,
            headers=headers,
            data=args.data
        )
    except Exception as e:
        print(f"❌ Request Error: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"📊 Response Status: {status} | Size: {len(body):,} bytes | Latency: {elapsed*1000:.1f}ms\n" + "=" * 60)

    if status == 422:
        print("⚠️  HTTP 422 (Unprocessable Entity) detected:")
        print("   The server rejected query parameters or request body (e.g. invalid limit/per_page or unsupported filter value).")
        print("   Response Body:")
        print(body[:600])
        sys.exit(1)

    if args.media:
        m3u8s = sorted(set(re.findall(r'https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*', body)))
        mp4s = sorted(set(re.findall(r'https?://[^\s"\'<>]+\.mp4[^\s"\'<>]*', body)))
        iframes = sorted(set(re.findall(r'<iframe[^>]+(?:src|data-src)=["\']([^"\']+)["\']', body, re.IGNORECASE)))
        print(f"📹 Media Discovery:")
        print(f"  • HLS Streams (.m3u8): {len(m3u8s)}")
        for u in m3u8s[:5]: print(f"    - {u}")
        print(f"  • Direct MP4 Streams: {len(mp4s)}")
        for u in mp4s[:5]: print(f"    - {u}")
        print(f"  • Video Iframes: {len(iframes)}")
        for u in iframes[:5]: print(f"    - {u}")
        return

    # Auto-detect JSON response
    is_json = args.json or body.strip().startswith("{") or body.strip().startswith("[")
    if is_json:
        try:
            data = json.loads(body)
            count = len(data) if isinstance(data, list) else len(data.get("series") or data.get("data") or [])
            print(f"✨ Parsed JSON Payload ({count} items found):")
            print(json.dumps(data, indent=2)[:3000])
            return
        except Exception:
            pass

    if args.regex:
        matches = re.findall(args.regex, body)
        print(f"Regex '{args.regex}' found {len(matches)} match(es):")
        for i, m in enumerate(matches[:20], 1):
            print(f"  [{i:2d}] {m}")
        return

    if args.selector:
        sel = args.selector.strip()
        tag, class_name, id_name = None, None, None
        if '#' in sel:
            parts = sel.split('#', 1)
            tag = parts[0] if parts[0] else None
            id_name = parts[1]
        elif '.' in sel:
            parts = sel.split('.', 1)
            tag = parts[0] if parts[0] else None
            class_name = parts[1]
        else:
            tag = sel

        parser_obj = SimpleSelectorParser(tag_name=tag, class_name=class_name, id_name=id_name)
        parser_obj.feed(body)
        print(f"Found {len(parser_obj.matches)} matching element(s):")
        for i, m in enumerate(parser_obj.matches[:10], 1):
            text = m['text'].strip().replace('\n', ' ')[:90]
            print(f"  [{i:2d}] <{m['tag']}> | Text: '{text}' | Attrs: {m['attrs']}")
        return

    print("--- First 500 characters of Body ---")
    print(body[:500])


if __name__ == "__main__":
    main()
