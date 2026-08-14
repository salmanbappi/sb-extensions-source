#!/usr/bin/env python3
"""
Aniyomi Extension Engine Master CLI Tool
Unified entrypoint for developers and AI coding agents to scaffold, test, validate,
lint, migrate domains, bump version, and manage individual and multi-source extensions.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

# Ensure repo root and scripts directory are in sys.path for cross-module imports
REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))


def resolve_extension_target(repo_root: Path, target: str = None, lang: str = None, name: str = None) -> tuple[str, str]:
    """Helper to resolve (lang, name) from target positional argument (e.g. 'vegamovies' or 'en/vegamovies') or flags."""
    src_dir = repo_root / "src"

    if target and "/" in target:
        parts = target.split("/", 1)
        return parts[0], parts[1]

    if lang and "/" in lang:
        parts = lang.split("/", 1)
        return parts[0], parts[1]

    if name and "/" in name:
        parts = name.split("/", 1)
        return parts[0], parts[1]

    resolved_name = name or target
    resolved_lang = lang

    if resolved_name:
        if resolved_lang and (src_dir / resolved_lang / resolved_name).exists():
            return resolved_lang, resolved_name
        for lang_dir in sorted(src_dir.iterdir()):
            if lang_dir.is_dir() and (lang_dir / resolved_name).exists():
                return lang_dir.name, resolved_name

    return resolved_lang, resolved_name


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
        html = urllib.request.urlopen(req, timeout=5).read().decode('utf-8', errors='ignore')
        icons = re.findall(r'<link[^>]+rel=["\'](?:shortcut )?icon["\'][^>]+href=["\']([^"\']+)["\']', html, re.IGNORECASE)
        for icon in icons:
            if icon.startswith("//"):
                fav_candidates.insert(0, f"https:{icon}")
            elif icon.startswith("/"):
                fav_candidates.insert(0, f"{base_url}{icon}")
            elif icon.startswith("http"):
                fav_candidates.insert(0, icon)
            else:
                fav_candidates.insert(0, f"{base_url}/{icon}")
    except Exception as e:
        print(f"  [!] Notice: HTML favicon extraction skipped ({e})")

    temp_raw = output_path.parent / "temp_favicon_raw"
    success = False
    for fav_url in fav_candidates:
        try:
            print(f"  -> Trying: {fav_url}")
            req = urllib.request.Request(fav_url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
            with urllib.request.urlopen(req, timeout=4) as response:
                content = response.read()
                if len(content) > 100:
                    with open(temp_raw, "wb") as f:
                        f.write(content)
                    success = True
                    break
        except Exception:
            continue

    if not success:
        print("❌ Could not download favicon from target site.")
        return False

    output_path.parent.mkdir(parents=True, exist_ok=True)
    conv = shutil.which("convert") or shutil.which("magick")
    if conv:
        try:
            cmd = [conv, f"{temp_raw}[0]", "-resize", "192x192", str(output_path)]
            subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if temp_raw.exists():
                temp_raw.unlink()
            print(f"✅ Successfully created 192x192 launcher icon: {output_path}")
            return True
        except Exception as e:
            print(f"⚠️ ImageMagick convert failed: {e}")

    # Fallback to pure Python minimal PNG generator if convert fails
    from scripts.create_extension import create_minimal_png
    create_minimal_png(output_path, 192, 192)
    if temp_raw.exists():
        temp_raw.unlink()
    print(f"✅ Generated standard fallback 192x192 PNG icon: {output_path}")
    return True


def bump_version(repo_root: Path, target_lang: str = None, target_name: str = None) -> bool:
    """Increments extVersionCode or overrideVersionCode in build.gradle."""
    lang, name = resolve_extension_target(repo_root, lang=target_lang, name=target_name)
    if not lang or not name:
        print("❌ Could not resolve target extension module.")
        return False

    gradle_file = repo_root / "src" / lang / name / "build.gradle"
    if not gradle_file.exists():
        print(f"❌ build.gradle not found at {gradle_file}")
        return False

    content = gradle_file.read_text(encoding="utf-8")

    # Check extVersionCode
    m_ext = re.search(r"extVersionCode\s*=\s*(\d+)", content)
    if m_ext:
        old_ver = int(m_ext.group(1))
        new_ver = old_ver + 1
        new_content = re.sub(r"extVersionCode\s*=\s*\d+", f"extVersionCode = {new_ver}", content)
        gradle_file.write_text(new_content, encoding="utf-8")
        print(f"🚀 Bumped {name} (src/{lang}/{name}) extVersionCode: {old_ver} -> {new_ver}")
        return True

    # Check overrideVersionCode
    m_over = re.search(r"overrideVersionCode\s*=\s*(\d+)", content)
    if m_over:
        old_ver = int(m_over.group(1))
        new_ver = old_ver + 1
        new_content = re.sub(r"overrideVersionCode\s*=\s*\d+", f"overrideVersionCode = {new_ver}", content)
        gradle_file.write_text(new_content, encoding="utf-8")
        print(f"🚀 Bumped {name} (src/{lang}/{name}) overrideVersionCode: {old_ver} -> {new_ver}")
        return True

    print(f"❌ No version code found to bump in {gradle_file}")
    return False


def bump_theme(repo_root: Path, theme_name: str, mode: str = "base") -> bool:
    """Bumps theme baseVersionCode in lib-multisrc/ or variant overrideVersionCode in src/."""
    theme_clean = theme_name.lower().replace("-", "").replace("_", "")
    theme_dir = repo_root / "lib-multisrc" / theme_clean
    theme_gradle = theme_dir / "build.gradle.kts"

    if not theme_gradle.exists():
        print(f"❌ Theme build.gradle.kts not found at {theme_gradle}")
        return False

    if mode in ("base", "all"):
        content = theme_gradle.read_text(encoding="utf-8")
        m_base = re.search(r"baseVersionCode\s*=\s*(\d+)", content)
        if m_base:
            old_v = int(m_base.group(1))
            new_v = old_v + 1
            new_content = re.sub(r"baseVersionCode\s*=\s*\d+", f"baseVersionCode = {new_v}", content)
            theme_gradle.write_text(new_content, encoding="utf-8")
            print(f"🚀 Bumped theme :lib-multisrc:{theme_clean} baseVersionCode: {old_v} -> {new_v}")
        else:
            print(f"⚠️ No baseVersionCode found in {theme_gradle}")

    if mode in ("variants", "all"):
        src_dir = repo_root / "src"
        count = 0
        if src_dir.exists():
            for g_file in src_dir.rglob("build.gradle"):
                g_txt = g_file.read_text(encoding="utf-8", errors="ignore")
                if re.search(rf"themePkg\s*=\s*['\"]{re.escape(theme_clean)}['\"]", g_txt):
                    ext_name = g_file.parent.name
                    lang_name = g_file.parent.parent.name
                    bump_version(repo_root, lang_name, ext_name)
                    count += 1
        print(f"✅ Bumped {count} variant module(s) for theme '{theme_clean}'.")

    return True


def bump_lib_dependents(repo_root: Path, lib_name: str) -> bool:
    """Cascades version bumps to all extension modules that depend on :lib:<lib_name>."""
    clean_lib = lib_name.replace("lib:", "").replace(":", "").strip()
    dep_pattern = f':lib:{clean_lib}'
    src_dir = repo_root / "src"

    bumped = []
    for g_file in src_dir.rglob("build.gradle"):
        txt = g_file.read_text(encoding="utf-8", errors="ignore")
        if dep_pattern in txt:
            ext_name = g_file.parent.name
            lang_name = g_file.parent.parent.name
            bump_version(repo_root, lang_name, ext_name)
            bumped.append(f"src/{lang_name}/{ext_name}")

    if bumped:
        print(f"\n🎉 Successfully bumped {len(bumped)} dependent extension(s) for :lib:{clean_lib}.")
    else:
        print(f"ℹ️ No extension modules found directly depending on :lib:{clean_lib}.")
    return True


def migrate_domain(repo_root: Path, target: str, new_domain: str, test_reachability: bool = True) -> bool:
    """Automates base URL domain migrations, updates preference defaults, bumps version, and checks live connectivity."""
    lang, name = resolve_extension_target(repo_root, target=target)
    if not lang or not name:
        print("❌ Could not resolve target extension module.")
        return False

    ext_path = repo_root / "src" / lang / name
    if not ext_path.exists():
        print(f"❌ Extension src/{lang}/{name} not found.")
        return False

    new_domain_clean = new_domain.rstrip("/")
    print(f"🌐 Migrating domain for src/{lang}/{name} to: {new_domain_clean}\n" + "=" * 60)

    # 1. Test live HTTP reachability
    if test_reachability:
        print(f"1️⃣ Testing reachability on new domain: {new_domain_clean}...")
        try:
            req = urllib.request.Request(
                new_domain_clean,
                headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
            )
            with urllib.request.urlopen(req, timeout=6) as resp:
                print(f"  ✓ Reached {new_domain_clean} (HTTP Status: {resp.status})")
        except Exception as e:
            print(f"  ⚠️ Warning: Live HTTP check encountered: {e}")

    # 2. Update Kotlin & Gradle files
    print("\n2️⃣ Updating baseUrl in Kotlin source files & build.gradle...")
    kt_dir = ext_path / "src"
    updated_files = 0
    if kt_dir.exists():
        for kt_file in kt_dir.rglob("*.kt"):
            txt = kt_file.read_text(encoding="utf-8")

            # Replace baseUrl default constants or hardcoded override
            new_txt = re.sub(r'PREF_BASE_URL_DEFAULT\s*=\s*["\'][^"\']+["\']', f'PREF_BASE_URL_DEFAULT = "{new_domain_clean}"', txt)
            new_txt = re.sub(r'override\s+val\s+baseUrl\s*=\s*["\'][^"\']+["\']', f'override val baseUrl = "{new_domain_clean}"', new_txt)

            if txt != new_txt:
                kt_file.write_text(new_txt, encoding="utf-8")
                updated_files += 1
                print(f"  ✓ Updated {kt_file.name}")

    gradle_file = ext_path / "build.gradle"
    if gradle_file.exists():
        g_txt = gradle_file.read_text(encoding="utf-8")
        new_g_txt = re.sub(r'baseUrl\s*=\s*["\'][^"\']+["\']', f"baseUrl = '{new_domain_clean}'", g_txt)
        if g_txt != new_g_txt:
            gradle_file.write_text(new_g_txt, encoding="utf-8")
            updated_files += 1
            print(f"  ✓ Updated build.gradle")

    # 3. Bump version code
    print("\n3️⃣ Incrementing extension version code...")
    bump_version(repo_root, lang, name)

    print(f"\n🎉 Domain migration complete for src/{lang}/{name} ({updated_files} file(s) updated).")
    return True


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


def publish_extension(repo_root: Path, target_lang: str = None, target_name: str = None, commit_msg: str = None, no_bump: bool = False) -> bool:
    """Validates extension, formats files, bumps version code, stages git files, commits, and pushes to remote GitHub repository."""
    lang, name = resolve_extension_target(repo_root, lang=target_lang, name=target_name)
    if not lang or not name:
        print("❌ Could not resolve target extension module. Usage: cli.py publish <name> [-m 'msg']")
        return False

    ext_path = repo_root / "src" / lang / name
    if not ext_path.exists():
        print(f"❌ Extension directory {ext_path} not found.")
        return False

    print(f"🚀 Publishing extension: src/{lang}/{name}...\n" + "=" * 60)

    # 1. Run static validation & formatting
    print("1️⃣ Running static code validation & linting...")
    format_codebase(repo_root, lang, name)
    if not validate_extensions(repo_root, lang, name):
        print("❌ Validation failed. Fix errors before publishing.")
        return False

    # 2. Bump version
    if no_bump:
        print("\n2️⃣ Skipping version code bump (--no-bump specified).")
    else:
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
    commit_res = subprocess.run(cmd_commit, cwd=repo_root, capture_output=True, text=True)
    if commit_res.returncode != 0:
        if "nothing to commit" in commit_res.stdout:
            print("ℹ️ Nothing to commit, working tree clean.")
        else:
            print(f"❌ Git commit failed: {commit_res.stderr}")
            return False
    else:
        print("✓ Committed successfully.")

    # 5. Push
    print("\n5️⃣ Pushing to remote GitHub repository...")
    cmd_push = ["git", "push"]
    push_res = subprocess.run(cmd_push, cwd=repo_root, capture_output=True, text=True)
    if push_res.returncode != 0:
        if "set-upstream" in push_res.stderr or "no upstream branch" in push_res.stderr:
            branch_res = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=repo_root, capture_output=True, text=True)
            current_branch = branch_res.stdout.strip() or "main"
            print(f"  -> Setting upstream tracking for branch: {current_branch}...")
            push_res = subprocess.run(["git", "push", "-u", "origin", current_branch], cwd=repo_root, capture_output=True, text=True)
        if push_res.returncode != 0:
            print(f"❌ Git push failed: {push_res.stderr}")
            return False

    print("\n🎉 Extension successfully published to GitHub!")
    return True


def validate_extensions(repo_root: Path, target_lang: str = None, target_name: str = None) -> bool:
    """Performs rigorous static analysis validation on extension modules without building APKs."""
    src_dir = repo_root / "src"
    lib_dir = repo_root / "lib"
    if not src_dir.exists():
        print("❌ src/ directory not found.")
        return False

    if target_lang or target_name:
        target_lang, target_name = resolve_extension_target(repo_root, lang=target_lang, name=target_name)

    target_desc = f"src/{target_lang}/{target_name}" if (target_lang and target_name) else "all extension modules"
    print(f"🔎 Validating {target_desc}...\n" + "=" * 60)

    issue_count = 0
    valid_count = 0

    langs_to_check = [target_lang] if target_lang else [d.name for d in src_dir.iterdir() if d.is_dir()]

    for lang in sorted(langs_to_check):
        lang_dir = src_dir / lang
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

                # Check extClass format (must start with dot)
                ext_class_m = re.search(r"extClass\s*=\s*['\"]([^'\"]+)['\"]", gradle_txt)
                if ext_class_m and not ext_class_m.group(1).startswith("."):
                    issues.append(f"Invalid extClass '{ext_class_m.group(1)}' — must start with a dot (e.g. '.{ext_class_m.group(1)}')")

                # Check common.gradle inclusion
                if "common.gradle" not in gradle_txt:
                    issues.append("Missing common.gradle application (apply from: \"$rootDir/common.gradle\")")

                # Check dangling :lib dependencies
                lib_deps = re.findall(r"implementation\(project\(['\"]:lib:([^'\"]+)['\"]\)\)", gradle_txt)
                for lib_dep in lib_deps:
                    if not (lib_dir / lib_dep).exists():
                        issues.append(f"Dangling lib dependency ':lib:{lib_dep}' — lib/{lib_dep} directory does not exist")

            # 2. Check AndroidManifest.xml Deep XML Validation
            manifest_file = ext_path / "AndroidManifest.xml"
            if not manifest_file.exists():
                issues.append("Missing AndroidManifest.xml")
            else:
                try:
                    tree = ET.parse(manifest_file)
                    root = tree.getroot()
                    has_feature = any(elem.attrib.get('{http://schemas.android.com/apk/res/android}name') == 'tachiyomi.animeextension' for elem in root.findall('uses-feature'))
                    if not has_feature:
                        issues.append("AndroidManifest.xml missing '<uses-feature android:name=\"tachiyomi.animeextension\"/>'")

                    meta_tags = {elem.attrib.get('{http://schemas.android.com/apk/res/android}name'): elem.attrib.get('{http://schemas.android.com/apk/res/android}value')
                                 for elem in root.findall('.//meta-data')}
                    if 'tachiyomi.animeextension.class' not in meta_tags:
                        issues.append("AndroidManifest.xml missing meta-data 'tachiyomi.animeextension.class'")
                    if meta_tags.get('tachiyomi.animeextension.versionId') != '2':
                        issues.append("AndroidManifest.xml 'tachiyomi.animeextension.versionId' must be '2' (v16 engine)")

                    app_elem = root.find('application')
                    if app_elem is not None and app_elem.attrib.get('{http://schemas.android.com/apk/res/android}icon'):
                        tools_replace = app_elem.attrib.get('{http://schemas.android.com/tools}replace', '')
                        if 'android:icon' not in tools_replace:
                            issues.append("AndroidManifest.xml <application> defines android:icon but lacks tools:replace=\"...android:icon...\" (will fail Gradle manifest merge with :core)")
                except Exception as e:
                    issues.append(f"Malformed AndroidManifest.xml: {e}")

            # 3. Check App Icon Binary Integrity
            icon_file = ext_path / "res" / "drawable" / "ic_launcher.png"
            fallback_icon = ext_path / "res" / "drawable-xxhdpi" / "icon.png"
            target_icon = icon_file if icon_file.exists() else (fallback_icon if fallback_icon.exists() else None)

            if not target_icon:
                issues.append("Missing launcher icon (res/drawable/ic_launcher.png)")
            else:
                if target_icon.stat().st_size == 0:
                    issues.append(f"Launcher icon is empty (0 bytes): {target_icon.name}")
                else:
                    try:
                        header = target_icon.read_bytes()[:8]
                        if header != b'\x89PNG\r\n\x1a\n':
                            issues.append(f"Launcher icon {target_icon.name} is not a valid PNG binary (magic header mismatch)")
                    except Exception:
                        pass

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

                # Check deprecated Video constructors & properties
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


def format_codebase(repo_root: Path, target_lang: str = None, target_name: str = None, check_only: bool = False) -> bool:
    """Formats Kotlin, Gradle, and XML files by stripping trailing whitespace, normalizing CRLF to LF,
    ensuring a single final newline, and collapsing excessive blank lines.
    """
    target_desc = f"src/{target_lang}/{target_name}" if (target_lang and target_name) else "entire repository"
    mode_desc = "Checking" if check_only else "Formatting"
    print(f"✨ {mode_desc} code style & formatting across {target_desc}...\n" + "=" * 60)

    if target_lang and target_name:
        scan_paths = [repo_root / "src" / target_lang / target_name]
    else:
        scan_paths = [repo_root / "src", repo_root / "lib", repo_root / "lib-multisrc", repo_root / "scripts", repo_root / "core"]

    file_extensions = {".kt", ".kts", ".gradle", ".xml", ".properties", ".py"}
    modified_count = 0
    checked_count = 0

    for path in scan_paths:
        if not path.exists():
            continue
        files_to_scan = [path] if path.is_file() else sorted([f for f in path.rglob("*") if f.is_file() and f.suffix in file_extensions])
        for f in files_to_scan:
            try:
                raw_text = f.read_text(encoding="utf-8", errors="ignore")
            except Exception:
                continue

            checked_count += 1
            lines = raw_text.splitlines()
            cleaned_lines = [line.rstrip(" \t") for line in lines]

            # Collapse 3+ consecutive empty lines to 1
            collapsed = []
            empty_count = 0
            for line in cleaned_lines:
                if not line:
                    empty_count += 1
                    if empty_count <= 2:
                        collapsed.append(line)
                else:
                    empty_count = 0
                    collapsed.append(line)

            new_text = "\n".join(collapsed).strip() + "\n"
            if not collapsed or not any(collapsed):
                new_text = ""

            if raw_text != new_text:
                modified_count += 1
                rel = f.relative_to(repo_root)
                if check_only:
                    print(f"  ⚠️  Formatting violation: {rel}")
                else:
                    f.write_text(new_text, encoding="utf-8")
                    print(f"  ✓ Formatted: {rel}")

    if check_only:
        if modified_count == 0:
            print(f"\n🎉 All {checked_count} file(s) are cleanly formatted!")
            return True
        else:
            print(f"\n❌ {modified_count} file(s) require formatting. Run `cli.py format` to fix.")
            return False
    else:
        print(f"\n🎉 Formatting complete: {modified_count} of {checked_count} file(s) updated.")
        return True


def lint_codebase(repo_root: Path, target_lang: str = None, target_name: str = None) -> bool:
    """Scans Kotlin code for code smells, blocking calls, anti-patterns, companion object placement, and DTO null-safety."""
    target_desc = f"src/{target_lang}/{target_name}" if (target_lang and target_name) else "Extension Codebase"
    print(f"🔍 Running Linter & Code Quality Inspection across {target_desc}...\n" + "=" * 60)
    warnings = 0

    if target_lang and target_name:
        scan_roots = [repo_root / "src" / target_lang / target_name]
    else:
        scan_roots = [repo_root / "src", repo_root / "lib", repo_root / "lib-multisrc"]

    for scan_root in scan_roots:
        if not scan_root.exists():
            continue
        for kt_file in sorted(scan_root.rglob("*.kt")):
            content = kt_file.read_text(encoding="utf-8", errors="ignore")
            rel_path = kt_file.relative_to(repo_root)
            file_warnings = []

            # 1. Blocking Thread.sleep call
            if "Thread.sleep" in content:
                file_warnings.append("Blocking Thread.sleep call found (use rateLimit() or delay() in coroutines)")

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

            # 9. Deprecated positional Video constructor
            if re.search(r'\bVideo\s*\([^)]*,[^)]*,[^)]*,[^)]*\)', content):
                if not re.search(r'\bVideo\s*\(\s*videoUrl\s*=', content):
                    file_warnings.append("Deprecated 4-arg positional Video(...) constructor — use Video(videoUrl=, videoTitle=, headers=)")

            # 10. Companion object syntax mistake
            if re.search(r'\bcompanion\s*\{', content):
                file_warnings.append("Syntax error 'companion {' — must be 'companion object {'")

            # 11. DTO Null-Safety
            if "@Serializable" in content and "data class" in content:
                for match in re.finditer(r'@Serializable(?:\([^)]*\))?\s+(?:private\s+|protected\s+|internal\s+|public\s+)?data\s+class\s+(\w+)\s*\(([\s\S]*?)\)', content):
                    cls_name = match.group(1)
                    param_block = match.group(2)
                    for line in param_block.splitlines():
                        clean_line = line.strip().rstrip(",")
                        if (clean_line.startswith("val ") or clean_line.startswith("var ")) and "=" not in clean_line:
                            prop = clean_line.split(":")[0].replace("val ", "").replace("var ", "").strip()
                            file_warnings.append(f"DTO null-safety violation in {cls_name}.{prop} — missing default fallback (e.g. `? = null`)")
                            break

            # 12. Dynamic / Ephemeral tokens in SEpisode.url
            if "SEpisode" in content and "setUrlWithoutDomain" in content:
                if re.search(r'setUrlWithoutDomain\([^)]*(?:\?token=|\?session=|\?sig=|\?expires=)', content):
                    file_warnings.append("Ephemeral / dynamic token embedded in SEpisode.url — use stable permanent anchor URL (e.g. ${anime.url}#season=$s&ep=$e)")

            # 13. Non-zero base episode numbering (* 1000 offset anti-pattern)
            if re.search(r'\(\s*(?:globalSeason|seasonNum|seasonVal|season\.season_number|s)\s*\*\s*1000', content):
                file_warnings.append("Episode numbering offset bug (season * 1000) — triggers false 'Missing 1000 items' badge in AniZen. Use epNum.toFloat() or ((season - 1) * 100 + ep).toFloat()")

            # 14. Missing initialized = true in getAnimeDetails
            if "getAnimeDetails" in content and "initialized = true" not in content and "abstract class" not in content and "interface " not in content:
                file_warnings.append("Missing 'initialized = true' inside getAnimeDetails — causes continuous detail re-fetch loops in Aniyomi v16")

            # 15. Preference keys declared outside companion object
            if re.search(r'private\s+const\s+val\s+PREF_', content):
                if "companion object" not in content:
                    file_warnings.append("Preference key constants declared outside companion object — should be inside companion object")

            if file_warnings:
                for w in file_warnings:
                    print(f"  ⚠️  {rel_path}: {w}")
                warnings += len(file_warnings)

    if warnings == 0:
        print("  ✓ No lint warnings or code smells detected across codebase.")
    else:
        print(f"\nSummary: {warnings} lint warning(s) found.")
    return True


def audit_all(repo_root: Path) -> bool:
    """Runs a full repository audit: static validation, code linting, workspace cleaning, and catalog doc generation."""
    print("🛡️ Running Master Repository Audit & Health Check...\n" + "=" * 60)
    print("Step 1/4: Static Extension Validation")
    v_ok = validate_extensions(repo_root)
    print("\n" + "-" * 60)

    print("Step 2/4: Kotlin Codebase Quality Linting")
    l_ok = lint_codebase(repo_root)
    print("\n" + "-" * 60)

    print("Step 3/4: Workspace Cache & Artifact Cleaning")
    c_ok = clean_workspace(repo_root)
    print("\n" + "-" * 60)

    print("Step 4/4: Extension Catalog Documentation Sync")
    d_ok = generate_doc(repo_root)
    print("\n" + "=" * 60)

    success = v_ok and l_ok and c_ok and d_ok
    if success:
        print("🎉 Master Repository Audit Passed Cleanly!")
    else:
        print("⚠️ Master Repository Audit Completed with Warnings/Issues.")
    return success


def doctor(repo_root: Path) -> bool:
    """Diagnoses developer environment: Python, Git, ImageMagick, Java/Gradle, Android SDK, and lib/ health."""
    print("🩺 Running Aniyomi Developer Environment Doctor & Diagnostic Health Check...\n" + "=" * 70)
    all_ok = True
    warnings = 0
    errors = 0

    # 1. Python Environment Check
    print("1️⃣  Python Runtime Environment:")
    py_ver = sys.version_info
    py_str = f"{py_ver.major}.{py_ver.minor}.{py_ver.micro}"
    if py_ver >= (3, 10):
        print(f"  ✅ Python {py_str} ({sys.executable})")
    else:
        print(f"  ❌ Python {py_str} is outdated (Python 3.10+ required)")
        all_ok = False
        errors += 1

    # Check optional packages
    opt_pkgs = {"bs4": "BeautifulSoup4 (HTML DOM parsing)", "PIL": "Pillow (Icon processing)"}
    for mod, desc in opt_pkgs.items():
        try:
            __import__(mod)
            print(f"  ✅ Optional Package: {desc} is available")
        except ImportError:
            print(f"  ℹ️  Optional Package: {desc} not installed (stdlib fallbacks active)")

    # 2. Git Version Control Check
    print("\n2️⃣  Git Version Control System:")
    git_bin = shutil.which("git")
    if git_bin:
        try:
            git_ver_out = subprocess.run(["git", "--version"], capture_output=True, text=True, check=True).stdout.strip()
            print(f"  ✅ {git_ver_out} ({git_bin})")
            branch_res = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=repo_root, capture_output=True, text=True)
            active_branch = branch_res.stdout.strip() if branch_res.returncode == 0 else "unknown"
            remote_res = subprocess.run(["git", "remote", "get-url", "origin"], cwd=repo_root, capture_output=True, text=True)
            remote_url = remote_res.stdout.strip() if remote_res.returncode == 0 else "No remote origin configured"
            print(f"  ℹ️  Active Branch: `{active_branch}` | Origin: `{remote_url}`")
        except Exception as e:
            print(f"  ⚠️  Git installed but repository query encountered issue: {e}")
            warnings += 1
    else:
        print("  ❌ Git is not installed or not found on PATH.")
        all_ok = False
        errors += 1

    # 3. Image Processing (ImageMagick)
    print("\n3️⃣  Image Processing Tooling:")
    img_bin = shutil.which("convert") or shutil.which("magick")
    if img_bin:
        print(f"  ✅ ImageMagick rasterizer available ({img_bin})")
    else:
        print("  ℹ️  ImageMagick (convert/magick) not found on PATH (pure-Python stdlib PNG generator active)")

    # 4. Java & Android Environment
    print("\n4️⃣  Java & Android SDK Environment:")
    java_bin = shutil.which("java")
    if java_bin:
        try:
            java_ver_out = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
            print(f"  ✅ {java_ver_out} ({java_bin})")
        except Exception:
            print(f"  ✅ Java runtime found ({java_bin})")
    else:
        print("  ℹ️  Java runtime not on PATH (Not required for Python CLI developer workflow)")

    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if android_home and Path(android_home).exists():
        print(f"  ✅ Android SDK: {android_home}")
    else:
        print("  ℹ️  ANDROID_HOME not set (Remote CI builds handle APK compilation)")

    # 5. Shared Extractors (lib/ health)
    print("\n5️⃣  Shared Extractor Libraries (`lib/`):")
    lib_dir = repo_root / "lib"
    if lib_dir.exists():
        extractors = [d.name for d in lib_dir.iterdir() if d.is_dir()]
        print(f"  ✅ Found {len(extractors)} shared extractor modules in lib/")
    else:
        print("  ❌ lib/ directory missing.")
        all_ok = False
        errors += 1

    print("\n" + "=" * 70)
    if all_ok and errors == 0:
        print("🎉 Doctor Diagnosis: System environment is fully operational!")
        return True
    else:
        print(f"⚠️ Doctor Diagnosis: Found {errors} error(s) and {warnings} warning(s).")
        return False


def main():
    try:
        repo_root = Path(__file__).resolve().parent.parent
        scripts_dir = repo_root / "scripts"

        commands_info = {
            "create": {
                "script": "create_extension.py",
                "desc": "Scaffold a new Aniyomi extension module (HTML, API, or Theme) with preferences, metadata, and extractors."
            },
            "create-theme": {
                "script": None,
                "desc": "Scaffold a brand new multi-source theme module in lib-multisrc/<theme_name>."
            },
            "doctor": {
                "script": None,
                "desc": "Diagnose developer environment (Python, Git, ImageMagick, Java/Gradle, Android SDK, lib/ health)."
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
            "bump-theme": {
                "script": None,
                "desc": "Increment baseVersionCode in lib-multisrc/ or overrideVersionCode across theme variants."
            },
            "bump-lib": {
                "script": None,
                "desc": "Cascade version bumps to all extension modules depending on a shared extractor in lib/."
            },
            "migrate-domain": {
                "script": None,
                "desc": "Automate base URL domain migrations across Kotlin sources, update preferences, bump version, and test reachability."
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
            },
            "format": {
                "script": None,
                "desc": "Format Kotlin, Gradle, and XML files (strip trailing whitespace, normalize CRLF, ensure final newline)."
            },
            "audit-all": {
                "script": None,
                "desc": "Run full repository health audit (validation, linting, cache cleaning, and doc catalog sync)."
            },
            "test-pipeline": {
                "script": "test_pipeline.py",
                "desc": "Run full 5-stage automated scraper verification (Popular -> Details -> Episodes -> Hosters -> Video Streams)."
            },
            "fix": {
                "script": "ast_fixer.py",
                "desc": "Auto-remediate Kotlin AST code smells and API v16 model invariants automatically."
            },
            "probe-stream": {
                "script": "probe_stream.py",
                "desc": "Deep media inspector for HLS (M3U8), DASH, direct video streams, codecs, and subtitles."
            },
            "json-to-dto": {
                "script": "json_to_dto.py",
                "desc": "Convert JSON API responses, files, or HAR dumps to null-safe Kotlinx serialization DTOs."
            },
            "deobfuscate": {
                "script": "deobfuscate.py",
                "desc": "Reverse-engineer Dean Edwards, PlayerJS, CryptoJS AES, and Stego media payloads."
            },
            "canary-monitor": {
                "script": "canary_monitor.py",
                "desc": "Monitor health across all 65+ video extractors in lib/ and generate health matrices."
            },
            "test-filters": {
                "script": "test_filters.py",
                "desc": "Combinatorial search filter matrix fuzzer testing filter permutations against live endpoints."
            },
            "sandbox": {
                "script": "sandbox.py",
                "desc": "Zero-APK fast in-memory Kotlin runtime simulator for popular, search, and detail workflows."
            }
        }

        epilog_lines = [f"Available Commands ({len(commands_info)} Total):", "-" * 40]
        for i, (cmd_name, cmd_data) in enumerate(commands_info.items(), 1):
            epilog_lines.append(f"  {i:2d}. {cmd_name:18s} {cmd_data['desc']}")
        epilog_lines.extend([
            "",
            "Examples:",
            "  python3 scripts/cli.py doctor",
            "  python3 scripts/cli.py create -i",
            "  python3 scripts/cli.py fix <module>",
            "  python3 scripts/cli.py probe-stream 'https://example.com/master.m3u8' --deep",
            "  python3 scripts/cli.py json-to-dto https://api.site.com/anime/1",
            "  python3 scripts/cli.py deobfuscate 'eval(function(p,a,c,k,e,r)...)'",
            "  python3 scripts/cli.py canary-monitor --export",
            "  python3 scripts/cli.py test-filters <module>",
            "  python3 scripts/cli.py sandbox <module> --action popular",
            "  python3 scripts/cli.py format <module>",
            "  python3 scripts/cli.py bump-version <module>",
            "  python3 scripts/cli.py publish <module> -m 'fix episode parsing'",
            "  python3 scripts/cli.py audit-all"
        ])

        parser = argparse.ArgumentParser(
            description="🚀 Aniyomi AI Extension Engine Master CLI",
            formatter_class=argparse.RawTextHelpFormatter,
            epilog="\n".join(epilog_lines)
        )

        parser.add_argument("command", choices=list(commands_info.keys()), help="Subcommand to run")
        parser.add_argument("args", nargs=argparse.REMAINDER, help="Arguments passed to the subcommand")

        if len(sys.argv) == 1:
            parser.print_help()
            sys.exit(0)

        args = parser.parse_args()

        if args.command == "doctor":
            success = doctor(repo_root)
            sys.exit(0 if success else 1)

        if args.command == "create-theme":
            theme_parser = argparse.ArgumentParser(prog="cli.py create-theme")
            theme_parser.add_argument("theme_name", help="Name of the new theme (e.g. 'streamwish', 'dooplay')")
            theme_args = theme_parser.parse_args(args.args)
            from scripts.create_extension import generate_theme_scaffold
            success = generate_theme_scaffold(theme_args.theme_name, repo_root)
            sys.exit(0 if success else 1)

        if args.command == "migrate-domain":
            mig_parser = argparse.ArgumentParser(prog="cli.py migrate-domain")
            mig_parser.add_argument("target", help="Target extension module (e.g. 'vegamovies' or 'en/vegamovies')")
            mig_parser.add_argument("--new-domain", required=True, help="New base URL domain (e.g. 'https://newdomain.com')")
            mig_parser.add_argument("--no-test", action="store_true", help="Skip live HTTP reachability check")
            mig_args = mig_parser.parse_args(args.args)
            success = migrate_domain(repo_root, mig_args.target, mig_args.new_domain, test_reachability=not mig_args.no_test)
            sys.exit(0 if success else 1)

        if args.command == "bump-theme":
            bt_parser = argparse.ArgumentParser(prog="cli.py bump-theme")
            bt_parser.add_argument("theme_name", help="Theme package name (e.g. 'anikototheme', 'dooplay')")
            bt_parser.add_argument("--base", action="store_true", help="Bump baseVersionCode in lib-multisrc (default)")
            bt_parser.add_argument("--variants", action="store_true", help="Bump overrideVersionCode for all theme variants")
            bt_parser.add_argument("--all", action="store_true", help="Bump both baseVersionCode and all variant overrideVersionCodes")
            bt_args = bt_parser.parse_args(args.args)
            mode = "all" if bt_args.all else ("variants" if bt_args.variants else "base")
            success = bump_theme(repo_root, bt_args.theme_name, mode=mode)
            sys.exit(0 if success else 1)

        if args.command == "bump-lib":
            bl_parser = argparse.ArgumentParser(prog="cli.py bump-lib")
            bl_parser.add_argument("lib_name", help="Extractor library name in lib/ (e.g. 'dood-extractor', 'playlist-utils')")
            bl_args = bl_parser.parse_args(args.args)
            success = bump_lib_dependents(repo_root, bl_args.lib_name)
            sys.exit(0 if success else 1)

        if args.command == "format":
            fmt_parser = argparse.ArgumentParser(prog="cli.py format")
            fmt_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            fmt_parser.add_argument("--lang", help="Target extension lang")
            fmt_parser.add_argument("--name", help="Target extension directory name")
            fmt_parser.add_argument("--check", action="store_true", help="Check formatting status without modifying files")
            fmt_args = fmt_parser.parse_args(args.args)
            lang, name = resolve_extension_target(repo_root, fmt_args.target, fmt_args.lang, fmt_args.name)
            success = format_codebase(repo_root, lang, name, check_only=fmt_args.check)
            sys.exit(0 if success else 1)

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
            lint_parser = argparse.ArgumentParser(prog="cli.py lint")
            lint_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            lint_parser.add_argument("--lang", help="Target extension lang")
            lint_parser.add_argument("--name", help="Target extension directory name")
            lint_args = lint_parser.parse_args(args.args)
            lang, name = resolve_extension_target(repo_root, lint_args.target, lint_args.lang, lint_args.name)
            success = lint_codebase(repo_root, lang, name)
            sys.exit(0 if success else 1)

        if args.command == "audit-all":
            success = audit_all(repo_root)
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
            pub_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            pub_parser.add_argument("--lang", help="Target extension lang")
            pub_parser.add_argument("--name", help="Target extension directory name")
            pub_parser.add_argument("-m", "--message", help="Commit message")
            pub_parser.add_argument("--no-bump", action="store_true", help="Skip version bump if version code was already incremented")
            pub_args = pub_parser.parse_args(args.args)
            if not pub_args.target and not (pub_args.lang and pub_args.name):
                pub_parser.print_help()
                print("\n❌ Error: Target extension module is required (e.g. `cli.py publish <module>`).")
                sys.exit(1)
            lang, name = resolve_extension_target(repo_root, pub_args.target, pub_args.lang, pub_args.name)
            success = publish_extension(repo_root, lang, name, pub_args.message, no_bump=pub_args.no_bump)
            sys.exit(0 if success else 1)

        if args.command == "validate":
            val_parser = argparse.ArgumentParser(prog="cli.py validate")
            val_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            val_parser.add_argument("--lang", help="Target extension lang")
            val_parser.add_argument("--name", help="Target extension directory name")
            val_parser.add_argument("--all", action="store_true", help="Validate all extensions")
            val_args = val_parser.parse_args(args.args)
            if val_args.all:
                lang, name = None, None
            else:
                lang, name = resolve_extension_target(repo_root, val_args.target, val_args.lang, val_args.name)
            success = validate_extensions(repo_root, lang, name)
            sys.exit(0 if success else 1)

        if args.command == "bump-version":
            bump_parser = argparse.ArgumentParser(prog="cli.py bump-version")
            bump_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            bump_parser.add_argument("--lang", help="Target extension lang")
            bump_parser.add_argument("--name", help="Target extension directory name")
            bump_args = bump_parser.parse_args(args.args)
            if not bump_args.target and not (bump_args.lang and bump_args.name):
                bump_parser.print_help()
                print("\n❌ Error: Target extension module is required (e.g. `cli.py bump-version <module>`).")
                sys.exit(1)
            lang, name = resolve_extension_target(repo_root, bump_args.target, bump_args.lang, bump_args.name)
            success = bump_version(repo_root, lang, name)
            sys.exit(0 if success else 1)

        if args.command == "test-pipeline":
            pipe_parser = argparse.ArgumentParser(prog="cli.py test-pipeline")
            pipe_parser.add_argument("target", help="Target extension name (e.g. 'zinkmovies' or 'vegamovies') or base URL")
            pipe_parser.add_argument("--query", "-q", help="Optional search query to test search flow")
            pipe_args = pipe_parser.parse_args(args.args)
            from scripts.test_pipeline import PipelineTester
            target = pipe_args.target
            if target.startswith("http://") or target.startswith("https://"):
                base_url = target
            else:
                found_base_url = None
                for kt in repo_root.rglob("*.kt"):
                    if kt.parent.name == target or target in kt.name.lower():
                        content = kt.read_text(encoding="utf-8", errors="ignore")
                        m = re.search(r'PREF_BASE_URL_DEFAULT\s*=\s*["\']([^"\']+)["\']', content)
                        if not m:
                            m = re.search(r'override\s+val\s+baseUrl\s*=\s*["\']([^"\']+)["\']', content)
                        if m:
                            found_base_url = m.group(1)
                            break
                if not found_base_url:
                    print(f"❌ Could not resolve base URL for module: {target}")
                    sys.exit(1)
                base_url = found_base_url
            tester = PipelineTester(base_url)
            success = tester.run(query=pipe_args.query)
            sys.exit(0 if success else 1)

        if args.command == "info":
            info_parser = argparse.ArgumentParser(prog="cli.py info")
            info_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            info_parser.add_argument("--lang", help="Target extension lang")
            info_parser.add_argument("--name", help="Target extension directory name")
            info_args = info_parser.parse_args(args.args)
            if not info_args.target and not (info_args.lang and info_args.name):
                info_parser.print_help()
                print("\n❌ Error: Target extension module is required (e.g. `cli.py info <module>`).")
                sys.exit(1)
            lang, name = resolve_extension_target(repo_root, info_args.target, info_args.lang, info_args.name)
            success = show_info(repo_root, lang, name)
            sys.exit(0 if success else 1)

        if args.command == "fetch-icon":
            fetch_parser = argparse.ArgumentParser(prog="cli.py fetch-icon")
            fetch_parser.add_argument("--url", required=True, help="Target website URL")
            fetch_parser.add_argument("target", nargs="?", help="Target extension name")
            fetch_parser.add_argument("--lang", help="Target extension lang")
            fetch_parser.add_argument("--name", help="Target extension directory name")

            icon_args = fetch_parser.parse_args(args.args)
            lang, name = resolve_extension_target(repo_root, icon_args.target, icon_args.lang, icon_args.name)
            if not name:
                print("❌ Error: Target extension name is required (e.g. `cli.py fetch-icon --url <url> <target>`).")
                sys.exit(1)
            out_path = repo_root / "src" / (lang or "en") / name / "res" / "drawable" / "ic_launcher.png"
            success = fetch_icon(icon_args.url, out_path)
            sys.exit(0 if success else 1)

        script_name = commands_info[args.command]["script"]
        script_path = scripts_dir / script_name

        if not script_path.exists():
            print(f"❌ Script not found: {script_path}")
            sys.exit(1)

        cmd = [sys.executable, str(script_path)] + args.args
        result = subprocess.run(cmd)
        sys.exit(result.returncode)

    except KeyboardInterrupt:
        print("\n🛑 Operation cancelled by user.")
        sys.exit(130)
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
