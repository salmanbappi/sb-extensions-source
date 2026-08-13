#!/usr/bin/env python3
"""
Upstream Library Synchronization Module for Aniyomi Extractor Libraries (`lib/`)
Fetches, checks, diffs, and updates shared extractor modules from upstream repositories
(e.g., aniyomiorg/anime-extensions, keiyoushi/extensions-source).
"""

import argparse
import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional

UPSTREAM_REPOS = {
    "cursedyomi": [
        "https://raw.githubusercontent.com/Claudemirovsky/cursedyomi-extensions/master",
        "https://raw.githubusercontent.com/Claudemirovsky/cursedyomi-extensions/main"
    ],
    "keiyoushi": [
        "https://raw.githubusercontent.com/keiyoushi/extensions-source/main",
        "https://raw.githubusercontent.com/keiyoushi/extensions-source/master"
    ],
    "yuzono": [
        "https://raw.githubusercontent.com/yuzono/anime-extensions/main",
        "https://raw.githubusercontent.com/yuzono/anime-extensions/master",
        "https://raw.githubusercontent.com/Claudemirovsky/cursedyomi-extensions/master"
    ],
    "aniyomiorg": [
        "https://raw.githubusercontent.com/aniyomiorg/anime-extensions/master",
        "https://raw.githubusercontent.com/aniyomiorg/anime-extensions/main",
        "https://raw.githubusercontent.com/Claudemirovsky/cursedyomi-extensions/master"
    ]
}


USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


def fetch_raw_file(url: str) -> Optional[str]:
    """Fetches text content from raw GitHub URL."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                return resp.read().decode("utf-8", errors="ignore")
    except Exception:
        pass
    return None


def fetch_upstream_file(upstream_key: str, module_name: str, rel_path: Path) -> Optional[str]:
    candidate_bases = UPSTREAM_REPOS.get(upstream_key, UPSTREAM_REPOS["keiyoushi"])
    rel_str = str(rel_path)
    rel_kt = rel_str.replace("src/main/java/", "src/main/kotlin/")
    rel_java = rel_str.replace("src/main/kotlin/", "src/main/java/")

    subpath_templates = [
        f"lib/{module_name}/{rel_str}",
        f"lib/{module_name}/{rel_kt}",
        f"lib/{module_name}/{rel_java}",
    ]
    for base in candidate_bases:
        for subpath in subpath_templates:
            url = f"{base}/{subpath}"
            content = fetch_raw_file(url)
            if content:
                return content
    return None





def get_local_extractors(repo_root: Path) -> Dict[str, Path]:
    """Returns dictionary of extractor module name -> module directory Path."""
    lib_dir = repo_root / "lib"
    if not lib_dir.exists():
        return {}
    return {d.name: d for d in lib_dir.iterdir() if d.is_dir()}


def check_module_updates(repo_root: Path, module_name: str, upstream_key: str = "keiyoushi") -> bool:
    """Checks if a local extractor module differs from upstream."""
    lib_dir = repo_root / "lib" / module_name
    if not lib_dir.exists():
        print(f"❌ Local module lib/{module_name} not found.")
        return False

    kt_files = list(lib_dir.glob("src/main/java/**/*.kt"))
    if not kt_files:
        print(f"❌ No Kotlin source files found in lib/{module_name}")
        return False

    has_updates = False

    for kt_path in kt_files:
        rel_path = kt_path.relative_to(lib_dir)
        upstream_content = fetch_upstream_file(upstream_key, module_name, rel_path)

        if not upstream_content:
            print(f"  [?] Upstream file not reachable: {rel_path}")
            continue

        local_content = kt_path.read_text(encoding="utf-8")
        local_hash = hashlib.sha256(local_content.encode("utf-8")).hexdigest()
        upstream_hash = hashlib.sha256(upstream_content.encode("utf-8")).hexdigest()

        if local_hash != upstream_hash:
            print(f"  ⚡ Update available for {module_name} ({kt_path.name})")
            has_updates = True
        else:
            print(f"  ✓ {module_name} ({kt_path.name}) is up-to-date.")

    return has_updates


def sync_module(repo_root: Path, module_name: str, upstream_key: str = "keiyoushi", dry_run: bool = False) -> bool:
    """Syncs a single extractor module from upstream repository."""
    lib_dir = repo_root / "lib" / module_name
    if not lib_dir.exists():
        print(f"❌ Local module lib/{module_name} not found.")
        return False

    kt_files = list(lib_dir.glob("src/main/java/**/*.kt"))
    updated_count = 0

    for kt_path in kt_files:
        rel_path = kt_path.relative_to(lib_dir)
        upstream_content = fetch_upstream_file(upstream_key, module_name, rel_path)

        if not upstream_content:
            continue

        local_content = kt_path.read_text(encoding="utf-8")
        if local_content != upstream_content:
            print(f"🚀 Updating lib/{module_name}/{rel_path}...")
            if not dry_run:
                kt_path.write_text(upstream_content, encoding="utf-8")
            updated_count += 1


        local_content = kt_path.read_text(encoding="utf-8")
        if local_content != upstream_content:
            print(f"🚀 Updating lib/{module_name}/{rel_path}...")
            if not dry_run:
                kt_path.write_text(upstream_content, encoding="utf-8")
            updated_count += 1

    if updated_count > 0:
        print(f"✅ Successfully synced {updated_count} file(s) in lib/{module_name}")
        return True
    else:
        print(f"✓ lib/{module_name} is already up to date.")
        return True


def main():
    parser = argparse.ArgumentParser(description="Synchronize extractor modules from upstream repositories.")
    parser.add_argument("--module", help="Target module name (e.g. dood-extractor)")
    parser.add_argument("--all", action="store_true", help="Sync/check all local extractor modules")
    parser.add_argument("--upstream", choices=list(UPSTREAM_REPOS.keys()), default="aniyomiorg", help="Upstream repo provider")
    parser.add_argument("--check", action="store_true", help="Check for updates without modifying files")
    parser.add_argument("--dry-run", action="store_true", help="Perform a trial run without writing changes")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    local_modules = get_local_extractors(repo_root)

    if not args.module and not args.all:
        parser.print_help()
        sys.exit(1)

    target_modules = [args.module] if args.module else sorted(list(local_modules.keys()))

    print(f"🔄 Syncing/Auditing {len(target_modules)} extractor module(s) against '{args.upstream}' upstream...\n")

    for mod in target_modules:
        if args.check:
            check_module_updates(repo_root, mod, args.upstream)
        else:
            sync_module(repo_root, mod, args.upstream, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
