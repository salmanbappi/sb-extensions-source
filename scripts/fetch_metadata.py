#!/usr/bin/env python3
"""
Metadata API Fetcher & Tester Module for Aniyomi Extensions.
Queries Jikan v4, AniList GraphQL, Kitsu, TMDB, and OMDb to enrich anime episode metadata.
"""

import argparse
import json
import re
import urllib.parse
import urllib.request
from typing import Any, Dict, Optional

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


def http_get_json(url: str, headers: Optional[Dict[str, str]] = None) -> Optional[Dict[str, Any]]:
    req_headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    try:
        req = urllib.request.Request(url, headers=req_headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"  [!] HTTP GET failed for {url[:60]}: {e}")
    return None


def http_post_json(url: str, payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    headers = {"User-Agent": USER_AGENT, "Content-Type": "application/json", "Accept": "application/json"}
    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"  [!] HTTP POST failed for {url[:60]}: {e}")
    return None


def fetch_jikan_episodes(mal_id: str, max_pages: int = 10) -> Dict[int, Dict[str, Any]]:
    episodes = {}
    page = 1
    while page <= max_pages:
        url = f"https://api.jikan.moe/v4/anime/{mal_id}/episodes?page={page}"
        res = http_get_json(url)
        if not res or "data" not in res:
            break
        for ep in res["data"]:
            num = ep.get("mal_id")
            if num:
                episodes[int(num)] = {
                    "title": ep.get("title"),
                    "aired": ep.get("aired"),
                    "filler": ep.get("filler", False)
                }
        # Check pagination
        pagination = res.get("pagination", {})
        if not pagination.get("has_next_page", False):
            break
        page += 1
    if page > max_pages:
        print(f"  [!] Jikan: reached max_pages limit ({max_pages}). Some episodes may be missing.")
    return episodes


def fetch_anilist_metadata(mal_id: str) -> Dict[str, Any]:
    query = """
    query ($idMal: Int) {
      Media(idMal: $idMal, type: ANIME) {
        id
        bannerImage
        streamingEpisodes {
          title
          thumbnail
        }
      }
    }
    """
    try:
        mal_id_int = int(mal_id)
    except ValueError:
        return {"streaming_episodes": {}, "banner": None}

    res = http_post_json("https://graphql.anilist.co", {"query": query, "variables": {"idMal": mal_id_int}})
    result = {"streaming_episodes": {}, "banner": None}
    if res and "data" in res and res["data"].get("Media"):
        media = res["data"]["Media"]
        result["banner"] = media.get("bannerImage")
        streams = media.get("streamingEpisodes") or []
        for idx, stream in enumerate(streams, start=1):
            result["streaming_episodes"][idx] = {
                "title": stream.get("title"),
                "thumbnail": stream.get("thumbnail")
            }
    return result


def fetch_kitsu_episodes(mal_id: str, max_pages: int = 10) -> Dict[int, Dict[str, Any]]:
    map_url = f"https://kitsu.app/api/edge/mappings?filter[externalSite]=myanimelist/anime&filter[externalId]={mal_id}&include=item"
    map_res = http_get_json(map_url)
    kitsu_id = None
    if map_res and "included" in map_res:
        for item in map_res["included"]:
            if item.get("type") == "anime":
                kitsu_id = item.get("id")
                break
    if not kitsu_id:
        return {}

    episodes = {}
    next_url: Optional[str] = f"https://kitsu.app/api/edge/anime/{kitsu_id}/episodes?page[limit]=20&sort=number"
    pages = 0
    while next_url and pages < max_pages:
        ep_res = http_get_json(next_url)
        pages += 1
        if not ep_res or "data" not in ep_res:
            break
        for ep in ep_res["data"]:
            attrs = ep.get("attributes", {})
            num = attrs.get("number")
            if num:
                thumb = attrs.get("thumbnail", {}).get("original") if attrs.get("thumbnail") else None
                desc = attrs.get("description", "")
                if desc:
                    desc = re.sub(r"<[^>]+>", "", desc).strip()
                episodes[int(num)] = {
                    "title": attrs.get("canonicalTitle"),
                    "description": desc,
                    "thumbnail": thumb,
                    "airdate": attrs.get("airdate")
                }
        # Follow next page link
        next_url = (ep_res.get("links") or {}).get("next")
    if pages >= max_pages and next_url:
        print(f"  [!] Kitsu: reached max_pages limit ({max_pages}). Some episodes may be missing.")
    return episodes


def fetch_tmdb_episodes(title: str, api_key: str, season: int = 1) -> Dict[int, Dict[str, Any]]:
    if not api_key:
        return {}
    query_enc = urllib.parse.quote(title)
    search_url = f"https://api.themoviedb.org/3/search/multi?api_key={api_key}&query={query_enc}&language=en-US"
    search_res = http_get_json(search_url)
    if not search_res or not search_res.get("results"):
        return {}

    best_id = None
    media_type = None
    for res in search_res["results"]:
        m_type = res.get("media_type")
        if m_type in ("tv", "movie"):
            best_id = res.get("id")
            media_type = m_type
            break

    if not best_id or media_type != "tv":
        return {}

    season_url = f"https://api.themoviedb.org/3/tv/{best_id}/season/{season}?api_key={api_key}&language=en-US"
    season_res = http_get_json(season_url)
    episodes = {}
    if season_res and "episodes" in season_res:
        for ep in season_res["episodes"]:
            num = ep.get("episode_number")
            still_path = ep.get("still_path")
            thumb = f"https://image.tmdb.org/t/p/w500{still_path}" if still_path else None
            episodes[int(num)] = {
                "title": ep.get("name"),
                "description": ep.get("overview"),
                "thumbnail": thumb,
                "airdate": ep.get("air_date")
            }
    return episodes


def run_fetch_metadata(mal_id: str, title: Optional[str] = None, tmdb_key: str = "", format_type: str = "table", max_pages: int = 10):
    """Main CLI orchestrator for fetching & merging episode metadata."""
    print(f"🚀 Fetching metadata for MAL ID: {mal_id} (Title: {title or 'N/A'})...")

    jikan_eps = fetch_jikan_episodes(mal_id, max_pages) if mal_id else {}
    anilist_data = fetch_anilist_metadata(mal_id) if mal_id else {"streaming_episodes": {}, "banner": None}
    anilist_eps = anilist_data["streaming_episodes"]
    kitsu_eps = fetch_kitsu_episodes(mal_id, max_pages) if mal_id else {}
    tmdb_eps = fetch_tmdb_episodes(title, tmdb_key) if title and tmdb_key else {}

    all_episode_nums = sorted(list(set(jikan_eps.keys()) | set(anilist_eps.keys()) | set(kitsu_eps.keys()) | set(tmdb_eps.keys())))

    merged = {}
    for num in all_episode_nums:
        jk = jikan_eps.get(num, {})
        al = anilist_eps.get(num, {})
        kt = kitsu_eps.get(num, {})
        tm = tmdb_eps.get(num, {})

        ep_title = jk.get("title") or kt.get("title") or tm.get("title") or al.get("title") or f"Episode {num}"
        ep_desc = kt.get("description") or tm.get("description")
        ep_thumb = al.get("thumbnail") or kt.get("thumbnail") or tm.get("thumbnail")
        ep_air = jk.get("aired") or kt.get("airdate") or tm.get("airdate")

        merged[num] = {
            "episode_number": num,
            "title": ep_title,
            "description": ep_desc,
            "thumbnail": ep_thumb,
            "airdate": ep_air,
            "is_filler": jk.get("filler", False)
        }

    if format_type == "json":
        print(json.dumps({"mal_id": mal_id, "banner": anilist_data["banner"], "episodes": merged}, indent=2))
    else:
        print(f"\n✅ Merged Metadata for {len(merged)} Episodes:")
        print("=" * 110)
        print(f"{'Ep':<5} | {'Title':<35} | {'Thumbnail URL':<45} | {'Air Date'}")
        print("-" * 110)
        for num, data in merged.items():
            t_str = (data['title'][:32] + '...') if data['title'] and len(data['title']) > 35 else (data['title'] or '')
            thumb_str = (data['thumbnail'][:42] + '...') if data['thumbnail'] and len(data['thumbnail']) > 45 else (data['thumbnail'] or 'None')
            air_str = data['airdate'] or 'N/A'
            print(f"{num:<5} | {t_str:<35} | {thumb_str:<45} | {air_str}")
        print("=" * 110)


def main():
    import os
    parser = argparse.ArgumentParser(description="Fetch and merge anime episode metadata from external APIs.")
    parser.add_argument("--mal-id", required=True, help="MyAnimeList Anime ID")
    parser.add_argument("--title", help="Anime title for TMDB search fallback")
    parser.add_argument("--tmdb-key", default="", help="TMDB API key (or set TMDB_API_KEY env var)")
    parser.add_argument("--format", choices=["table", "json"], default="table", help="Output format")
    parser.add_argument("--max-pages", type=int, default=10, help="Max pages to fetch from paginated APIs (default: 10)")
    args = parser.parse_args()
    # TMDB key: prefer CLI arg, then env var
    tmdb_key = args.tmdb_key or os.environ.get("TMDB_API_KEY", "")
    run_fetch_metadata(args.mal_id, args.title, tmdb_key, args.format, args.max_pages)


if __name__ == "__main__":
    main()
