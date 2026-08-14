#!/usr/bin/env python3
"""
Lightweight Local Scraper & Endpoint Test Suite Tool (Zero external dependencies)
Supports custom HTTP headers (-H, --raw-headers), Cookie Jar persistence, SSL configuration,
exponential backoff, HTML/regex selectors, JSON parsing, media sniffer, and interactive REPL sandbox.
"""

import argparse
import http.cookiejar
import json
import math
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
# Cookie Jar & HTTP Session Handler
# ==============================================================================

class ScraperSession:
    """Manages HTTP connections, cookie jars, SSL contexts, and request retries."""

    def __init__(self, cookie_jar_path: Optional[str] = None, insecure: bool = False, raw_cookie: Optional[str] = None):
        self.cookie_jar_path = cookie_jar_path
        self.cookie_jar = http.cookiejar.MozillaCookieJar(cookie_jar_path) if cookie_jar_path else http.cookiejar.CookieJar()
        
        if cookie_jar_path and os.path.exists(cookie_jar_path):
            try:
                self.cookie_jar.load(ignore_discard=True, ignore_expires=True)
            except Exception as e:
                print(f"  [!] Warning: Failed to load cookie jar ({e})", file=sys.stderr)

        self.raw_cookie = raw_cookie
        self.insecure = insecure
        self.ssl_context = ssl._create_unverified_context() if insecure else None

        # Build custom urllib opener
        cookie_processor = urllib.request.HTTPCookieProcessor(self.cookie_jar)
        https_handler = urllib.request.HTTPSHandler(context=self.ssl_context) if self.ssl_context else urllib.request.HTTPSHandler()
        self.opener = urllib.request.build_opener(cookie_processor, https_handler)

    def save_cookies(self):
        if self.cookie_jar_path and isinstance(self.cookie_jar, http.cookiejar.MozillaCookieJar):
            try:
                self.cookie_jar.save(ignore_discard=True, ignore_expires=True)
            except Exception as e:
                print(f"  [!] Warning: Failed to save cookie jar ({e})", file=sys.stderr)

    def fetch(
        self,
        url: str,
        method: str = "GET",
        headers: Optional[Dict[str, str]] = None,
        data: Optional[Union[str, bytes]] = None,
        timeout: int = 15,
        max_retries: int = 2
    ) -> Tuple[int, str, Dict[str, str], float]:
        """Performs HTTP request with retry logic and latency measurement."""
        req_headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
        }
        if headers:
            req_headers.update(headers)

        if self.raw_cookie:
            req_headers["Cookie"] = self.raw_cookie

        encoded_data = None
        if data:
            encoded_data = data.encode("utf-8") if isinstance(data, str) else data

        req = urllib.request.Request(url, data=encoded_data, headers=req_headers, method=method.upper())

        last_error = None
        for attempt in range(max_retries + 1):
            start_time = time.perf_counter()
            try:
                with self.opener.open(req, timeout=timeout) as response:
                    elapsed = time.perf_counter() - start_time
                    resp_body = response.read().decode("utf-8", errors="replace")
                    resp_headers = dict(response.headers)
                    self.save_cookies()
                    return response.status, resp_body, resp_headers, elapsed
            except urllib.error.HTTPError as e:
                elapsed = time.perf_counter() - start_time
                resp_body = e.read().decode("utf-8", errors="replace") if e.fp else ""
                resp_headers = dict(e.headers)
                self.save_cookies()
                if e.code in (429, 500, 502, 503, 504) and attempt < max_retries:
                    time.sleep(math.pow(2, attempt))
                    continue
                return e.code, resp_body, resp_headers, elapsed
            except (urllib.error.URLError, TimeoutError, ConnectionResetError) as e:
                last_error = e
                if attempt < max_retries:
                    time.sleep(math.pow(2, attempt))
                    continue
                break

        raise RuntimeError(f"Request failed after {max_retries} retries: {last_error}")


# ==============================================================================
# HTML Parser & CSS Selector Fallback
# ==============================================================================

class SimpleSelectorParser(HTMLParser):
    """HTML Parser with class, ID, tag, and attribute matching."""

    def __init__(self, tag_name: Optional[str] = None, class_name: Optional[str] = None, id_name: Optional[str] = None, attr_name: Optional[str] = None, attr_val: Optional[str] = None):
        super().__init__()
        self.tag_name = tag_name.lower() if tag_name else None
        self.class_name = class_name
        self.id_name = id_name
        self.attr_name = attr_name
        self.attr_val = attr_val
        self.matches: List[Dict[str, Union[str, Dict[str, str]]]] = []
        self._current_match: Optional[Dict] = None
        self._depth = 0

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, str]]):
        attrs_dict = dict(attrs)
        classes = attrs_dict.get('class', '').split()
        element_id = attrs_dict.get('id', '')

        is_match = True
        if self.tag_name and tag.lower() != self.tag_name:
            is_match = False
        if self.class_name and self.class_name not in classes:
            is_match = False
        if self.id_name and element_id != self.id_name:
            is_match = False
        if self.attr_name:
            if self.attr_name not in attrs_dict:
                is_match = False
            elif self.attr_val and attrs_dict[self.attr_name] != self.attr_val:
                is_match = False

        if is_match:
            self._current_match = {
                'tag': tag,
                'attrs': attrs_dict,
                'text': ''
            }
            self._depth = 1
        elif self._current_match:
            self._depth += 1

    def handle_data(self, data: str):
        if self._current_match:
            self._current_match['text'] += data

    def handle_endtag(self, tag: str):
        if self._current_match:
            self._depth -= 1
            if self._depth == 0:
                self.matches.append(self._current_match)
                self._current_match = None


def parse_raw_devtools_headers(raw: str) -> Dict[str, str]:
    """Parses raw headers copied from Chrome/Firefox DevTools Network Tab."""
    headers = {}
    for line in raw.strip().splitlines():
        line = line.strip()
        if not line or line.startswith(":") or line.startswith("GET ") or line.startswith("POST "):
            continue
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip()] = v.strip()
    return headers


# ==============================================================================
# Interactive REPL Sandbox
# ==============================================================================

def run_interactive_repl(session: ScraperSession, url: str, headers: Dict[str, str]):
    """Interactive selector, regex, and media sniffer REPL sandbox."""
    print(f"\n🌐 Fetching initial payload from: {url}...")
    try:
        status, body, resp_headers, lat = session.fetch(url, headers=headers)
        print(f"✅ Loaded {len(body):,} bytes (HTTP Status: {status}, Latency: {lat*1000:.1f}ms)")
    except Exception as e:
        print(f"❌ Failed to fetch: {e}")
        return

    print("\n💡 Type 'help' for available commands, 'exit' or 'quit' to exit.\n")

    while True:
        try:
            cmd = input("scraper-repl> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nExiting REPL.")
            break

        if not cmd:
            continue
        if cmd in ("exit", "quit"):
            break

        if cmd == "help":
            print("""
Available Sandbox Commands:
  css <selector>         Run CSS selector (e.g. `div.anime-card`, `a.title`, `#player`)
  regex <pattern>        Run regular expression on HTML body
  media                  Scan for .m3u8, .mp4, and video iframe embed hosters
  links                  List all anchor hyperlinks found on the page
  headers                Display remote server response headers
  cookies                Display active session cookies
  json [path]            Format and query body as JSON (dotted path: `data.episodes.0.url`)
  reload                 Re-fetch URL from remote server
  save <filepath>        Save raw HTML body to a local file
  export kotlin <sel>    Generate a ready-to-paste Kotlin Jsoup snippet
""")
        elif cmd.startswith("css "):
            sel = cmd[4:].strip()
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
            print(f"Found {len(parser_obj.matches)} match(es):")
            for i, m in enumerate(parser_obj.matches[:12], 1):
                text_snippet = m['text'].strip().replace('\n', ' ')[:90]
                print(f"  [{i:2d}] <{m['tag']}>: '{text_snippet}' | attrs: {m['attrs']}")
            if len(parser_obj.matches) > 12:
                print(f"  ... and {len(parser_obj.matches) - 12} more.")

        elif cmd.startswith("regex "):
            pattern = cmd[6:].strip()
            try:
                matches = re.findall(pattern, body)
                print(f"Found {len(matches)} match(es):")
                for i, m in enumerate(matches[:15], 1):
                    print(f"  [{i:2d}] {m}")
                if len(matches) > 15:
                    print(f"  ... and {len(matches) - 15} more.")
            except Exception as e:
                print(f"❌ Regex error: {e}")

        elif cmd == "media":
            m3u8s = sorted(set(re.findall(r'https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*', body)))
            mp4s = sorted(set(re.findall(r'https?://[^\s"\'<>]+\.mp4[^\s"\'<>]*', body)))
            iframes = sorted(set(re.findall(r'<iframe[^>]+(?:src|data-src)=["\']([^"\']+)["\']', body, re.IGNORECASE)))
            jwplayers = sorted(set(re.findall(r'(?:file|source)\s*:\s*["\'](https?://[^"\']+)["\']', body, re.IGNORECASE)))

            print(f"\n📹 Media Discovery Results:")
            print(f"  • HLS Streams (.m3u8): {len(m3u8s)}")
            for u in m3u8s[:6]: print(f"    - {u}")
            print(f"  • Direct MP4 Streams: {len(mp4s)}")
            for u in mp4s[:6]: print(f"    - {u}")
            print(f"  • Video Iframes: {len(iframes)}")
            for u in iframes[:6]: print(f"    - {u}")
            if jwplayers:
                print(f"  • Inline Player Files: {len(jwplayers)}")
                for u in jwplayers[:4]: print(f"    - {u}")
            print()

        elif cmd == "links":
            links = sorted(set(re.findall(r'href=["\']([^"\'#]+)["\']', body)))
            print(f"Found {len(links)} link(s):")
            for i, l in enumerate(links[:20], 1):
                print(f"  [{i:2d}] {l}")

        elif cmd == "headers":
            print(json.dumps(resp_headers, indent=2))

        elif cmd == "cookies":
            cookies = [f"{c.name}={c.value} (domain={c.domain}, path={c.path})" for c in session.cookie_jar]
            print(f"Active Session Cookies ({len(cookies)}):")
            for c in cookies:
                print(f"  • {c}")

        elif cmd.startswith("json"):
            try:
                data = json.loads(body)
                parts = cmd.split(None, 1)
                if len(parts) > 1:
                    path = parts[1].strip()
                    curr = data
                    for key in path.split('.'):
                        if isinstance(curr, list) and key.isdigit():
                            curr = curr[int(key)]
                        elif isinstance(curr, dict):
                            curr = curr.get(key)
                        else:
                            curr = None
                    print(json.dumps(curr, indent=2)[:3000])
                else:
                    print(json.dumps(data, indent=2)[:3000])
            except Exception as e:
                print(f"❌ Not valid JSON: {e}")

        elif cmd == "reload":
            print(f"🔄 Re-fetching from {url}...")
            status, body, resp_headers, lat = session.fetch(url, headers=headers)
            print(f"✅ Reloaded {len(body):,} bytes (HTTP Status: {status}, Latency: {lat*1000:.1f}ms)")

        elif cmd.startswith("save "):
            filepath = Path(cmd[5:].strip())
            filepath.write_text(body, encoding="utf-8")
            print(f"💾 Saved body to {filepath} ({len(body):,} bytes).")

        elif cmd.startswith("export kotlin"):
            sel = cmd[13:].strip() or "div.anime-card"
            snippet = f"""// Jsoup Parsing Snippet
val doc = response.asJsoup()
val items = doc.select("{sel}").map {{ element ->
    SAnime.create().apply {{
        title = element.selectFirst("h2.title, a.title")?.text() ?: ""
        setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }}
}}"""
            print(snippet)
        else:
            print(f"Unknown command: '{cmd}'. Type 'help' for command list.")


# ==============================================================================
# Extension Auto-Resolution Helpers
# ==============================================================================

REPO_ROOT = Path(__file__).resolve().parent.parent if "__file__" in globals() else Path.cwd()


def resolve_extension_target(repo_root: Path, target: Optional[str] = None, lang: Optional[str] = None, name: Optional[str] = None) -> Tuple[Optional[str], Optional[str]]:
    """Helper to resolve (lang, name) from target positional argument (e.g. 'moviewala' or 'en/moviewala')."""
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


def inspect_extension_module(repo_root: Path, lang: str, name: str) -> Tuple[Optional[str], Dict[str, str]]:
    """Extracts baseUrl and default headers from extension source files."""
    ext_dir = repo_root / "src" / lang / name
    if not ext_dir.exists():
        return None, {}

    base_url = None
    extracted_headers = {}

    for kt_file in ext_dir.rglob("*.kt"):
        content = kt_file.read_text(encoding="utf-8", errors="ignore")
        if not base_url:
            m_base = re.search(r'(?:baseUrl|defaultBaseUrl)\s*=\s*["\'](https?://[^"\']+)["\']', content)
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

    if base_url:
        if "Referer" not in extracted_headers:
            extracted_headers["Referer"] = f"{base_url}/"
        if "Origin" not in extracted_headers:
            extracted_headers["Origin"] = base_url

    return base_url, extracted_headers


# ==============================================================================
# CLI Entrypoint
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="Lightweight local scraping & endpoint verification tool with media discovery and interactive REPL.",
        formatter_class=argparse.RawTextHelpFormatter
    )
    parser.add_argument("target", nargs="?", help="Target URL (e.g. 'https://example.com') or extension module name (e.g. 'moviewala')")
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

    args = parser.parse_args()

    target_url = args.url
    injected_headers = {}

    if not target_url and args.target:
        if args.target.startswith("http://") or args.target.startswith("https://"):
            target_url = args.target
        else:
            lang, name = resolve_extension_target(REPO_ROOT, target=args.target)
            if lang and name:
                base_url, ext_headers = inspect_extension_module(REPO_ROOT, lang, name)
                if base_url:
                    target_url = base_url
                    injected_headers.update(ext_headers)
                    print(f"🔍 Auto-detected target extension: src/{lang}/{name}")
                    print(f"🌐 Resolved Base URL: {target_url}")
                    if injected_headers:
                        print(f"🛡️  Auto-injected headers: {', '.join(injected_headers.keys())}")
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

    if args.json:
        try:
            data = json.loads(body)
            print(json.dumps(data, indent=2)[:3000])
        except Exception as e:
            print(f"Failed to parse body as JSON: {e}")
            print(body[:1000])
        return

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
