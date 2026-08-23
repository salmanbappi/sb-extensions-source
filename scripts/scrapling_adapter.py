#!/usr/bin/env python3
"""
Scrapling Adapter Module for Aniyomi Extension Engine
----------------------------------------------------
Provides adaptive DOM parsing, CSS/XPath selector evaluation, anti-bot bypass,
and video iframe extraction using D4Vinci/Scrapling with graceful fallback.
"""

import json
import os
import re
import ssl
import sys
import time
import types
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Tuple, Union

# Ensure shim for orjson if not compiled/installed on current platform (e.g. Termux/ARM without maturin)
if "orjson" not in sys.modules:
    try:
        import orjson  # type: ignore
    except ImportError:
        class _OrjsonShim(types.ModuleType):
            OPT_INDENT_2 = 1
            OPT_SORT_KEYS = 2

            @staticmethod
            def loads(s: Union[str, bytes, bytearray]) -> Any:
                if isinstance(s, (bytes, bytearray)):
                    s = s.decode("utf-8")
                return json.loads(s)

            @staticmethod
            def dumps(obj: Any, default: Any = None, option: Any = None) -> bytes:
                return json.dumps(obj, default=default).encode("utf-8")

        sys.modules["orjson"] = _OrjsonShim("orjson")

# Try loading Scrapling components
_HAS_SCRAPLING = False
_Adaptor = None
_Fetcher = None
_StealthyFetcher = None

try:
    from scrapling.parser import Adaptor as _Adaptor  # type: ignore
    _HAS_SCRAPLING = True
except Exception:
    _HAS_SCRAPLING = False

try:
    from scrapling.fetchers import Fetcher as _Fetcher, StealthyFetcher as _StealthyFetcher  # type: ignore
except Exception:
    _Fetcher = None
    _StealthyFetcher = None


def is_scrapling_available() -> bool:
    """Returns True if Scrapling DOM parser is loaded and functional."""
    return _HAS_SCRAPLING and _Adaptor is not None


def is_stealth_available() -> bool:
    """Returns True if Scrapling browser stealth fetcher is functional."""
    return _StealthyFetcher is not None


def create_adaptor(html: str) -> Optional[Any]:
    """Creates a Scrapling Adaptor instance for the given HTML."""
    if not is_scrapling_available():
        return None
    try:
        return _Adaptor(html)
    except Exception:
        return None


def query_css(html_or_adaptor: Union[str, Any], selector: str, get_all: bool = False, adaptive: bool = False) -> Union[Optional[str], List[str]]:
    """Evaluates a CSS selector using Scrapling Adaptor."""
    adaptor = html_or_adaptor if hasattr(html_or_adaptor, "css") else create_adaptor(html_or_adaptor)
    if not adaptor:
        return [] if get_all else None

    try:
        res = adaptor.css(selector, adaptive=adaptive)
        if get_all:
            return [str(item) for item in res.getall()]
        val = res.get()
        return str(val) if val is not None else None
    except Exception:
        return [] if get_all else None


def query_xpath(html_or_adaptor: Union[str, Any], xpath: str, get_all: bool = False) -> Union[Optional[str], List[str]]:
    """Evaluates an XPath expression using Scrapling Adaptor."""
    adaptor = html_or_adaptor if hasattr(html_or_adaptor, "xpath") else create_adaptor(html_or_adaptor)
    if not adaptor:
        return [] if get_all else None

    try:
        res = adaptor.xpath(xpath)
        if get_all:
            return [str(item) for item in res.getall()]
        val = res.get()
        return str(val) if val is not None else None
    except Exception:
        return [] if get_all else None


def select_elements_scrapling(html: str, selector: str, adaptive: bool = False) -> Optional[List[Dict[str, Any]]]:
    """Evaluates CSS selector and returns structured element dicts: tag, attrs, text, html."""
    adaptor = create_adaptor(html)
    if not adaptor:
        return None

    try:
        nodes = adaptor.css(selector, adaptive=adaptive)
        results: List[Dict[str, Any]] = []
        for node in nodes:
            attrs = {}
            if hasattr(node, "attrib"):
                attrs = dict(node.attrib)
            text = ""
            if hasattr(node, "text"):
                text = str(node.text or "").strip()
            raw_html = ""
            if hasattr(node, "html"):
                raw_html = str(node.html or "")
            tag = getattr(node, "tag", "div")
            results.append({
                "tag": tag,
                "attrs": attrs,
                "text": text,
                "html": raw_html,
            })
        return results
    except Exception:
        return None


def extract_video_embeds(html: str) -> List[str]:
    """Extracts all iframe URLs, embed sources, video tags, and stream endpoints from HTML."""
    urls = set()

    # 1. Scrapling Adaptor extraction
    adaptor = create_adaptor(html)
    if adaptor:
        try:
            for ifr in adaptor.css("iframe::attr(src)").getall():
                if ifr and ifr.strip() and not ifr.startswith("data:"):
                    urls.add(ifr.strip())
            for ifr in adaptor.css("iframe::attr(data-src)").getall():
                if ifr and ifr.strip() and not ifr.startswith("data:"):
                    urls.add(ifr.strip())
            for src in adaptor.css("video source::attr(src), video::attr(src)").getall():
                if src and src.strip():
                    urls.add(src.strip())
        except Exception:
            pass

    # 2. Regex fallback / additions for JS embedded players
    patterns = [
        r'iframe[^>]+src=["\']([^"\']+)["\']',
        r'data-src=["\'](https?://[^"\']+)["\']',
        r'file\s*:\s*["\'](https?://[^"\']+\.(?:m3u8|mp4)[^"\']*)["\']',
        r'sources\s*:\s*\[\s*\{\s*file\s*:\s*["\']([^"\']+)["\']',
        r'player\.src\(["\']([^"\']+)["\']\)',
    ]
    for pat in patterns:
        for m in re.finditer(pat, html, re.IGNORECASE):
            u = m.group(1).strip()
            if u and not u.startswith("data:"):
                urls.add(u)

    return sorted(list(urls))


def fetch_url_scrapling(url: str, stealth: bool = False, timeout: int = 15,
                        headers: Optional[Dict[str, str]] = None) -> Tuple[int, str, Dict[str, str], float]:
    """Fetches a URL using Scrapling's stealth or HTTP fetcher, falling back to standard urllib."""
    start_t = time.time()
    user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    # 1. Try StealthyFetcher if requested and available
    if stealth and _StealthyFetcher is not None:
        try:
            page = _StealthyFetcher.fetch(url, headless=True, timeout=timeout * 1000)
            latency = (time.time() - start_t) * 1000
            status = getattr(page, "status", 200)
            content = getattr(page, "html", None) or getattr(page, "text", "") or ""
            return status, content, {}, latency
        except Exception as e:
            pass

    # 2. Try Fetcher if available
    if _Fetcher is not None:
        try:
            page = _Fetcher.fetch(url, timeout=timeout)
            latency = (time.time() - start_t) * 1000
            status = getattr(page, "status", 200)
            content = getattr(page, "html", None) or getattr(page, "text", "") or ""
            return status, content, {}, latency
        except Exception:
            pass

    # 3. Fallback to standard urllib with SSL bypass
    ssl_ctx = ssl.create_default_context()
    ssl_ctx.check_hostname = False
    ssl_ctx.verify_mode = ssl.CERT_NONE

    req_headers = {
        "User-Agent": user_agent,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    }
    if headers:
        req_headers.update(headers)

    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ssl_ctx) as resp:
            data = resp.read()
            content = data.decode("utf-8", errors="replace")
            latency = (time.time() - start_t) * 1000
            resp_headers = {k.lower(): v for k, v in resp.headers.items()}
            return resp.status, content, resp_headers, latency
    except urllib.error.HTTPError as e:
        latency = (time.time() - start_t) * 1000
        try:
            content = e.read().decode("utf-8", errors="replace")
        except Exception:
            content = ""
        resp_headers = {k.lower(): v for k, v in e.headers.items()} if e.headers else {}
        return e.code, content, resp_headers, latency
    except Exception as e:
        latency = (time.time() - start_t) * 1000
        return 0, f"Fetch error: {e}", {}, latency
