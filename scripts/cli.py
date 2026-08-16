#!/usr/bin/env python3
"""
Aniyomi Extension Engine Master CLI Tool
Unified entrypoint for developers and AI coding agents to scaffold, test, validate,
lint, migrate domains, bump version, and manage individual and multi-source extensions.
"""

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.parse
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
        matches = []
        if src_dir.exists():
            for lang_dir in sorted(src_dir.iterdir()):
                if lang_dir.is_dir() and (lang_dir / resolved_name).exists():
                    matches.append(lang_dir.name)
        if len(matches) == 1:
            return matches[0], resolved_name
        elif len(matches) > 1:
            print(f"⚠️ Ambiguous target '{resolved_name}' found in multiple languages: {matches}. Defaulting to '{matches[0]}'. (Specify as '{matches[0]}/{resolved_name}' to avoid ambiguity)")
            return matches[0], resolved_name

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
        with urllib.request.urlopen(req, timeout=5) as resp:
            html = resp.read().decode('utf-8', errors='ignore')
        matches = re.finditer(r'<link\b(?=[^>]*?\brel=[\"\'](?:shortcut )?icon[\"\'])[^>]*?\bhref=(?:\"([^\"]+)\"|\'([^\']+)\')', html, re.IGNORECASE)
        for m in matches:
            icon = m.group(1) or m.group(2)
            if not icon:
                continue
            if icon.startswith("data:"):
                fav_candidates.insert(0, icon)
            elif icon.startswith("//"):
                fav_candidates.insert(0, f"https:{icon}")
            elif icon.startswith("/"):
                fav_candidates.insert(0, f"{base_url}{icon}")
            elif icon.startswith("http"):
                fav_candidates.insert(0, icon)
            else:
                fav_candidates.insert(0, f"{base_url}/{icon}")
    except Exception as e:
        print(f"  [!] Notice: HTML favicon extraction skipped ({e})")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temp_raw = output_path.parent / "temp_favicon.raw"
    temp_file = None
    success = False

    for fav_url in fav_candidates:
        try:
            print(f"  -> Trying: {fav_url[:80]}{'...' if len(fav_url) > 80 else ''}")
            if fav_url.startswith("data:"):
                if "base64," in fav_url:
                    b64_data = fav_url.split("base64,", 1)[1]
                    raw_bytes = base64.b64decode(b64_data)
                else:
                    data_part = fav_url.split(",", 1)[1] if "," in fav_url else ""
                    raw_bytes = urllib.parse.unquote(data_part).encode("utf-8")
                ext = ".svg" if "svg" in fav_url else ".png"
                temp_file = output_path.parent / f"temp_favicon{ext}"
                with open(temp_file, "wb") as f:
                    f.write(raw_bytes)
                success = True
                break
            else:
                req = urllib.request.Request(fav_url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
                with urllib.request.urlopen(req, timeout=4) as response:
                    content = response.read()
                    if len(content) > 50:
                        if b"<svg" in content[:200] or ".svg" in fav_url:
                            ext = ".svg"
                        elif ".ico" in fav_url:
                            ext = ".ico"
                        else:
                            ext = ".png"
                        temp_file = output_path.parent / f"temp_favicon{ext}"
                        with open(temp_file, "wb") as f:
                            f.write(content)
                        success = True
                        break
        except Exception:
            continue

    if not success or not temp_file or not temp_file.exists():
        print("❌ Could not download favicon from target site.")
        return False

    conv = shutil.which("convert") or shutil.which("magick")
    destinations = [output_path]
    xxhdpi_path = output_path.parent.parent / "drawable-xxhdpi" / "icon.png"
    if xxhdpi_path.parent.exists() or (output_path.parent.parent / "drawable-xxhdpi").exists():
        xxhdpi_path.parent.mkdir(parents=True, exist_ok=True)
        destinations.append(xxhdpi_path)

    if conv:
        try:
            input_spec = str(temp_file) if temp_file.suffix != ".ico" else f"{temp_file}[0]"
            for dest in destinations:
                dest.parent.mkdir(parents=True, exist_ok=True)
                cmd = [conv, "-background", "none", "-density", "300", input_spec, "-resize", "192x192", str(dest)]
                subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
            if temp_file.exists():
                temp_file.unlink()
            print(f"✅ Successfully created 192x192 launcher icon: {output_path}")
            return True
        except Exception as e:
            print(f"⚠️ ImageMagick convert failed: {e}")

    # Fallback to pure Python minimal PNG generator if convert fails
    from scripts.create_extension import create_minimal_png
    for dest in destinations:
        dest.parent.mkdir(parents=True, exist_ok=True)
        create_minimal_png(dest, 192, 192)
    if temp_file and temp_file.exists():
        temp_file.unlink()
    print(f"⚠️  ImageMagick unavailable — generated a blank placeholder 192x192 PNG at {output_path}.")
    print("   Replace it with the real favicon manually for production use.")
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

    # Check extVersionCode — use multiline regex to exclude comment lines (// or #)
    m_ext = re.search(r"(?m)^(?![ \t]*//).*?extVersionCode\s*=\s*(\d+)", content)
    if m_ext:
        old_ver = int(m_ext.group(1))
        new_ver = old_ver + 1
        new_content = re.sub(r"(?m)^((?![ \t]*//).*?)extVersionCode\s*=\s*\d+", lambda mo: mo.group(1) + f"extVersionCode = {new_ver}", content, count=1)
        gradle_file.write_text(new_content, encoding="utf-8")
        print(f"🚀 Bumped {name} (src/{lang}/{name}) extVersionCode: {old_ver} -> {new_ver}")
        return True

    # Check overrideVersionCode — exclude comment lines
    m_over = re.search(r"(?m)^(?![ \t]*//).*?overrideVersionCode\s*=\s*(\d+)", content)
    if m_over:
        old_ver = int(m_over.group(1))
        new_ver = old_ver + 1
        new_content = re.sub(r"(?m)^((?![ \t]*//).*?)overrideVersionCode\s*=\s*\d+", lambda mo: mo.group(1) + f"overrideVersionCode = {new_ver}", content, count=1)
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


def migrate_domain(repo_root: Path, target: str, new_domain: str, test_reachability: bool = True, dry_run: bool = False) -> bool:
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
    mode_label = "[DRY RUN] " if dry_run else ""
    print(f"🌐 {mode_label}Migrating domain for src/{lang}/{name} to: {new_domain_clean}\n" + "=" * 60)

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
    action_verb = "Would update" if dry_run else "Updating"
    print(f"\n2️⃣ {action_verb} baseUrl in Kotlin source files & build.gradle...")
    kt_dir = ext_path / "src"
    updated_files = 0
    if kt_dir.exists():
        for kt_file in kt_dir.rglob("*.kt"):
            txt = kt_file.read_text(encoding="utf-8")

            # Replace baseUrl default constants or hardcoded override
            new_txt = re.sub(r'PREF_BASE_URL_DEFAULT\s*=\s*["\'][^"\']+["\']', lambda _: f'PREF_BASE_URL_DEFAULT = "{new_domain_clean}"', txt)
            new_txt = re.sub(r'override\s+val\s+baseUrl\s*=\s*["\'][^"\']+["\']', lambda _: f'override val baseUrl = "{new_domain_clean}"', new_txt)

            if txt != new_txt:
                if not dry_run:
                    kt_file.write_text(new_txt, encoding="utf-8")
                updated_files += 1
                verb = "Would update" if dry_run else "Updated"
                print(f"  ✓ {verb} {kt_file.name}")

    gradle_file = ext_path / "build.gradle"
    if gradle_file.exists():
        g_txt = gradle_file.read_text(encoding="utf-8")
        new_g_txt = re.sub(
            r'baseUrl\s*=\s*(["\'])([^"\']+)\1',
            lambda m: f"baseUrl = {m.group(1)}{new_domain_clean}{m.group(1)}",
            g_txt
        )
        if g_txt != new_g_txt:
            if not dry_run:
                gradle_file.write_text(new_g_txt, encoding="utf-8")
            updated_files += 1
            verb = "Would update" if dry_run else "Updated"
            print(f"  ✓ {verb} build.gradle")

    # 3. Bump version code
    if not dry_run:
        print("\n3️⃣ Incrementing extension version code...")
        bump_version(repo_root, lang, name)
    else:
        print("\n3️⃣ [DRY RUN] Would increment extVersionCode in build.gradle")

    finish_verb = "simulation complete" if dry_run else "complete"
    print(f"\n🎉 Domain migration {finish_verb} for src/{lang}/{name} ({updated_files} file(s) affected).")
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

    # 1. Run auto-fix, dependency detection, formatting & static validation
    print("1️⃣ Running pre-flight auto-remediation, formatting & static validation...")
    try:
        from scripts.ast_fixer import fix_codebase
        fix_codebase(repo_root, lang, name)
    except Exception as e:
        print(f"  [!] Notice: AST auto-remediation skipped ({e})")

    detect_script = repo_root / "scripts" / "detect_extractors.py"
    if detect_script.exists():
        subprocess.run([sys.executable, str(detect_script), "--lang", lang, "--name", name, "--fix"], cwd=repo_root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)

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

    # 3. Stage changes — only the extension directory to avoid committing unrelated
    #    in-progress changes in lib/, lib-multisrc/, or core/.
    print("\n3️⃣ Staging git changes...")
    cmd_add = ["git", "add", str(ext_path)]
    subprocess.run(cmd_add, cwd=repo_root, check=True, timeout=30)

    git_env = os.environ.copy()
    git_env["GIT_TERMINAL_PROMPT"] = "0"

    # 4. Commit
    msg = commit_msg or f"{name}: bump version and maintenance update"
    print(f"\n4️⃣ Committing: '{msg}'...")
    cmd_commit = ["git", "commit", "-m", msg]
    commit_res = subprocess.run(cmd_commit, cwd=repo_root, capture_output=True, text=True, env=git_env, timeout=30)
    if commit_res.returncode != 0:
        if "nothing to commit" in (commit_res.stdout + commit_res.stderr):
            print("ℹ️ Nothing to commit, working tree clean.")
        else:
            print(f"❌ Git commit failed: {commit_res.stderr}")
            return False
    else:
        print("✓ Committed successfully.")

    # 5. Push
    print("\n5️⃣ Pushing to remote GitHub repository...")
    cmd_push = ["git", "push"]
    push_res = subprocess.run(cmd_push, cwd=repo_root, capture_output=True, text=True, env=git_env, timeout=60)
    if push_res.returncode != 0:
        branch_res = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=repo_root, capture_output=True, text=True)
        current_branch = branch_res.stdout.strip() or "master"
        if "set-upstream" in push_res.stderr or "no upstream branch" in push_res.stderr:
            print(f"  -> Setting upstream tracking for branch: {current_branch}...")
            push_res = subprocess.run(["git", "push", "-u", "origin", current_branch], cwd=repo_root, capture_output=True, text=True, env=git_env, timeout=60)
        elif "rejected" in push_res.stderr or "fetch first" in push_res.stderr or "non-fast-forward" in push_res.stderr:
            print(f"  -> Remote has newer commits. Syncing branch '{current_branch}' via git pull --rebase...")
            pull_res = subprocess.run(["git", "pull", "--rebase", "origin", current_branch], cwd=repo_root, capture_output=True, text=True, env=git_env, timeout=60)
            if pull_res.returncode == 0:
                print("  -> Rebase succeeded. Retrying push...")
                push_res = subprocess.run(["git", "push", "origin", current_branch], cwd=repo_root, capture_output=True, text=True, env=git_env, timeout=60)
            else:
                print(f"❌ Git pull --rebase failed: {pull_res.stderr}")
                return False
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
            kt_files = list(kt_dir.rglob("*.kt")) if kt_dir.exists() else []
            if not kt_files:
                issues.append(f"Missing main Kotlin source in: src/eu/kanade/tachiyomi/animeextension/{lang}/{ext_name}/")

            expected_pkg = f"eu.kanade.tachiyomi.animeextension.{lang}.{ext_name}"
            for kt in kt_files:
                content = kt.read_text(encoding="utf-8", errors="ignore")
                pkg_match = re.search(r"^\s*package\s+([^\s;]+)", content, re.MULTILINE)
                if not pkg_match or not (pkg_match.group(1) == expected_pkg or pkg_match.group(1).startswith(f"{expected_pkg}.")):
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
    for p in repo_root.rglob("temp_favicon*"):
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

            # Collapse 2+ consecutive empty lines to 1 (ktlint allows at most 1 blank line)
            collapsed = []
            empty_count = 0
            for line in cleaned_lines:
                if not line:
                    empty_count += 1
                    if empty_count <= 1:
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

    # Kotlin continuation-line suffixes: if a line ends with any of these,
    # it is joined with the following line(s) until a closing )/}/; is found.
    _CONTINUATION_SUFFIX_RE = re.compile(
        r'(?:[\(\,\+]|&&|\|\||->|\.)[ \t]*$'
    )
    _CLOSED_RE = re.compile(r'[)\};][ \t]*(?://.*)?$')

    def _join_continuation_lines(raw_lines: list) -> list:
        """Collapse Kotlin continuation lines into logical lines.

        Returns a list of (first_lineno, joined_text) tuples where
        *first_lineno* is 1-indexed and maps back to the original file.
        """
        joined: list = []
        i = 0
        while i < len(raw_lines):
            lineno = i + 1
            buf = raw_lines[i].rstrip('\n')
            while _CONTINUATION_SUFFIX_RE.search(buf) and not _CLOSED_RE.search(buf):
                i += 1
                if i >= len(raw_lines):
                    break
                buf = buf.rstrip() + ' ' + raw_lines[i].strip().rstrip('\n')
            joined.append((lineno, buf))
            i += 1
        return joined

    def _warn_on_joined(logical_lines: list, file_warnings: list, pattern, message, flags=0):
        """Emit a warning if *pattern* matches any logical line, reporting
        the first physical line number of the matched group."""
        for lineno, text in logical_lines:
            if re.search(pattern, text, flags):
                file_warnings.append(f"[L{lineno}] {message}")
                return  # report once per file

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

            # Build joined logical lines for multi-line pattern detection.
            raw_lines = content.splitlines(keepends=True)
            logical_lines = _join_continuation_lines(raw_lines)
            # joined_content reassembles the logical lines for full-file patterns.
            joined_content = '\n'.join(text for _, text in logical_lines)

            # 1. Blocking Thread.sleep call
            if "Thread.sleep" in content:
                file_warnings.append("Blocking Thread.sleep call found (use rateLimit() or delay() in coroutines)")

            # 2. Raw baseUrl string concatenation instead of absUrl()
            if re.search(r'"\$baseUrl"\s*\+\s*\w+\.attr\(', joined_content):
                file_warnings.append('Manual "$baseUrl" + attr() prepend — use element.attr("abs:src") or absUrl() instead')

            # 3. Date parsing without runCatching
            for match in re.findall(r'SimpleDateFormat\([^)]+\)\.parse\(', joined_content):
                idx = joined_content.find(match)
                ctx = joined_content[max(0, idx - 200):idx + 50]
                if "runCatching" not in ctx:
                    file_warnings.append("Date parsing without runCatching wrapping — can throw ParseException")
                    break

            # 4. Sequential for-loop over embed URLs instead of parallelCatchingFlatMap
            if re.search(r'for\s*\(\w+\s+in\s+(?:hosters|embedUrls|servers|links)\)', joined_content):
                if "parallelCatchingFlatMap" not in content:
                    file_warnings.append("Sequential for-loop over hosters/servers — consider parallelCatchingFlatMap for parallel extraction")

            # 5. Raw json.decodeFromString without parseAs<> wrapper
            if re.search(r'json\.decodeFromString<', joined_content) and "parseAs<" not in content:
                file_warnings.append("Raw json.decodeFromString<> — prefer response.parseAs<T>() wrapper pattern")

            # 6. Force-unwrap null!! on preference getString
            if re.search(r'preferences\.getString\([^)]+\)!!', joined_content):
                file_warnings.append('Force-unwrap preferences.getString()!! — use ?: "default" fallback instead')

            # 7. Hardcoded session/CF cookies in headers
            if re.search(r'(?:cf_clearance|PHPSESSID|__cfduid)["\']', joined_content):
                file_warnings.append("Hardcoded session/CF cookie literal found — cookies should be fetched dynamically")

            # 8. Deprecated it.quality Video property
            if "it.quality" in content:
                file_warnings.append("Deprecated Video property 'it.quality' — use it.videoTitle (v16 API)")

            # 9. Deprecated positional Video constructor — use joined_content so
            #    multi-line Video( calls are caught.
            _warn_on_joined(
                logical_lines, file_warnings,
                r'\bVideo\s*\([^)]*,[^)]*,[^)]*,[^)]*\)',
                "Deprecated 4-arg positional Video(...) constructor — use Video(videoUrl=, videoTitle=, headers=)",
            )
            # Suppress if named form is already used
            if any("[L" in w and "4-arg positional Video" in w for w in file_warnings):
                if re.search(r'\bVideo\s*\(\s*videoUrl\s*=', joined_content):
                    file_warnings = [w for w in file_warnings if "4-arg positional Video" not in w]

            # 10. Companion object syntax mistake
            if re.search(r'\bcompanion\s*\{', joined_content):
                file_warnings.append("Syntax error 'companion {' — must be 'companion object {'")

            # 11. DTO Null-Safety
            if "@Serializable" in content and "data class" in content:
                for match in re.finditer(r'@Serializable(?:\([^)]*\))?\s+(?:private\s+|protected\s+|internal\s+|public\s+)?data\s+class\s+(\w+)\s*\([\s\S]*?\)', content):
                    cls_name = match.group(1)
                    param_block = match.group(2) if match.lastindex and match.lastindex >= 2 else ""
                    for line in param_block.splitlines():
                        clean_line = line.strip().rstrip(",")
                        # Strip annotations like @SerialName("...") or @Transient
                        clean_line = re.sub(r'@[A-Za-z0-9_]+(?:\([^)]*\))?\s*', '', clean_line).strip()
                        if clean_line.startswith("val ") or clean_line.startswith("var "):
                            prop = clean_line.split(":")[0].replace("val ", "").replace("var ", "").strip()
                            if "=" not in clean_line and not clean_line.endswith("?"):
                                file_warnings.append(f"DTO null-safety violation in {cls_name}.{prop} — missing default fallback (e.g. `? = null`)")
                            # Check if votes/rating/score are typed as Int/Long instead of Double/Float
                            if re.search(r'\b(?:votes|rating|score|stars|rank)\b', prop, re.IGNORECASE):
                                if re.search(r':\s*(?:Int|Long)\??', clean_line):
                                    file_warnings.append(f"DTO field {cls_name}.{prop} is typed as Int/Long — consider Double? to prevent JsonDecodingException on decimal JSON payloads (e.g. 0.0)")

            # 12. Dynamic / Ephemeral tokens in SEpisode.url
            if "SEpisode" in content and "setUrlWithoutDomain" in content:
                if re.search(r'setUrlWithoutDomain\([^)]*(?:\?token=|\?session=|\?sig=|\?expires=)', joined_content):
                    file_warnings.append("Ephemeral / dynamic token embedded in SEpisode.url — use stable permanent anchor URL (e.g. ${anime.url}#season=$s&ep=$e)")

            # 13. Subclassing extensions.utils.Source while redeclaring inherited json or preferences
            if "class " in content and ": Source()" in content:
                if re.search(r'(?:private|val)\s+json\s*(?::\s*Json)?\s*by\s+', content):
                    file_warnings.append("Redundant 'json' property declaration in Source subclass — 'json' is already provided by extensions.utils.Source")
                if re.search(r'(?:private|val)\s+preferences\s*(?::\s*SharedPreferences)?\s*by\s+', content):
                    file_warnings.append("Redundant 'preferences' property declaration in Source subclass — 'preferences' is already provided by extensions.utils.Source")

            # 14. setUrlWithoutDomain inside helper DTO classes
            if "data class " in content and "setUrlWithoutDomain(" in content:
                file_warnings.append("setUrlWithoutDomain() called inside data class/DTO where AnimeHttpSource receiver is not in scope — use this.url = ... instead")

            # 15. Non-zero base episode numbering (* 1000 offset anti-pattern)
            if re.search(r'\(\s*(?:globalSeason|seasonNum|seasonVal|season\.season_number|s)\s*\*\s*1000', joined_content):
                file_warnings.append("Episode numbering offset bug (season * 1000) — triggers false 'Missing 1000 items' badge in AniZen. Use epNum.toFloat() or ((season - 1) * 100 + ep).toFloat()")

            # 16. Missing initialized = true in getAnimeDetails
            if "getAnimeDetails" in content and "initialized = true" not in content and "abstract class" not in content and "interface " not in content:
                file_warnings.append("Missing 'initialized = true' inside getAnimeDetails — causes continuous detail re-fetch loops in Aniyomi v16")

            # 17. Preference keys declared outside companion object
            if re.search(r'private\s+const\s+val\s+PREF_', content):
                if "companion object" not in content:
                    file_warnings.append("Preference key constants declared outside companion object — should be inside companion object")

            # 18. Multi-Hoster / Folder Architecture Invariants
            if "override suspend fun getHosterList" in content:
                if "override suspend fun getVideoList(hoster: Hoster)" not in content and "override suspend fun getVideoList(" not in content:
                    file_warnings.append("Custom 'getHosterList' implemented but missing 'override suspend fun getVideoList(hoster: Hoster)'")
                if "hoster.hosterName" in content and re.search(r'videoNameGen\s*=\s*\{[^}]*\$\{hoster\.hosterName\}', content):
                    file_warnings.append("Redundant hoster name in videoNameGen prefix — the hoster folder already displays the server name in Aniyomi UI")

            # 19. runBlocking { } inside coroutine
            _warn_on_joined(
                logical_lines, file_warnings,
                r'\brunBlocking\s*\{',
                "runBlocking { } inside coroutine — use withContext(Dispatchers.IO) { } instead",
            )

            # 20. Double.toString() decimal suffix on whole-number episode strings
            _warn_on_joined(
                logical_lines, file_warnings,
                r'episode_number\s*\.\s*toString\s*\(\s*\)',
                "Direct Double.toString() on episode_number produces trailing '.0' (e.g. '1.0') — use episodeNumber.toInt().toString() or if (num % 1.0 == 0.0) num.toInt().toString() else num.toString()",
            )

            # 21. Invalid SAnime status constants
            invalid_sanime = re.findall(r'\bSAnime\.(NOT_YET_RELEASED|AIRING|RELEASING|FINISHED|HIATUS)\b', content)
            if invalid_sanime:
                file_warnings.append(f"Invalid SAnime status constant(s) '{', '.join(set(invalid_sanime))}' — use ONGOING, COMPLETED, LICENSED, PUBLISHING_FINISHED, CANCELLED, ON_HIATUS, or UNKNOWN")

            # 22. sortVideos non-extension declaration signature clash
            if re.search(r'(?:private\s+)?fun\s+sortVideos\s*\(\s*\w+\s*:\s*List<Video>\s*\)', content):
                file_warnings.append("Declared 'fun sortVideos(videos: List<Video>)' clashing with AnimeHttpSource — use 'override fun List<Video>.sortVideos(): List<Video>'")

            # 23. Redundant "Server:" prefix in hosterName
            if re.search(r'Hoster\s*\(\s*(?:hosterName\s*=\s*)?["\']Server:\s+', content):
                file_warnings.append('Redundant "Server:" prefix in hosterName — use clean provider name (e.g. "Misa", "MegaCloud")')

            # 24. Suspend extractor called from non-suspend private helper function
            suspend_extractors = ["vidMolyExtractor", "voeExtractor", "filemoonExtractor", "luluExtractor", "streamWishExtractor", "vidGuardExtractor"]
            for m in re.finditer(r'(?:private|protected|internal)?\s+fun\s+(\w+)\s*\([^)]*\)\s*(?::\s*List<Video>|\s*\{)', content):
                fn_name = m.group(1)
                full_match = m.group(0)
                if "suspend" not in full_match and fn_name not in ["videoListParse", "popularAnimeParse", "latestUpdatesParse", "searchAnimeParse", "animeDetailsParse", "episodeListParse", "setupPreferenceScreen", "sortVideos"]:
                    start_pos = m.end()
                    fn_chunk = content[start_pos:start_pos + 1200]
                    for ext in suspend_extractors:
                        if f"{ext}.videosFromUrl" in fn_chunk:
                            file_warnings.append(f"Function '{fn_name}' calls suspend extractor '{ext}.videosFromUrl' but is not marked 'suspend' — use 'private suspend fun {fn_name}(...)'")
                            break

            # 25. PlaylistUtils.extractFromHls parameter mismatch (subtitleTracks / audioTracks vs subtitleList / audioList)
            if re.search(r'extractFromHls\s*\([^)]*subtitleTracks\s*=', joined_content):
                file_warnings.append("PlaylistUtils.extractFromHls() parameter name mismatch: 'subtitleTracks' is invalid — use 'subtitleList = ...'")
            if re.search(r'extractFromHls\s*\([^)]*audioTracks\s*=', joined_content):
                file_warnings.append("PlaylistUtils.extractFromHls() parameter name mismatch: 'audioTracks' is invalid — use 'audioList = ...'")

            # 26. UniversalExtractor method name mismatch
            if re.search(r'universalExtractor\s*\.\s*toVideoList\s*\(', joined_content) or re.search(r'UniversalExtractor\s*\([^)]*\)\s*\.\s*toVideoList\s*\(', joined_content):
                file_warnings.append("UniversalExtractor method mismatch: '.toVideoList()' does not exist — use '.videosFromUrl(origRequestUrl, origRequestHeader, ...)'")


            if file_warnings:
                for w in file_warnings:
                    print(f"  ⚠️  {rel_path}: {w}")
                warnings += len(file_warnings)

    if warnings == 0:
        print("  ✓ No lint warnings or code smells detected across codebase.")
    else:
        print(f"\nSummary: {warnings} lint warning(s) found.")
    return warnings == 0


def inspect_hosters(repo_root: Path, target_lang: str, target_name: str) -> bool:
    """Inspects and audits hoster folder architecture, server grouping, and stream quality sorting."""
    target_src = repo_root / "src" / (target_lang or "all") / target_name
    if not target_src.exists():
        print(f"❌ Target extension directory not found: {target_src}")
        return False

    print(f"📁 Inspecting Hoster Folder Architecture for src/{target_lang}/{target_name}...\n" + "=" * 60)

    kt_files = list(target_src.rglob("*.kt"))
    has_hoster_list = False
    has_video_list_hoster = False
    has_server_pref = False
    has_sort_videos = False
    hoster_methods = []

    for kt in kt_files:
        content = kt.read_text(encoding="utf-8", errors="ignore")
        if "override suspend fun getHosterList" in content:
            has_hoster_list = True
            hoster_methods.append(f"{kt.name}: getHosterList")
        if "override suspend fun getVideoList(hoster: Hoster)" in content:
            has_video_list_hoster = True
            hoster_methods.append(f"{kt.name}: getVideoList(Hoster)")
        if "PREF_SERVER_KEY" in content or "Preferred Server" in content:
            has_server_pref = True
        if "fun List<Video>.sortVideos" in content or "sortVideos()" in content:
            has_sort_videos = True

    print("📊 Static Hoster Architecture Analysis:")
    print(f"  • Custom Server Folders (`getHosterList`): {'✅ Implemented' if has_hoster_list else '⚠️ Single Default Folder'}")
    print(f"  • Hoster Resolver (`getVideoList(Hoster)`): {'✅ Implemented' if has_video_list_hoster else '⚠️ Missing'}")
    print(f"  • Preferred Server Preference Setting:     {'✅ Configured' if has_server_pref else 'ℹ️ Not set'}")
    print(f"  • 4-Tier Stream Quality Sorting:           {'✅ Implemented' if has_sort_videos else '⚠️ Missing'}")
    if hoster_methods:
        print("\n🔍 Detected Implementations:")
        for m in hoster_methods:
            print(f"  • {m}")

    print("\n💡 Best Practice Recommendations for Hoster Folders:")
    print("  1. Ensure each distinct server (Player 1, Player 2, VidStreaming, MegaCloud) has its own `Hoster` instance.")
    print("  2. Do not duplicate the server name in the video quality title inside that folder.")
    print("  3. Include audio track tags next to the resolution (e.g. `1080p [Hindi]`, `720p [English]`).")
    print("  4. Provide a 'Preferred Server' ListPreference for custom user prioritization.")

def preflight_extension(repo_root: Path, target_lang: str, target_name: str) -> bool:
    """Chains the entire quality gate pipeline for a single extension before PR or release."""
    target_lang, target_name = resolve_extension_target(repo_root, lang=target_lang, name=target_name)
    if not target_lang or not target_name:
        print("❌ Could not resolve target extension module. Specify as '<module>' or '<lang>/<module>'.")
        return False
    mod_path = f"src/{target_lang}/{target_name}"
    print(f"🚀 Running Master Pre-Flight Quality Gate on {mod_path}...\n" + "=" * 70)

    # 1. Format
    print("Step 1/5: Formatting Codebase...")
    format_codebase(repo_root, target_lang, target_name)
    print("\n" + "-" * 70)

    # 2. AST Auto-Remediation
    print("Step 2/5: AST Model & Invariant Remediation...")
    from scripts.ast_fixer import auto_fix_target
    auto_fix_target(repo_root, target_lang, target_name)
    print("\n" + "-" * 70)

    # 3. Detect & Patch Missing Extractor Dependencies
    print("Step 3/5: Extractor & Transitive Dependency Resolution...")
    detect_script = repo_root / "scripts" / "detect_extractors.py"
    if detect_script.exists():
        subprocess.run(
            [sys.executable, str(detect_script), "--lang", target_lang, "--name", target_name, "--fix"],
            cwd=repo_root,
            timeout=20
        )
    print("\n" + "-" * 70)

    # 4. Lint Code Quality
    print("Step 4/5: Static AST & Rule Quality Linting...")
    l_ok = lint_codebase(repo_root, target_lang, target_name)
    print("\n" + "-" * 70)

    # 5. Static Manifest & Gradle Validation
    print("Step 5/5: Static Manifest, Gradle, and Asset Validation...")
    v_ok = validate_extensions(repo_root, target_lang, target_name)
    print("\n" + "=" * 70)

    success = l_ok and v_ok
    if success:
        print(f"🎉 Pre-Flight Check PASSED for {mod_path}! Module is 100% ready for PR and release.")
    else:
        print(f"❌ Pre-Flight Check FAILED for {mod_path}. Resolve the issues reported above.")
    return success


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

    # Check optional packages (F-28: expanded from just bs4/PIL)
    opt_pkgs = {
        "bs4": "BeautifulSoup4 (HTML DOM parsing)",
        "PIL": "Pillow (Icon processing)",
        "requests": "requests (HTTP client for site-recon/probe-stream)",
        "lxml": "lxml (fast HTML/XML parser)",
    }
    for mod, desc in opt_pkgs.items():
        try:
            __import__(mod)
            print(f"  ✅ Optional Package: {desc} is available")
        except ImportError:
            print(f"  ℹ️  Optional Package: {desc} not installed (some CLI commands may not work)")

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

    # GitHub CLI check (F-26)
    gh_bin = shutil.which("gh")
    if gh_bin:
        try:
            gh_ver = subprocess.run(["gh", "--version"], capture_output=True, text=True).stdout.splitlines()[0]
            print(f"  ✅ GitHub CLI: {gh_ver} ({gh_bin})")
        except Exception:
            print(f"  ✅ GitHub CLI found ({gh_bin})")
    else:
        print("  ⚠️  GitHub CLI (gh) not found — required for publish/CI workflow (gh run view, gh run download)")
        warnings += 1

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

    # ADB check (F-26)
    adb_bin = shutil.which("adb")
    if adb_bin:
        print(f"  ✅ ADB found ({adb_bin})")
    else:
        print("  ℹ️  ADB not found on PATH (required for Connected ADB Device Workflow)")

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
            "agent": {
                "script": None,
                "desc": "Autonomous multi-agent swarms (pipeline, recon, deobfuscate, critique, heal)."
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
            },
            "preflight": {
                "script": None,
                "desc": "One-shot master quality gate chaining format, fix, detect-extractors, lint, and validate."
            },
            "inspect-hosters": {
                "script": None,
                "desc": "Inspect & audit hoster folder architecture, server grouping, and stream quality sorting."
            },
            "test-extractors": {
                "script": "test_extractors.py",
                "desc": "Alias for test-extractor: Unit test and verify regexes, unpackers, and decoders for extractors."
            },
            "fetch-skip-times": {
                "script": "fetch_metadata.py",
                "desc": "Fetch intro (OP), outro (ED), and recap skip timestamps from AniSkip API."
            },
            "cross-map-id": {
                "script": "fetch_metadata.py",
                "desc": "Cross-map anime and movie IDs across MAL, AniList, IMDb, TMDB, TVDB, and SIMKL."
            },
            "site-recon": {
                "script": "site_recon.py",
                "desc": "Parallel full-site reconnaissance, CMS/theme fingerprinting, and auto-scaffold generator."
            },
            "ai-selectors": {
                "script": "ai_scraper.py",
                "desc": "AI-powered HTML reverse engineering, Jsoup CSS selector, and Kotlin parser generator."
            },
            "auto-create": {
                "script": "auto_create.py",
                "desc": "Autonomous 1-click extension synthesizer: Recon -> AI Selectors -> Kotlin -> Icon -> Validate."
            },
            "status-update": {
                "script": "status_notifier.py",
                "desc": "Post clean status update & AI review sentiment metrics to Discord #status-update channel."
            }
        }

        grouped_categories = [
            ("🤖 Multi-Agent Swarms & Autonomous Workflows", [
                ("agent", "Autonomous multi-agent swarms (pipeline, recon, deobfuscate, critique, heal)."),
            ]),
            ("🏗️  Scaffolding & DTOs", [
                ("auto-create", "Autonomous 1-click synthesizer (Recon -> AI Selectors -> Kotlin -> Icon -> Validate)."),
                ("site-recon", "Parallel full-site reconnaissance, CMS/theme fingerprinting, and scaffold advisor."),
                ("ai-selectors", "AI-powered HTML reverse engineering, Jsoup CSS selector, and Kotlin parser generator."),
                ("create", "Scaffold full extension boilerplate (HTML, API, Theme, Movie-Locker)."),
                ("create-theme", "Scaffold a new multi-source theme in lib-multisrc/."),
                ("json-to-dto", "Ingest JSON API responses or HAR files and generate v16 null-safe DTOs."),
            ]),
            ("🔬 Testing & Scraper Sandbox", [
                ("test-scraper", "Test live HTTP requests, CSS selectors, regex, DevTools headers, and REPL."),
                ("test-pipeline", "Run full 5-stage automated scraper verification (Popular -> Stream)."),
                ("test-extractor", "Unit test video embed resolvers and unpackers (alias: test-extractors)."),
                ("test-filters", "Combinatorial search filter matrix fuzzer."),
                ("sandbox", "Zero-APK fast in-memory Kotlin runtime simulator."),
            ]),
            ("🛡️  Code Quality, Auto-Fix & Validation", [
                ("preflight", "One-shot master quality gate chaining format, fix, deps, lint, and validate."),
                ("validate", "Perform static analysis validation without Gradle APK build (supports --fix)."),
                ("lint", "Scan for code smells, anti-patterns, and API invariants (supports --fix)."),
                ("fix", "Auto-remediate Kotlin AST code smells and API v16 invariants."),
                ("format", "Format Kotlin, Gradle, and XML files (strip whitespace, CRLF normalization)."),
                ("inspect-hosters", "Inspect & audit hoster folder architecture and stream quality sorting."),
            ]),
            ("📡 Media & Stream Diagnostics", [
                ("probe-stream", "Deep media inspector for HLS (.m3u8), DASH, direct streams, and codecs."),
                ("deobfuscate", "Decode Dean Edwards, PlayerJS, CryptoJS AES, and Stego media payloads."),
                ("fetch-metadata", "Fetch episode metadata from Jikan, AniList, Kitsu, TMDB, and TVMaze."),
                ("fetch-skip-times", "Fetch AniSkip intro/outro/recap skip timestamps."),
                ("cross-map-id", "Cross-map IDs across MAL, AniList, IMDb, TMDB, and SIMKL."),
                ("fetch-icon", "Fetch website favicon and convert to 192x192 PNG launcher icon."),
            ]),
            ("🚀 Release & Versioning", [
                ("publish", "Pre-flight auto-fix, format, validate, bump version, commit, and push."),
                ("bump-version", "Increment extVersionCode in build.gradle."),
                ("bump-theme", "Increment baseVersionCode in lib-multisrc."),
                ("bump-lib", "Cascade version bumps to all modules depending on a lib."),
                ("migrate-domain", "Automate base URL domain migration across sources."),
                ("status-update", "Broadcast extension status, providers, audio, and sentiment to #status-update."),
            ]),
            ("🩺 Maintenance & Diagnostics", [
                ("doctor", "Diagnose developer environment (Python, Git, Java, Android SDK)."),
                ("audit-all", "Run master repository health audit across all modules."),
                ("canary-monitor", "Monitor health across all 65+ video extractors in lib/."),
                ("sync-lib", "Synchronize shared extractors with upstream repositories."),
                ("verify-extractors", "Empirically test video extractors against live HTTP streams."),
                ("auto-maintain", "Run automated maintenance (sync, fix dependencies, validate)."),
                ("info", "Show detailed metadata for a single extension module."),
                ("list", "List installed extension modules and version codes."),
                ("list-extractors", "List all 65 pre-built video extractor libraries."),
                ("doc", "Generate markdown extension catalog table."),
                ("clean", "Purge temporary build caches, pyc files, and temporary artifacts."),
            ]),
        ]

        epilog_lines = [f"Available Commands ({len(commands_info)} Total):", "=" * 60]
        for cat_title, cmds in grouped_categories:
            epilog_lines.append(f"\n{cat_title}:")
            for c_name, c_desc in cmds:
                epilog_lines.append(f"  {c_name:18s} {c_desc}")

        epilog_lines.extend([
            "\n" + "=" * 60,
            "Common Workflow Examples:",
            "  python3 scripts/cli.py doctor",
            "  python3 scripts/cli.py create --name AnimeFlix --lang en --baseUrl https://animeflix.live",
            "  python3 scripts/cli.py test-scraper animestream --popular",
            "  python3 scripts/cli.py probe-stream 'https://example.com/master.m3u8' --deep",
            "  python3 scripts/cli.py json-to-dto https://api.site.com/anime/1",
            "  python3 scripts/cli.py validate <module> --fix",
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
        if args.command == "test-extractors":
            args.command = "test-extractor"

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
            mig_parser.add_argument("--dry-run", action="store_true", help="Preview modifications without writing to disk")
            mig_args = mig_parser.parse_args(args.args)
            success = migrate_domain(repo_root, mig_args.target, mig_args.new_domain, test_reachability=not mig_args.no_test, dry_run=mig_args.dry_run)
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
            lint_parser.add_argument("--fix", action="store_true", help="Auto-fix AST smells and v16 invariants before linting")
            lint_args = lint_parser.parse_args(args.args)
            lang, name = resolve_extension_target(repo_root, lint_args.target, lint_args.lang, lint_args.name)
            if lint_args.fix:
                from scripts.ast_fixer import fix_codebase
                fix_codebase(repo_root, lang, name)
            success = lint_codebase(repo_root, lang, name)
            sys.exit(0 if success else 1)

        if args.command == "preflight":
            pf_parser = argparse.ArgumentParser(prog="cli.py preflight")
            pf_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            pf_parser.add_argument("--lang", help="Target extension lang")
            pf_parser.add_argument("--name", help="Target extension directory name")
            pf_args = pf_parser.parse_args(args.args)
            if not pf_args.target and not (pf_args.lang and pf_args.name):
                pf_parser.print_help()
                print("\n❌ Error: Target extension module is required (e.g. `cli.py preflight <module>`).")
                sys.exit(1)
            lang, name = resolve_extension_target(repo_root, pf_args.target, pf_args.lang, pf_args.name)
            success = preflight_extension(repo_root, lang, name)
            sys.exit(0 if success else 1)

        if args.command == "inspect-hosters":
            hoster_parser = argparse.ArgumentParser(prog="cli.py inspect-hosters")
            hoster_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            hoster_parser.add_argument("--lang", help="Target extension lang")
            hoster_parser.add_argument("--name", help="Target extension directory name")
            hoster_args = hoster_parser.parse_args(args.args)
            if not hoster_args.target and not (hoster_args.lang and hoster_args.name):
                hoster_parser.print_help()
                print("\n❌ Error: Target extension module is required (e.g. `cli.py inspect-hosters <module>`).")
                sys.exit(1)
            lang, name = resolve_extension_target(repo_root, hoster_args.target, hoster_args.lang, hoster_args.name)
            success = inspect_hosters(repo_root, lang, name)
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
            val_parser.add_argument("--fix", action="store_true", help="Auto-fix AST smells and missing dependencies before validating")
            val_args = val_parser.parse_args(args.args)
            if val_args.all:
                lang, name = None, None
            else:
                lang, name = resolve_extension_target(repo_root, val_args.target, val_args.lang, val_args.name)
            if val_args.fix:
                from scripts.ast_fixer import fix_codebase
                fix_codebase(repo_root, lang, name)
                detect_script = repo_root / "scripts" / "detect_extractors.py"
                if detect_script.exists():
                    d_cmd = [sys.executable, str(detect_script), "--fix"]
                    if lang and name:
                        d_cmd.extend(["--lang", lang, "--name", name])
                    d_res = subprocess.run(d_cmd, cwd=repo_root, capture_output=True, text=True, timeout=20)
                    if d_res.returncode != 0:
                        print(f"  ⚠️  detect_extractors exited with code {d_res.returncode}: {d_res.stderr.strip()}")
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
            fetch_parser.add_argument("--url", help="Target website URL")
            fetch_parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
            fetch_parser.add_argument("--lang", help="Target extension lang")
            fetch_parser.add_argument("--name", help="Target extension directory name")

            icon_args = fetch_parser.parse_args(args.args)
            lang, name = resolve_extension_target(repo_root, icon_args.target, icon_args.lang, icon_args.name)
            if not name:
                print("❌ Error: Target extension name is required (e.g. `cli.py fetch-icon <target>` or `cli.py fetch-icon --url <url> <target>`).")
                sys.exit(1)

            target_url = icon_args.url
            if not target_url:
                target_src = repo_root / "src" / (lang or "en") / name
                for kt in target_src.rglob("*.kt"):
                    src_text = kt.read_text(encoding="utf-8", errors="ignore")
                    m = re.search(r'(?:PREF_BASE_URL_DEFAULT|PREF_DOMAIN_DEFAULT|DOMAIN(?:_DEFAULT)?)\s*=\s*["\']([^"\']+)["\']', src_text)
                    if not m:
                        m = re.search(r'override\s+val\s+baseUrl\s*=\s*["\']([^"\']+)["\']', src_text)
                    if m:
                        target_url = m.group(1)
                        break

            if not target_url:
                print("❌ Error: Target website URL is required (specify --url <url> or define baseUrl in extension source).")
                sys.exit(1)

            out_path = repo_root / "src" / (lang or "en") / name / "res" / "drawable" / "ic_launcher.png"
            success = fetch_icon(target_url, out_path)
            sys.exit(0 if success else 1)

        if args.command == "fetch-skip-times":
            script_path = scripts_dir / "fetch_metadata.py"
            # Support positional MAL ID: cli.py fetch-skip-times <id>
            # F-05: validate that the positional arg is numeric before forwarding
            extra = []
            if args.args and not args.args[0].startswith("-"):
                mal_id_raw = args.args[0]
                if not mal_id_raw.isdigit():
                    print(f"❌ fetch-skip-times: MAL ID must be a numeric value, got '{mal_id_raw}'")
                    sys.exit(1)
                extra = ["--mal-id", mal_id_raw] + args.args[1:]
            else:
                extra = args.args
            cmd = [sys.executable, str(script_path), "--aniskip"] + extra
            result = subprocess.run(cmd, timeout=300)  # F-12: network I/O timeout
            sys.exit(result.returncode)

        if args.command == "agent":
            from scripts.agents import OrchestratorAgent, ReconSwarmAgent, DeobfuscatorAgent, AdversarialCriticAgent, CiGuardianAgent
            agent_parser = argparse.ArgumentParser(prog="cli.py agent")
            agent_subparsers = agent_parser.add_subparsers(dest="subcommand", help="Agent subcommand")

            pipe_p = agent_subparsers.add_parser("pipeline", help="Run full autonomous multi-agent pipeline")
            pipe_p.add_argument("url", help="Target website URL")
            pipe_p.add_argument("--name", help="Extension name")
            pipe_p.add_argument("--lang", default="en", help="Language code")

            recon_p = agent_subparsers.add_parser("recon", help="Run deep reconnaissance and schema inference")
            recon_p.add_argument("url", help="Target website URL")

            deobf_p = agent_subparsers.add_parser("deobfuscate", help="Solve obfuscated JS/Player payload")
            deobf_p.add_argument("payload", help="Encrypted / packed JS string")
            deobf_p.add_argument("--engine", default="auto", choices=["auto", "packer", "playerjs", "aes", "rc4"])
            deobf_p.add_argument("--key", default="", help="Cipher key")

            crit_p = agent_subparsers.add_parser("critique", help="Run adversarial red team critique on extension code")
            crit_p.add_argument("target", help="Module name or file path")

            heal_p = agent_subparsers.add_parser("heal", help="Triage compiler / CI failure and apply AST patch")
            heal_p.add_argument("log_file", help="Compiler build log file")
            # Note: --file was removed (F-29) — it was parsed but never used in dispatch

            agent_args = agent_parser.parse_args(args.args)
            if not agent_args.subcommand:
                agent_parser.print_help()
                sys.exit(1)

            if agent_args.subcommand == "pipeline":
                orchestrator = OrchestratorAgent()
                res = orchestrator.run_pipeline(agent_args.url, name=agent_args.name, lang=agent_args.lang)
                print(json.dumps(res, indent=2))
                sys.exit(0 if res.get("status") == "SUCCESS" else 1)

            elif agent_args.subcommand == "recon":
                swarm = ReconSwarmAgent()
                res = swarm.explore_site(agent_args.url)
                print(json.dumps(res, indent=2))
                sys.exit(0)

            elif agent_args.subcommand == "deobfuscate":
                import dataclasses
                solver = DeobfuscatorAgent()
                res = solver.solve(agent_args.payload, key=agent_args.key or None)
                print(json.dumps(dataclasses.asdict(res), indent=2))
                sys.exit(0 if res.success else 1)

            elif agent_args.subcommand == "critique":
                import dataclasses
                critic = AdversarialCriticAgent()
                target_path = Path(agent_args.target)
                code = target_path.read_text(encoding="utf-8") if target_path.exists() else agent_args.target
                report = critic.review(code)
                print(json.dumps(dataclasses.asdict(report), indent=2, default=str))
                sys.exit(0 if report.is_passing else 1)

            elif agent_args.subcommand == "heal":
                import dataclasses
                guardian = CiGuardianAgent()
                log_txt = Path(agent_args.log_file).read_text(encoding="utf-8")
                triage = guardian.triage_log(log_txt)
                print(json.dumps(dataclasses.asdict(triage), indent=2, default=str))
                sys.exit(0 if not triage.has_errors else 1)

        if args.command == "cross-map-id":
            script_path = scripts_dir / "fetch_metadata.py"
            cmd = [sys.executable, str(script_path), "--cross-map"] + args.args
            result = subprocess.run(cmd, timeout=300)
            sys.exit(result.returncode)

        script_name = commands_info[args.command]["script"]
        # F-01/F-16: Guard against None script — all None-script commands must have an
        # explicit dispatch block above. If one is missing, fail fast with a clear message.
        if script_name is None:
            print(f"❌ No dispatch handler registered for command '{args.command}'. This is a bug — please add an explicit 'if args.command == \"{args.command}\"' block.")
            sys.exit(1)
        script_path = scripts_dir / script_name

        if not script_path.exists():
            print(f"❌ Script not found: {script_path}")
            sys.exit(1)

        cmd = [sys.executable, str(script_path)] + args.args
        result = subprocess.run(cmd, timeout=300)  # F-13: prevent indefinite hang on network scripts
        sys.exit(result.returncode)

    except KeyboardInterrupt:
        print("\n🛑 Operation cancelled by user.")
        sys.exit(130)
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
