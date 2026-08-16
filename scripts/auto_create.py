#!/usr/bin/env python3
"""
Autonomous 1-Click Aniyomi Extension Synthesizer

Fully automates the end-to-end extension creation lifecycle:
1. Deep Site Reconnaissance (CMS & Theme detection, open REST APIs, video hosters).
2. AI-Powered CSS Selector & DOM extraction (Groq Llama 3.3 / Gemini Flash).
3. Deterministic v16 Kotlin Source Code generation with strict null-safety.
4. Manifest and build.gradle configuration with auto-detected :lib:* dependencies.
5. Favicon discovery & 192x192 PNG launcher generation.
6. Local preflight quality gate execution (Format -> AST Fix -> Lint -> Validate).
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))

from scripts.site_recon import SiteRecon
from scripts.ai_scraper import call_groq, call_gemini, call_opencode
from scripts.create_extension import generate_extension, generate_theme_scaffold, to_pascal_case, create_minimal_png


def extract_ai_selectors_json(html_content: str) -> Dict[str, str]:
    """Uses Groq / Gemini to extract a strict JSON dictionary of CSS selectors."""
    prompt = f"""
Analyze this website HTML snippet and extract the exact CSS selectors for scraping an Aniyomi video extension.
Output ONLY a raw valid JSON object with these EXACT keys:
{{
  "popularSelector": "CSS selector for anime/movie cards in popular or latest listing (e.g. div.film-detail, .movie-card)",
  "popularTitleSelector": "CSS selector or attribute for title (e.g. h3 a, a.title, .film-name)",
  "popularLinkSelector": "CSS selector or attribute for link (e.g. a.poster, a[href])",
  "popularThumbSelector": "CSS selector or attribute for poster image (e.g. img.poster, img[data-src])",
  "detailsSynopsisSelector": "CSS selector for description/synopsis (e.g. div.synopsis, .description)",
  "detailsGenreSelector": "CSS selector for genres (e.g. .genres a, .tags a)",
  "detailsStatusSelector": "CSS selector for status (e.g. .status, span.status-tag)",
  "episodeSelector": "CSS selector for episode list items (e.g. div.episodes a, ul.episode-list li a)",
  "episodeNameSelector": "CSS selector for episode title or number (e.g. a, span.ep-name)",
  "episodeLinkSelector": "CSS selector for episode watch URL (e.g. a[href], a)"
}}

HTML Snippet:
```html
{html_content[:15000]}
```
"""
    # Try Groq first for sub-second speed, fallback to Gemini Flash
    raw_json = call_groq(prompt) or call_gemini(prompt) or call_opencode(prompt)
    if not raw_json:
        return {}

    try:
        # Strip markdown fences if present
        m = re.search(r'\{[\s\S]*\}', raw_json)
        if m:
            return json.loads(m.group(0))
    except Exception:
        pass
    return {}


def synthesize_extension(url: str, name: Optional[str] = None, lang: str = "en") -> bool:
    """Orchestrates autonomous extension creation."""
    print("=" * 80)
    print("      🚀 AUTONOMOUS 1-CLICK EXTENSION SYNTHESIZER (v16 ENGINE)")
    print("=" * 80)
    print(f"Target URL: {url} | Language: {lang}")

    # Stage 1: Parallel Site Reconnaissance
    recon = SiteRecon(url)
    report = recon.run(deep_samples=3)

    if not name:
        name = recon.domain.split(".")[0].capitalize().replace("-", "")

    pkg_name = name.lower().replace(" ", "").replace("-", "")
    class_name = to_pascal_case(name)

    print(f"\n[+] Synthesizing Extension Module: src/{lang}/{pkg_name} ({class_name})")

    # Stage 2: CMS / Theme Fast Path
    cms = report.get("cms", "")
    if "DooPlay" in cms:
        print("  [✓] DooPlay Theme detected -> Generating DooPlay variant scaffold...")
        generate_extension(
            ext_name=name,
            lang=lang,
            base_url=recon.base_url,
            site_type="theme",
            repo_root=REPO_ROOT,
            has_filters=True,
            has_preferences=True,
            theme_name="dooplay"
        )
    elif "ToroTheme" in cms:
        print("  [✓] ToroTheme detected -> Generating ToroTheme variant scaffold...")
        generate_extension(
            ext_name=name,
            lang=lang,
            base_url=recon.base_url,
            site_type="theme",
            repo_root=REPO_ROOT,
            has_filters=True,
            has_preferences=True,
            theme_name="torotheme"
        )
    else:
        # Stage 3: AI Selector Extraction for Standard HTML / Custom CMS
        print("  [+] Extracting CSS selectors via Multi-Model AI Engine...")
        selectors = extract_ai_selectors_json(recon.root_html)

        # Stage 4: Generate Full Source Scaffold
        generate_extension(
            ext_name=name,
            lang=lang,
            base_url=recon.base_url,
            site_type="html",
            repo_root=REPO_ROOT,
            has_filters=True,
            has_preferences=bool(report.get("libs")),
            has_metadata=True,
            custom_selectors=selectors
        )

    # Stage 5: Inject Auto-Detected Video Extractor Dependencies
    libs = report.get("libs", [])
    if libs:
        build_gradle_path = REPO_ROOT / "src" / lang / pkg_name / "build.gradle"
        if build_gradle_path.exists():
            content = build_gradle_path.read_text(encoding="utf-8")
            dep_lines = [f"    implementation(project(\"{lib}\"))" for lib in sorted(libs) if f"project(\"{lib}\")" not in content]
            if dep_lines:
                content = content.replace("dependencies {", "dependencies {\n" + "\n".join(dep_lines))
                build_gradle_path.write_text(content, encoding="utf-8")
                print(f"  [✓] Injected {len(dep_lines)} video extractor dependencies into build.gradle")

    # Stage 6: Discover & Generate Launcher Icon
    icon_path = REPO_ROOT / "src" / lang / pkg_name / "res" / "drawable" / "ic_launcher.png"
    from scripts.cli import fetch_icon
    print("  [+] Fetching website launcher icon...")
    icon_success = fetch_icon(recon.base_url, icon_path)
    if not icon_success or not icon_path.exists():
        create_minimal_png(icon_path)
        print("  [!] Used minimal fallback PNG icon.")

    # Stage 7: Master Pre-flight Quality Gate
    print("\n[+] Running Master Pre-flight Quality Gates...")
    from scripts.ast_fixer import fix_codebase
    from scripts.cli import format_codebase, validate_extensions

    fix_codebase(REPO_ROOT, lang, pkg_name)
    format_codebase(REPO_ROOT, lang, pkg_name)
    val_success = validate_extensions(REPO_ROOT, lang, pkg_name)

    print("\n" + "=" * 80)
    if val_success:
        print(f"🎉 SUCCESS: Extension '{name}' ({lang}/{pkg_name}) is fully synthesized and validated!")
        print(f"📁 Source Location: src/{lang}/{pkg_name}")
        print("💡 Next Step: Run `python3 scripts/cli.py test-scraper " + pkg_name + " --popular`")
    else:
        print(f"⚠️ Extension '{name}' created with minor warnings. Review src/{lang}/{pkg_name}")
    print("=" * 80 + "\n")
    return val_success


def main():
    parser = argparse.ArgumentParser(description="Autonomous 1-Click Aniyomi Extension Synthesizer")
    parser.add_argument("url", help="Target anime/movie website URL (e.g. 'https://animeflix.live')")
    parser.add_argument("--name", help="Extension name (optional, defaults to domain name)")
    parser.add_argument("--lang", default="en", help="Extension language (default: 'en')")
    args = parser.parse_args()

    success = synthesize_extension(args.url, name=args.name, lang=args.lang)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
