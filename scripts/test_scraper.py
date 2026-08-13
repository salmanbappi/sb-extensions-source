#!/usr/bin/env python3
"""
Lightweight Local Scraper & Endpoint Test Suite Tool (Zero external dependencies)
Fetch target URLs, test regex/HTML selectors, JSON API payloads, and validate full Aniyomi source endpoints.
"""

import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from html.parser import HTMLParser


class SimpleSelectorParser(HTMLParser):
    def __init__(self, tag_name=None, class_name=None, id_name=None):
        super().__init__()
        self.tag_name = tag_name.lower() if tag_name else None
        self.class_name = class_name
        self.id_name = id_name
        self.matches = []
        self._current_match = None

    def handle_starttag(self, tag, attrs):
        attr_dict = dict(attrs)
        tag_match = (self.tag_name is None) or (tag.lower() == self.tag_name)

        class_match = True
        if self.class_name:
            classes = attr_dict.get('class', '').split()
            class_match = self.class_name in classes

        id_match = True
        if self.id_name:
            id_match = attr_dict.get('id') == self.id_name

        if tag_match and class_match and id_match:
            self._current_match = {
                'tag': tag,
                'attrs': attr_dict,
                'text': ''
            }
            self.matches.append(self._current_match)

    def handle_data(self, data):
        if self._current_match and data.strip():
            self._current_match['text'] += data.strip() + ' '


def fetch_url(url: str, user_agent: str = None, referer: str = None, headers: dict = None) -> tuple[int, str, dict]:
    req_headers = {
        'User-Agent': user_agent or 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.5',
    }
    if referer:
        req_headers['Referer'] = referer
    if headers:
        req_headers.update(headers)

    req = urllib.request.Request(url, headers=req_headers)
    try:
        start_t = time.time()
        with urllib.request.urlopen(req, timeout=15) as response:
            status = response.status
            content = response.read().decode('utf-8', errors='ignore')
            resp_headers = dict(response.info())
            latency = round(time.time() - start_t, 2)
            resp_headers['X-Latency'] = str(latency)
            return status, content, resp_headers
    except urllib.error.HTTPError as e:
        content = e.read().decode('utf-8', errors='ignore') if e.fp else ""
        return e.code, content, dict(e.headers)
    except Exception as e:
        print(f"Error fetching URL: {e}", file=sys.stderr)
        return 0, str(e), {}


def test_endpoint_pipeline(target_url: str, endpoint: str):
    print(f"\n🧪 Running Aniyomi Endpoint Test Suite for: {target_url}\n" + "=" * 60)
    domain = target_url.split("//")[-1].split("/")[0]
    base_url = f"https://{domain}"

    # 1. Test Popular / Homepage
    if endpoint in ['popular', 'all']:
        print("\n1. Testing Popular / Homepage Endpoint...")
        status, html, headers = fetch_url(base_url)
        print(f"   • Status: {status} | Latency: {headers.get('X-Latency', '0')}s | Length: {len(html)} bytes")
        cards = re.findall(r'<article[^>]+>(.*?)</article>', html, re.DOTALL)
        if not cards:
            cards = re.findall(r'<a[^>]+href=\"([^\"]+)\"[^>]*>.*?<img[^>]+src=\"([^\"]+)\"', html, re.DOTALL)
        print(f"   • Found {len(cards)} post cards / items.")
        if status == 200 and len(cards) > 0:
            print("   ✓ Popular Endpoint: PASS")
        else:
            print("   ⚠️ Popular Endpoint: WARN (No cards or non-200 status)")

    # 2. Test Search Endpoint
    if endpoint in ['search', 'all']:
        search_query = "a"
        search_url = f"{base_url}/?s={search_query}"
        print(f"\n2. Testing Search Endpoint ({search_url})...")
        status, html, headers = fetch_url(search_url)
        print(f"   • Status: {status} | Latency: {headers.get('X-Latency', '0')}s | Length: {len(html)} bytes")
        results = re.findall(r'<a[^>]+href=\"([^\"]+)\"', html)
        print(f"   • Found {len(results)} search link results.")
        if status == 200 and len(results) > 0:
            print("   ✓ Search Endpoint: PASS")
        else:
            print("   ⚠️ Search Endpoint: WARN")

    # 3. Test Details & Episodes Endpoint
    if endpoint in ['details', 'episodes', 'all']:
        print(f"\n3. Testing Detail & Episode Extraction on Target: {target_url}...")
        status, html, headers = fetch_url(target_url)
        print(f"   • Status: {status} | Latency: {headers.get('X-Latency', '0')}s | Length: {len(html)} bytes")

        title_m = re.search(r'<h[1-3][^>]*>(.*?)</h[1-3]>', html, re.DOTALL)
        title = re.sub(r'<[^>]+>', '', title_m.group(1)).strip() if title_m else "Unknown"
        print(f"   • Title: {title}")

        # Download / Episode links
        dwd_links = re.findall(r'<a[^>]+href=\"([^\"]*nexdrive[^\"]*|[^\"]*vcloud[^\"]*|[^\"]*fast-dl[^\"]*|[^\"]*vgmlinks[^\"]*)\"[^>]*>(.*?)</a>', html, re.DOTALL | re.IGNORECASE)
        print(f"   • Found {len(dwd_links)} download/episode link(s).")
        for l_url, l_html in dwd_links[:3]:
            l_text = re.sub(r'<[^>]+>', ' ', l_html).strip()
            print(f"     - {l_text[:40]} -> {l_url[:60]}")

        if status == 200:
            print("   ✓ Detail & Episode Endpoint: PASS")
        else:
            print("   ⚠️ Detail Endpoint: WARN")


def main():
    parser = argparse.ArgumentParser(description="Local Scraper & Endpoint Test Tool")
    parser.add_argument("--url", required=True, help="Target URL to fetch or test")
    parser.add_argument("--endpoint", choices=['popular', 'latest', 'search', 'details', 'episodes', 'videos', 'all'], help="Run endpoint test suite")
    parser.add_argument("--user-agent", help="Custom User-Agent header")
    parser.add_argument("--referer", help="Custom Referer header")
    parser.add_argument("--regex", help="Regex pattern to test against response text")
    parser.add_argument("--selector", help="Simple selector e.g. 'div.title', 'a', '#player'")
    parser.add_argument("--is-json", action="store_true", help="Parse output as JSON and format")

    args = parser.parse_args()

    if args.endpoint:
        test_endpoint_pipeline(args.url, args.endpoint)
        return

    print(f"Fetching URL: {args.url} ...")
    status, body, headers = fetch_url(args.url, user_agent=args.user_agent, referer=args.referer)

    print(f"Status Code: {status}")
    print(f"Content Length: {len(body)} bytes\n")

    if args.is_json:
        try:
            data = json.loads(body)
            print("--- Formatted JSON Output ---")
            print(json.dumps(data, indent=2)[:2000])
            if len(json.dumps(data)) > 2000:
                print("\n... (truncated output)")
        except Exception as e:
            print(f"Failed to parse body as JSON: {e}")
            print(body[:1000])
        return

    if args.regex:
        print(f"--- Testing Regex: '{args.regex}' ---")
        matches = re.findall(args.regex, body)
        print(f"Found {len(matches)} match(es):")
        for i, match in enumerate(matches[:20], 1):
            print(f" [{i}] {match}")
        if len(matches) > 20:
            print(f" ... and {len(matches) - 20} more.")
        return

    if args.selector:
        print(f"--- Testing Selector: '{args.selector}' ---")
        tag, class_name, id_name = None, None, None

        sel = args.selector.strip()
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

        parser = SimpleSelectorParser(tag_name=tag, class_name=class_name, id_name=id_name)
        parser.feed(body)

        print(f"Found {len(parser.matches)} matching element(s):")
        for i, m in enumerate(parser.matches[:10], 1):
            text = m['text'].strip()[:100]
            attrs = m['attrs']
            print(f" [{i}] Tag: <{m['tag']}> | Text: '{text}' | Attrs: {attrs}")
        if len(parser.matches) > 10:
            print(f" ... and {len(parser.matches) - 10} more.")
        return

    print("--- First 500 characters of Body ---")
    print(body[:500])


if __name__ == "__main__":
    main()
