#!/usr/bin/env python3
"""
Auto-Detection Module for Aniyomi Extractor Libraries (`lib/`)
Scans source files, target HTML, or embed URLs to detect video players and hosters,
matching them against shared extractors in `lib/` (with dynamic discovery of all 65 modules).
"""

import argparse
import re
import sys
from pathlib import Path
from typing import List, Dict, Optional, Tuple

# Ensure repo root and scripts directory are in sys.path
REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))


# Supplementary domain & regex patterns for known video hosters and utilities
KNOWN_EXTRACTOR_PATTERNS = {
    "dood-extractor": {
        "name": "DoodStream",
        "class": "DoodExtractor",
        "regex": r"https?://(?:www\.)?(?:dood(?:stream)?|ds2play|do0od|d000?d|doodp|doodl)\.[a-z0-9.]+",
        "snippet": 'val videoList = DoodExtractor(client).videosFromUrl(url, quality = "DoodStream")'
    },
    "filemoon-extractor": {
        "name": "FileMoon",
        "class": "FilemoonExtractor",
        "regex": r"https?://(?:www\.)?(?:filemoon|fmoonembed|moonplayer|filemoonhd)\.[a-z0-9.]+",
        "snippet": 'val videoList = FilemoonExtractor(client, headers).videosFromUrl(url, prefix = "FileMoon - ")'
    },
    "streamtape-extractor": {
        "name": "StreamTape",
        "class": "StreamTapeExtractor",
        "regex": r"https?://(?:www\.)?(?:streamtape|strcloud|tapecontent|stape|streamta\.pe|stape\.fun)\.[a-z0-9.]+",
        "snippet": 'val video = StreamTapeExtractor(client).videoFromUrl(url, quality = "StreamTape")'
    },
    "streamwish-extractor": {
        "name": "StreamWish",
        "class": "StreamWishExtractor",
        "regex": r"https?://(?:www\.)?(?:streamwish|strwish|embedwish|wishembed|swdyu|sfastwish|fsdcma|awish|wishfast|mwish)\.[a-z0-9.]+",
        "snippet": 'val videoList = StreamWishExtractor(client, headers).videosFromUrl(url)'
    },
    "vidhide-extractor": {
        "name": "VidHide",
        "class": "VidHideExtractor",
        "regex": r"https?://(?:www\.)?(?:vidhide|vidhidepro|vidhidefast|streamhide|guccihide|moflixstream|ahvsh)\.[a-z0-9.]+",
        "snippet": 'val videoList = VidHideExtractor(client, headers).videosFromUrl(url)'
    },
    "voe-extractor": {
        "name": "VOE",
        "class": "VoeExtractor",
        "regex": r"https?://(?:www\.)?(?:voe|voe-network|maxfinish|realserver|audaciousdefaulthouse)\.[a-z0-9.]+",
        "snippet": 'val videoList = VoeExtractor(client).videosFromUrl(url)'
    },
    "megacloud-extractor": {
        "name": "MegaCloud",
        "class": "MegaCloudExtractor",
        "regex": r"https?://(?:www\.)?(?:megacloud|rapid-cloud|rabbitstream)\.[a-z0-9.]+",
        "snippet": 'val videoList = MegaCloudExtractor(client, headers).getVideosFromUrl(url)'
    },
    "mixdrop-extractor": {
        "name": "MixDrop",
        "class": "MixDropExtractor",
        "regex": r"https?://(?:www\.)?mixdro?op\.[a-z0-9.]+",
        "snippet": 'val videoList = MixDropExtractor(client).videoFromUrl(url)'
    },
    "mp4upload-extractor": {
        "name": "Mp4Upload",
        "class": "Mp4uploadExtractor",
        "regex": r"https?://(?:www\.)?mp4upload\.com",
        "snippet": 'val videoList = Mp4uploadExtractor(client).videosFromUrl(url, headers)'
    },
    "vidsrc-extractor": {
        "name": "VidSrc",
        "class": "VidSrcExtractor",
        "regex": r"https?://(?:www\.)?(?:vidsrc\d*|vidsrcme|vidsrcto)\.[a-z0-9.]+",
        "snippet": 'val videoList = VidSrcExtractor(client, headers).videosFromUrl(url)'
    },
    "chillx-extractor": {
        "name": "Chillx",
        "class": "ChillxExtractor",
        "regex": r"https?://(?:www\.)?(?:chillx|bestx|streamx|vidembed)\.[a-z0-9.]+",
        "snippet": 'val videoList = ChillxExtractor(client, headers).videoFromUrl(url)'
    },
    "burstcloud-extractor": {
        "name": "BurstCloud",
        "class": "BurstCloudExtractor",
        "regex": r"https?://(?:www\.)?(?:burstcloud|burst)\.[a-z0-9.]+",
        "snippet": 'val videoList = BurstCloudExtractor(client).videoFromUrl(url)'
    },
    "fastream-extractor": {
        "name": "Fastream",
        "class": "FastreamExtractor",
        "regex": r"https?://(?:www\.)?fastream\.[a-z0-9.]+",
        "snippet": 'val videoList = FastreamExtractor(client, headers).videosFromUrl(url)'
    },
    "lulu-extractor": {
        "name": "LuluStream",
        "class": "LuluExtractor",
        "regex": r"https?://(?:www\.)?(?:luluvdo|lulustream)\.[a-z0-9.]+",
        "snippet": 'val videoList = LuluExtractor(client).videosFromUrl(url)'
    },
    "vidguard-extractor": {
        "name": "VidGuard",
        "class": "VidGuardExtractor",
        "regex": r"https?://(?:www\.)?(?:vidguard|vgfplayer|vembed|v6embed)\.[a-z0-9.]+",
        "snippet": 'val videoList = VidGuardExtractor(client).videosFromUrl(url)'
    },
    "okru-extractor": {
        "name": "Okru",
        "class": "OkruExtractor",
        "regex": r"https?://(?:www\.)?(?:ok\.ru|odnoklassniki\.ru)/video(?:embed)?/\d+",
        "snippet": 'val videoList = OkruExtractor(client).videosFromUrl(url)'
    },
    "gdriveplayer-extractor": {
        "name": "GdrivePlayer",
        "class": "GdrivePlayerExtractor",
        "regex": r"https?://(?:www\.)?gdriveplayer\.[a-z0-9.]+",
        "snippet": 'val videoList = GdrivePlayerExtractor(client).videosFromUrl(url)'
    },
    "sendvid-extractor": {
        "name": "Sendvid",
        "class": "SendvidExtractor",
        "regex": r"https?://(?:www\.)?sendvid\.com",
        "snippet": 'val videoList = SendvidExtractor(client).videosFromUrl(url)'
    },
    "streamhub-extractor": {
        "name": "StreamHub",
        "class": "StreamHubExtractor",
        "regex": r"https?://(?:www\.)?streamhub\.[a-z0-9.]+",
        "snippet": 'val videoList = StreamHubExtractor(client).videosFromUrl(url)'
    },
    "yourupload-extractor": {
        "name": "YourUpload",
        "class": "YourUploadExtractor",
        "regex": r"https?://(?:www\.)?yourupload\.com",
        "snippet": 'val videoList = YourUploadExtractor(client).videoFromUrl(url)'
    },
    "playlist-utils": {
        "name": "PlaylistUtils",
        "class": "PlaylistUtils",
        "regex": r"(?:m3u8|PlaylistUtils|extractFromHls)",
        "snippet": 'val videoList = PlaylistUtils(client, headers).extractFromHls(playlistUrl)'
    },
    "universal-extractor": {
        "name": "UniversalExtractor",
        "class": "UniversalExtractor",
        "regex": r"UniversalExtractor",
        "snippet": 'val videoList = UniversalExtractor(client).toVideoList(url, headers)'
    },
    "m3u8server": {
        "name": "M3U8Server",
        "class": "M3u8Server",
        "regex": r"(?:M3u8Server|M3u8Integration|createProxyUrl)",
        "snippet": 'val proxyUrl = M3u8Integration(client).createProxyUrl(url, headers)'
    },
    "cloudflare-interceptor": {
        "name": "CloudflareInterceptor",
        "class": "CloudflareInterceptor",
        "regex": r"CloudflareInterceptor",
        "snippet": 'val client = network.client.newBuilder().addInterceptor(CloudflareInterceptor(context)).build()'
    },
    "unpacker": {
        "name": "JsUnpacker",
        "class": "JsUnpacker",
        "regex": r"(?:JsUnpacker|autoUnpacker|eval\(function\(p,a,c,k,e,)",
        "snippet": 'val unpackedJs = JsUnpacker.unpackAndCombine(packedJs)'
    }
}


def discover_all_extractors(repo_root: Path) -> List[Dict]:
    """Dynamically scans the lib/ directory to discover all available extractor modules and their Kotlin metadata."""
    lib_dir = repo_root / "lib"
    if not lib_dir.exists():
        return []

    registry = []
    for mod_dir in sorted(lib_dir.iterdir()):
        if not mod_dir.is_dir():
            continue

        mod_name = mod_dir.name
        kt_files = list(mod_dir.glob("src/main/java/**/*.kt")) + list(mod_dir.glob("src/main/kotlin/**/*.kt"))

        main_class = None
        full_package = None
        for kt in kt_files:
            content = kt.read_text(encoding="utf-8", errors="ignore")
            pkg_m = re.search(r"^package\s+([a-zA-Z0-9_.]+)", content, re.MULTILINE)
            cls_m = re.search(r"^(?:open\s+|abstract\s+)?class\s+([A-Za-z0-9_]+)", content, re.MULTILINE)
            if pkg_m and cls_m:
                full_package = f"{pkg_m.group(1)}.{cls_m.group(1)}"
                main_class = cls_m.group(1)
                break

        pattern_info = KNOWN_EXTRACTOR_PATTERNS.get(mod_name, {})
        display_name = pattern_info.get("name") or main_class or mod_name.replace("-extractor", "").replace("-", " ").title()
        cls_name = main_class or pattern_info.get("class") or "".join(w.capitalize() for w in mod_name.split("-"))
        regex = pattern_info.get("regex") or rf"https?://(?:www\.)?{re.escape(mod_name.replace('-extractor', ''))}\.[a-z0-9.]+"
        snippet = pattern_info.get("snippet") or f'val videoList = {cls_name}(client).videosFromUrl(url)'
        package_str = full_package or f"eu.kanade.tachiyomi.lib.{mod_name.replace('-', '')}.{cls_name}"

        registry.append({
            "name": display_name,
            "module": mod_name,
            "class": cls_name,
            "package": package_str,
            "dependency": f'implementation(project(":lib:{mod_name}"))',
            "regex": regex,
            "snippet": snippet
        })

    return registry


def inject_extractor_into_source(kt_file: Path, extractors: list[dict], dry_run: bool = False) -> bool:
    """Injects missing Kotlin imports and lazy declarations for extractors into a Kotlin source file."""
    if not kt_file.exists():
        return False

    content = kt_file.read_text(encoding="utf-8")
    original = content
    modified = False

    # 1. Inject imports
    imports_to_add = []
    for ext in extractors:
        import_stmt = f"import {ext['package']}"
        if import_stmt not in content:
            imports_to_add.append(import_stmt)

    if imports_to_add:
        # Find last import
        all_imports = list(re.finditer(r"^import\s+[^\n]+", content, re.MULTILINE))
        if all_imports:
            last_import = all_imports[-1]
            insert_pos = last_import.end()
            content = content[:insert_pos] + "\n" + "\n".join(imports_to_add) + content[insert_pos:]
            modified = True

    # 2. Inject lazy declarations inside the main class body
    KNOWN_SINGLETONS = {"JsUnpacker", "LZString", "CryptoAES", "DataImage"}
    decls_to_add = []
    for ext in extractors:
        cls = ext["class"]
        if cls in KNOWN_SINGLETONS:
            continue  # Singleton objects are invoked statically

        prop_name = cls[0].lower() + cls[1:]
        if not any(prop_name.endswith(suffix) for suffix in ("Extractor", "Utils", "Server", "Fetcher", "Interceptor", "Scraper")):
            prop_name += "Extractor"
        decl = f"    private val {prop_name} by lazy {{ {cls}(client) }}"
        if decl not in content and f"{cls}(" not in content:
            decls_to_add.append(decl)

    if decls_to_add:
        cls_match = re.search(r"class\s+[A-Za-z0-9_]+\s*(?:\([^)]*\))?\s*:\s*[^{]+\{", content)
        if cls_match:
            insert_pos = cls_match.end()
            content = content[:insert_pos] + "\n\n" + "\n".join(decls_to_add) + content[insert_pos:]
            modified = True

    if modified:
        if dry_run:
            print(f"[DRY RUN] Would inject {len(extractors)} extractor import(s)/decl(s) into {kt_file.name}")
        else:
            kt_file.write_text(content, encoding="utf-8")
            print(f"🚀 Successfully injected extractor imports/declarations into {kt_file.name}!")
        return True
    return False


def extract_urls_from_dom_or_text(text: str) -> List[str]:
    """Extracts candidate video URLs from HTML iframes, player configs, or raw text."""
    urls = set()
    # 1. iframes & src attributes
    for m in re.finditer(r'<iframe[^>]+(?:src|data-src)=["\']([^"\']+)["\']', text, re.IGNORECASE):
        urls.add(m.group(1))
    # 2. Player configs (JWPlayer, PlayerJS, VideoJS)
    for m in re.finditer(r'(?:file|source|src)\s*:\s*["\'](https?://[^"\']+)["\']', text, re.IGNORECASE):
        urls.add(m.group(1))
    # 3. Direct http URLs
    for m in re.finditer(r'https?://[a-zA-Z0-9_.\-]+(?::\d+)?/[^\s"\'<>]+', text):
        urls.add(m.group(0))
    return sorted(urls)


def scan_text_for_extractors(text: str, registry: List[Dict]) -> List[Dict]:
    """Matches text, HTML, or code against the extractor registry."""
    matched = []
    seen = set()

    for ext in registry:
        if ext["module"] in seen:
            continue
        try:
            if re.search(ext["regex"], text, re.IGNORECASE) or ext["class"] in text or ext["module"] in text:
                matched.append(ext)
                seen.add(ext["module"])
        except re.error:
            if ext["class"] in text:
                matched.append(ext)
                seen.add(ext["module"])

    return matched


def main():
    parser = argparse.ArgumentParser(
        description="Dynamic Extractor Discovery, Payload Matcher & Dependency Injector"
    )
    parser.add_argument("target", nargs="?", help="Target extension name (e.g. 'animestream' or 'en/animestream')")
    parser.add_argument("--lang", help="Target extension lang")
    parser.add_argument("--name", help="Target extension directory name")
    parser.add_argument("--url", help="Sample video embed URL or web page URL")
    parser.add_argument("--html", help="HTML content snippet to analyze")
    parser.add_argument("--fix", action="store_true", help="Automatically insert missing dependencies into build.gradle")
    parser.add_argument("--inject", action="store_true", help="Automatically insert missing imports and lazy declarations into Kotlin source")
    parser.add_argument("--dry-run", action="store_true", help="Perform trial run without modifying files")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    registry = discover_all_extractors(repo_root)

    target_lang = args.lang
    target_name = args.name
    if args.target:
        if "/" in args.target:
            target_lang, target_name = args.target.split("/", 1)
        else:
            target_name = args.target
            src_dir = repo_root / "src"
            if src_dir.exists():
                for l_dir in sorted(src_dir.iterdir()):
                    if l_dir.is_dir() and (l_dir / target_name).exists():
                        target_lang = l_dir.name
                        break

    if not args.url and not args.html and not (target_lang and target_name):
        parser.print_help()
        print(f"\n📦 Loaded {len(registry)} extractor modules dynamically from lib/.")
        sys.exit(1)

    scan_payload = args.html or args.url
    if scan_payload:
        print(f"🔍 Analyzing payload ({len(scan_payload)} chars)...\n")
        extracted_urls = extract_urls_from_dom_or_text(scan_payload)
        if extracted_urls:
            print(f"  Found {len(extracted_urls)} candidate URL(s) in DOM/text.")

        matches = scan_text_for_extractors(scan_payload, registry)
        if not matches:
            print("❌ No pre-built extractor match found for this payload.")
            print("💡 Consider using UniversalExtractor or PlaylistUtils for direct MP4/M3U8 streams.")
            sys.exit(0)

        print(f"📦 Matched {len(matches)} Extractor(s):\n" + "=" * 50)
        for m in matches:
            print(f"✅ {m['name']} (`:lib:{m['module']}`)")
            print(f"  • Gradle Dependency: {m['dependency']}")
            print(f"  • Kotlin Import:     import {m['package']}")
            print(f"  • Usage Snippet:\n      {m['snippet']}\n")
        sys.exit(0)

    if target_lang and target_name:
        ext_path = repo_root / "src" / target_lang / target_name
        gradle_file = ext_path / "build.gradle"
        kt_dir = ext_path / "src"

        if not ext_path.exists():
            print(f"❌ Extension src/{target_lang}/{target_name} not found.")
            sys.exit(1)

        print(f"🔍 Scanning extension codebase: src/{target_lang}/{target_name}...\n")

        kt_files = list(kt_dir.glob("**/*.kt"))
        combined_kt = "\n".join(f.read_text(encoding="utf-8", errors="ignore") for f in kt_files)

        matches = scan_text_for_extractors(combined_kt, registry)
        gradle_content = gradle_file.read_text(encoding="utf-8") if gradle_file.exists() else ""
        missing_deps = []

        for m in matches:
            mod_pattern = f":lib:{m['module']}"
            if mod_pattern not in gradle_content:
                missing_deps.append(m)

        if missing_deps:
            print(f"⚠️ Found {len(missing_deps)} extractor(s) referenced in code but missing from build.gradle:")
            for m in missing_deps:
                print(f"  - Missing: {m['dependency']}")

            if (args.fix or args.inject) and gradle_file.exists():
                if args.dry_run:
                    print("\n[DRY RUN] Would add missing dependencies to build.gradle.")
                else:
                    dep_lines = "\n".join(f"    {m['dependency']}" for m in missing_deps)
                    if "dependencies {" in gradle_content:
                        new_gradle = gradle_content.replace("dependencies {", f"dependencies {{\n{dep_lines}")
                    else:
                        new_gradle = gradle_content.rstrip() + f"\n\ndependencies {{\n{dep_lines}\n}}\n"
                    gradle_file.write_text(new_gradle, encoding="utf-8")
                    print("\n🚀 Successfully added missing dependencies to build.gradle!")
        else:
            print(f"✓ All referenced extractor dependencies ({len(matches)} matched) are present in build.gradle.")

        if args.inject and matches and kt_files:
            main_kt = kt_files[0]
            for kt in kt_files:
                if "Source" in kt.name or kt.stem.lower() == target_name.lower():
                    main_kt = kt
                    break
            inject_extractor_into_source(main_kt, matches, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
