#!/usr/bin/env python3
"""
High-Speed Automated Site Reconnaissance & Fingerprinting Engine

Probes a target streaming or anime website in parallel (<2s) to discover:
1. CMS & Theme (WordPress, DooPlay, ToroTheme, PsychoPlay, Next.js, Nuxt).
2. Open REST APIs (/wp-json/wp/v2/, /api/v1/).
3. Sitemaps, RSS feeds, and robots.txt routes.
4. Video hosters and matching :lib:* extractors across sample pages.
5. Search & filter URL parameter matrix.
6. Automatic scaffolding command recommendation.
"""

import argparse
import json
import os
import re
import ssl
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))

from scripts.secrets_loader import get_secret

# Ignore SSL errors for reconnaissance
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
DEFAULT_FIRECRAWL_KEY = get_secret("FIRECRAWL_API_KEY", "")

# Extractor signatures mapped to Aniyomi :lib:* dependencies
EXTRACTOR_SIGNATURES = {
    "StreamWish": (re.compile(r'https?://(?:www\.)?(?:streamwish|wishfast|strwish|awish|wishembed|embedwish)\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:streamwish-extractor"),
    "DoodStream": (re.compile(r'https?://(?:www\.)?(?:dood|doodstream|doods|ds2play|doodp)\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:dood-extractor"),
    "Filemoon": (re.compile(r'https?://(?:www\.)?(?:filemoon|moonplayer|fmoon)\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:filemoon-extractor"),
    "StreamTape": (re.compile(r'https?://(?:www\.)?streamtape\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:streamtape-extractor"),
    "Mp4Upload": (re.compile(r'https?://(?:www\.)?mp4upload\.com/([a-z0-9]+)', re.I), ":lib:mp4upload-extractor"),
    "Vidplay": (re.compile(r'https?://(?:www\.)?(?:vidplay|vidsrc|mycloud)\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:vidplay-extractor"),
    "VidHide": (re.compile(r'https?://(?:www\.)?(?:vidhide|vidhd|streamhide)\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:vidhide-extractor"),
    "MixDrop": (re.compile(r'https?://(?:www\.)?mixdrop\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:mixdrop-extractor"),
    "Luluvdo": (re.compile(r'https?://(?:www\.)?luluvdo\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:luluvdo-extractor"),
    "Voe": (re.compile(r'https?://(?:www\.)?voe\.[a-z0-9]+/([a-z0-9]+)', re.I), ":lib:voe-extractor"),
    "Gofile": (re.compile(r'https?://(?:www\.)?gofile\.io/d/([a-z0-9]+)', re.I), ":lib:gofile-extractor"),
    "Pixeldrain": (re.compile(r'https?://(?:www\.)?pixeldrain\.com/[ul]/([a-z0-9]+)', re.I), ":lib:pixeldrain-extractor"),
    "HubCloud": (re.compile(r'https?://(?:www\.)?(?:hubcloud|fastcloud|drivehub)\.[a-z0-9]+', re.I), ":lib:hubcloud-extractor"),
    "GDFlix": (re.compile(r'https?://(?:www\.)?gdflix\.[a-z0-9]+', re.I), ":lib:gdflix-extractor"),
    "FilePress": (re.compile(r'https?://(?:www\.)?filepress\.[a-z0-9]+', re.I), ":lib:filepress-extractor"),
}


def fetch_url(url: str, timeout: int = 8, headers: Optional[Dict[str, str]] = None) -> Tuple[int, str, Dict[str, str], float]:
    """Fetches a URL and returns (status_code, content, headers, latency_ms)."""
    req_headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    }
    if headers:
        req_headers.update(headers)

    req = urllib.request.Request(url, headers=req_headers)
    start_t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as resp:
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
        return 0, str(e), {}, latency


class SiteRecon:
    def __init__(self, base_url: str):
        if not base_url.startswith("http://") and not base_url.startswith("https://"):
            base_url = "https://" + base_url
        self.base_url = base_url.rstrip("/")
        parsed = urllib.parse.urlparse(self.base_url)
        self.domain = parsed.netloc

        self.root_status = 0
        self.root_html = ""
        self.root_headers = {}
        self.root_latency = 0.0

        self.probe_results: Dict[str, Tuple[int, str, Dict[str, str], float]] = {}
        self.sample_pages: List[str] = []
        self.detected_extractors: Set[str] = set()
        self.detected_libs: Set[str] = set()
        self.cms_scores: Dict[str, int] = {
            "DooPlay (WordPress)": 0,
            "ToroTheme (WordPress)": 0,
            "PsychoPlay (WordPress)": 0,
            "WordPress REST API": 0,
            "Next.js (SSR / JSON)": 0,
            "Nuxt.js": 0,
            "Custom HTML / CMS": 0,
        }

    def run(self, deep_samples: int = 3, use_jina: bool = False) -> Dict[str, Any]:
        """Runs the complete reconnaissance sweep."""
        print(f"🚀 Launching Parallel Reconnaissance Probe against: {self.base_url}")

        probe_targets = {
            "root": self.base_url,
            "robots": f"{self.base_url}/robots.txt",
            "sitemap": f"{self.base_url}/sitemap.xml",
            "sitemap_index": f"{self.base_url}/sitemap_index.xml",
            "feed": f"{self.base_url}/feed/",
            "wp_json": f"{self.base_url}/wp-json/",
            "wp_posts": f"{self.base_url}/wp-json/wp/v2/posts?per_page=1",
            "dooplay_api": f"{self.base_url}/wp-json/dooplay/v1/",
        }

        # Stage 1: Parallel HTTP Probes
        with ThreadPoolExecutor(max_workers=8) as executor:
            future_to_key = {executor.submit(fetch_url, url): key for key, url in probe_targets.items()}
            for future in as_completed(future_to_key):
                key = future_to_key[future]
                try:
                    res = future.result()
                    self.probe_results[key] = res
                except Exception as e:
                    self.probe_results[key] = (0, str(e), {}, 0.0)

        self.root_status, self.root_html, self.root_headers, self.root_latency = self.probe_results.get("root", (0, "", {}, 0.0))

        # Stage 2: CMS & Framework Fingerprinting
        self._analyze_fingerprints()

        # Stage 3: Extract Sample Detail Pages
        self._extract_sample_links(limit=deep_samples)

        # Stage 4: Deep Scan Sample Pages for Video Extractors
        self._scan_sample_extractors()

        # Optional: Jina Reader preview
        jina_summary = None
        if use_jina:
            jina_summary = self._fetch_jina_preview()

        # Stage 5: Compile Report & Scaffolding Recommendation
        return self._generate_report(jina_summary)

    def _analyze_fingerprints(self):
        """Scores CMS and theme signatures."""
        html = self.root_html
        headers = self.root_headers

        # Cloudflare / Server checks
        server = headers.get("server", "").lower()
        if "cloudflare" in server or "cf-ray" in headers:
            self.cms_scores["Cloudflare WAF"] = 100

        # DooPlay Checks
        if "assets/js/front.scripts.min.js" in html or "class=\"dt_poster\"" in html or "id=\"seasons\"" in html or "class=\"episodios\"" in html:
            self.cms_scores["DooPlay (WordPress)"] += 40
        if self.probe_results.get("dooplay_api", (0,))[0] == 200:
            self.cms_scores["DooPlay (WordPress)"] += 50
        if "doo_player_ajax" in html:
            self.cms_scores["DooPlay (WordPress)"] += 40

        # ToroTheme Checks
        if "torotheme" in html or "torocounter.js" in html or "class=\"torotable\"" in html or "id=\"play_video\"" in html:
            self.cms_scores["ToroTheme (WordPress)"] += 50

        # PsychoPlay Checks
        if "psychoplay" in html or "class=\"playbox\"" in html or "class=\"anime-episodes\"" in html:
            self.cms_scores["PsychoPlay (WordPress)"] += 50

        # Generic WordPress REST Checks
        if self.probe_results.get("wp_posts", (0,))[0] == 200:
            self.cms_scores["WordPress REST API"] += 60
        elif self.probe_results.get("wp_json", (0,))[0] == 200:
            self.cms_scores["WordPress REST API"] += 30

        if "/wp-content/" in html or "/wp-includes/" in html or "wp-json" in html:
            self.cms_scores["WordPress (Core)"] = 50

        # Next.js Checks
        if "<script id=\"__NEXT_DATA__\"" in html or "/_next/static/" in html:
            self.cms_scores["Next.js (SSR / JSON)"] += 80

        # Nuxt Checks
        if "window.__NUXT__" in html or "/_nuxt/" in html:
            self.cms_scores["Nuxt.js"] += 80

    def _extract_sample_links(self, limit: int = 3):
        """Extracts sample movie/anime content links from root HTML, sitemaps, or feed."""
        links = set()

        # 1. From RSS Feed
        feed_status, feed_xml, _, _ = self.probe_results.get("feed", (0, "", {}, 0.0))
        if feed_status == 200:
            item_links = re.findall(r'<link>([^<]+)</link>', feed_xml)
            for l in item_links:
                if self.domain in l and l != self.base_url and not l.endswith("/feed/"):
                    links.add(l.strip())

        # 2. From Root HTML
        if len(links) < limit:
            hrefs = re.findall(r'href=["\'](https?://' + re.escape(self.domain) + r'/[^"\']+)["\']', self.root_html)
            for h in hrefs:
                if not any(x in h.lower() for x in ["/page/", "/category/", "/genre/", "/tag/", "/contact", "/dmca", "/about", "/login", "/register"]):
                    if h != self.base_url and h != self.base_url + "/":
                        links.add(h)

        # 3. Relative links in Root HTML
        if len(links) < limit:
            rel_hrefs = re.findall(r'href=["\'](/[^"\'#?]+\.html?)["\']', self.root_html)
            for r in rel_hrefs:
                links.add(f"{self.base_url}{r}")

        self.sample_pages = list(links)[:limit]

    def _scan_sample_extractors(self):
        """Scans sample pages in parallel to find embedded video players."""
        if not self.sample_pages:
            return

        with ThreadPoolExecutor(max_workers=len(self.sample_pages)) as executor:
            future_to_url = {executor.submit(fetch_url, url): url for url in self.sample_pages}
            for future in as_completed(future_to_url):
                try:
                    status, html, _, _ = future.result()
                    if status == 200:
                        for name, (pattern, lib_dep) in EXTRACTOR_SIGNATURES.items():
                            if pattern.search(html):
                                self.detected_extractors.add(name)
                                self.detected_libs.add(lib_dep)

                        if ".m3u8" in html:
                            self.detected_extractors.add("Direct HLS (.m3u8)")
                            self.detected_libs.add(":lib:playlist-utils")
                        if "eval(function(p,a,c,k,e,r)" in html:
                            self.detected_extractors.add("Dean Edwards Unpacker")
                            self.detected_libs.add(":lib:unpacker")
                except Exception:
                    pass

    def _fetch_jina_preview(self) -> Optional[str]:
        """Fetches a high-speed Jina Reader markdown extraction."""
        jina_url = f"https://r.jina.ai/{self.base_url}"
        status, md_content, _, _ = fetch_url(jina_url, timeout=10)
        if status == 200 and len(md_content) > 100:
            lines = [l for l in md_content.splitlines() if l.strip()][:15]
            return "\n".join(lines)
        return None

    def _fetch_firecrawl_scrape(self, api_key: str = DEFAULT_FIRECRAWL_KEY) -> Optional[str]:
        """Fetches a full headless browser markdown extraction via Firecrawl API."""
        endpoint = "https://api.firecrawl.dev/v1/scrape"
        payload = json.dumps({"url": self.base_url, "formats": ["markdown"]}).encode("utf-8")
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": DEFAULT_USER_AGENT
        }
        req = urllib.request.Request(endpoint, data=payload, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=15, context=SSL_CTX) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("success") and "data" in data:
                    md = data["data"].get("markdown", "")
                    lines = [l for l in md.splitlines() if l.strip()][:15]
                    return "\n".join(lines)
        except Exception as e:
            print(f"  [!] Firecrawl scrape error: {e}", file=sys.stderr)
        return None

    def _fetch_firecrawl_map(self, api_key: str = DEFAULT_FIRECRAWL_KEY, limit: int = 20) -> List[str]:
        """Maps all indexed URLs on the target domain using Firecrawl /v1/map."""
        endpoint = "https://api.firecrawl.dev/v1/map"
        payload = json.dumps({"url": self.base_url, "limit": limit}).encode("utf-8")
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": DEFAULT_USER_AGENT
        }
        req = urllib.request.Request(endpoint, data=payload, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=15, context=SSL_CTX) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("success") and "links" in data:
                    return data.get("links", [])
        except Exception as e:
            print(f"  [!] Firecrawl map error: {e}", file=sys.stderr)
        return []

    def _generate_report(self, preview_text: Optional[str] = None, preview_engine: str = "Jina Reader") -> Dict[str, Any]:
        """Compiles the summary and generates the scaffolding command."""
        top_cms = max(self.cms_scores.items(), key=lambda x: x[1])
        identified_cms = top_cms[0] if top_cms[1] >= 30 else "Custom HTML / CMS"

        site_type = "html"
        theme_flag = ""
        if "DooPlay" in identified_cms:
            site_type = "theme"
            theme_flag = "--theme dooplay"
        elif "ToroTheme" in identified_cms:
            site_type = "theme"
            theme_flag = "--theme torotheme"
        elif "WordPress REST" in identified_cms:
            site_type = "api"
        elif "Next.js" in identified_cms or "Nuxt" in identified_cms:
            site_type = "api"

        suggested_name = self.domain.split(".")[0].capitalize().replace("-", "")

        cmd_parts = [
            f"python3 scripts/cli.py create",
            f"--name \"{suggested_name}\"",
            f"--baseUrl \"{self.base_url}\"",
            f"--siteType \"{site_type}\"",
            f"--lang \"en\"",
        ]
        if theme_flag:
            cmd_parts.append(theme_flag)
        if self.detected_libs:
            cmd_parts.append(f"--with-preferences")

        scaffold_cmd = " \\\n  ".join(cmd_parts)

        print("\n" + "=" * 80)
        print("                 🚀 RAPID SITE RECONNAISSANCE REPORT")
        print("=" * 80)
        print(f"Target URL:         {self.base_url}")
        print(f"Server Status:      {self.root_status} (Latency: {self.root_latency:.1f}ms)")
        print(f"Server Headers:     {self.root_headers.get('server', 'Unknown')}")
        print(f"Cloudflare WAF:     {'Detected 🛡️' if 'Cloudflare WAF' in self.cms_scores else 'Direct / No CF'}")
        print("-" * 80)
        print(f"🏆 Identified CMS:  {identified_cms} (Confidence: {top_cms[1]}%)")
        print(f"📡 REST API:        {'Active & Open (/wp-json/wp/v2/)' if self.probe_results.get('wp_posts', (0,))[0] == 200 else 'Closed / Custom'}")
        print(f"📰 RSS Feed:        {'Available (/feed/)' if self.probe_results.get('feed', (0,))[0] == 200 else 'None'}")
        print(f"🗺️  Sitemap:         {'Active (/sitemap.xml)' if self.probe_results.get('sitemap', (0,))[0] == 200 or self.probe_results.get('sitemap_index', (0,))[0] == 200 else 'None'}")
        print(f"📄 Sample Content:  {len(self.sample_pages)} pages inspected")
        for idx, sp in enumerate(self.sample_pages, 1):
            print(f"   {idx}. {sp}")

        print("-" * 80)
        print(f"🎬 Detected Video Extractors ({len(self.detected_extractors)}):")
        if self.detected_extractors:
            for ext in sorted(self.detected_extractors):
                print(f"   • {ext}")
        else:
            print("   ℹ️ No standard external iframe hosters detected (may use custom video locker or direct HLS).")

        if self.detected_libs:
            print(f"\n📦 Recommended Gradle Dependencies ({len(self.detected_libs)}):")
            for lib in sorted(self.detected_libs):
                print(f"   implementation(project(\"{lib}\"))")

        if preview_text:
            print("-" * 80)
            print(f"🤖 {preview_engine} AI Markdown Preview (First 15 lines):")
            print(preview_text)

        print("=" * 80)
        print("💡 RECOMMENDED SCAFFOLDING COMMAND:")
        print(scaffold_cmd)
        print("=" * 80 + "\n")

        return {
            "target": self.base_url,
            "status": self.root_status,
            "cms": identified_cms,
            "extractors": list(self.detected_extractors),
            "libs": list(self.detected_libs),
            "scaffold_command": scaffold_cmd.replace(" \\\n  ", " "),
        }


def main():
    parser = argparse.ArgumentParser(description="High-Speed Automated Site Reconnaissance & Fingerprinting Engine")
    parser.add_argument("url", help="Target website URL (e.g. 'https://vegamoviess.you')")
    parser.add_argument("--samples", type=int, default=3, help="Number of sample content pages to deep-scan (default: 3)")
    parser.add_argument("--jina", action="store_true", help="Fetch clean AI markdown preview via Jina Reader API")
    parser.add_argument("--firecrawl", action="store_true", help="Fetch deep headless browser markdown via Firecrawl API")
    parser.add_argument("--firecrawl-map", action="store_true", help="Discover all site URLs using Firecrawl /v1/map")
    parser.add_argument("--firecrawl-key", default="", help="Custom Firecrawl API key")
    parser.add_argument("--json", action="store_true", help="Output machine-readable JSON")
    args = parser.parse_args()

    fc_key = args.firecrawl_key or DEFAULT_FIRECRAWL_KEY
    recon = SiteRecon(args.url)

    if args.firecrawl_map:
        print(f"🗺️  Mapping site via Firecrawl /v1/map...")
        links = recon._fetch_firecrawl_map(api_key=fc_key, limit=args.samples * 3)
        if links:
            recon.sample_pages = [l for l in links if l != recon.base_url and l != recon.base_url + "/"][:args.samples]

    preview_text = None
    preview_engine = "Jina Reader"
    if args.firecrawl:
        print("🔥 Running Firecrawl Headless Browser Scrape...")
        preview_text = recon._fetch_firecrawl_scrape(api_key=fc_key)
        preview_engine = "Firecrawl Headless Browser"
    elif args.jina:
        preview_text = recon._fetch_jina_preview()

    report = recon.run(deep_samples=args.samples, use_jina=False)
    if preview_text:
        print("-" * 80)
        print(f"🤖 {preview_engine} Markdown Output:")
        print(preview_text)
        print("-" * 80)

    if args.json:
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
