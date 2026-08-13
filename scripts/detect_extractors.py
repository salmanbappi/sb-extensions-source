#!/usr/bin/env python3
"""
Auto-Detection Module for Aniyomi Extractor Libraries (`lib/`)
Scans source files or target HTML/URLs to detect embed players, matching them to shared extractors in `lib/`.
"""

import argparse
import re
import sys
from pathlib import Path
from typing import List, Dict

EXTRACTOR_REGISTRY = [
    {
        "name": "DoodStream",
        "module": "dood-extractor",
        "class": "DoodExtractor",
        "package": "eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor",
        "constructor": "DoodExtractor(client)",
        "dependency": 'implementation(project(":lib:dood-extractor"))',
        "regex": r"https?://(?:www\.)?(?:dood(?:stream)?|ds2play|do0od|d000?d)\.[a-z0-9.]+",
        "snippet": 'val videoList = DoodExtractor(client).videosFromUrl(url, quality = "DoodStream")'
    },
    {
        "name": "FileMoon",
        "module": "filemoon-extractor",
        "class": "FilemoonExtractor",
        "package": "eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor",
        "constructor": "FilemoonExtractor(client, headers)",
        "dependency": 'implementation(project(":lib:filemoon-extractor"))',
        "regex": r"https?://(?:www\.)?(?:filemoon|fmoonembed|moonplayer)\.[a-z0-9.]+",
        "snippet": 'val videoList = FilemoonExtractor(client, headers).videosFromUrl(url, prefix = "FileMoon - ")'
    },
    {
        "name": "StreamTape",
        "module": "streamtape-extractor",
        "class": "StreamTapeExtractor",
        "package": "eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor",
        "constructor": "StreamTapeExtractor(client)",
        "dependency": 'implementation(project(":lib:streamtape-extractor"))',
        "regex": r"https?://(?:www\.)?(?:streamtape|strcloud|tapecontent|stape)\.[a-z0-9.]+",
        "snippet": 'val video = StreamTapeExtractor(client).videoFromUrl(url, quality = "StreamTape")'
    },
    {
        "name": "StreamWish",
        "module": "streamwish-extractor",
        "class": "StreamWishExtractor",
        "package": "eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor",
        "constructor": "StreamWishExtractor(client, headers)",
        "dependency": 'implementation(project(":lib:streamwish-extractor"))',
        "regex": r"https?://(?:www\.)?(?:streamwish|strwish|embedwish|wishembed|swdyu|sfastwish|fsdcma|awish|wishfast)\.[a-z0-9.]+",
        "snippet": 'val videoList = StreamWishExtractor(client, headers).videosFromUrl(url)'
    },
    {
        "name": "VidHide",
        "module": "vidhide-extractor",
        "class": "VidHideExtractor",
        "package": "eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor",
        "constructor": "VidHideExtractor(client, headers)",
        "dependency": 'implementation(project(":lib:vidhide-extractor"))',
        "regex": r"https?://(?:www\.)?(?:vidhide|vidhidepro|vidhidefast|streamhide|guccihide|moflixstream)\.[a-z0-9.]+",
        "snippet": 'val videoList = VidHideExtractor(client, headers).videosFromUrl(url)'
    },
    {
        "name": "VOE",
        "module": "voe-extractor",
        "class": "VoeExtractor",
        "package": "eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor",
        "constructor": "VoeExtractor(client)",
        "dependency": 'implementation(project(":lib:voe-extractor"))',
        "regex": r"https?://(?:www\.)?(?:voe|voe-network|maxfinish|realserver)\.[a-z0-9.]+",
        "snippet": 'val videoList = VoeExtractor(client).videosFromUrl(url)'
    },
    {
        "name": "MegaCloud",
        "module": "megacloud-extractor",
        "class": "MegaCloudExtractor",
        "package": "eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor",
        "constructor": "MegaCloudExtractor(client, headers)",
        "dependency": 'implementation(project(":lib:megacloud-extractor"))',
        "regex": r"https?://(?:www\.)?megacloud\.[a-z0-9.]+",
        "snippet": 'val videoList = MegaCloudExtractor(client, headers).getVideosFromUrl(url)'
    },
    {
        "name": "MixDrop",
        "module": "mixdrop-extractor",
        "class": "MixDropExtractor",
        "package": "eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor",
        "constructor": "MixDropExtractor(client)",
        "dependency": 'implementation(project(":lib:mixdrop-extractor"))',
        "regex": r"https?://(?:www\.)?mixdro?op\.[a-z0-9.]+",
        "snippet": 'val videoList = MixDropExtractor(client).videoFromUrl(url)'
    },
    {
        "name": "Mp4Upload",
        "module": "mp4upload-extractor",
        "class": "Mp4uploadExtractor",
        "package": "eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor",
        "constructor": "Mp4uploadExtractor(client)",
        "dependency": 'implementation(project(":lib:mp4upload-extractor"))',
        "regex": r"https?://(?:www\.)?mp4upload\.com",
        "snippet": 'val videoList = Mp4uploadExtractor(client).videosFromUrl(url, headers)'
    },
    {
        "name": "VidSrc",
        "module": "vidsrc-extractor",
        "class": "VidSrcExtractor",
        "package": "eu.kanade.tachiyomi.lib.vidsrcextractor.VidSrcExtractor",
        "constructor": "VidSrcExtractor(client, headers)",
        "dependency": 'implementation(project(":lib:vidsrc-extractor"))',
        "regex": r"https?://(?:www\.)?vidsrc\d*\.[a-z0-9.]+",
        "snippet": 'val videoList = VidSrcExtractor(client, headers).videosFromUrl(url)'
    }
]


def scan_text_for_extractors(text: str) -> List[dict]:
    matched = []
    for ext in EXTRACTOR_REGISTRY:
        if re.search(ext["regex"], text, re.IGNORECASE) or ext["class"] in text:
            matched.append(ext)
    return matched


def main():
    parser = argparse.ArgumentParser(description="Auto-detect required extractors for Aniyomi extensions.")
    parser.add_argument("--lang", help="Target extension lang")
    parser.add_argument("--name", help="Target extension directory name")
    parser.add_argument("--url", help="Sample video embed URL")
    parser.add_argument("--fix", action="store_true", help="Automatically insert missing dependencies into build.gradle")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    if args.url:
        print(f"🔍 Analyzing URL: {args.url}\n")
        matches = scan_text_for_extractors(args.url)
        if not matches:
            print("❌ No pre-built extractor match found for this URL.")
            print("💡 Consider using UniversalExtractor for direct MP4/M3U8 links.")
            sys.exit(0)

        for m in matches:
            print(f"✅ Matched Extractor: {m['name']} (`:lib:{m['module']}`)")
            print(f"  • Gradle Dependency: {m['dependency']}")
            print(f"  • Kotlin Import:     import {m['package']}")
            print(f"  • Usage Snippet:\n      {m['snippet']}\n")
        sys.exit(0)

    if args.lang and args.name:
        ext_path = repo_root / "src" / args.lang / args.name
        gradle_file = ext_path / "build.gradle"
        kt_dir = ext_path / "src"

        if not ext_path.exists():
            print(f"❌ Extension src/{args.lang}/{args.name} not found.")
            sys.exit(1)

        print(f"🔍 Scanning extension codebase: src/{args.lang}/{args.name}...\n")

        kt_files = list(kt_dir.glob("**/*.kt"))
        combined_kt = "\n".join(f.read_text(encoding="utf-8", errors="ignore") for f in kt_files)

        matches = scan_text_for_extractors(combined_kt)

        gradle_content = gradle_file.read_text(encoding="utf-8") if gradle_file.exists() else ""
        missing_deps = []

        for m in matches:
            if m["module"] not in gradle_content:
                missing_deps.append(m)

        if missing_deps:
            print(f"⚠️ Found {len(missing_deps)} extractor(s) referenced in code but missing from build.gradle:")
            for m in missing_deps:
                print(f"  - Missing: {m['dependency']}")

            if args.fix and gradle_file.exists():
                dep_lines = "\n".join(f"    {m['dependency']}" for m in missing_deps)
                new_gradle = gradle_content.replace("dependencies {", f"dependencies {{\n{dep_lines}")
                gradle_file.write_text(new_gradle, encoding="utf-8")
                print("\n🚀 Successfully added missing dependencies to build.gradle!")
        else:
            print("✓ All referenced extractor dependencies are present in build.gradle.")


if __name__ == "__main__":
    main()
