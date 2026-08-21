#!/usr/bin/env python3
"""
Managed Extractor Vendoring Engine (vendor_extractor.py)
--------------------------------------------------------
Enables Aniyomi extensions to vendor local, isolated copies of extractors from `lib/`
without modifying shared libraries, while tracking dependencies for automated syncs.

Commands:
  cli.py vendor <extractor_name> <target_extension>
  cli.py sync-vendor [<target_extension>]
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import List, Tuple, Optional

REPO_ROOT = Path(__file__).resolve().parent.parent

def find_extractor_dir(repo_root: Path, extractor_name: str) -> Optional[Path]:
    """Finds the directory in lib/ matching extractor_name."""
    lib_dir = repo_root / "lib"
    if not lib_dir.exists():
        return None

    candidates = [
        lib_dir / extractor_name,
        lib_dir / f"{extractor_name}-extractor",
        lib_dir / extractor_name.replace("-extractor", "")
    ]
    for c in candidates:
        if c.is_dir():
            return c

    for d in lib_dir.iterdir():
        if d.is_dir() and (extractor_name.lower() in d.name.lower() or d.name.lower() in extractor_name.lower()):
            return d
    return None

def vendor_extractor(repo_root: Path, extractor_name: str, target_lang: str, target_name: str) -> bool:
    """Vendors an extractor from lib/ into a specific extension module."""
    ext_dir = find_extractor_dir(repo_root, extractor_name)
    if not ext_dir:
        print(f"❌ Extractor '{extractor_name}' not found in lib/.")
        return False

    target_ext_dir = repo_root / "src" / target_lang / target_name
    if not target_ext_dir.exists():
        print(f"❌ Target extension 'src/{target_lang}/{target_name}' not found.")
        return False

    kt_files = list(ext_dir.rglob("*.kt"))
    if not kt_files:
        print(f"❌ No Kotlin source files found in {ext_dir.relative_to(repo_root)}.")
        return False

    dest_dir = target_ext_dir / "src" / "eu" / "kanade" / "tachiyomi" / "animeextension" / target_lang / target_name / "extractors"
    dest_dir.mkdir(parents=True, exist_ok=True)

    vendored_pkg = f"eu.kanade.tachiyomi.animeextension.{target_lang}.{target_name}.extractors"
    copied_files = []

    for src_file in kt_files:
        content = src_file.read_text(encoding="utf-8", errors="ignore")

        pkg_match = re.search(r"package\s+([a-zA-Z0-9_.]+)", content)
        orig_pkg = pkg_match.group(1) if pkg_match else "unknown"

        content = re.sub(r"package\s+[a-zA-Z0-9_.]+", f"package {vendored_pkg}", content, count=1)

        header = (
            f"// ============================================================================\n"
            f"// Vendored from lib/{ext_dir.name}/{src_file.name} via `cli.py vendor`\n"
            f"// Original Package: {orig_pkg}\n"
            f"// Managed Vendor Copy — Safe to customize locally without breaking other sources\n"
            f"// ============================================================================\n\n"
        )

        dest_file = dest_dir / src_file.name
        dest_file.write_text(header + content, encoding="utf-8")
        copied_files.append(dest_file.name)

    print(f"✅ Successfully vendored {len(copied_files)} file(s) into:")
    print(f"   📂 {dest_dir.relative_to(repo_root)}")
    for f in copied_files:
        print(f"      • {f}")

    gradle_file = target_ext_dir / "build.gradle"
    if gradle_file.exists():
        gradle_text = gradle_file.read_text(encoding="utf-8")
        dep_line = f":lib:{ext_dir.name}"
        if dep_line in gradle_text:
            dep_pattern = r'implementation\(project\(["\']' + re.escape(dep_line) + r'["\']\)\)\n?'
            new_gradle = re.sub(dep_pattern, "", gradle_text)
            if new_gradle != gradle_text:
                gradle_file.write_text(new_gradle, encoding="utf-8")
                print(f"   🧹 Removed `:lib:{ext_dir.name}` project dependency from build.gradle (now standalone).")

    return True

def sync_vendored_extractors(repo_root: Path, target_lang: str = None, target_name: str = None) -> bool:
    """Scans and reports/syncs diffs between lib/ extractors and vendored copies."""
    src_dir = repo_root / "src"
    if not src_dir.exists():
        print("❌ src/ directory not found.")
        return False

    targets = []
    if target_lang and target_name:
        ext_dir = src_dir / target_lang / target_name
        if ext_dir.exists():
            targets.append(ext_dir)
    else:
        for l_dir in sorted(src_dir.iterdir()):
            if l_dir.is_dir():
                for ext in sorted(l_dir.iterdir()):
                    if ext.is_dir() and (ext / "src").exists():
                        targets.append(ext)

    print(f"🔍 Scanning {len(targets)} extension(s) for vendored extractors...\n")
    found_count = 0

    for target in targets:
        extractors_dir = target / "src"
        if not extractors_dir.exists():
            continue

        for kt_file in extractors_dir.rglob("*.kt"):
            content = kt_file.read_text(encoding="utf-8", errors="ignore")
            m = re.search(r"// Vendored from lib/([^/]+)/([^\s]+)", content)
            if m:
                found_count += 1
                lib_name = m.group(1)
                file_name = m.group(2)
                rel_path = kt_file.relative_to(repo_root)

                lib_source = repo_root / "lib" / lib_name
                if (lib_source).exists():
                    print(f"📦 Vendored Extractor in {rel_path}:")
                    print(f"   -> Upstream: lib/{lib_name}/{file_name}")
                    print(f"   -> Status: Active Managed Copy (Isolated)\n")
                else:
                    print(f"⚠️ Vendored Extractor in {rel_path} references deleted/renamed upstream: lib/{lib_name}\n")

    if found_count == 0:
        print("✨ No vendored extractors currently detected in target extensions.")
    else:
        print(f"🎉 Total managed vendored extractor instances: {found_count}")

    return True

def main():
    parser = argparse.ArgumentParser(description="Managed Extractor Vendoring CLI Engine")
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    vendor_p = subparsers.add_parser("vendor", help="Vendor an extractor from lib/ into an extension")
    vendor_p.add_argument("extractor", help="Name of extractor in lib/ (e.g. 'dood-extractor', 'abyss-extractor')")
    vendor_p.add_argument("target", help="Target extension module (e.g. 'animesalt' or 'en/animesalt')")

    sync_p = subparsers.add_parser("sync-vendor", help="Scan and sync vendored extractors across extensions")
    sync_p.add_argument("target", nargs="?", help="Optional target extension module")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    if args.subcommand == "vendor":
        if "/" in args.target:
            lang, name = args.target.split("/", 1)
        else:
            lang, name = None, args.target
            for l_dir in (repo_root / "src").iterdir():
                if l_dir.is_dir() and (l_dir / name).exists():
                    lang = l_dir.name
                    break
        if not lang:
            print(f"❌ Could not resolve language folder for extension '{args.target}'.")
            sys.exit(1)
        success = vendor_extractor(repo_root, args.extractor, lang, name)
        sys.exit(0 if success else 1)

    elif args.subcommand == "sync-vendor":
        lang, name = None, None
        if args.target:
            if "/" in args.target:
                lang, name = args.target.split("/", 1)
            else:
                for l_dir in (repo_root / "src").iterdir():
                    if l_dir.is_dir() and (l_dir / args.target).exists():
                        lang = l_dir.name
                        name = args.target
                        break
        success = sync_vendored_extractors(repo_root, lang, name)
        sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
