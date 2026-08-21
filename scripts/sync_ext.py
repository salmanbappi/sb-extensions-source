#!/usr/bin/env python3
"""
Upstream Extension Synchronization Module for Aniyomi Extensions (`src/`)
Fetches, checks, diffs, and updates extension modules from upstream repositories
(e.g., yuzono/anime-extensions, keiyoushi/extensions-source, aniyomiorg/anime-extensions).
"""

import argparse
import difflib
import json
import re
import sys
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional, Tuple

UPSTREAM_REPOS = {
    "yuzono": [
        "https://raw.githubusercontent.com/yuzono/anime-extensions/master",
        "https://raw.githubusercontent.com/yuzono/anime-extensions/main",
        "https://raw.githubusercontent.com/yuzono/aniyomi-extensions/master",
    ],
    "keiyoushi": [
        "https://raw.githubusercontent.com/keiyoushi/extensions-source/main",
        "https://raw.githubusercontent.com/keiyoushi/extensions-source/master",
    ],
    "cursedyomi": [
        "https://raw.githubusercontent.com/Claudemirovsky/cursedyomi-extensions/master",
        "https://raw.githubusercontent.com/Claudemirovsky/cursedyomi-extensions/main",
    ],
    "aniyomiorg": [
        "https://raw.githubusercontent.com/aniyomiorg/anime-extensions/master",
        "https://raw.githubusercontent.com/aniyomiorg/anime-extensions/main",
    ],
}

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

def fetch_raw_file(url: str) -> Optional[str]:
    """Fetches text content from a raw GitHub URL."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                return resp.read().decode("utf-8", errors="ignore")
    except Exception:
        pass
    return None

def get_upstream_file_content(upstream_key: str, lang: str, ext_name: str, rel_path: str) -> Optional[str]:
    """Fetches a specific file from the specified upstream repository."""
    candidate_bases = UPSTREAM_REPOS.get(upstream_key, UPSTREAM_REPOS["yuzono"])
    for base in candidate_bases:
        url = f"{base}/src/{lang}/{ext_name}/{rel_path}"
        content = fetch_raw_file(url)
        if content is not None:
            return content
    return None

def fetch_upstream_dir_listing(upstream_key: str, lang: str, ext_name: str) -> List[str]:
    """Attempts to discover files for an extension via GitHub API or known conventional paths."""
    repo_map = {
        "yuzono": "yuzono/anime-extensions",
        "keiyoushi": "keiyoushi/extensions-source",
        "cursedyomi": "Claudemirovsky/cursedyomi-extensions",
        "aniyomiorg": "aniyomiorg/anime-extensions",
    }
    repo_slug = repo_map.get(upstream_key, "yuzono/anime-extensions")
    api_url = f"https://api.github.com/repos/{repo_slug}/git/trees/master?recursive=1"

    found_files = []
    try:
        req = urllib.request.Request(api_url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode("utf-8"))
                prefix = f"src/{lang}/{ext_name}/"
                for item in data.get("tree", []):
                    path = item.get("path", "")
                    if path.startswith(prefix) and item.get("type") == "blob":
                        found_files.append(path[len(prefix):])
    except Exception:
        pass

    if not found_files:
        # Fallback to standard conventions
        capitalized = ext_name.capitalize()
        found_files = [
            "build.gradle",
            f"src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/{capitalized}.kt",
            f"src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/{capitalized}Dto.kt",
            f"src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/{capitalized}Filters.kt",
            f"src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/{capitalized}Queries.kt",
            f"src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/{capitalized}Bundle.kt",
            f"src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/{capitalized}Crypto.kt",
            f"src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/{capitalized}KeyManager.kt",
            f"src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/extractors/{capitalized}Extractor.kt",
        ]

    return found_files

BINARY_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".ico", ".jar", ".zip"}

def diff_extension(repo_root: Path, lang: str, ext_name: str, upstream_key: str = "yuzono") -> bool:
    """Compares local extension files against upstream."""
    ext_dir = repo_root / "src" / lang / ext_name
    if not ext_dir.exists():
        print(f"❌ Local extension directory not found: src/{lang}/{ext_name}")
        return False

    print(f"🔍 Checking upstream [{upstream_key}] for src/{lang}/{ext_name}...")
    upstream_files = fetch_upstream_dir_listing(upstream_key, lang, ext_name)

    diffs_found = 0
    for rel_path in upstream_files:
        if any(rel_path.lower().endswith(ext) for ext in BINARY_EXTENSIONS):
            continue

        local_path = ext_dir / rel_path
        # Also check case-insensitive match (e.g. MKissa.kt vs Mkissa.kt)
        if not local_path.exists():
            parent_dir = local_path.parent
            if parent_dir.exists():
                for sibling in parent_dir.iterdir():
                    if sibling.name.lower() == local_path.name.lower():
                        local_path = sibling
                        break

        upstream_content = get_upstream_file_content(upstream_key, lang, ext_name, rel_path)
        if upstream_content is None:
            continue

        if not local_path.exists():
            print(f"  ➕ Upstream has new file: {rel_path}")
            diffs_found += 1
            continue

        local_content = local_path.read_text(encoding="utf-8", errors="ignore")
        if local_content.strip() != upstream_content.strip():
            diffs_found += 1
            print(f"  📝 Diff found in {rel_path}:")
            diff = difflib.unified_diff(
                local_content.splitlines(),
                upstream_content.splitlines(),
                fromfile=f"local/{rel_path}",
                tofile=f"upstream/{rel_path}",
                lineterm="",
                n=2
            )
            for line in list(diff)[:15]:
                print(f"    {line}")
            print()

    if diffs_found == 0:
        print(f"✨ src/{lang}/{ext_name} is fully in sync with upstream [{upstream_key}].")
    else:
        print(f"⚠️ Total differences found: {diffs_found}")
    return diffs_found > 0

def sync_extension(repo_root: Path, lang: str, ext_name: str, upstream_key: str = "yuzono", dry_run: bool = False) -> bool:
    """Syncs and updates an extension from upstream."""
    ext_dir = repo_root / "src" / lang / ext_name
    if not ext_dir.exists():
        print(f"❌ Local extension directory not found: src/{lang}/{ext_name}")
        return False

    print(f"🚀 Syncing src/{lang}/{ext_name} from upstream [{upstream_key}]...")
    upstream_files = fetch_upstream_dir_listing(upstream_key, lang, ext_name)

    updated_count = 0
    for rel_path in upstream_files:
        upstream_content = get_upstream_file_content(upstream_key, lang, ext_name, rel_path)
        if upstream_content is None:
            continue

        local_path = ext_dir / rel_path
        if local_path.exists():
            local_content = local_path.read_text(encoding="utf-8", errors="ignore")
            if local_content.strip() == upstream_content.strip():
                continue

        if dry_run:
            print(f"  [Dry-Run] Would update {rel_path}")
            updated_count += 1
        else:
            local_path.parent.mkdir(parents=True, exist_ok=True)
            local_path.write_text(upstream_content, encoding="utf-8")
            print(f"  ✅ Updated: {rel_path}")
            updated_count += 1

    print(f"\n🎉 Sync complete: {updated_count} file(s) updated.")
    return True

def main():
    parser = argparse.ArgumentParser(description="Synchronize extension source code from upstream repositories.")
    parser.add_argument("target", help="Target extension (e.g. <module> or <lang>/<module>)")
    parser.add_argument("--upstream", choices=list(UPSTREAM_REPOS.keys()), default="yuzono", help="Upstream repository source (default: yuzono)")
    parser.add_argument("--check", "--diff", dest="diff_only", action="store_true", help="Check and show diffs without applying changes")
    parser.add_argument("--dry-run", action="store_true", help="Preview changes without modifying files")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    target = args.target
    if "/" in target:
        lang, name = target.split("/", 1)
    else:
        lang, name = "en", target

    if args.diff_only:
        diff_extension(repo_root, lang, name, args.upstream)
    else:
        sync_extension(repo_root, lang, name, args.upstream, dry_run=args.dry_run)

if __name__ == "__main__":
    main()
