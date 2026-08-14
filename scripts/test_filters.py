#!/usr/bin/env python3
"""
Combinatorial Search Filter Matrix Tester & Fuzzer
Extracts AnimeFilter definitions from Kotlin sources and tests filter query combinations against live endpoints.
"""

import argparse
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Dict, List, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent


class FilterFuzzer:
    def __init__(self, base_url: str, headers: Dict[str, str] = None):
        self.base_url = base_url.rstrip("/")
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        }
        if headers:
            self.headers.update(headers)

    def extract_filters_from_source(self, kt_dir: Path) -> List[Dict]:
        """Extracts Filter classes and values from Kotlin source files."""
        filters = []
        for kt in kt_dir.rglob("*.kt"):
            content = kt.read_text(encoding="utf-8", errors="ignore")
            # Look for UriPartFilter or Select filters
            select_matches = re.finditer(
                r'class\s+([A-Za-z0-9_]+Filter)\s*(?:\([^)]*\))?\s*:\s*UriPartFilter\(\s*["\']([^"\']+)["\']\s*,\s*arrayOf\((.*?)\)\s*\)',
                content,
                re.DOTALL
            )
            for m in select_matches:
                filter_name = m.group(1)
                display_name = m.group(2)
                pairs_str = m.group(3)
                options = re.findall(r'Pair\(\s*["\']([^"\']*)["\']\s*,\s*["\']([^"\']*)["\']\s*\)', pairs_str)
                filters.append({
                    "type": "select",
                    "name": filter_name,
                    "display": display_name,
                    "options": options
                })
        return filters

    def test_filter_permutation(self, search_path: str, params: Dict[str, str]) -> Tuple[int, int]:
        """Tests a single filter query combination against the server."""
        query_string = urllib.parse.urlencode(params)
        full_url = f"{self.base_url}{search_path}?{query_string}" if query_string else f"{self.base_url}{search_path}"

        req = urllib.request.Request(full_url, headers=self.headers)
        try:
            with urllib.request.urlopen(req, timeout=8) as resp:
                status = resp.getcode()
                body = resp.read()
                return status, len(body)
        except urllib.error.HTTPError as e:
            return e.code, 0
        except Exception:
            return 0, 0


def main():
    parser = argparse.ArgumentParser(description="Combinatorial Search Filter Matrix Tester & Fuzzer")
    parser.add_argument("target", help="Target extension name (e.g. 'animestream' or 'en/animestream')")
    parser.add_argument("--search-path", default="/search", help="Search endpoint path (default: /search)")

    args = parser.parse_args()

    # Resolve extension
    src_dir = REPO_ROOT / "src"
    target_dir = None
    if "/" in args.target:
        target_dir = src_dir / args.target
    else:
        for l_dir in sorted(src_dir.iterdir()):
            if l_dir.is_dir() and (l_dir / args.target).exists():
                target_dir = l_dir / args.target
                break

    if not target_dir or not target_dir.exists():
        print(f"❌ Target extension '{args.target}' not found in src/.")
        sys.exit(1)

    # Extract base URL
    base_url = None
    for kt in target_dir.rglob("*.kt"):
        content = kt.read_text(encoding="utf-8", errors="ignore")
        m = re.search(r'(?:PREF_BASE_URL_DEFAULT|PREF_DOMAIN_DEFAULT|DOMAIN(?:_DEFAULT)?)\s*=\s*["\']([^"\']+)["\']', content)
        if not m:
            m = re.search(r'override\s+val\s+baseUrl\s*=\s*["\']([^"\']+)["\']', content)
        if m:
            base_url = m.group(1)
            break

    if not base_url:
        # Check build.gradle
        gradle_file = target_dir / "build.gradle"
        if gradle_file.exists():
            gm = re.search(r'baseUrl\s*=\s*["\']([^"\']+)["\']', gradle_file.read_text(encoding="utf-8"))
            if gm:
                base_url = gm.group(1)

    if not base_url:
        print("❌ Could not extract baseUrl from extension.")
        sys.exit(1)

    print(f"🧪 Testing Search Filters for: {target_dir.name} ({base_url})\n" + "=" * 60)
    fuzzer = FilterFuzzer(base_url)
    filters = fuzzer.extract_filters_from_source(target_dir / "src")

    if not filters:
        print("ℹ️ No dynamic UriPartFilter classes found in module.")
        # Test base search
        status, length = fuzzer.test_filter_permutation(args.search_path, {"q": "test"})
        print(f"  • Base Search Test: HTTP {status} ({length} bytes)")
        sys.exit(0)

    print(f"📦 Discovered {len(filters)} filter group(s):")
    for f in filters:
        print(f"  • {f['display']} ({len(f['options'])} options)")

    print("\n🚀 Fuzzing sample permutations against endpoint...")
    for f in filters:
        for opt_name, opt_val in f["options"][:3]:  # Test first 3 options of each filter
            if not opt_val:
                continue
            status, length = fuzzer.test_filter_permutation(args.search_path, {f['display'].lower(): opt_val})
            status_emoji = "✅" if status == 200 else "⚠️"
            print(f"  {status_emoji} Filter [{f['display']} = {opt_name}] -> HTTP {status} ({length} bytes)")

    print("\n✅ Filter fuzz testing completed successfully.")


if __name__ == "__main__":
    main()
