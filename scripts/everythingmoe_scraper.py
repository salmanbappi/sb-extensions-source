#!/usr/bin/env python3
"""Automatic scraper for EverythingMoe (https://everythingmoe.com).

EverythingMoe is a community-curated directory/review site for anime & manga
streaming sites. Each entry lives at /s/<slug> and embeds a `var siteData = {...}`
JSON blob with the site's URL, mirrors, icon, tags, rank, and user reviews.

This script crawls:
  - /            -> the full directory listing (all /s/<slug> entries)
  - /graveyard   -> dead / delisted sites
  - /post/       -> guide posts (repos, quickstarts)
  - /monitor     -> downtime detector page (JS-rendered, reported as-is)
  - /s/<slug>    -> each site's review page (siteData JSON)

Output: JSON file (default: everythingmoe_sites.json) + printed summary.

Usage:
  python3 scripts/everythingmoe_scraper.py
  python3 scripts/everythingmoe_scraper.py --limit 20 --parallel 4
  python3 scripts/everythingmoe_scraper.py --json          # full JSON to stdout
  python3 scripts/everythingmoe_scraper.py --output out.json
"""

import argparse
import concurrent.futures
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from html import unescape
from pathlib import Path
from typing import Any, Dict, List, Optional

BASE_URL = "https://everythingmoe.com"
DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

def safe_int(val: Any, default: int = 0) -> int:
    """Safely convert any value to int without raising exceptions."""
    try:
        if val is None:
            return default
        return int(val)
    except (ValueError, TypeError):
        return default

def fetch(url: str, timeout: int = 25) -> str:
    """GET a page with a browser UA. Enforces http/https scheme and raises on non-200."""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError(f"Disallowed URL scheme: {parsed.scheme}")

    req = urllib.request.Request(url, headers={"User-Agent": DEFAULT_UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", errors="ignore")
        if resp.status != 200:
            raise RuntimeError(f"HTTP {resp.status} for {url}")
        return body

def extract_hrefs(html: str) -> List[str]:
    return re.findall(r'href="([^"]+)"', html)

def extract_site_data(html: str) -> Optional[Dict[str, Any]]:
    """Parse the `var siteData = {...}` JSON blob embedded in /s/<slug> pages."""
    marker = "var siteData = "
    idx = html.find(marker)
    if idx == -1:
        return None
    start = idx + len(marker)
    try:
        obj, _ = json.JSONDecoder().raw_decode(html[start:])
        return obj if isinstance(obj, dict) else None
    except (ValueError, json.JSONDecodeError):
        return None

def get_meta_description(html: str) -> Optional[str]:
    m = re.search(r'<meta\s+name="description"\s+content="([^"]+)"', html, re.IGNORECASE)
    return m.group(1).strip() if m else None

def fetch_json(url: str, timeout: int = 20) -> Optional[Any]:
    """Fetch and parse JSON from an API endpoint safely."""
    try:
        content = fetch(url, timeout=timeout)
        return json.loads(content)
    except Exception as e:
        print(f"  [!] Failed to fetch {url}: {e}")
        return None

def fetch_main_cache() -> Dict[str, Any]:
    """Fetch the master /data/cache/main.json containing pros, cons, info, and mirrors for 912 sites."""
    res = fetch_json(f"{BASE_URL}/data/cache/main.json")
    return res if isinstance(res, dict) else {}

def fetch_thread_counts() -> Dict[str, int]:
    """Fetch global review and comment thread counts from /comments/threadcount.json."""
    res = fetch_json(f"{BASE_URL}/comments/threadcount.json")
    return res if isinstance(res, dict) else {}

def parse_site_page(slug: str, html: str, cache_entry: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Extract structured data from one /s/<slug> page, augmented with /data/cache/main.json."""
    data = extract_site_data(html) or {}
    cache = cache_entry or {}

    title_m = re.search(r"<title>([^<]+?)\s*-\s*EverythingMoe</title>", html, re.I)
    title = (data.get("title") or cache.get("altname") or (title_m.group(1).strip() if title_m else None) or slug)

    # mirrors come in `key<<url#key<<url` / `url#url` packed strings
    def unpack_alts(value: Any) -> List[str]:
        if not value or not isinstance(value, str):
            return []
        out: List[str] = []
        for part in value.split("#"):
            part = part.strip()
            if not part:
                continue
            if "<<" in part:
                part = part.split("<<", 1)[1].strip()
            if part.startswith(("http://", "https://")):
                out.append(part)
        return out

    def dedupe(lst: List[str]) -> List[str]:
        seen = set()
        res = []
        for item in lst:
            if item not in seen:
                seen.add(item)
                res.append(item)
        return res

    expand = data.get("expand") if isinstance(data.get("expand"), dict) else {}
    expand_altlink = expand.get("altlink") or cache.get("altlink")
    expand_pos = expand.get("positive") or cache.get("positive") or cache.get("ex-positive")
    expand_neg = expand.get("negative") or cache.get("negative") or cache.get("ex-negative")
    expand_info = expand.get("info") or cache.get("info") or cache.get("ex-info") or cache.get("note")

    pros = [p.strip() for p in str(expand_pos or "").split("#") if p.strip()] if expand_pos else []
    cons = [c.strip() for c in str(expand_neg or "").split("#") if c.strip()] if expand_neg else []
    info = str(expand_info).strip() if expand_info and str(expand_info).strip() else None

    dead_val = data.get("DEAD")
    dead_reason: Optional[str] = None
    if dead_val and str(dead_val).lower() != "false":
        dead_reason = str(dead_val) if not isinstance(dead_val, bool) else "Dead"
    dead = bool(dead_reason)

    reviews = data.get("reviews") or []
    cleaned_reviews = []
    total_votes = 0
    for r in reviews:
        if not isinstance(r, dict):
            continue
        vote = safe_int(r.get("vote"), 0)
        total_votes += vote
        raw_rev = r.get("review")
        rev_str = unescape(str(raw_rev)).strip() if raw_rev is not None else None
        cleaned_reviews.append({
            "name": str(r["name"]) if r.get("name") is not None else None,
            "review": rev_str,
            "time": safe_int(r.get("time"), 0) or None,
            "vote": vote,
            "type": str(r.get("type")) if r.get("type") is not None else None,
        })

    pos_cnt = sum(1 for r in cleaned_reviews if r.get("type") == "1" or (r.get("vote") or 0) > 0)
    mixed_cnt = sum(1 for r in cleaned_reviews if r.get("type") == "0" or (r.get("vote") or 0) == 0)
    neg_cnt = sum(1 for r in cleaned_reviews if r.get("type") == "-1" or (r.get("vote") or 0) < 0)
    tot = len(cleaned_reviews)
    sentiment = {
        "positive": pos_cnt,
        "mixed": mixed_cnt,
        "negative": neg_cnt,
        "positive_pct": round((pos_cnt / tot) * 100) if tot else 0,
        "mixed_pct": round((mixed_cnt / tot) * 100) if tot else 0,
        "negative_pct": round((neg_cnt / tot) * 100) if tot else 0,
    }

    all_raw_mirrors = (
        unpack_alts(expand_altlink)
        + unpack_alts(data.get("ex-altlink2"))
        + unpack_alts(data.get("ex-altlink"))
        + unpack_alts(cache.get("ex-altlink"))
        + unpack_alts(cache.get("exx-altlink"))
    )
    mirrors = dedupe(all_raw_mirrors)
    extra_links = dedupe(unpack_alts(data.get("extra-link")) + unpack_alts(cache.get("extra-link")) + unpack_alts(cache.get("extralink")))

    cdn_domains = [d.strip() for d in str(cache.get("domains") or cache.get("domain") or "").split(",") if d.strip()]

    return {
        "slug": slug,
        "name": title,
        "url": data.get("link") or "",
        "icon": data.get("icon") or "",
        "tags": [t.strip() for t in str(data.get("filter") or "").split(",") if t.strip()],
        "mirrors": mirrors,
        "extra_links": extra_links,
        "cdn_domains": cdn_domains,
        "pros": pros,
        "cons": cons,
        "info": info,
        "rank": data.get("rank") or "",
        "category": data.get("type") or "",
        "description": get_meta_description(html),
        "dead": dead,
        "dead_reason": dead_reason,
        "review_count": len(cleaned_reviews),
        "review_vote_sum": total_votes,
        "sentiment": sentiment,
        "reviews": cleaned_reviews,
    }

def scrape_site_pages(slugs: List[str], main_cache: Optional[Dict[str, Any]] = None, parallel: int = 2, delay: float = 0.3) -> List[Dict[str, Any]]:
    """Fetch every /s/<slug> page (rate-limited, optionally parallel)."""
    cache = main_cache or {}

    def work(slug: str) -> Dict[str, Any]:
        try:
            html = fetch(f"{BASE_URL}/s/{slug}")
            entry = parse_site_page(slug, html, cache.get(slug))
            time.sleep(delay)
            return entry
        except Exception as e:
            c_entry = cache.get(slug, {})
            return {
                "slug": slug,
                "name": slug,
                "url": "",
                "error": str(e),
                "dead": False,
                "pros": [p.strip() for p in str(c_entry.get("positive") or "").split("#") if p.strip()],
                "cons": [c.strip() for c in str(c_entry.get("negative") or "").split("#") if c.strip()],
                "info": str(c_entry.get("info") or "").strip() or None,
            }

    if parallel <= 1:
        return [work(s) for s in slugs]

    results: List[Dict[str, Any]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=parallel) as pool:
        futures = {pool.submit(work, slug): slug for slug in slugs}
        for fut in concurrent.futures.as_completed(futures):
            results.append(fut.result())
    # preserve directory order
    order = {slug: i for i, slug in enumerate(slugs)}
    results.sort(key=lambda e: order.get(e.get("slug"), 0))
    return results

def scrape(limit: Optional[int] = None) -> Dict[str, Any]:
    print(f"🔍 Scraping {BASE_URL} ...")

    # 1. Master Cache & Thread Counts from backend API
    print("  Fetching master /data/cache/main.json & threadcount.json ...")
    main_cache = fetch_main_cache()
    thread_counts = fetch_thread_counts()
    print(f"  Master cache loaded: {len(main_cache)} entries, Thread counts: {len(thread_counts)}")

    # 2. Directory from homepage
    home = fetch(f"{BASE_URL}/")
    slugs: List[str] = []
    seen = set()
    for href in extract_hrefs(home):
        m = re.match(r"^/s/([^/]+)$", href)
        if m:
            slug = m.group(1)
            if slug not in seen:
                seen.add(slug)
                slugs.append(slug)
    print(f"  Directory entries found: {len(slugs)}")

    # 2. Graveyard (dead sites)
    dead_slugs: List[str] = []
    try:
        graveyard = fetch(f"{BASE_URL}/graveyard")
        dead_slugs = sorted(set(re.findall(r"/s/([^/\"']+)", graveyard)))
        print(f"  Graveyard entries: {len(dead_slugs)}")
    except Exception as e:
        print(f"  Graveyard fetch failed: {e}")

    # 3. Posts
    posts: List[Dict[str, str]] = []
    try:
        posts_html = fetch(f"{BASE_URL}/post/")
        for m in re.finditer(r'<a[^>]+href="(/post/[^"]+)"[^>]*>(.*?)</a>', posts_html, re.S):
            title = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", m.group(2))).strip()
            posts.append({"url": m.group(1), "title": title})
    except Exception as e:
        print(f"  Posts fetch failed: {e}")

    # 4. Monitor (downtime detector — JS-rendered, report as-is)
    monitor: Dict[str, Any] = {}
    try:
        mon_html = fetch(f"{BASE_URL}/monitor")
        mon_title_m = re.search(r"<title[^>]*>([^<]*)</title>", mon_html, re.I)
        monitor = {
            "url": f"{BASE_URL}/monitor",
            "title": (mon_title_m.group(1).strip() if mon_title_m else "Downtime Detector"),
            "note": "Statuses are rendered client-side (JS); page fetched OK but live statuses require a browser.",
        }
    except Exception as e:
        monitor = {"error": str(e)}

    # 5. Each site page (optionally limited for quick runs)
    crawl_slugs = slugs[:limit] if limit else slugs
    sites = scrape_site_pages(crawl_slugs, main_cache=main_cache)

    # mark graveyard slugs as dead if the page didn't already flag them
    for site in sites:
        if site.get("slug") in dead_slugs and not site.get("dead"):
            site["dead"] = True
            site["dead_reason"] = "Listed in graveyard"

    alive = [s for s in sites if not s.get("dead")]
    total_reviews = sum(s.get("review_count", 0) for s in sites)

    result = {
        "crawled_at": datetime.now(timezone.utc).isoformat(),
        "site": BASE_URL,
        "stats": {
            "directory_entries": len(slugs),
            "scraped_ok": len([s for s in sites if not s.get("error")]),
            "alive": len(alive),
            "dead": len(sites) - len(alive),
            "graveyard_entries": len(dead_slugs),
            "posts": len(posts),
            "total_reviews": total_reviews,
        },
        "sites": sites,
        "graveyard": dead_slugs,
        "posts": posts,
        "monitor": monitor,
    }
    return result

def main() -> int:
    parser = argparse.ArgumentParser(description="Automatic scraper for everythingmoe.com")
    parser.add_argument("--limit", type=int, default=None, help="Only scrape the first N directory entries")
    parser.add_argument("--parallel", type=int, default=2, help="Concurrent /s/ page fetches (default 2)")
    parser.add_argument("--delay", type=float, default=0.3, help="Politeness delay between requests per worker")
    parser.add_argument("--output", default="everythingmoe_sites.json", help="Output JSON path")
    parser.add_argument("--json", action="store_true", help="Dump full JSON to stdout")
    parser.add_argument("--no-write", action="store_true", help="Do not write the output file")
    args = parser.parse_args()

    result = scrape(limit=args.limit)

    # Summary
    stats = result["stats"]
    print("\n" + "=" * 60)
    print("📊 EverythingMoe Scrape Summary")
    print("=" * 60)
    print(f"  Directory entries : {stats['directory_entries']}")
    print(f"  Scraped OK        : {stats['scraped_ok']}")
    print(f"  Alive sites       : {stats['alive']}")
    print(f"  Dead sites        : {stats['dead']}")
    print(f"  Graveyard entries : {stats['graveyard_entries']}")
    print(f"  Posts             : {stats['posts']}")
    print(f"  Total reviews     : {stats['total_reviews']}")
    print("-" * 60)
    for site in result["sites"][:15]:
        status = "❌ dead" if site.get("dead") else "✅ alive"
        name = site.get("name") or site.get("slug")
        print(f"  {status:>10}  {name:<30} {site.get('url', '')[:50]}")
    if len(result["sites"]) > 15:
        print(f"  ... and {len(result['sites']) - 15} more")

    if not args.no_write:
        out_path = Path(args.output).resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\n💾 Saved to {out_path}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0

if __name__ == "__main__":
    sys.exit(main())
