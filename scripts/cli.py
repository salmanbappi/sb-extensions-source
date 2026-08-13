#!/usr/bin/env python3
"""
Aniyomi Extension Engine Master CLI Tool
Unified entrypoint for AI agents and developers to scaffold, test, validate, bump version, and manage extensions.
"""

import argparse
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path


def fetch_icon(url: str, output_path: Path):
    """Fetches favicon from target URL and converts it to 192x192 PNG launcher icon."""
    print(f"🔍 Fetching favicon from: {url}")
    domain = url.split("//")[-1].split("/")[0]
    base_url = f"https://{domain}"

    fav_candidates = [
        f"{base_url}/favicon.ico",
        f"{base_url}/favicon.png",
        f"{base_url}/apple-touch-icon.png",
        f"{base_url}/apple-touch-icon-precomposed.png",
    ]

    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
        with urllib.request.urlopen(req, timeout=10) as resp:
            html = resp.read().decode('utf-8', errors='ignore')
            icon_tags = re.findall(r'<link[^>]+rel=\"[^\"]*(?:icon|shortcut|apple)[^\"]*\"[^>]+href=\"([^\"]+)\"', html, re.IGNORECASE)
            for href in icon_tags:
                if href.startswith("//"):
                    fav_candidates.insert(0, f"https:{href}")
                elif href.startswith("http"):
                    fav_candidates.insert(0, href)
                elif href.startswith("/"):
                    fav_candidates.insert(0, f"{base_url}{href}")
    except Exception as e:
        print(f"  [!] Note: HTML scrap for favicon failed ({e}), using default favicon paths...")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temp_raw = output_path.parent / "temp_favicon_raw"

    downloaded = False
    for fav_url in fav_candidates:
        try:
            req = urllib.request.Request(fav_url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status == 200:
                    with open(temp_raw, "wb") as f:
                        f.write(resp.read())
                    downloaded = True
                    print(f"  [+] Downloaded favicon from: {fav_url}")
                    break
        except Exception:
            continue

    if not downloaded:
        print(f"  [!] Could not fetch live favicon from {url}. Icon generation requires manual placement or fallback.")
        return False

    cmd = ["convert", str(temp_raw), "-background", "none", "-gravity", "center", "-extent", "200x200", "-resize", "192x192", str(output_path)]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"  [✓] Converted launcher icon saved to: {output_path}")
        if temp_raw.exists():
            temp_raw.unlink()
        return True
    except Exception:
        if temp_raw.exists():
            temp_raw.rename(output_path)
            print(f"  [✓] Saved raw icon to: {output_path}")
            return True
        return False


def bump_version(repo_root: Path, target_lang: str, target_name: str) -> bool:
    """Increments extVersionCode or overrideVersionCode in build.gradle."""
    ext_path = repo_root / "src" / target_lang / target_name
    gradle_file = ext_path / "build.gradle"
    if not gradle_file.exists():
        print(f"❌ build.gradle not found at {gradle_file}")
        return False

    content = gradle_file.read_text(encoding="utf-8")

    ext_match = re.search(r"extVersionCode\s*=\s*(\d+)", content)
    override_match = re.search(r"overrideVersionCode\s*=\s*(\d+)", content)

    if ext_match:
        old_val = int(ext_match.group(1))
        new_val = old_val + 1
        new_content = re.sub(r"extVersionCode\s*=\s*\d+", f"extVersionCode = {new_val}", content)
        gradle_file.write_text(new_content, encoding="utf-8")
        print(f"🚀 Incremented extVersionCode: {old_val} -> {new_val} in {gradle_file.relative_to(repo_root)}")
        return True
    elif override_match:
        old_val = int(override_match.group(1))
        new_val = old_val + 1
        new_content = re.sub(r"overrideVersionCode\s*=\s*\d+", f"overrideVersionCode = {new_val}", content)
        gradle_file.write_text(new_content, encoding="utf-8")
        print(f"🚀 Incremented overrideVersionCode: {old_val} -> {new_val} in {gradle_file.relative_to(repo_root)}")
        return True
    else:
        print(f"❌ Neither extVersionCode nor overrideVersionCode found in {gradle_file}")
        return False


def show_info(repo_root: Path, target_lang: str, target_name: str) -> bool:
    """Displays detailed summary of an extension module."""
    ext_path = repo_root / "src" / target_lang / target_name
    if not ext_path.exists():
        print(f"❌ Extension src/{target_lang}/{target_name} not found.")
        return False

    print(f"ℹ️ Module Info: src/{target_lang}/{target_name}\n" + "=" * 50)
    gradle_file = ext_path / "build.gradle"
    if gradle_file.exists():
        content = gradle_file.read_text(encoding="utf-8")
        name_m = re.search(r"extName\s*=\s*['\"]([^'\"]+)['\"]", content)
        class_m = re.search(r"extClass\s*=\s*['\"]([^'\"]+)['\"]", content)
        ver_m = re.search(r"(?:extVersionCode|overrideVersionCode)\s*=\s*(\d+)", content)
        deps = re.findall(r"implementation\(project\(['\"]([^'\"]+)['\"]\)\)", content)

        print(f"  • Name: {name_m.group(1) if name_m else 'Unknown'}")
        print(f"  • Class: {class_m.group(1) if class_m else 'Unknown'}")
        print(f"  • Version Code: {ver_m.group(1) if ver_m else 'Unknown'}")
        print(f"  • Lib Dependencies ({len(deps)}): {', '.join(deps) if deps else 'None'}")

    icon_file = ext_path / "res" / "drawable" / "ic_launcher.png"
    manifest_file = ext_path / "AndroidManifest.xml"
    kt_dir = ext_path / "src" / "eu" / "kanade" / "tachiyomi" / "animeextension" / target_lang / target_name
    kt_files = list(kt_dir.glob("*.kt")) if kt_dir.exists() else []

    print(f"  • Manifest: {'✓ Present' if manifest_file.exists() else '❌ Missing'}")
    print(f"  • Launcher Icon: {'✓ Present' if icon_file.exists() else '❌ Missing'}")
    print(f"  • Kotlin Source Files ({len(kt_files)}): {', '.join(f.name for f in kt_files)}")
    return True



def validate_extensions(repo_root: Path, target_lang: str = None, target_name: str = None):
    """Statically validates extension modules without running Gradle APK builds."""
    src_root = repo_root / "src"
    if not src_root.exists():
        print("❌ No src/ directory found.")
        return

    print("🔎 Auditing and Validating Extension Modules (Static Code Analysis)...\n")

    valid_count = 0
    issue_count = 0

    langs = [target_lang] if target_lang else [d.name for d in src_root.iterdir() if d.is_dir()]

    for lang in sorted(langs):
        lang_dir = src_root / lang
        if not lang_dir.exists():
            continue

        ext_dirs = [target_name] if target_name else [d.name for d in lang_dir.iterdir() if d.is_dir()]

        for ext_name in sorted(ext_dirs):
            ext_path = lang_dir / ext_name
            if not ext_path.exists():
                print(f"❌ Module src/{lang}/{ext_name} not found.")
                continue

            issues = []

            # 1. Check build.gradle
            gradle_file = ext_path / "build.gradle"
            if not gradle_file.exists():
                issues.append("Missing build.gradle")
            else:
                gradle_txt = gradle_file.read_text(encoding="utf-8")
                if "extVersionCode" not in gradle_txt and "overrideVersionCode" not in gradle_txt:
                    issues.append("Missing extVersionCode or overrideVersionCode in build.gradle")

            # 2. Check AndroidManifest.xml
            manifest_file = ext_path / "AndroidManifest.xml"
            if not manifest_file.exists():
                issues.append("Missing AndroidManifest.xml")

            # 3. Check App Icon
            icon_file = ext_path / "res" / "drawable" / "ic_launcher.png"
            fallback_icon = ext_path / "res" / "drawable-xxhdpi" / "icon.png"
            if not icon_file.exists() and not fallback_icon.exists():
                issues.append("Missing launcher icon (res/drawable/ic_launcher.png)")

            # 4. Check Kotlin source files & Package declaration
            kt_dir = ext_path / "src" / "eu" / "kanade" / "tachiyomi" / "animeextension" / lang / ext_name
            kt_files = list(kt_dir.glob("*.kt")) if kt_dir.exists() else []
            if not kt_files:
                issues.append(f"Missing main Kotlin source in: src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/")

            expected_pkg = f"eu.kanade.tachiyomi.animeextension.{lang}.{ext_name}"
            for kt in kt_files:
                content = kt.read_text(encoding="utf-8", errors="ignore")
                if not content.startswith(f"package {expected_pkg}"):
                    issues.append(f"Mismatched package declaration in {kt.name} (Expected: package {expected_pkg})")
                if "getAnimeDetails" in content and "initialized = true" not in content:
                    issues.append(f"Missing 'initialized = true' inside getAnimeDetails in {kt.name}")
                if "ParsedAnimeHttpSource" in content:
                    issues.append(f"Legacy class 'ParsedAnimeHttpSource' found in {kt.name} (v16 Rule: Must extend extensions.utils.Source)")


            if issues:
                issue_count += 1
                print(f"❌ src/{lang}/{ext_name}:")
                for iss in issues:
                    print(f"   • {iss}")
            else:
                valid_count += 1
                print(f"  ✓ src/{lang}/{ext_name} - OK")

    print(f"\nSummary: {valid_count} extension module(s) passed static validation. {issue_count} module(s) had issues.")
    return issue_count == 0



def main():
    repo_root = Path(__file__).resolve().parent.parent
    scripts_dir = repo_root / "scripts"

    commands_info = {
        "create": {
            "script": "create_extension.py",
            "desc": "Scaffold a new Aniyomi extension module (HTML, API, or Theme) with preferences, metadata, and extractors."
        },
        "validate": {
            "script": None,
            "desc": "Perform static analysis validation on extension modules without Gradle APK compilation."
        },
        "bump-version": {
            "script": None,
            "desc": "Increment extVersionCode or overrideVersionCode in build.gradle for an extension module."
        },
        "info": {
            "script": None,
            "desc": "Display detailed summary and dependency info for an extension module."
        },
        "fetch-icon": {
            "script": None,
            "desc": "Download website favicon and convert it into res/drawable/ic_launcher.png."
        },
        "fetch-metadata": {
            "script": "fetch_metadata.py",
            "desc": "Fetch and merge anime/movie episode metadata from external APIs (Jikan, AniList, Kitsu, TMDB)."
        },
        "auto-maintain": {
            "script": "auto_maintain.py",
            "desc": "Execute full automated maintenance (sync extractors, fix dependencies, run static validation)."
        },
        "detect-extractors": {
            "script": "detect_extractors.py",
            "desc": "Auto-detect required video extractors from embed URLs or extension codebase, and update build.gradle dependencies."
        },
        "sync-lib": {
            "script": "sync_lib.py",
            "desc": "Synchronize and update shared lib/ extractor modules from upstream repositories (Yuzono/Keiyoushi/Aniyomiorg)."
        },
        "test-scraper": {
            "script": "test_scraper.py",
            "desc": "Test live HTTP requests, Jsoup CSS selectors, regex patterns, or JSON API payloads locally without Gradle."
        },
        "test-extractor": {
            "script": "test_extractors.py",
            "desc": "Test video embed link extraction logic (DoodStream, StreamTape, FileMoon, MixDrop, VidSrc) locally."
        },
        "list-extractors": {
            "script": None,
            "desc": "List all 65 pre-built video extractor libraries available in the lib/ directory."
        }
    }

    parser = argparse.ArgumentParser(
        description="🚀 Aniyomi AI Extension Engine Master CLI",
        formatter_class=argparse.RawTextHelpFormatter,
        epilog=f"""
Available Commands ({len(commands_info)} Total):
----------------------------------------
  1. create            {commands_info['create']['desc']}
  2. validate          {commands_info['validate']['desc']}
  3. bump-version      {commands_info['bump-version']['desc']}
  4. info              {commands_info['info']['desc']}
  5. fetch-icon        {commands_info['fetch-icon']['desc']}
  6. fetch-metadata    {commands_info['fetch-metadata']['desc']}
  7. auto-maintain     {commands_info['auto-maintain']['desc']}
  8. detect-extractors {commands_info['detect-extractors']['desc']}
  9. sync-lib          {commands_info['sync-lib']['desc']}
 10. test-scraper      {commands_info['test-scraper']['desc']}
 11. test-extractor    {commands_info['test-extractor']['desc']}
 12. list-extractors   {commands_info['list-extractors']['desc']}

Examples:
  python3 scripts/cli.py auto-maintain
  python3 scripts/cli.py sync-lib --module dood-extractor
  python3 scripts/cli.py validate
"""
    )




    parser.add_argument("command", choices=list(commands_info.keys()), help="Subcommand to run")
    parser.add_argument("args", nargs=argparse.REMAINDER, help="Arguments passed to the subcommand")

    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(0)

    args = parser.parse_args()

    if args.command == "list-extractors":
        lib_dir = repo_root / "lib"
        if not lib_dir.exists():
            print("❌ lib/ directory not found.")
            sys.exit(1)
        extractors = sorted([d.name for d in lib_dir.iterdir() if d.is_dir()])
        print(f"📦 Found {len(extractors)} pre-built extractor modules in lib/\n")
        for i, ext in enumerate(extractors, 1):
            print(f"  {i:2d}. {ext}")
        print("\nSee .agents/skills/extractor-registry/SKILL.md for usage code snippets.")
        sys.exit(0)

    if args.command == "validate":
        val_parser = argparse.ArgumentParser(prog="cli.py validate")
        val_parser.add_argument("--lang", help="Target extension lang")
        val_parser.add_argument("--name", help="Target extension directory name")
        val_args, _ = val_parser.parse_known_args(args.args)
        success = validate_extensions(repo_root, val_args.lang, val_args.name)
        sys.exit(0 if success else 1)

    if args.command == "bump-version":
        bump_parser = argparse.ArgumentParser(prog="cli.py bump-version")
        bump_parser.add_argument("--lang", required=True, help="Target extension lang")
        bump_parser.add_argument("--name", required=True, help="Target extension directory name")
        bump_args = bump_parser.parse_args(args.args)
        success = bump_version(repo_root, bump_args.lang, bump_args.name)
        sys.exit(0 if success else 1)

    if args.command == "info":
        info_parser = argparse.ArgumentParser(prog="cli.py info")
        info_parser.add_argument("--lang", required=True, help="Target extension lang")
        info_parser.add_argument("--name", required=True, help="Target extension directory name")
        info_args = info_parser.parse_args(args.args)
        success = show_info(repo_root, info_args.lang, info_args.name)
        sys.exit(0 if success else 1)

    if args.command == "fetch-icon":
        fetch_parser = argparse.ArgumentParser(prog="cli.py fetch-icon")
        fetch_parser.add_argument("--url", required=True, help="Target website URL")
        fetch_parser.add_argument("--lang", default="en", help="Target extension lang")
        fetch_parser.add_argument("--name", required=True, help="Target extension directory name")

        icon_args = fetch_parser.parse_args(args.args)
        out_path = repo_root / "src" / icon_args.lang / icon_args.name / "res" / "drawable" / "ic_launcher.png"
        success = fetch_icon(icon_args.url, out_path)
        sys.exit(0 if success else 1)

    script_name = commands_info[args.command]["script"]
    script_path = scripts_dir / script_name

    cmd = [sys.executable, str(script_path)] + args.args
    result = subprocess.run(cmd)
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()

