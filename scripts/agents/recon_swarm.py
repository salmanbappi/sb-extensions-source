"""
Recon Swarm Agent
Handles deep route exploration, HAR network archive analysis, dynamic schema inference,
and endpoint mapping for target streaming platforms.
"""

import json
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set
from urllib.parse import parse_qs, urlparse


@dataclass
class NetworkEntry:
    url: str
    method: str
    status: int
    request_headers: Dict[str, str] = field(default_factory=dict)
    response_headers: Dict[str, str] = field(default_factory=dict)
    post_data: Optional[str] = None
    response_text: Optional[str] = None
    content_type: str = ""


@dataclass
class DiscoveredRoute:
    path: str
    route_type: str  # 'search', 'detail', 'episode', 'stream', 'filter', 'api'
    params: List[str] = field(default_factory=list)
    sample_values: Dict[str, Any] = field(default_factory=dict)
    headers: Dict[str, str] = field(default_factory=dict)


@dataclass
class SchemaField:
    name: str
    kotlin_type: str
    is_nullable: bool = True
    default_value: Optional[str] = "null"
    json_key: Optional[str] = None
    nested_schema: Optional[Dict[str, Any]] = None


@dataclass
class SiteMap:
    base_url: str
    name: str
    media_streams: List[str] = field(default_factory=list)
    api_endpoints: List[str] = field(default_factory=list)
    discovered_routes: List[DiscoveredRoute] = field(default_factory=list)
    detected_schemas: Dict[str, List[SchemaField]] = field(default_factory=dict)
    auth_tokens: Dict[str, str] = field(default_factory=dict)
    cookies: Dict[str, str] = field(default_factory=dict)
    required_headers: Dict[str, str] = field(default_factory=dict)


class HarAnalyzer:
    """Analyzes HAR (HTTP Archive) data to extract media streams, APIs, and auth headers."""

    STREAM_EXTENSIONS = (
        ".m3u8",
        ".mp4",
        ".mkv",
        "/playlist",
        "/manifest",
        ".vtt",
        ".srt",
    )
    MEDIA_MIME_TYPES = (
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "video/mp4",
        "video/webm",
        "application/dash+xml",
        "text/vtt",
    )

    def parse_har(self, har_content: str | dict) -> List[NetworkEntry]:
        """Parses HAR JSON content into structured NetworkEntry objects."""
        if isinstance(har_content, str):
            data = json.loads(har_content)
        else:
            data = har_content

        entries = []
        raw_entries = data.get("log", {}).get("entries", [])
        for item in raw_entries:
            req = item.get("request", {})
            res = item.get("response", {})

            url = req.get("url", "")
            method = req.get("method", "GET")
            status = res.get("status", 0)

            req_headers = {
                h.get("name", ""): h.get("value", "")
                for h in req.get("headers", [])
                if h.get("name")
            }
            res_headers = {
                h.get("name", ""): h.get("value", "")
                for h in res.get("headers", [])
                if h.get("name")
            }

            post_data = req.get("postData", {}).get("text")
            res_content = res.get("content", {})
            response_text = res_content.get("text")
            content_type = res_content.get("mimeType", "")

            entries.append(
                NetworkEntry(
                    url=url,
                    method=method,
                    status=status,
                    request_headers=req_headers,
                    response_headers=res_headers,
                    post_data=post_data,
                    response_text=response_text,
                    content_type=content_type,
                )
            )
        return entries

    def extract_media_streams(self, entries: List[NetworkEntry]) -> List[str]:
        """Extracts direct HLS, DASH, or video stream URLs."""
        streams: Set[str] = set()
        for entry in entries:
            url_lower = entry.url.lower()
            if any(ext in url_lower for ext in self.STREAM_EXTENSIONS):
                streams.add(entry.url)
            elif any(mime in entry.content_type.lower() for mime in self.MEDIA_MIME_TYPES):
                streams.add(entry.url)
        return sorted(list(streams))

    def extract_api_endpoints(self, entries: List[NetworkEntry]) -> List[str]:
        """Identifies JSON API endpoints."""
        apis: Set[str] = set()
        for entry in entries:
            is_json_mime = "json" in entry.content_type.lower()
            is_json_body = (
                entry.response_text
                and entry.response_text.strip().startswith(("{", "["))
            )
            if is_json_mime or is_json_body:
                parsed = urlparse(entry.url)
                clean_url = f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
                apis.add(clean_url)
        return sorted(list(apis))

    def extract_auth_and_cookies(
        self, entries: List[NetworkEntry]
    ) -> tuple[Dict[str, str], Dict[str, str], Dict[str, str]]:
        """Extracts auth tokens, cookies (like cf_clearance), and common request headers."""
        tokens: Dict[str, str] = {}
        cookies: Dict[str, str] = {}
        headers: Dict[str, str] = {}

        for entry in entries:
            for k, v in entry.request_headers.items():
                k_lower = k.lower()
                if k_lower == "authorization":
                    tokens["Authorization"] = v
                elif k_lower == "x-csrf-token" or k_lower == "x-xsrf-token":
                    tokens[k] = v
                elif k_lower == "cookie":
                    cookie_pairs = [c.strip() for c in v.split(";") if "=" in c]
                    for pair in cookie_pairs:
                        c_name, c_val = pair.split("=", 1)
                        cookies[c_name.strip()] = c_val.strip()
                elif k_lower in ("referer", "user-agent", "origin", "x-requested-with"):
                    headers[k] = v

        return tokens, cookies, headers


class RouteExplorer:
    """Explores routes, pagination schemes, search parameters, and filters from HTML/JS text."""

    SEARCH_PATTERNS = [
        r'href=[\'"]([^\'"]*?(?:search|keyword|query|q=)[^\'"]*)[\'"]',
        r'action=[\'"]([^\'"]*?(?:search|catalog|filter)[^\'"]*)[\'"]',
        r'fetch\([\'"]([^\'"]*?(?:search|api|query)[^\'"]*)[\'"]',
    ]

    EPISODE_PATTERNS = [
        r'href=[\'"]([^\'"]*?(?:/watch/|/episode/|/ep/|-episode-|\?ep=)[^\'"]*)[\'"]',
        r'data-url=[\'"]([^\'"]*?(?:/watch/|/episode/|/ep/|\?ep=)[^\'"]*)[\'"]',
        r'data-id=[\'"]([0-9a-zA-Z_-]+)[\'"]',
    ]

    EMBED_PATTERNS = [
        r'<iframe[^>]+src=[\'"]([^\'"]*?(?:embed|player|video|v/|e/)[^\'"]*)[\'"]',
        r'sources:\s*\[\s*\{\s*file:\s*[\'"]([^\'"]+)[\'"]',
    ]

    def discover_routes_from_html(self, html: str, base_url: str) -> List[DiscoveredRoute]:
        """Extracts dynamic routes and categorized endpoints from HTML content."""
        routes: List[DiscoveredRoute] = []

        # 1. Search endpoints
        for pattern in self.SEARCH_PATTERNS:
            for match in re.finditer(pattern, html, re.IGNORECASE):
                url_match = match.group(1)
                parsed = urlparse(url_match)
                query_params = list(parse_qs(parsed.query).keys())
                routes.append(
                    DiscoveredRoute(
                        path=parsed.path or url_match,
                        route_type="search",
                        params=query_params,
                        sample_values={"sample_url": url_match},
                    )
                )

        # 2. Episode & Detail patterns
        for pattern in self.EPISODE_PATTERNS:
            for match in re.finditer(pattern, html, re.IGNORECASE):
                val = match.group(1)
                routes.append(
                    DiscoveredRoute(
                        path=val,
                        route_type="episode" if any(x in val for x in ("ep", "watch", "episode")) else "detail",
                        params=[],
                        sample_values={"sample_match": val},
                    )
                )

        # 3. Stream / Embed patterns
        for pattern in self.EMBED_PATTERNS:
            for match in re.finditer(pattern, html, re.IGNORECASE):
                val = match.group(1)
                routes.append(
                    DiscoveredRoute(
                        path=val,
                        route_type="stream",
                        params=[],
                        sample_values={"embed_url": val},
                    )
                )

        # Deduplicate routes by path and route_type
        unique_map: Dict[tuple[str, str], DiscoveredRoute] = {}
        for r in routes:
            key = (r.path, r.route_type)
            if key not in unique_map:
                unique_map[key] = r

        return list(unique_map.values())


class SchemaInferer:
    """Infers null-safe Kotlin types from JSON payloads."""

    RATING_KEYWORDS = {"score", "rating", "vote", "avg", "average", "rank", "popularity"}

    def infer_kotlin_type(self, key: str, value: Any) -> str:
        """Infers the corresponding Kotlin type for a JSON value, respecting API v16 conventions."""
        key_lower = key.lower()

        # Rule: Scores/Ratings MUST use Double? to prevent JsonDecodingException
        if any(kw in key_lower for kw in self.RATING_KEYWORDS) and (
            isinstance(value, (int, float, str)) or value is None
        ):
            return "Double?"

        if value is None:
            return "String?"
        elif isinstance(value, bool):
            return "Boolean?"
        elif isinstance(value, int):
            return "Int?"
        elif isinstance(value, float):
            return "Double?"
        elif isinstance(value, str):
            return "String?"
        elif isinstance(value, list):
            if not value:
                return "List<String>?"
            first_item = value[0]
            if isinstance(first_item, dict):
                class_name = "".join(w.capitalize() for w in key.split("_")) + "ItemDto"
                return f"List<{class_name}>?"
            inner_type = self.infer_kotlin_type(key + "_item", first_item).replace("?", "")
            return f"List<{inner_type}>?"
        elif isinstance(value, dict):
            class_name = "".join(w.capitalize() for w in key.split("_")) + "Dto"
            return f"{class_name}?"
        return "String?"

    def infer_schema(self, root_class_name: str, payload: dict | list) -> Dict[str, List[SchemaField]]:
        """Recursively analyzes JSON and returns a mapping of ClassName -> List[SchemaField]."""
        schemas: Dict[str, List[SchemaField]] = {}

        if isinstance(payload, list):
            if not payload or not isinstance(payload[0], dict):
                return schemas
            target_dict = payload[0]
        elif isinstance(payload, dict):
            target_dict = payload
        else:
            return schemas

        fields: List[SchemaField] = []
        nested_to_process: List[tuple[str, Any]] = []

        for k, v in target_dict.items():
            k_type = self.infer_kotlin_type(k, v)
            fields.append(
                SchemaField(
                    name=k,
                    kotlin_type=k_type,
                    is_nullable=True,
                    default_value="null",
                    json_key=k,
                )
            )

            if isinstance(v, dict):
                nested_class_name = "".join(w.capitalize() for w in k.split("_")) + "Dto"
                nested_to_process.append((nested_class_name, v))
            elif isinstance(v, list) and v and isinstance(v[0], dict):
                nested_class_name = "".join(w.capitalize() for w in k.split("_")) + "ItemDto"
                nested_to_process.append((nested_class_name, v[0]))

        schemas[root_class_name] = fields

        for n_name, n_val in nested_to_process:
            nested_schemas = self.infer_schema(n_name, n_val)
            schemas.update(nested_schemas)

        return schemas


class ReconSwarmAgent:
    """Autonomous reconnaissance swarm synthesizing network traces and site structure."""

    def __init__(self):
        self.har_analyzer = HarAnalyzer()
        self.route_explorer = RouteExplorer()
        self.schema_inferer = SchemaInferer()

    def run_recon(
        self,
        base_url: str,
        name: str,
        har_data: Optional[str | dict] = None,
        html_samples: Optional[List[str]] = None,
        json_samples: Optional[Dict[str, Any]] = None,
    ) -> SiteMap:
        """Executes full recon workflow and returns a unified SiteMap."""
        site_map = SiteMap(base_url=base_url, name=name)

        # 1. Process HAR entries if available
        if har_data:
            entries = self.har_analyzer.parse_har(har_data)
            site_map.media_streams.extend(self.har_analyzer.extract_media_streams(entries))
            site_map.api_endpoints.extend(self.har_analyzer.extract_api_endpoints(entries))
            tokens, cookies, req_headers = self.har_analyzer.extract_auth_and_cookies(entries)
            site_map.auth_tokens.update(tokens)
            site_map.cookies.update(cookies)
            site_map.required_headers.update(req_headers)

        # 2. Process HTML samples
        if html_samples:
            for html in html_samples:
                routes = self.route_explorer.discover_routes_from_html(html, base_url)
                site_map.discovered_routes.extend(routes)

        # 3. Process JSON samples for schema inference
        if json_samples:
            for root_name, sample in json_samples.items():
                inferred = self.schema_inferer.infer_schema(root_name, sample)
                site_map.detected_schemas.update(inferred)

        return site_map

    def explore_site(self, url: str) -> Dict[str, Any]:
        """Performs live reconnaissance of a URL, extracting sitemap, routes, and schemas."""
        import ssl
        import urllib.request
        from urllib.parse import urlparse
        import dataclasses

        clean_url = url if "://" in url else f"https://{url}"
        parsed = urlparse(clean_url)
        base_url = f"{parsed.scheme}://{parsed.netloc}"
        name = parsed.netloc.split(".")[0].capitalize()

        html_samples = []
        try:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            req = urllib.request.Request(
                clean_url,
                headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"}
            )
            with urllib.request.urlopen(req, timeout=8, context=ctx) as resp:
                html_samples.append(resp.read().decode("utf-8", errors="replace"))
        except Exception:
            pass

        site_map = self.run_recon(
            base_url=base_url,
            name=name,
            html_samples=html_samples if html_samples else None,
        )
        return dataclasses.asdict(site_map)

    @staticmethod
    def fetch_jina_markdown(url: str, timeout: int = 10) -> Optional[str]:
        """Fetches high-speed markdown extraction via Jina Reader (Zero API key required)."""
        import ssl
        import urllib.request
        jina_url = f"https://r.jina.ai/{url}" if not url.startswith("https://r.jina.ai/") else url
        try:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            req = urllib.request.Request(
                jina_url,
                headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
            )
            with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
                if resp.status == 200:
                    return resp.read().decode("utf-8", errors="replace")
        except Exception:
            pass
        return None

    @staticmethod
    def fetch_firecrawl_scrape(url: str, api_key: str, timeout: int = 15) -> Optional[str]:
        """Scrapes headless JS web page into clean markdown via Firecrawl API (Requires FIRECRAWL_API_KEY)."""
        import ssl
        import urllib.request
        if not api_key:
            return None
        endpoint = "https://api.firecrawl.dev/v1/scrape"
        payload = json.dumps({"url": url, "formats": ["markdown"]}).encode("utf-8")
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        }
        try:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            req = urllib.request.Request(endpoint, data=payload, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("success") and "data" in data:
                    return data["data"].get("markdown", "")
        except Exception:
            pass
        return None
