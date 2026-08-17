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

try:
    import readline
except ImportError:
    pass


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
                    try:
                        raw_bytes = zlib.decompress(raw_bytes)
                    except Exception:
                        try:
                            raw_bytes = zlib.decompress(raw_bytes, -zlib.MAX_WBITS)
                        except Exception:
                            pass

                charset = response.info().get_content_charset() or "utf-8"
                body = raw_bytes.decode(charset, errors="replace")
                resp_headers = {k: v for k, v in response.info().items()}

                if "<title>Just a moment..." in body or "cf-chl-" in body or "challenges.cloudflare.com" in body:
                    print("\n⚠️ [Notice] Cloudflare Bot Protection Challenge Detected!")
                    print("  💡 Tip: Pass session cookies with `-c 'cf_clearance=...'` or headers with `-H 'User-Agent: ...'`")
                    print("  💡 Or add `:lib:cloudflare-interceptor` in your extension build.gradle.\n")

                return status, body, resp_headers, duration
        except urllib.error.HTTPError as e:
            duration = time.time() - start_time
            raw_bytes = e.read() if e.fp else b""
            body = raw_bytes.decode("utf-8", errors="replace")
            resp_headers = {k: v for k, v in e.headers.items()} if e.headers else {}
            return e.code, body, resp_headers, duration
        except (urllib.error.URLError, TimeoutError, ConnectionResetError, ssl.SSLError, OSError) as e:
            duration = time.time() - start_time
            return 0, f"Network Error: {e}", {}, duration


# ==============================================================================
# HTML Parser (Tag, Class, ID selector simulator without external deps)
# ==============================================================================

# ---------------------------------------------------------------------------
# BS4 optional fast-path for CSS selector evaluation
# ---------------------------------------------------------------------------
try:
    from bs4 import BeautifulSoup as _BeautifulSoup  # type: ignore
    _HAS_BS4 = True
except ImportError:
    _HAS_BS4 = False


class SimpleSelectorParser(HTMLParser):
    """Lightweight HTML element extractor matching basic tag/class/id selectors.

    Supports a single simple token: ``tag``, ``.class``, ``#id``, and
    combinations thereof (e.g. ``div.container``, ``a#link``).  For compound
    selectors use :func:`select_elements` which delegates here or to BS4.
    """

    def __init__(self, tag_name: Optional[str] = None, class_name: Optional[str] = None,
                 id_name: Optional[str] = None):
        super().__init__()
        self.target_tag = tag_name.lower() if tag_name else None
        self.target_class = class_name.lower() if class_name else None
        self.target_id = id_name.lower() if id_name else None
        self.matches: List[Dict[str, Any]] = []
        self._current_match: Optional[Dict[str, Any]] = None
        self._depth = 0

    def handle_starttag(self, tag, attrs):
        attr_dict = dict(attrs)
        tag_matched = not self.target_tag or tag.lower() == self.target_tag
        id_matched = not self.target_id or attr_dict.get("id", "").lower() == self.target_id
        class_matched = (not self.target_class
                         or self.target_class in attr_dict.get("class", "").lower().split())

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


# ---------------------------------------------------------------------------
# Stdlib node model (used when BS4 is absent)
# ---------------------------------------------------------------------------

class _Node:
    """Minimal DOM node used by the stdlib CSS selector engine."""
    __slots__ = ("tag", "attrs", "children", "parent", "text")

    def __init__(self, tag: str, attrs: Dict[str, str],
                 parent: Optional["_Node"] = None):
        self.tag = tag.lower()
        self.attrs = {k.lower(): v for k, v in attrs.items()}
        self.children: List["_Node"] = []
        self.parent = parent
        self.text = ""

    def get_text(self) -> str:
        return self.text


class _DOMBuilder(HTMLParser):
    """Builds a minimal DOM tree from HTML for the stdlib CSS engine."""

    # Tags that never have a closing tag in HTML5
    _VOID = frozenset([
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    ])

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = _Node("__root__", {})
        self._stack: List[_Node] = [self.root]

    def handle_starttag(self, tag, attrs):
        node = _Node(tag, dict(attrs), parent=self._stack[-1])
        self._stack[-1].children.append(node)
        if tag.lower() not in self._VOID:
            self._stack.append(node)

    def handle_endtag(self, tag):
        for i in range(len(self._stack) - 1, 0, -1):
            if self._stack[i].tag == tag.lower():
                self._stack = self._stack[:i]
                break

    def handle_data(self, data):
        if len(self._stack) > 1:
            self._stack[-1].text += data


# ---------------------------------------------------------------------------
# Token-level simple selector matcher
# ---------------------------------------------------------------------------

_ATTR_RE = re.compile(
    r'\[([^\[\]=~|^$*]+)'
    r'(?:([*^$~|]?=)["\']?([^"\]]*)["\']?)?'
    r'\]'
)
_PSEUDO_NTH_RE = re.compile(r':nth-child\((\d+)\)', re.IGNORECASE)


def _token_matches(node: "_Node", token: str) -> bool:
    """Return True if *node* matches a single simple CSS token.

    A token may contain a tag, ``.class``, ``#id``, ``[attr...]``, and
    ``:first-child`` / ``:last-child`` / ``:nth-child(n)`` pseudo-classes,
    in any combination.
    """
    remaining = token

    # --- Extract attribute selectors first (may contain dots/hashes) --------
    attr_tests: List[tuple] = []
    def _collect_attr(m):
        attr_tests.append((m.group(1).strip(), m.group(2) or "", m.group(3) or ""))
        return ""
    remaining = _ATTR_RE.sub(_collect_attr, remaining)

    # --- Extract pseudo-classes ----------------------------------------------
    pseudo_first = ":first-child" in remaining
    pseudo_last  = ":last-child"  in remaining
    nth_match    = _PSEUDO_NTH_RE.search(remaining)
    nth_n        = int(nth_match.group(1)) if nth_match else None
    remaining    = re.sub(r':(?:first|last)-child', '', remaining, flags=re.IGNORECASE)
    remaining    = _PSEUDO_NTH_RE.sub('', remaining)

    # --- Split tag / .class / #id --------------------------------------------
    tag_part = ""
    id_part = ""
    classes = []

    # Tag is leading alphanumeric or *
    tag_match = re.match(r'^[a-zA-Z0-9*_-]+', remaining)
    if tag_match:
        tag_part = tag_match.group(0)
        remaining_parts = remaining[len(tag_part):]
    else:
        remaining_parts = remaining

    # Extract all #id and .class tokens
    for match in re.finditer(r'([#.][a-zA-Z0-9_-]+)', remaining_parts):
        tok = match.group(1)
        if tok.startswith('#'):
            id_part = tok[1:]
        elif tok.startswith('.'):
            classes.append(tok[1:])

    # --- Tag check -----------------------------------------------------------
    if tag_part and tag_part != "*" and node.tag != tag_part.lower():
        return False

    # --- ID check ------------------------------------------------------------
    if id_part and node.attrs.get("id", "") != id_part:
        return False

    # --- Class check ---------------------------------------------------------
    if classes:
        node_classes = node.attrs.get("class", "").split()
        for c in classes:
            if c not in node_classes:
                return False

    # --- Attribute checks ----------------------------------------------------
    for attr_name, op, expected in attr_tests:
        actual = node.attrs.get(attr_name, None)
        if actual is None:
            return False
        if op == "":
            pass  # attribute presence only
        elif op == "=":
            if actual != expected:
                return False
        elif op == "*=":
            if expected not in actual:
                return False
        elif op == "^=":
            if not actual.startswith(expected):
                return False
        elif op == "$=":
            if not actual.endswith(expected):
                return False
        elif op == "~=":
            if expected not in actual.split():
                return False

    # --- Pseudo-class checks (require parent context) ------------------------
    if (pseudo_first or pseudo_last or nth_n is not None) and node.parent:
        siblings = [c for c in node.parent.children
                    if isinstance(c, _Node) and c.tag == node.tag]
        try:
            pos = siblings.index(node)  # 0-indexed
        except ValueError:
            return False
        if pseudo_first and pos != 0:
            return False
        if pseudo_last and pos != len(siblings) - 1:
            return False
        if nth_n is not None and pos + 1 != nth_n:
            return False

    return True


def _collect_text(node: "_Node") -> str:
    """Recursively collect all text from a node and its descendants."""
    parts = [node.text]
    for child in node.children:
        parts.append(_collect_text(child))
    return "".join(parts)


def _stdlib_select(root: "_Node", selector: str) -> List[Dict[str, Any]]:
    """Evaluate a CSS selector against a DOM tree built by _DOMBuilder.

    Supports:
    * Descendant combinator (whitespace): ``div.container a``
    * Child combinator (``>``):            ``ul > li``
    * Simple tokens: tag, ``.class``, ``#id``
    * Attribute selectors: ``[attr]``, ``[attr=v]``, ``[attr*=v]``,
      ``[attr^=v]``, ``[attr$=v]``
    * Pseudo-classes: ``:first-child``, ``:last-child``, ``:nth-child(n)``
    """
    # Split the selector into segments separated by the child combinator.
    # Each segment is then whitespace-split into descendant tokens.
    # We represent the full chain as a list of (token, is_direct_child) pairs
    # where *is_direct_child* applies to the relationship **before** this token.

    # Normalise: collapse runs of spaces around >
    selector = re.sub(r'\s*>\s*', ' > ', selector.strip())
    raw_parts = selector.split()

    chain: List[tuple] = []   # list of (token_str, require_direct_parent)
    direct_next = False
    for part in raw_parts:
        if part == ">":
            direct_next = True
        else:
            chain.append((part, direct_next))
            direct_next = False

    if not chain:
        return []

    def _walk(node: "_Node", depth: int) -> List["_Node"]:
        """Recursively find nodes matching chain[depth:]."""
        token, require_direct = chain[depth]
        results: List["_Node"] = []
        for child in node.children:
            if not isinstance(child, _Node):
                continue
            if _token_matches(child, token):
                if depth == len(chain) - 1:
                    results.append(child)
                else:
                    results.extend(_walk(child, depth + 1))
            elif not require_direct:
                # Descendant: recurse without advancing chain
                results.extend(_walk(child, depth))
            if require_direct:
                # For child combinator we still need to recurse into
                # non-matching children to advance the chain at next level
                if not _token_matches(child, token):
                    # already handled above in elif branch; skip duplicates
                    pass
        return results

    matched_nodes = _walk(root, 0)
    results: List[Dict[str, Any]] = []
    for n in matched_nodes:
        results.append({"tag": n.tag, "attrs": n.attrs, "text": _collect_text(n)})
    return results


def select_elements(html: str, selector: str) -> List[Dict[str, Any]]:
    """Evaluate *selector* against *html* and return a list of element dicts.

    Each dict has keys: ``tag`` (str), ``attrs`` (dict), ``text`` (str).

    Uses ``bs4.BeautifulSoup.select()`` when BeautifulSoup is available for
    maximum Jsoup parity; falls back to the stdlib DOM engine otherwise.
    """
    if _HAS_BS4:
        soup = _BeautifulSoup(html, "html.parser")
        return [
            {
                "tag": el.name,
                "attrs": {k: (" ".join(v) if isinstance(v, list) else v)
                          for k, v in (el.attrs or {}).items()},
                "text": el.get_text(),
            }
            for el in soup.select(selector)
        ]

    # ---------- stdlib fallback ----------------------------------------------
    sel = selector.strip()

    # Fast-path: single simple token (no spaces, no >, no [)
    _simple_re = re.compile(r'^[a-zA-Z0-9.*#.-]*$')
    is_compound = (' ' in sel or '>' in sel or '[' in sel or ':' in sel
                   or sel.count('.') > 1 or sel.count('#') > 1)

    if not is_compound:
        # Original SimpleSelectorParser is sufficient
        tag_part = id_part = class_part = None
        if '#' in sel:
            p = sel.split('#', 1)
            tag_part = p[0] or None
            id_part = p[1] or None
        elif '.' in sel:
            p = sel.split('.', 1)
            tag_part = p[0] or None
            class_part = p[1] or None
        else:
            tag_part = sel or None
        p_obj = SimpleSelectorParser(tag_name=tag_part, class_name=class_part, id_name=id_part)
        p_obj.feed(html)
        return p_obj.matches

    # Full compound selector — build DOM and evaluate
    builder = _DOMBuilder()
    builder.feed(html)
    return _stdlib_select(builder.root, sel)


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
            matches = select_elements(current_body, arg)
            print(f"🎯 Selector '{arg}' found {len(matches)} element(s):")
            for i, m in enumerate(matches[:10], 1):
                text = m['text'].strip().replace('\n', ' ')[:90]
                print(f"  [{i:2d}] <{m['tag']}> Text: '{text}' | Attrs: {m['attrs']}")
        elif cmd in ["help", "?"]:
            print("\n📖 Available REPL Commands:")
            print("  select <css_selector>  - Test CSS selector (e.g. 'select div.title' or 'select #player')")
            print("  regex <pattern>        - Test regex against body (e.g. 'regex /stream/(\\w+)')")
            print("  media                  - Sniff .m3u8, .mp4, and video iframe hosters")
            print("  json                   - View body formatted as JSON")
            print("  refetch                - Re-fetch the current target URL")
            print("  url <new_url>          - Switch target URL and fetch")
            print("  exit / quit / q        - Exit REPL session\n")
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

        # Extract popularAnimeRequest / getPopularAnime
        m_pop = re.search(r'(?:fun\s+popularAnimeRequest|override\s+suspend\s+fun\s+getPopularAnime)[^{=]*[={][^}]*?GET\s*\(\s*["\']([^"\']+)["\']', content)
        if not m_pop:
            m_pop = re.search(r'GET\s*\(\s*["\']([^"\']*(?:popular|trending|movies)[^"\']*)["\']', content, re.IGNORECASE)
        if m_pop:
            ep = m_pop.group(1)
            endpoints["popular"] = ep if ep.startswith("http") or "$baseUrl" in ep else f"$baseUrl{ep if ep.startswith('/') else '/' + ep}"

        # Extract latestUpdatesRequest / getLatestUpdates
        m_latest = re.search(r'(?:fun\s+latestUpdatesRequest|override\s+suspend\s+fun\s+getLatestUpdates)[^{=]*[={][^}]*?GET\s*\(\s*["\']([^"\']+)["\']', content)
        if not m_latest:
            m_latest = re.search(r'GET\s*\(\s*["\']([^"\']*(?:latest|recent|updates)[^"\']*)["\']', content, re.IGNORECASE)
        if m_latest:
            ep = m_latest.group(1)
            endpoints["latest"] = ep if ep.startswith("http") or "$baseUrl" in ep else f"$baseUrl{ep if ep.startswith('/') else '/' + ep}"

        # Extract searchAnimeRequest / getSearchAnime base url
        m_search = re.search(r'(?:fun\s+searchAnimeRequest|override\s+suspend\s+fun\s+getSearchAnime)[^{=]*[={][^}]*?GET\s*\(\s*["\']([^"\']+)["\']', content)
        if not m_search:
            m_search = re.search(r'GET\s*\(\s*["\']([^"\']*(?:search|\?s=|\?q=)[^"\']*)["\']', content, re.IGNORECASE)
        if m_search:
            ep = m_search.group(1)
            endpoints["search"] = ep if ep.startswith("http") or "$baseUrl" in ep else f"$baseUrl{ep if ep.startswith('/') else '/' + ep}"

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

    if args.benchmark > 0:
        print(f"⏱️ Running benchmark across {args.benchmark} requests...")
        latencies = [elapsed]
        for idx in range(2, args.benchmark + 1):
            try:
                _, _, _, el = session.fetch(
                    url=target_url,
                    method=args.method,
                    headers=headers,
                    data=args.data
                )
                latencies.append(el)
            except Exception as e:
                print(f"  [Req {idx}] Failed: {e}")
        if latencies:
            avg_ms = (sum(latencies) / len(latencies)) * 1000
            min_ms = min(latencies) * 1000
            max_ms = max(latencies) * 1000
            print(f"📊 Benchmark Results ({len(latencies)} requests):")
            print(f"  • Min: {min_ms:.1f}ms | Max: {max_ms:.1f}ms | Avg: {avg_ms:.1f}ms\n" + "=" * 60)

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
        matches = select_elements(body, args.selector.strip())
        print(f"Found {len(matches)} matching element(s):")
        for i, m in enumerate(matches[:10], 1):
            text = m['text'].strip().replace('\n', ' ')[:90]
            print(f"  [{i:2d}] <{m['tag']}> | Text: '{text}' | Attrs: {m['attrs']}")
        return

    print("--- First 500 characters of Body ---")
    print(body[:500])


if __name__ == "__main__":
    main()
