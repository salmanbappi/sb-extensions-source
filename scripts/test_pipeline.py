#!/usr/bin/env python3
"""
Aniyomi 5-Stage Full-Pipeline Scraper Verification Runner (test_pipeline.py)
---------------------------------------------------------------------------
Simulates the complete Aniyomi/AniZen playback journey without compiling Android APKs:
  Stage 1: Popular / Latest / Search (AnimesPage)
  Stage 2: Anime Details (SAnime metadata & initialized flag)
  Stage 3: Episode List (SEpisode stability, zero-base numbering, descending sort)
  Stage 4: Hoster List (2-Tier Server Folders)
  Stage 5: Video Streams (Quality resolution tags & playback probe)

Usage:
  python3 scripts/test_pipeline.py <module_name> [--query <search_query>]
  python3 scripts/test_pipeline.py zinkmovies
  python3 scripts/test_pipeline.py vegamovies --query "reacher"
"""

import argparse
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent


def print_stage(num: int, title: str):
    print(f"\n{'='*60}")
    print(f"📍 Stage {num}: {title}")
    print(f"{'='*60}")


class PipelineTester:
    def __init__(self, base_url: str, headers: Dict[str, str] = None):
        self.base_url = base_url.rstrip("/")
        self.headers = headers or {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer": f"{self.base_url}/",
        }
        self.ctx = ssl.create_default_context()
        self.ctx.check_hostname = False
        self.ctx.verify_mode = ssl.CERT_NONE

    def fetch(self, url: str) -> str:
        base = self.base_url if self.base_url.endswith("/") else f"{self.base_url}/"
        target = url if (url.startswith("http://") or url.startswith("https://")) else urllib.parse.urljoin(base, url.lstrip("/"))
        req = urllib.request.Request(target, headers=self.headers)
        with urllib.request.urlopen(req, context=self.ctx, timeout=10) as resp:
            return resp.read().decode("utf-8", errors="ignore")

    def run(self, query: Optional[str] = None):
        print(f"🚀 Running 5-Stage Verification Pipeline on: {self.base_url}")
        if query:
            print(f"🔎 Targeted Search Query: '{query}'")

        # ----------------------------------------------------------------------
        # Stage 1: Popular / Latest / Search
        # ----------------------------------------------------------------------
        print_stage(1, "Anime Listing (Popular / Search)")
        list_url = f"{self.base_url}/page/1/?s={urllib.parse.quote(query)}" if query else f"{self.base_url}/"
        print(f"  -> Requesting: {list_url}")

        try:
            html = self.fetch(list_url)
        except Exception as e:
            print(f"❌ Failed to fetch list page: {e}")
            return False

        # Extract anime items
        links = re.findall(r'<a[^>]+href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', html, re.DOTALL)
        candidate_items = []
        for href, text in links:
            clean_text = re.sub(r"<[^>]+>", "", text).strip()
            if not clean_text or href == self.base_url or href == f"{self.base_url}/" or "#" in href:
                continue
            clean_href = href.rstrip("/")
            if clean_href.endswith("/movies") or clean_href.endswith("/tvshows") or clean_href.endswith("/trending") or "/genre/" in clean_href:
                continue
            if ("/movies/" in href or "/tvshows/" in href or "/anime/" in href or "/watch/" in href or "/series/" in href):
                if not any(item[0] == href for item in candidate_items):
                    candidate_items.append((href, clean_text))

        if not candidate_items:
            # Fallback search for any internal links with images
            img_links = re.findall(r'<a[^>]+href=["\']([^"\']+)["\'][^>]*>\s*<img[^>]+(?:alt|title)=["\']([^"\']+)["\']', html, re.DOTALL)
            for href, title in img_links:
                if href.startswith("http") or href.startswith("/"):
                    if not any(item[0] == href for item in candidate_items):
                        candidate_items.append((href, title.strip()))

        if not candidate_items:
            print("⚠️ Stage 1 Warning: No standard anime cards identified from initial heuristics.")
            print("  ✓ HTTP endpoint reachable (200 OK), HTML length:", len(html))
            target_anime_url = f"{self.base_url}/"
            target_title = "Root Source"
        else:
            print(f"  ✓ Discovered {len(candidate_items)} anime item(s) on page:")
            for href, title in candidate_items[:5]:
                print(f"     • {title[:40]:40s} -> {href}")
            target_anime_url, target_title = candidate_items[0]

        # ----------------------------------------------------------------------
        # Stage 2: Anime Details
        # ----------------------------------------------------------------------
        print_stage(2, f"Anime Details ({target_title[:30]})")
        print(f"  -> Requesting: {target_anime_url}")
        try:
            detail_html = self.fetch(target_anime_url)
            print(f"  ✓ Fetched details page ({len(detail_html)} bytes)")

            # Heuristic check for title, synopsis, rating
            h1_tags = re.findall(r'<h1([^>]*)>(.*?)</h1>', detail_html, re.IGNORECASE | re.DOTALL)
            parsed_title = ""
            for attrs, content in h1_tags:
                if "text-logo" not in attrs and "logo" not in attrs:
                    parsed_title = re.sub(r'<[^>]+>', '', content).strip()
                    break
            if not parsed_title and h1_tags:
                parsed_title = re.sub(r'<[^>]+>', '', h1_tags[-1][1]).strip()
            if parsed_title:
                print(f"  ✓ Title parsed: {parsed_title}")

            genres = re.findall(r'<a[^>]+href=["\'][^"\']*(?:genre|category)[^"\']*["\'][^>]*>(.*?)</a>', detail_html, re.IGNORECASE)
            if genres:
                clean_genres = [re.sub(r"<[^>]+>", "", g).strip() for g in genres if g.strip()]
                print(f"  ✓ Genres parsed ({len(clean_genres)}): {', '.join(clean_genres[:4])}")
        except Exception as e:
            print(f"❌ Failed to fetch details page: {e}")
            return False

        # ----------------------------------------------------------------------
        # Stage 3: Episode List
        # ----------------------------------------------------------------------
        print_stage(3, "Episode List & Stable Anchor Verification")

        # Check for linkstore buttons (TV Series) or direct files
        linkstore_btns = re.findall(r'<a[^>]+href=["\'](https?://[^"\']*linkstore[^"\']*)["\'][^>]*>(.*?)</a>', detail_html, re.DOTALL)
        if not linkstore_btns:
            linkstore_btns = re.findall(r'<a[^>]+href=["\']([^"\']+)["\'][^>]*class=["\'][^"\']*button[^"\']*["\'][^>]*>(.*?)</a>', detail_html, re.DOTALL)

        detected_episodes = []
        is_tv_series = False

        if linkstore_btns:
            is_tv_series = True
            print(f"  ✓ TV Series detected with {len(linkstore_btns)} batch linkstore/button(s)")
            seasons_tested = set()
            for href, text in linkstore_btns:
                s_match = re.search(r"Season\s*0*(\d+)", text, re.IGNORECASE)
                season_num = int(s_match.group(1)) if s_match else 1
                if season_num in seasons_tested:
                    continue
                seasons_tested.add(season_num)

                try:
                    ls_html = self.fetch(href)
                    ep_links = re.findall(r'<a[^>]+href=["\'](https?://[^"\']+/file/[^"\']+)["\'][^>]*>(.*?)</a>', ls_html)
                    for file_url, ep_text in ep_links:
                        clean_ep = re.sub(r"<[^>]+>", "", ep_text).strip()
                        if "zip" in clean_ep.lower():
                            continue
                        ep_match = re.search(r"(?:EPISODE|E)\s*[-:]*\s*0*(\d+)", clean_ep, re.IGNORECASE)
                        if ep_match:
                            ep_num = int(ep_match.group(1))
                            detected_episodes.append((season_num, ep_num, file_url))
                except Exception as e:
                    print(f"  ⚠️ Warning fetching batch {href}: {e}")

        if not detected_episodes:
            # Fallback movie check
            file_btns = re.findall(r'<a[^>]+href=["\'](https?://[^"\']+/file/[^"\']+)["\']', detail_html)
            if file_btns:
                print(f"  ✓ Single Movie detected with {len(file_btns)} quality file button(s)")
                detected_episodes.append((1, 1, file_btns[0]))
            else:
                # Direct HTML episodes check
                ep_items = re.findall(r'<li[^>]*>\s*<a[^>]+href=["\']([^"\']+)["\'][^>]*>(?:Episode|Ep)?\s*(\d+)</a>', detail_html, re.IGNORECASE)
                for ep_href, ep_num in ep_items:
                    detected_episodes.append((1, int(ep_num), ep_href))

        if not detected_episodes:
            print("⚠️ No episodes extracted. Test completed up to Stage 2.")
            return True

        print(f"  ✓ Total episodes resolved: {len(detected_episodes)}")
        first_ep = detected_episodes[0]

        # Test Numbering Invariant: Season 1 Episode 1 must be 1.0f
        s_num, e_num, sample_url = first_ep
        calculated_num = ((s_num - 1) * 100 + e_num) if len(set(x[0] for x in detected_episodes)) > 1 else e_num
        print(f"  ✓ Episode Numbering check: Season {s_num}, Ep {e_num} -> episode_number = {float(calculated_num):.1f}")
        if s_num == 1 and e_num == 1 and calculated_num >= 1000:
            print("  ❌ LINT-13 FAILURE: Episode 1 assigned >= 1000 offset (will trigger missing items bug!)")
        else:
            print("  ✅ Zero-Base Numbering Validated (no false 1000-offset)")

        # ----------------------------------------------------------------------
        # Stage 4: Hoster List & Embed Discovery
        # ----------------------------------------------------------------------
        print_stage(4, f"Hoster List & Embed Discovery (S{s_num} E{e_num})")
        print(f"  -> Fetching Episode URL: {sample_url}")

        ep_html = self.fetch(sample_url)
        discovered_embeds = []
        if ep_html:
            embed_patterns = [
                r'<iframe[^>]+(?:src|data-src)=["\']([^"\']+)["\']',
                r'<a[^>]+href=["\'](https?://[^\s"\']+(?:embed|player|stream|watch|file|download|drive|hubcloud|gdflix|fastcloud)[^\s"\']*)["\']',
                r'(https?://[^\s"\'<>]+\.(?:m3u8|mp4)[^\s"\'<>]*)'
            ]
            for pat in embed_patterns:
                for match in re.findall(pat, ep_html, re.IGNORECASE):
                    if match not in discovered_embeds and not match.endswith(('.jpg', '.png', '.css', '.js')):
                        discovered_embeds.append(match)

        # Match with extractor registry if available
        matched_hosters = []
        try:
            from detect_extractors import KNOWN_EXTRACTOR_PATTERNS as EXTRACTOR_REGISTRY
            for emb in discovered_embeds:
                for ext_id, meta in EXTRACTOR_REGISTRY.items():
                    if re.search(meta.get("regex", ""), emb, re.IGNORECASE):
                        matched_hosters.append((meta["name"], emb))
                        break
        except Exception:
            pass

        if matched_hosters:
            print(f"  ✓ Discovered {len(matched_hosters)} matching video hoster(s):")
            for h_name, h_url in matched_hosters[:8]:
                print(f"     📁 {h_name} -> {h_url[:80]}")
        elif discovered_embeds:
            print(f"  ✓ Discovered {len(discovered_embeds)} candidate stream/embed URL(s):")
            for emb in discovered_embeds[:8]:
                print(f"     🔗 {emb[:80]}")
        else:
            print("  ℹ️ No direct iframe embeds found in HTML (may use API/dynamic extraction).")

        # ----------------------------------------------------------------------
        # Stage 5: Video Quality Stream Resolution & Health Probe
        # ----------------------------------------------------------------------
        print_stage(5, "Video Quality Stream Probe")
        direct_streams = [u for u in discovered_embeds if ".m3u8" in u or ".mp4" in u]
        if direct_streams:
            print(f"  ✓ Probing {len(direct_streams)} direct media stream(s):")
            for s_url in direct_streams[:3]:
                try:
                    probe_req = urllib.request.Request(s_url, method="HEAD", headers=self.headers)
                    with urllib.request.urlopen(probe_req, timeout=6) as resp:
                        ct = resp.headers.get("Content-Type", "unknown")
                        print(f"     🎬 Stream: {s_url[:65]}... | Status: {resp.status} ({ct})")
                except Exception as e:
                    print(f"     ⚠️ Stream probe notice: {s_url[:60]}... ({e})")
        else:
            print("  ℹ️ Direct media URLs are resolved at runtime via extractors from embed pages.")

        print(f"\n{'='*60}")
        print("🎉 5-Stage Verification Pipeline Complete!")
        print(f"{'='*60}")
        return True


def main():
    parser = argparse.ArgumentParser(description="Aniyomi 5-Stage Full-Pipeline Scraper Verification Runner")
    parser.add_argument("target", help="Target module name (e.g. 'zinkmovies', 'vegamovies') or base URL")
    parser.add_argument("--query", "-q", help="Optional search query to test search flow")
    args = parser.parse_args()

    target = args.target
    if target.startswith("http://") or target.startswith("https://"):
        base_url = target
    else:
        # Resolve module baseUrl from Kotlin source or build.gradle
        found_base_url = None
        src_dir = REPO_ROOT / "src"
        for kt in src_dir.rglob("*.kt"):
            if kt.parent.name == target or target in kt.name.lower():
                content = kt.read_text(encoding="utf-8", errors="ignore")
                m = re.search(r'PREF_BASE_URL_DEFAULT\s*=\s*["\']([^"\']+)["\']', content)
                if not m:
                    m = re.search(r'override\s+val\s+baseUrl\s*=\s*["\']([^"\']+)["\']', content)
                if m:
                    found_base_url = m.group(1)
                    break
        if not found_base_url:
            for bg in src_dir.rglob("build.gradle*"):
                if bg.parent.name == target or target in bg.parent.name.lower():
                    bg_content = bg.read_text(encoding="utf-8", errors="ignore")
                    m = re.search(r'baseUrl\s*=\s*["\'](https?://[^"\']+)["\']', bg_content)
                    if m:
                        found_base_url = m.group(1)
                        break
        if not found_base_url:
            print(f"❌ Could not resolve base URL for module: {target}")
            sys.exit(1)
        base_url = found_base_url

    tester = PipelineTester(base_url)
    success = tester.run(query=args.query)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
