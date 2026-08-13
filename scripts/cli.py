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


def resolve_extension_target(repo_root: Path, target: str = None, lang: str = None, name: str = None) -> tuple[str, str]:
    """Helper to resolve (lang, name) from target positional argument (e.g. 'vegamovies' or 'en/vegamovies') or flags."""
    src_dir = repo_root / "src"

    if target:
        if "/" in target:
            parts = target.split("/", 1)
            return parts[0], parts[1]
        name = target
        lang = None

    if name:
        if lang and (src_dir / lang / name).exists():
            return lang, name
        for lang_dir in sorted(src_dir.iterdir()):
            if lang_dir.is_dir() and (lang_dir / name).exists():
                return lang_dir.name, name

    return lang, name


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


def bump_version(repo_root: Path, target_lang: str = None, target_name: str = None) -> bool:
    """Increments extVersionCode or overrideVersionCode in build.gradle."""
    lang, name = resolve_extension_target(repo_root, lang=target_lang, name=target_name)
    if not lang or not name:
        print("❌ Target extension not specified or could not be resolved.")
        return False

    ext_path = repo_root / "src" / lang / name
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


def show_info(repo_root: Path, target_lang: str = None, target_name: str = None) -> bool:
    """Displays detailed summary of an extension module."""
    lang, name = resolve_extension_target(repo_root, lang=target_lang, name=target_name)
    if not lang or not name:
        print("❌ Could not resolve target extension module.")
        return False

    ext_path = repo_root / "src" / lang / name
    if not ext_path.exists():
        print(f"❌ Extension src/{lang}/{name} not found.")
        return False

    print(f"ℹ️ Module Info: src/{lang}/{name}\n" + "=" * 50)
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
    kt_dir = ext_path / "src" / "eu" / "kanade" / "tachiyomi" / "animeextension" / lang / name
    kt_files = list(kt_dir.glob("*.kt")) if kt_dir.exists() else []

    print(f"  • Manifest: {'✓ Present' if manifest_file.exists() else '❌ Missing'}")
    print(f"  • Launcher Icon: {'✓ Present' if icon_file.exists() else '❌ Missing'}")
    print(f"  • Kotlin Source Files ({len(kt_files)}): {', '.join(f.name for f in kt_files)}")
    return True


def list_extensions(repo_root: Path):
    """Lists all available extensions with language, version code, and path."""
    src_dir = repo_root / "src"
    if not src_dir.exists():
        print("❌ src/ directory not found.")
        return
    print("📦 Installed Extension Modules Catalog:\n" + "=" * 60)
    total = 0
    for lang_dir in sorted(src_dir.iterdir()):
        if lang_dir.is_dir():
            modules = sorted([d for d in lang_dir.iterdir() if d.is_dir()])
            print(f"\n🌐 [{lang_dir.name}] ({len(modules)} modules):")
            for m in modules:
                total += 1
                gradle_file = m / "build.gradle"
                ver = "?"
                if gradle_file.exists():
                    txt = gradle_file.read_text(encoding="utf-8", errors="ignore")
                    m_ver = re.search(r"(?:extVersionCode|overrideVersionCode)\s*=\s*(\d+)", txt)
                    if m_ver:
                        ver = m_ver.group(1)
                print(f"  • {m.name:25s} (v{ver}) -> src/{lang_dir.name}/{m.name}")
    print(f"\nTotal: {total} extension module(s) installed.")


def publish_extension(repo_root: Path, target_lang: str = None, target_name: str = None, commit_msg: str = None) -> bool:
    """Validates extension, bumps version code, stages git files, commits, and pushes to remote GitHub repository."""
    lang, name = resolve_extension_target(repo_root, lang=target_lang, name=target_name)
    if not lang or not name:
        print("❌ Could not resolve target extension module. Usage: cli.py publish <name> [-m 'msg']")
        return False

    ext_path = repo_root / "src" / lang / name
    if not ext_path.exists():
        print(f"❌ Extension directory {ext_path} not found.")
        return False

    print(f"🚀 Publishing extension: src/{lang}/{name}...\n" + "=" * 60)

    # 1. Run static validation
    print("1️⃣ Running static code validation...")
    if not validate_extensions(repo_root, lang, name):
        print("❌ Validation failed. Fix errors before publishing.")
        return False

    # 2. Bump version
    print("\n2️⃣ Bumping version code...")
    if not bump_version(repo_root, lang, name):
        print("❌ Failed to bump version code.")
        return False

    # 3. Stage changes
    print("\n3️⃣ Staging git changes...")
    cmd_add = ["git", "add", str(ext_path)]
    subprocess.run(cmd_add, cwd=repo_root, check=True)

    # 4. Commit
    msg = commit_msg or f"{name}: bump version and maintenance update"
    print(f"\n4️⃣ Committing: '{msg}'...")
    cmd_commit = ["git", "commit", "-m", msg]
    subprocess.run(cmd_commit, cwd=repo_root, check=True)

    # 5. Push
    print("\n5️⃣ Pushing to GitHub remote...")
    cmd_push = ["git", "push", "origin", "master"]
    result = subprocess.run(cmd_push, cwd=repo_root)
    if result.returncode == 0:
        print(f"\n🎉 Successfully published {name} (src/{lang}/{name}) to GitHub!")
        return True
    else:
        print("❌ Git push failed.")
        return False



def validate_extensions(repo_root: Path, target_lang: str = None, target_name: str = None):
    """Statically validates extension modules without running Gradle APK builds."""
    src_root = repo_root / "src"
    if not src_root.exists():
        print("❌ No src/ directory found.")
        return False

    resolved_lang, resolved_name = resolve_extension_target(repo_root, target=target_name, lang=target_lang, name=target_name)
    if resolved_name:
        target_name = resolved_name
        if resolved_lang:
            target_lang = resolved_lang

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
                pkg_match = re.search(r"^\s*package\s+([^\s;]+)", content, re.MULTILINE)
                if not pkg_match or pkg_match.group(1) != expected_pkg:
                    issues.append(f"Mismatched package declaration in {kt.name} (Expected: package {expected_pkg})")
                if "companion {" in content and "companion object {" not in content:
                    issues.append(f"Syntax error in {kt.name}: 'companion {{' missing 'object' keyword (must be 'companion object {{')")
                if "getAnimeDetails" in content and "initialized = true" not in content:
                    issues.append(f"Missing 'initialized = true' inside getAnimeDetails in {kt.name}")
                if "ParsedAnimeHttpSource" in content:
                    issues.append(f"Legacy class 'ParsedAnimeHttpSource' found in {kt.name} (v16 Rule: Must extend extensions.utils.Source)")
                # Catch both named deprecated params (url=, quality=) and 4-arg positional form
                has_deprecated_named = re.search(r"Video\s*\(\s*url\s*=", content) or re.search(r"Video\s*\([^)]*quality\s*=", content)
                has_positional_4arg = re.search(r'\bVideo\s*\([^)]*,[^)]*,[^)]*,[^)]*\)', content) and not re.search(r'\bVideo\s*\(\s*videoUrl\s*=', content)
                if has_deprecated_named or has_positional_4arg:
                    issues.append(f"Deprecated Video constructor in {kt.name} (v16 Rule: Use Video(videoUrl=, videoTitle=, headers=))")
                if "it.quality" in content:
                    issues.append(f"Deprecated Video property 'it.quality' in {kt.name} (v16 Rule: Use it.videoTitle)")

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


def clean_workspace(repo_root: Path) -> bool:
    """Removes temporary files, caches, and build artifacts."""
    print("🧹 Cleaning workspace cache & temporary build artifacts...")
    count = 0
    for p in repo_root.rglob("__pycache__"):
        if p.is_dir():
            import shutil
            shutil.rmtree(p, ignore_errors=True)
            count += 1
    for p in repo_root.rglob("*.pyc"):
        try:
            p.unlink(missing_ok=True)
            count += 1
        except Exception:
            pass
    for p in repo_root.rglob("temp_favicon_raw"):
        try:
            p.unlink(missing_ok=True)
            count += 1
        except Exception:
            pass
    print(f"✅ Workspace cleaned! ({count} item(s) purged)")
    return True


def generate_doc(repo_root: Path) -> bool:
    """Generates an up-to-date markdown catalog table of all extension modules."""
    src_dir = repo_root / "src"
    extensions = []
    for lang_dir in sorted(src_dir.iterdir()):
        if lang_dir.is_dir():
            for ext_dir in sorted(lang_dir.iterdir()):
                if ext_dir.is_dir():
                    gradle_file = ext_dir / "build.gradle"
                    if gradle_file.exists():
                        content = gradle_file.read_text(encoding="utf-8")
                        name_m = re.search(r"extName\s*=\s*['\"]([^'\"]+)['\"]", content)
                        ver_m = re.search(r"(?:extVersionCode|overrideVersionCode)\s*=\s*(\d+)", content)
                        name = name_m.group(1) if name_m else ext_dir.name
                        ver = ver_m.group(1) if ver_m else "1"
                        extensions.append((lang_dir.name, name, ext_dir.name, ver))

    print(f"📚 Extension Catalog ({len(extensions)} total modules):\n" + "=" * 60)
    print(f"| Language | Extension Name | Directory | Version Code |")
    print(f"| :--- | :--- | :--- | :---: |")
    for lang, name, folder, ver in extensions:
        print(f"| `{lang}` | {name} | `src/{lang}/{folder}` | `{ver}` |")
    return True


def lint_codebase(repo_root: Path) -> bool:
    """Scans Kotlin code for code smells, blocking calls, anti-patterns, and missing headers.
    Covers both src/ extension sources and lib/ shared extractor modules.
    """
    print("🔍 Running Linter & Code Quality Inspection across Extension Codebase...\n" + "=" * 60)
    warnings = 0

    # Scan both src/ (extensions) and lib/ (shared extractors) — lib/ is compiled too
    scan_roots = [repo_root / "src", repo_root / "lib"]

    for scan_root in scan_roots:
        if not scan_root.exists():
            continue
        for kt_file in sorted(scan_root.rglob("*.kt")):
            content = kt_file.read_text(encoding="utf-8", errors="ignore")
            rel_path = kt_file.relative_to(repo_root)
            file_warnings = []

            # 1. Blocking Thread.sleep call
            if "Thread.sleep" in content:
                file_warnings.append("Blocking Thread.sleep call found (use delay() in coroutines)")

            # 2. Raw baseUrl string concatenation instead of absUrl()
            if re.search(r'"\$baseUrl"\s*\+\s*\w+\.attr\(', content):
                file_warnings.append('Manual "$baseUrl" + attr() prepend — use element.attr("abs:src") or absUrl() instead')

            # 3. Date parsing without runCatching
            for match in re.findall(r'SimpleDateFormat\([^)]+\)\.parse\(', content):
                idx = content.find(match)
                ctx = content[max(0, idx - 200):idx + 50]
                if "runCatching" not in ctx:
                    file_warnings.append("Date parsing without runCatching wrapping — can throw ParseException")
                    break

            # 4. Sequential for-loop over embed URLs instead of parallelCatchingFlatMap
            if re.search(r'for\s*\(\w+\s+in\s+(?:hosters|embedUrls|servers|links)\)', content):
                if "parallelCatchingFlatMap" not in content:
                    file_warnings.append("Sequential for-loop over hosters/servers — consider parallelCatchingFlatMap for parallel extraction")

            # 5. Raw json.decodeFromString without parseAs<> wrapper
            if re.search(r'json\.decodeFromString<', content) and "parseAs<" not in content:
                file_warnings.append("Raw json.decodeFromString<> — prefer response.parseAs<T>() wrapper pattern")

            # 6. Force-unwrap null!! on preference getString
            if re.search(r'preferences\.getString\([^)]+\)!!', content):
                file_warnings.append("Force-unwrap preferences.getString()!! — use ?: \"default\" fallback instead")

            # 7. Hardcoded session/CF cookies in headers
            if re.search(r'(?:cf_clearance|PHPSESSID|__cfduid)["\']', content):
                file_warnings.append("Hardcoded session/CF cookie literal found — cookies should be fetched dynamically")

            # 8. Deprecated it.quality Video property
            if "it.quality" in content:
                file_warnings.append("Deprecated Video property 'it.quality' — use it.videoTitle (v16 API)")

            # 9. Deprecated positional Video constructor (4-arg form: Video(url, quality, videoUrl, headers))
            #    Matches Video(...) calls that are NOT using named parameters (videoUrl= / videoTitle=)
            if re.search(r'\bVideo\s*\([^)]*,[^)]*,[^)]*,[^)]*\)', content):
                if not re.search(r'\bVideo\s*\(\s*videoUrl\s*=', content):
                    file_warnings.append(
                        "Deprecated 4-arg positional Video(...) constructor — use Video(videoUrl=, videoTitle=, headers=) (v16 API)"
                    )

            if file_warnings:
                for w in file_warnings:
                    print(f"  ⚠️  {rel_path}: {w}")
                warnings += len(file_warnings)

    if warnings == 0:
        print("  ✓ No lint warnings or code smells detected across codebase.")
    else:
        print(f"\nSummary: {warnings} lint warning(s) found.")
    return True


def main():
    repo_root = Path(__file__).resolve().parent.parent
    scripts_dir = repo_root / "scripts"

    commands_info = {
        "create": {
            "script": "create_extension.py",
            "desc": "Scaffold a new Aniyomi extension module (HTML, API, or Theme) with preferences, metadata, and extractors."
        },
        "list": {
            "script": None,
            "desc": "List all installed extension modules with their language, directory, and version code."
        },
        "publish": {
            "script": None,
            "desc": "Validate, bump version code, git commit, and push an extension to GitHub in one automated command."
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
        "verify-extractors": {
            "script": "verify_extractors.py",
            "desc": "Empirically test video extractors against live HTTP video playback behavior instead of relying blindly on upstream."
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
        },
        "clean": {
            "script": None,
            "desc": "Purge temporary build caches, pyc files, and scratch raw files."
        },
        "doc": {
            "script": None,
            "desc": "Generate up-to-date Markdown extension catalog table for repository documentation."
        },
        "lint": {
            "script": None,
            "desc": "Perform code quality inspection and scan for code smells across Kotlin source files."
        }
    }

    parser = argparse.ArgumentParser(
        description="🚀 Aniyomi AI Extension Engine Master CLI",
        formatter_class=argparse.RawTextHelpFormatter,
        epilog=f"""
Available Commands ({len(commands_info)} Total):
----------------------------------------
  1. create            {commands_info['create']['desc']}
  2. list              {commands_info['list']['desc']}
  3. publish           {commands_info['publish']['desc']}
  4. validate          {commands_info['validate']['desc']}
  5. bump-version      {commands_info['bump-version']['desc']}
  6. info              {commands_info['info']['desc']}
  7. fetch-icon        {commands_info['fetch-icon']['desc']}
  8. fetch-metadata    {commands_info['fetch-metadata']['desc']}
  9. auto-maintain     {commands_info['auto-maintain']['desc']}
 10. detect-extractors {commands_info['detect-extractors']['desc']}
 11. sync-lib          {commands_info['sync-lib']['desc']}
 12. verify-extractors {commands_info['verify-extractors']['desc']}
 13. test-scraper      {commands_info['test-scraper']['desc']}
 14. test-extractor    {commands_info['test-extractor']['desc']}
 15. list-extractors   {commands_info['list-extractors']['desc']}
 16. clean             {commands_info['clean']['desc']}
 17. doc               {commands_info['doc']['desc']}
 18. lint              {commands_info['lint']['desc']}

Examples:
  python3 scripts/cli.py bump-version vegamovies
  python3 scripts/cli.py publish vegamovies -m "fix episode parsing"
  python3 scripts/cli.py list
  python3 scripts/cli.py info vegamovies
  python3 scripts/cli.py lint
"""
    )

    parser.add_argument("command", choices=list(commands_info.keys()), help="Subcommand to run")
    parser.add_argument("args", nargs=argparse.REMAINDER, help="Arguments passed to the subcommand")

    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(0)

    args = parser.parse_args()

    if args.command == "list":
        list_extensions(repo_root)
        sys.exit(0)

    if args.command == "clean":
        success = clean_workspace(repo_root)
        sys.exit(0 if success else 1)

    if args.command == "doc":
        success = generate_doc(repo_root)
        sys.exit(0 if success else 1)

    if args.command == "lint":
        success = lint_codebase(repo_root)
        sys.exit(0 if success else 1)

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

    if args.command == "publish":
        pub_parser = argparse.ArgumentParser(prog="cli.py publish")
        pub_parser.add_argument("target", nargs="?", help="Target extension name (e.g. vegamovies or en/vegamovies)")
        pub_parser.add_argument("--lang", help="Target extension lang")
        pub_parser.add_argument("--name", help="Target extension directory name")
        pub_parser.add_argument("-m", "--message", help="Commit message")
        pub_args = pub_parser.parse_args(args.args)
        success = publish_extension(repo_root, pub_args.lang or pub_args.target, pub_args.name or pub_args.target, pub_args.message)
        sys.exit(0 if success else 1)

    if args.command == "validate":
        val_parser = argparse.ArgumentParser(prog="cli.py validate")
        val_parser.add_argument("target", nargs="?", help="Target extension name (e.g. vegamovies)")
        val_parser.add_argument("--lang", help="Target extension lang")
        val_parser.add_argument("--name", help="Target extension directory name")
        val_args = val_parser.parse_args(args.args)
        success = validate_extensions(repo_root, val_args.lang or val_args.target, val_args.name or val_args.target)
        sys.exit(0 if success else 1)

    if args.command == "bump-version":
        bump_parser = argparse.ArgumentParser(prog="cli.py bump-version")
        bump_parser.add_argument("target", nargs="?", help="Target extension name (e.g. vegamovies)")
        bump_parser.add_argument("--lang", help="Target extension lang")
        bump_parser.add_argument("--name", help="Target extension directory name")
        bump_args = bump_parser.parse_args(args.args)
        success = bump_version(repo_root, bump_args.lang or bump_args.target, bump_args.name or bump_args.target)
        sys.exit(0 if success else 1)

    if args.command == "info":
        info_parser = argparse.ArgumentParser(prog="cli.py info")
        info_parser.add_argument("target", nargs="?", help="Target extension name (e.g. vegamovies)")
        info_parser.add_argument("--lang", help="Target extension lang")
        info_parser.add_argument("--name", help="Target extension directory name")
        info_args = info_parser.parse_args(args.args)
        success = show_info(repo_root, info_args.lang or info_args.target, info_args.name or info_args.target)
        sys.exit(0 if success else 1)

    if args.command == "fetch-icon":
        fetch_parser = argparse.ArgumentParser(prog="cli.py fetch-icon")
        fetch_parser.add_argument("--url", required=True, help="Target website URL")
        fetch_parser.add_argument("target", nargs="?", help="Target extension name")
        fetch_parser.add_argument("--lang", help="Target extension lang")
        fetch_parser.add_argument("--name", help="Target extension directory name")

        icon_args = fetch_parser.parse_args(args.args)
        lang, name = resolve_extension_target(repo_root, icon_args.target, icon_args.lang, icon_args.name)
        out_path = repo_root / "src" / (lang or "en") / (name or "") / "res" / "drawable" / "ic_launcher.png"
        success = fetch_icon(icon_args.url, out_path)
        sys.exit(0 if success else 1)

    script_name = commands_info[args.command]["script"]
    script_path = scripts_dir / script_name

    cmd = [sys.executable, str(script_path)] + args.args
    result = subprocess.run(cmd)
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()

