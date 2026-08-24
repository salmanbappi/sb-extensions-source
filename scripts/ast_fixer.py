#!/usr/bin/env python3
"""
AST-Aware Code Smell Auto-Fixer & Remediation Engine for Aniyomi Extensions (API v16)
Automatically resolves lint code smells and v16 model invariants without manual editing.
"""

import argparse
import re
import sys
from pathlib import Path
from typing import Tuple, List

REPO_ROOT = Path(__file__).resolve().parent.parent

# Pre-compiled AST & Code-Smell Patterns
RE_VIDEO_QUALITY = re.compile(r'\b(?:it|video)\.quality\b')
RE_VIDEO_QUALITY_SUB = re.compile(r'(\b(?:it|video))\.(?:quality)\b')
RE_VIDEO_CONSTRUCTOR = re.compile(r'\bVideo\s*\(')
RE_GET_DETAILS = re.compile(r'(override\s+suspend\s+fun\s+getAnimeDetails\s*\(\s*([a-zA-Z0-9_]+)\s*:\s*SAnime\s*\)\s*:\s*SAnime\s*\{)')
RE_DTO_CLASS = re.compile(r'(@(?:kotlinx\.serialization\.)?Serializable(?:\([^)]*\))?\s+(?:data\s+)?class\s+[A-Za-z0-9_]+(?:\s*<[^>]+>)?\s*\()')
RE_BASEURL_ATTR = re.compile(r'(?:"\$baseUrl"|baseUrl)\s*\+\s*([a-zA-Z0-9_]+)\.attr\(["\']href["\']\)')
RE_SORT_VIDEOS_FUNC = re.compile(r'(?:private\s+)?fun\s+sortVideos\s*\(\s*([a-zA-Z0-9_]+)\s*:\s*List<Video>\s*\)\s*:\s*List<Video>')
RE_SORT_VIDEOS_CALL = re.compile(r'(?<!fun\s)\bsortVideos\s*\(\s*([a-zA-Z0-9_]+)\s*\)')
RE_PLAYLIST_SUBTITLE = re.compile(r'(\bextractFromHls\s*\([^)]*)\bsubtitleTracks\s*=')
RE_PLAYLIST_AUDIO = re.compile(r'(\bextractFromHls\s*\([^)]*)\baudioTracks\s*=')

STATUS_REPLACEMENTS = [
    (re.compile(r'\bSAnime\.NOT_YET_RELEASED\b'), 'SAnime.UNKNOWN'),
    (re.compile(r'\bSAnime\.AIRING\b'), 'SAnime.ONGOING'),
    (re.compile(r'\bSAnime\.RELEASING\b'), 'SAnime.ONGOING'),
    (re.compile(r'\bSAnime\.FINISHED\b'), 'SAnime.COMPLETED'),
    (re.compile(r'\bSAnime\.HIATUS\b'), 'SAnime.ON_HIATUS'),
]

def split_params_depth_aware(params_str: str) -> list[str]:
    """Splits parameter declarations by comma, respecting generic brackets and parentheses."""
    parts = []
    current = []
    depth = 0
    in_quote = False
    quote_char = None

    for ch in params_str:
        if ch in ('"', "'"):
            if not in_quote:
                in_quote = True
                quote_char = ch
            elif quote_char == ch:
                in_quote = False
        elif not in_quote:
            if ch in ('<', '(', '[', '{'):
                depth += 1
            elif ch in ('>', ')', ']', '}'):
                depth = max(0, depth - 1)
            elif ch == ',' and depth == 0:
                parts.append(''.join(current).strip())
                current = []
                continue
        current.append(ch)

    if current:
        parts.append(''.join(current).strip())
    return [p for p in parts if p]

class ExtensionAstFixer:
    def __init__(self, dry_run: bool = False):
        self.dry_run = dry_run
        self.fixed_count = 0

    def fix_file(self, file_path: Path) -> Tuple[bool, List[str]]:
        """Applies AST auto-remediations to a Kotlin source file."""
        if not file_path.exists() or file_path.suffix != ".kt":
            return False, []

        content = file_path.read_text(encoding="utf-8")
        original = content
        fixes_applied = []

        # 1. Fix it.quality -> it.videoTitle
        if RE_VIDEO_QUALITY.search(content):
            content = RE_VIDEO_QUALITY_SUB.sub(r'\1.videoTitle', content)
            fixes_applied.append("Renamed `.quality` property to `.videoTitle` (v16)")

        # 2. Fix legacy positional Video(url, quality, ...) to named Video(videoUrl=, videoTitle=, ...)
        def migrate_video_constructors(text: str) -> Tuple[str, bool]:
            if "Video(" not in text:
                return text, False
            idx = 0
            changed = False
            result = []
            while idx < len(text):
                m = RE_VIDEO_CONSTRUCTOR.search(text, idx)
                if not m:
                    result.append(text[idx:])
                    break

                start_call = m.start()
                open_paren = m.end() - 1
                result.append(text[idx:start_call])

                # Find matching closing parenthesis
                depth = 1
                curr = open_paren + 1
                in_str = False
                str_char = None
                while curr < len(text) and depth > 0:
                    ch = text[curr]
                    if ch in ('"', "'") and (curr == 0 or text[curr - 1] != '\\'):
                        if not in_str:
                            in_str = True
                            str_char = ch
                        elif str_char == ch:
                            in_str = False
                    elif not in_str:
                        if ch in ('(', '[', '{', '<'):
                            depth += 1
                        elif ch in (')', ']', '}', '>'):
                            depth -= 1
                    curr += 1

                if depth == 0:
                    args_str = text[open_paren + 1:curr - 1].strip()
                    # Check if already using named arguments
                    if "videoUrl" in args_str or "videoTitle" in args_str:
                        result.append(text[start_call:curr])
                    else:
                        args = split_params_depth_aware(args_str)
                        if len(args) == 2:
                            result.append(f"Video(videoUrl = {args[0]}, videoTitle = {args[1]})")
                            changed = True
                        elif len(args) == 3:
                            result.append(f"Video(videoUrl = {args[0]}, videoTitle = {args[1]}, headers = {args[2]})")
                            changed = True
                        elif len(args) >= 4:
                            direct_url = args[2] if args[2] != "null" else args[0]
                            result.append(f"Video(videoUrl = {direct_url}, videoTitle = {args[1]}, headers = {args[3]})")
                            changed = True
                        else:
                            result.append(text[start_call:curr])
                    idx = curr
                else:
                    # Unbalanced paren fallback
                    result.append(text[start_call:open_paren + 1])
                    idx = open_paren + 1

            return "".join(result), changed

        new_content, video_migrated = migrate_video_constructors(content)
        if video_migrated:
            content = new_content
            fixes_applied.append("Migrated positional `Video(...)` constructors to named arguments (v16)")

        # 3. Ensure initialized = true in getAnimeDetails
        if "getAnimeDetails" in content and "initialized = true" not in content:
            if RE_GET_DETAILS.search(content):
                content = RE_GET_DETAILS.sub(r'\1\n        \2.initialized = true', content)
                fixes_applied.append("Injected `initialized = true` inside `getAnimeDetails`")

        # 4 & 5. Ensure @Serializable data class constructor fields have default = null fallbacks
        # and numeric score/rating/vote fields use Double? to prevent JsonDecodingException
        def process_dto_classes(text: str) -> Tuple[str, bool, bool]:
            nullability_modified = False
            type_modified = False

            pattern = re.compile(r'(@(?:kotlinx\.serialization\.)?Serializable(?:\([^)]*\))?\s+(?:data\s+)?class\s+[A-Za-z0-9_]+(?:\s*<[^>]+>)?\s*\()')
            pos = 0
            result = []

            while True:
                m = pattern.search(text, pos)
                if not m:
                    result.append(text[pos:])
                    break

                result.append(text[pos:m.start()])
                header = text[m.start():m.end()]
                result.append(header)

                idx = m.end()
                depth = 1
                in_quote = False
                quote_char = None

                while idx < len(text) and depth > 0:
                    ch = text[idx]
                    if ch in ('"', "'"):
                        if not in_quote:
                            in_quote = True
                            quote_char = ch
                        elif quote_char == ch:
                            in_quote = False
                    elif not in_quote:
                        if ch in ('(', '<', '{', '['):
                            depth += 1
                        elif ch in (')', '>', '}', ']'):
                            depth -= 1
                    idx += 1

                if depth == 0:
                    params_block = text[m.end():idx-1]
                    params = split_params_depth_aware(params_block)
                    fixed_params = []

                    for p in params:
                        p_clean = p.strip()
                        if not p_clean:
                            continue

                        # Check rating/score/vote type migration strictly inside DTO
                        val_match = re.search(r'\bval\s+([a-zA-Z0-9_]+)\s*:\s*(Int|Long)(\??)(.*)', p_clean)
                        if val_match:
                            prop_name = val_match.group(1)
                            if re.search(r'\b(?:votes|rating|score|stars|rank)\b', prop_name, re.IGNORECASE):
                                p_type_fixed = re.sub(r'(\bval\s+[a-zA-Z0-9_]+\s*:\s*)(Int|Long)', r'\1Double', p_clean)
                                if p_type_fixed != p_clean:
                                    type_modified = True
                                    p_clean = p_type_fixed

                        # Enforce nullability and default = null
                        if ":" in p_clean and "=" not in p_clean:
                            parts = p_clean.split(":", 1)
                            val_var = parts[0].strip()
                            type_part = parts[1].strip()
                            if not type_part.endswith("?"):
                                type_part += "?"
                            fixed_params.append(f"\n    {val_var}: {type_part} = null")
                            nullability_modified = True
                        else:
                            fixed_params.append(f"\n    {p_clean}")

                    result.append(','.join(fixed_params) + "\n)")
                    pos = idx
                else:
                    result.append(text[m.end():])
                    break

            return "".join(result), nullability_modified, type_modified

        new_content, null_mod, type_mod = process_dto_classes(content)
        if null_mod:
            content = new_content
            fixes_applied.append("Enforced null-safe default values (`? = null`) on `@Serializable` DTOs")
        if type_mod:
            content = new_content
            fixes_applied.append("Migrated integer rating/votes/score DTO fields to `Double` (prevents decimal JsonDecodingException)")

        # 6. Fix string concatenation "$baseUrl" + attr("href") -> element.attr("abs:href")
        if RE_BASEURL_ATTR.search(content):
            content = RE_BASEURL_ATTR.sub(r'\1.attr("abs:href")', content)
            fixes_applied.append('Replaced `"$baseUrl" + attr("href")` with `attr("abs:href")`')

        # 7. Fix invalid SAnime status enum references
        for pattern_re, replacement in STATUS_REPLACEMENTS:
            if pattern_re.search(content):
                content = pattern_re.sub(replacement, content)
                fixes_applied.append(f"Corrected invalid SAnime status constant to `{replacement}` (v16)")

        # 8. Fix sortVideos method declaration to extension method and receiver references in body
        if RE_SORT_VIDEOS_FUNC.search(content):
            content = RE_SORT_VIDEOS_FUNC.sub(r'override fun List<Video>.sortVideos(): List<Video>', content)
            content = RE_SORT_VIDEOS_CALL.sub(r'\1.sortVideos()', content)
            fixes_applied.append("Migrated `sortVideos(videos)` function to `override fun List<Video>.sortVideos(): List<Video>` (v16)")

        # Fix receiver inside sortVideos body: `return videos.sortedWith` -> `return this.sortedWith`
        if "override fun List<Video>.sortVideos()" in content:
            sv_pattern = re.compile(r'(override\s+fun\s+List<Video>\.sortVideos\s*\(\s*\)\s*:\s*List<Video>\s*\{[^}]*?)\bvideos\.(sortedWith|sortedBy|filter|map)', re.DOTALL)
            if sv_pattern.search(content):
                content = sv_pattern.sub(r'\1this.\2', content)
                fixes_applied.append("Fixed `videos.` reference to `this.` inside `List<Video>.sortVideos()` extension body")

        # 9. Fix Hoster named constructor parameters: Hoster(name = ..., url = ...) -> Hoster(hosterName = ..., hosterUrl = ...)
        re_hoster_params = re.compile(r'\bHoster\s*\(\s*name\s*=\s*([^,]+),\s*url\s*=\s*([^)]+)\)')
        if re_hoster_params.search(content):
            content = re_hoster_params.sub(r'Hoster(hosterName = \1, hosterUrl = \2)', content)
            fixes_applied.append("Migrated `Hoster(name=, url=)` constructor arguments to `Hoster(hosterName=, hosterUrl=)` (v16)")

        # 10. Fix UniversalExtractor call: universalExtractor.extractVideos(...) -> universalExtractor.videosFromUrl(...)
        re_univ_extract = re.compile(r'\buniversalExtractor\.extractVideos\s*\(')
        if re_univ_extract.search(content):
            content = re_univ_extract.sub(r'universalExtractor.videosFromUrl(', content)
            fixes_applied.append("Fixed `universalExtractor.extractVideos(...)` -> `universalExtractor.videosFromUrl(...)`")

        # 11. Fix setupPreferenceScreen extensions & parameter types
        if "setupPreferenceScreen" in content:
            # Fix addBaseUrlPreference called without screen receiver or with invalid parameter names
            re_baseurl_no_screen = re.compile(r'(?<!screen\.)\baddBaseUrlPreference\s*\(\s*screen\s*=\s*screen\s*,?')
            if re_baseurl_no_screen.search(content):
                content = re_baseurl_no_screen.sub(r'screen.addBaseUrlPreference(', content)
                fixes_applied.append("Fixed `addBaseUrlPreference(screen=screen, ...)` to `screen.addBaseUrlPreference(...)`")

            # Fix default = -> defaultUrl =
            re_pref_default = re.compile(r'(screen\.addBaseUrlPreference\s*\([^)]*?)\bdefault\s*=')
            if re_pref_default.search(content):
                content = re_pref_default.sub(r'\1defaultUrl =', content)
                fixes_applied.append("Fixed `addBaseUrlPreference` parameter `default` -> `defaultUrl`")

            def fix_base_url_call(m):
                call_text = m.group(0)
                if "preferences = preferences" not in call_text:
                    call_text = re.sub(r'screen\.addBaseUrlPreference\s*\(\n', 'screen.addBaseUrlPreference(\n            preferences = preferences,\n', call_text)
                return call_text

            re_base_url_call = re.compile(r'screen\.addBaseUrlPreference\s*\([^)]+\)', re.DOTALL)
            new_content = re_base_url_call.sub(fix_base_url_call, content)
            if new_content != content:
                content = new_content
                fixes_applied.append("Injected missing `preferences = preferences` in `screen.addBaseUrlPreference`")

            # Fix addListPreference arrayOf(...) -> listOf(...)
            re_listpref_array = re.compile(r'(screen\.addListPreference\s*\([^)]*?)\barrayOf\s*\(')
            if re_listpref_array.search(content):
                content = re_listpref_array.sub(r'\1listOf(', content)
                fixes_applied.append("Migrated `screen.addListPreference` `arrayOf(...)` to `listOf(...)`")

            # Ensure summary = "%s" in addListPreference if missing (idempotent)
            def fix_list_pref_call(m):
                call_text = m.group(0)
                if "summary =" not in call_text:
                    call_text = re.sub(r'(title\s*=\s*["\'][^"\']+["\']\s*,\n)', r'\1            summary = "%s",\n', call_text)
                return call_text

            re_list_pref_call = re.compile(r'screen\.addListPreference\s*\([^)]+\)', re.DOTALL)
            new_content = re_list_pref_call.sub(fix_list_pref_call, content)
            if new_content != content:
                content = new_content
                fixes_applied.append("Injected missing `summary = \"%s\"` in `screen.addListPreference`")

        # 12. Fix missing okhttp3.Request import if Request is used
        if re.search(r':\s*Request\b|\bRequest\s*\(', content) and "import okhttp3.Request" not in content:
            if "import okhttp3.Response" in content:
                content = content.replace("import okhttp3.Response", "import okhttp3.Request\nimport okhttp3.Response")
                fixes_applied.append("Injected missing `import okhttp3.Request`")
            elif "import okhttp3." in content:
                first_okhttp = re.search(r'import okhttp3\.[^\n]+', content)
                if first_okhttp:
                    content = content[:first_okhttp.start()] + "import okhttp3.Request\n" + content[first_okhttp.start():]
                    fixes_applied.append("Injected missing `import okhttp3.Request`")

        # 13. Fix PlaylistUtils.extractFromHls parameter names (subtitleTracks -> subtitleList, audioTracks -> audioList, customHlsHeaders -> referer)
        re_playlist_bad_headers = re.compile(r'(\bextractFromHls\s*\([^)]*)\b(?:customHlsHeaders|customHeaders)\s*=\s*(?:headers\.newBuilder\(\)\.set\(["\']Referer["\'],\s*([^)]+?)\)\.build\(\)|([a-zA-Z0-9_".\' /:-]+))')
        if re_playlist_bad_headers.search(content):
            content = re_playlist_bad_headers.sub(lambda m: f"{m.group(1)}referer = {m.group(2) or m.group(3)}", content)
            fixes_applied.append("Fixed `PlaylistUtils.extractFromHls` parameter `customHlsHeaders` -> `referer`")
        if RE_PLAYLIST_SUBTITLE.search(content):
            content = RE_PLAYLIST_SUBTITLE.sub(r'\1subtitleList =', content)
            fixes_applied.append("Fixed `PlaylistUtils.extractFromHls` parameter `subtitleTracks` -> `subtitleList`")
        if RE_PLAYLIST_AUDIO.search(content):
            content = RE_PLAYLIST_AUDIO.sub(r'\1audioList =', content)
            fixes_applied.append("Fixed `PlaylistUtils.extractFromHls` parameter `audioTracks` -> `audioList`")

        # 14. Fix invalid const val with string templates ($var or ${...})
        re_const_template = re.compile(r'\b(private\s+|protected\s+|internal\s+|public\s+)?const\s+val\s+([a-zA-Z0-9_]+)\s*=\s*("""[^"\\]*(?:\$[a-zA-Z0-9_{])[^"]*"""|"[^"\n\\]*(?:\$[a-zA-Z0-9_{])[^"\n]*")')
        if re_const_template.search(content):
            content = re_const_template.sub(r'\1val \2 = \3', content)
            fixes_applied.append("Fixed invalid `const val` containing string interpolation/templates -> `val`")

        # 15. Fix bare `return ...` inside `runCatching { ... }` blocks to `return@runCatching ...`
        def fix_runcatching_returns(text: str) -> Tuple[str, bool]:
            changed = False
            out = []
            idx = 0
            while idx < len(text):
                rc_match = re.search(r'\brunCatching\s*\{', text[idx:])
                if not rc_match:
                    out.append(text[idx:])
                    break

                brace_start = idx + rc_match.end() - 1
                out.append(text[idx:brace_start])

                depth = 0
                body_start = brace_start + 1
                body_end = -1
                for i in range(brace_start, len(text)):
                    if text[i] == '{':
                        depth += 1
                    elif text[i] == '}':
                        depth -= 1
                        if depth == 0:
                            body_end = i
                            break

                if body_end != -1:
                    body = text[body_start:body_end]
                    following = text[body_end + 1:body_end + 30]
                    if re.match(r'\s*\.getOr', following):
                        new_body = re.sub(r'(?<!@)(?<!return)\breturn\s+([^;\n]+)', r'return@runCatching \1', body)
                        if new_body != body:
                            changed = True
                            body = new_body
                    out.append('{' + body + '}')
                    idx = body_end + 1
                else:
                    out.append('{')
                    idx = brace_start + 1
            return "".join(out), changed

        new_content, rc_fixed = fix_runcatching_returns(content)
        if rc_fixed:
            content = new_content
            fixes_applied.append("Fixed bare `return` inside `runCatching` block to `return@runCatching`")

        # 11. Normalize line endings and whitespace
        lines = [line.rstrip() for line in content.replace("\r\n", "\n").split("\n")]
        # Collapse 3+ blank lines to 2
        normalized_lines = []
        blank_count = 0
        for line in lines:
            if not line:
                blank_count += 1
                if blank_count <= 2:
                    normalized_lines.append(line)
            else:
                blank_count = 0
                normalized_lines.append(line)
        content = "\n".join(normalized_lines).strip() + "\n"

        if content != original:
            if not self.dry_run:
                file_path.write_text(content, encoding="utf-8")
            self.fixed_count += 1
            return True, fixes_applied

        return False, []

def auto_fix_target(repo_root: Path, target_lang: str = None, target_name: str = None, dry_run: bool = False) -> bool:
    fixer = ExtensionAstFixer(dry_run=dry_run)
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
            print(f"❌ Target extension src/{target_lang}/{target_name} not found.")
            return False
    elif target_name:
        for l_dir in sorted(src_dir.iterdir()):
            if l_dir.is_dir() and (l_dir / target_name).exists():
                targets.append(l_dir / target_name)
                break
        if not targets:
            print(f"❌ Target extension '{target_name}' not found.")
            return False
    else:
        # All extensions
        for l_dir in sorted(src_dir.iterdir()):
            if l_dir.is_dir():
                for ext in sorted(l_dir.iterdir()):
                    if ext.is_dir() and (ext / "src").exists():
                        targets.append(ext)

    mode_label = "[DRY RUN] " if dry_run else ""
    print(f"🛠️  {mode_label}Running AST Auto-Remediation on {len(targets)} extension(s)...\n")

    total_remediated_files = 0
    for target in targets:
        kt_files = list(target.rglob("*.kt"))
        rel_mod = f"src/{target.parent.name}/{target.name}"
        mod_fixed = False

        for kt in kt_files:
            changed, fixes = fixer.fix_file(kt)
            if changed:
                if not mod_fixed:
                    print(f"📦 {rel_mod}:")
                    mod_fixed = True
                print(f"  • {kt.name}:")
                for f in fixes:
                    print(f"     ✅ {f}")
                total_remediated_files += 1

    print("\n" + "=" * 50)
    if total_remediated_files > 0:
        action_verb = "Would remediate" if dry_run else "Successfully auto-remediated"
        print(f"🎉 {action_verb} {total_remediated_files} file(s) across {len(targets)} extension(s)!")
    else:
        print("✨ Codebase is already clean! No auto-remediable AST smells detected.")

    return True

fix_codebase = auto_fix_target

def main():
    parser = argparse.ArgumentParser(description="AST Code Smell Auto-Fixer & Remediation Engine (API v16)")
    parser.add_argument("target", nargs="?", help="Target extension name (e.g. <module> or <lang>/<module>)")
    parser.add_argument("--lang", help="Target extension language")
    parser.add_argument("--name", help="Target extension directory name")
    parser.add_argument("--dry-run", action="store_true", help="Report what would be fixed without modifying files")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    target_lang = args.lang
    target_name = args.name
    if args.target:
        if "/" in args.target:
            target_lang, target_name = args.target.split("/", 1)
        else:
            target_name = args.target

    success = auto_fix_target(repo_root, target_lang, target_name, dry_run=args.dry_run)
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
