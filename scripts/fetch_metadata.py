#!/usr/bin/env python3
"""
Metadata API Fetcher & Tester Module for Aniyomi Extensions.
Queries Jikan v4, AniList GraphQL, Kitsu, TMDB, and OMDb to enrich anime episode metadata.
Includes exponential backoff, multi-season TMDB queries, rich AniList metadata, and payload schema validation.
"""

import argparse
import json
import math
import os
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Tuple, Union

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
RETRYABLE_STATUS_CODES = {429, 500, 502, 503, 504}


# ==============================================================================
# Network Utilities with Exponential Backoff & Retry Handling
# ==============================================================================

def http_request_with_retry(
    url: str,
    method: str = "GET",
    headers: Optional[Dict[str, str]] = None,
    data: Optional[bytes] = None,
    max_retries: int = 4,
    initial_delay: float = 1.0,
    backoff_factor: float = 2.0,
    timeout: float = 12.0
) -> Tuple[int, Optional[Union[Dict[str, Any], List[Any]]], Dict[str, str]]:
    """
    Executes an HTTP request with exponential backoff and jitter on 429 and 5xx errors.
    Respects HTTP 'Retry-After' headers when provided by the remote server.
    """
    req_headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/json"
    }
    if headers:
        req_headers.update(headers)

    req = urllib.request.Request(url, data=data, headers=req_headers, method=method.upper())

    last_status = 0
    last_resp_headers = {}

    for attempt in range(max_retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                status = resp.status
                resp_headers = dict(resp.info())
                raw_body = resp.read().decode("utf-8", errors="replace")
                try:
                    parsed_json = json.loads(raw_body)
                except Exception:
                    parsed_json = None
                return status, parsed_json, resp_headers

        except urllib.error.HTTPError as e:
            last_status = e.code
            last_resp_headers = dict(e.headers)

            if e.code in RETRYABLE_STATUS_CODES and attempt < max_retries:
                # Check for Retry-After header
                retry_after = e.headers.get("Retry-After")
                if retry_after:
                    try:
                        sleep_time = float(retry_after)
                    except ValueError:
                        sleep_time = initial_delay * (backoff_factor ** attempt)
                else:
                    jitter = random.uniform(0.1, 0.5)
                    sleep_time = (initial_delay * (backoff_factor ** attempt)) + jitter

                print(f"  [!] HTTP {e.code} for {url[:65]}... Retrying in {sleep_time:.2f}s (Attempt {attempt + 1}/{max_retries})", file=sys.stderr)
                time.sleep(sleep_time)
                continue
            else:
                raw_body = e.read().decode("utf-8", errors="replace") if e.fp else ""
                try:
                    parsed_json = json.loads(raw_body)
                except Exception:
                    parsed_json = None
                return last_status, parsed_json, last_resp_headers

        except (urllib.error.URLError, TimeoutError, ConnectionResetError) as e:
            if attempt < max_retries:
                jitter = random.uniform(0.1, 0.5)
                sleep_time = (initial_delay * (backoff_factor ** attempt)) + jitter
                print(f"  [!] Network error ({e}) for {url[:65]}... Retrying in {sleep_time:.2f}s", file=sys.stderr)
                time.sleep(sleep_time)
                continue
            else:
                print(f"  [!] HTTP request failed after {max_retries} retries: {e}", file=sys.stderr)
                return 0, None, {}

    return last_status, None, last_resp_headers


def http_get_json(url: str, headers: Optional[Dict[str, str]] = None, max_retries: int = 3) -> Optional[Dict[str, Any]]:
    status, payload, _ = http_request_with_retry(url, method="GET", headers=headers, max_retries=max_retries)
    return payload if isinstance(payload, dict) else None


def http_post_json(url: str, payload: Dict[str, Any], headers: Optional[Dict[str, str]] = None, max_retries: int = 3) -> Optional[Dict[str, Any]]:
    post_headers = {"Content-Type": "application/json"}
    if headers:
        post_headers.update(headers)
    data = json.dumps(payload).encode("utf-8")
    status, res, _ = http_request_with_retry(url, method="POST", headers=post_headers, data=data, max_retries=max_retries)
    return res if isinstance(res, dict) else None


# ==============================================================================
# Lightweight JSON Schema / Format Validator
# ==============================================================================

class SchemaValidator:
    """Zero-dependency JSON structure and type validation."""

    @staticmethod
    def validate(data: Any, schema: Dict[str, Any], path: str = "$") -> Tuple[bool, List[str]]:
        errors = []

        if not isinstance(data, dict):
            return False, [f"{path}: expected dict, got {type(data).__name__}"]

        for key, spec in schema.items():
            current_path = f"{path}.{key}"
            expected_type = spec.get("type")
            required = spec.get("required", False)

            if key not in data or data[key] is None:
                if required:
                    errors.append(f"Missing required field '{current_path}'")
                continue

            val = data[key]
            if expected_type:
                if isinstance(expected_type, list):
                    if not any(isinstance(val, t) for t in expected_type):
                        errors.append(f"{current_path}: expected one of {[t.__name__ for t in expected_type]}, got {type(val).__name__}")
                elif not isinstance(val, expected_type):
                    errors.append(f"{current_path}: expected {expected_type.__name__}, got {type(val).__name__}")

            if "schema" in spec and isinstance(val, dict):
                sub_valid, sub_errors = SchemaValidator.validate(val, spec["schema"], current_path)
                errors.extend(sub_errors)
            elif "item_schema" in spec and isinstance(val, list):
                for idx, item in enumerate(val):
                    if isinstance(item, dict):
                        sub_valid, sub_errors = SchemaValidator.validate(item, spec["item_schema"], f"{current_path}[{idx}]")
                        errors.extend(sub_errors)

        return len(errors) == 0, errors


JIKAN_EPISODES_SCHEMA = {
    "data": {"type": list, "required": True, "item_schema": {
        "mal_id": {"type": (int, float), "required": True},
        "title": {"type": (str, type(None))},
        "aired": {"type": (str, type(None))},
        "filler": {"type": bool}
    }},
    "pagination": {"type": dict, "required": False}
}

ANILIST_RESPONSE_SCHEMA = {
    "data": {"type": dict, "required": True, "schema": {
        "Media": {"type": (dict, type(None)), "required": True}
    }}
}


# ==============================================================================
# Provider Fetchers
# ==============================================================================

def fetch_jikan_episodes(mal_id: str, max_pages: int = 10) -> Dict[int, Dict[str, Any]]:
    """Fetch episode metadata from Jikan REST API (MyAnimeList)."""
    episodes = {}
    page = 1
    while page <= max_pages:
        url = f"https://api.jikan.moe/v4/anime/{mal_id}/episodes?page={page}"
        res = http_get_json(url)
        if not res or "data" not in res:
            break

        is_valid, validation_errors = SchemaValidator.validate(res, JIKAN_EPISODES_SCHEMA)
        if not is_valid:
            print(f"  [!] Jikan schema warning (Page {page}): {', '.join(validation_errors[:2])}", file=sys.stderr)

        for ep in res.get("data", []):
            num = ep.get("mal_id")
            if num is not None:
                episodes[int(num)] = {
                    "title": ep.get("title"),
                    "aired": ep.get("aired"),
                    "filler": ep.get("filler", False)
                }

        pagination = res.get("pagination", {})
        if not pagination.get("has_next_page", False):
            break
        page += 1
        time.sleep(0.35)  # Rate limiting courtesy pause

    if page > max_pages:
        print(f"  [!] Jikan: reached max_pages limit ({max_pages}). Some episodes may be missing.", file=sys.stderr)
    return episodes


def fetch_anilist_metadata(mal_id: str) -> Dict[str, Any]:
    """
    Fetch banner image, cover image, streaming provider episodes, and official links from AniList GraphQL.
    """
    query = """
    query ($idMal: Int) {
      Media(idMal: $idMal, type: ANIME) {
        id
        idMal
        title {
          romaji
          english
          native
        }
        bannerImage
        coverImage {
          extraLarge
          large
          medium
        }
        status
        episodes
        genres
        siteUrl
        streamingEpisodes {
          title
          thumbnail
          url
          site
        }
        externalLinks {
          id
          site
          url
          type
          language
        }
      }
    }
    """
    try:
        mal_id_int = int(mal_id)
    except ValueError:
        return {"streaming_episodes": {}, "banner": None, "cover": None, "external_links": [], "titles": {}}

    res = http_post_json("https://graphql.anilist.co", {"query": query, "variables": {"idMal": mal_id_int}})

    result = {
        "streaming_episodes": {},
        "banner": None,
        "cover": None,
        "external_links": [],
        "titles": {},
        "site_url": None,
        "genres": [],
        "status": None
    }

    if not res:
        return result

    is_valid, errors = SchemaValidator.validate(res, ANILIST_RESPONSE_SCHEMA)
    if not is_valid:
        print(f"  [!] AniList schema warning: {', '.join(errors)}", file=sys.stderr)

    media = (res.get("data") or {}).get("Media")
    if not media:
        return result

    result["banner"] = media.get("bannerImage")
    cover_obj = media.get("coverImage") or {}
    result["cover"] = cover_obj.get("extraLarge") or cover_obj.get("large")
    if not result["banner"]:
        result["banner"] = result["cover"]  # Fallback to high-res cover art

    result["titles"] = media.get("title") or {}
    result["site_url"] = media.get("siteUrl")
    result["genres"] = media.get("genres") or []
    result["status"] = media.get("status")

    # Streaming links & episode details
    streams = media.get("streamingEpisodes") or []
    for idx, stream in enumerate(streams, start=1):
        title_str = stream.get("title") or ""
        ep_match = re.search(r"Episode\s+(\d+)", title_str, re.IGNORECASE)
        ep_num = int(ep_match.group(1)) if ep_match else idx

        result["streaming_episodes"][ep_num] = {
            "title": stream.get("title"),
            "thumbnail": stream.get("thumbnail"),
            "url": stream.get("url"),
            "site": stream.get("site")
        }

    # External streaming platforms
    ext_links = media.get("externalLinks") or []
    for link in ext_links:
        if link.get("url"):
            result["external_links"].append({
                "site": link.get("site"),
                "url": link.get("url"),
                "type": link.get("type"),
                "language": link.get("language")
            })

    return result


def fetch_kitsu_episodes(mal_id: str, max_pages: int = 10) -> Dict[int, Dict[str, Any]]:
    """Fetch episode descriptions and thumbnails from Kitsu API."""
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
        next_url = (ep_res.get("links") or {}).get("next")

    if pages >= max_pages and next_url:
        print(f"  [!] Kitsu: reached max_pages limit ({max_pages}). Some episodes may be missing.", file=sys.stderr)
    return episodes


def parse_seasons_arg(season_str: str) -> List[int]:
    """Parses season arguments like '1', '1,2,3', '1-4' into a list of integers."""
    seasons = set()
    for part in season_str.split(","):
        part = part.strip()
        if "-" in part:
            bounds = part.split("-", 1)
            if bounds[0].isdigit() and bounds[1].isdigit():
                start, end = int(bounds[0]), int(bounds[1])
                for s in range(min(start, end), max(start, end) + 1):
                    seasons.add(s)
        elif part.isdigit():
            seasons.add(int(part))
    return sorted(list(seasons)) or [1]


def fetch_tmdb_episodes(title: str, api_key: str, seasons_spec: str = "1") -> Dict[int, Dict[str, Any]]:
    """
    Fetch TV metadata and episode descriptions from TMDB API with multi-season support.
    Supports single season ('1'), multiple ('1,2,3'), ranges ('1-3'), or 'all'.
    """
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

    target_seasons: List[int] = []
    if seasons_spec.lower() == "all":
        tv_url = f"https://api.themoviedb.org/3/tv/{best_id}?api_key={api_key}&language=en-US"
        tv_info = http_get_json(tv_url)
        if tv_info and "seasons" in tv_info:
            target_seasons = [s["season_number"] for s in tv_info["seasons"] if s.get("season_number", 0) > 0]
        if not target_seasons:
            target_seasons = [1]
    else:
        target_seasons = parse_seasons_arg(seasons_spec)

    episodes = {}
    running_ep_counter = 1

    for season_num in target_seasons:
        season_url = f"https://api.themoviedb.org/3/tv/{best_id}/season/{season_num}?api_key={api_key}&language=en-US"
        season_res = http_get_json(season_url)
        if not season_res or "episodes" not in season_res:
            continue

        for ep in season_res["episodes"]:
            ep_num = ep.get("episode_number") or running_ep_counter
            still_path = ep.get("still_path")
            thumb = f"https://image.tmdb.org/t/p/w500{still_path}" if still_path else None

            index_key = ep_num if len(target_seasons) == 1 else running_ep_counter
            episodes[index_key] = {
                "season": season_num,
                "season_episode": ep_num,
                "title": ep.get("name"),
                "description": ep.get("overview"),
                "thumbnail": thumb,
                "airdate": ep.get("air_date"),
                "vote_average": ep.get("vote_average")
            }
            running_ep_counter += 1

    return episodes


# ==============================================================================
# Main Orchestrator
# ==============================================================================

def run_fetch_metadata(
    mal_id: str,
    title: Optional[str] = None,
    tmdb_key: str = "",
    season: str = "1",
    format_type: str = "table",
    max_pages: int = 10
):
    """Main CLI orchestrator for fetching & merging episode metadata across all sources."""
    print(f"🚀 Fetching metadata for MAL ID: {mal_id} (Title: {title or 'N/A'}, TMDB Season(s): {season})...")

    jikan_eps = fetch_jikan_episodes(mal_id, max_pages) if mal_id else {}
    anilist_data = fetch_anilist_metadata(mal_id) if mal_id else {
        "streaming_episodes": {}, "banner": None, "cover": None, "external_links": [], "titles": {}
    }
    anilist_eps = anilist_data["streaming_episodes"]
    kitsu_eps = fetch_kitsu_episodes(mal_id, max_pages) if mal_id else {}
    tmdb_eps = fetch_tmdb_episodes(title, tmdb_key, season) if title and tmdb_key else {}

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
        ep_stream_url = al.get("url")

        merged[num] = {
            "episode_number": num,
            "title": ep_title,
            "description": ep_desc,
            "thumbnail": ep_thumb,
            "airdate": ep_air,
            "streaming_url": ep_stream_url,
            "streaming_site": al.get("site"),
            "season": tm.get("season", 1),
            "is_filler": jk.get("filler", False)
        }

    output_payload = {
        "mal_id": mal_id,
        "titles": anilist_data.get("titles"),
        "banner_url": anilist_data.get("banner"),
        "cover_url": anilist_data.get("cover"),
        "anilist_url": anilist_data.get("site_url"),
        "external_streaming_links": anilist_data.get("external_links"),
        "total_episodes": len(merged),
        "episodes": merged
    }

    if format_type == "json":
        print(json.dumps(output_payload, indent=2))
    else:
        print(f"\n🎨 Media Metadata:")
        print(f"   • English: {anilist_data.get('titles', {}).get('english') or 'N/A'}")
        print(f"   • Romaji:  {anilist_data.get('titles', {}).get('romaji') or 'N/A'}")
        print(f"   • Banner:  {anilist_data.get('banner') or 'None'}")
        print(f"   • Cover:   {anilist_data.get('cover') or 'None'}")
        if anilist_data.get("external_links"):
            streams = ", ".join([l['site'] for l in anilist_data['external_links'] if l.get('site')])
            print(f"   • Streams: {streams}")

        print(f"\n✅ Merged Metadata for {len(merged)} Episodes:")
        print("=" * 125)
        print(f"{'Ep':<5} | {'Title':<35} | {'Air Date':<12} | {'Thumbnail URL':<45} | {'Stream'}")
        print("-" * 125)
        for num, data in merged.items():
            t_str = (data['title'][:32] + '...') if data['title'] and len(data['title']) > 35 else (data['title'] or '')
            thumb_str = (data['thumbnail'][:42] + '...') if data['thumbnail'] and len(data['thumbnail']) > 45 else (data['thumbnail'] or 'None')
            air_str = (data['airdate'] or 'N/A')[:12]
            stream_str = data['streaming_site'] or ('Filler' if data['is_filler'] else 'Standard')
            print(f"{num:<5} | {t_str:<35} | {air_str:<12} | {thumb_str:<45} | {stream_str}")
        print("=" * 125)


def main():
    parser = argparse.ArgumentParser(description="Fetch and merge anime episode metadata from external APIs.")
    parser.add_argument("--mal-id", required=True, help="MyAnimeList Anime ID")
    parser.add_argument("--title", help="Anime title for TMDB search fallback")
    parser.add_argument("--tmdb-key", default="", help="TMDB API key (or set TMDB_API_KEY env var)")
    parser.add_argument("--season", default="1", help="TMDB season(s) to query (e.g. '1', '1,2,3', '1-4', or 'all')")
    parser.add_argument("--format", choices=["table", "json"], default="table", help="Output format")
    parser.add_argument("--max-pages", type=int, default=10, help="Max pages to fetch from paginated APIs (default: 10)")
    args = parser.parse_args()

    tmdb_key = args.tmdb_key or os.environ.get("TMDB_API_KEY", "")
    run_fetch_metadata(args.mal_id, args.title, tmdb_key, args.season, args.format, args.max_pages)


if __name__ == "__main__":
    main()
