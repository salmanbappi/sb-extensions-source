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
        if re.search(r'\b(?:it|video)\.quality\b', content):
            content = re.sub(r'(\b(?:it|video))\.(?:quality)\b', r'\1.videoTitle', content)
            fixes_applied.append("Renamed `.quality` property to `.videoTitle` (v16)")

        # 2. Fix legacy positional Video(url, quality, ...) to named Video(videoUrl=, videoTitle=, ...)
        def fix_legacy_video_constructor(match):
            args_str = match.group(1).strip()
            # If already using named args, skip
            if "videoUrl" in args_str or "videoTitle" in args_str:
                return match.group(0)

            args = split_params_depth_aware(args_str)
            if len(args) == 2:
                return f"Video(videoUrl = {args[0]}, videoTitle = {args[1]})"
            elif len(args) == 3:
                return f"Video(videoUrl = {args[0]}, videoTitle = {args[1]}, headers = {args[2]})"
            elif len(args) >= 4:
                # In v15: Video(url, quality, videoUrl?, headers?)
                direct_url = args[2] if args[2] != "null" else args[0]
                return f"Video(videoUrl = {direct_url}, videoTitle = {args[1]}, headers = {args[3]})"
            return match.group(0)

        new_content = re.sub(r'\bVideo\(([^()]+)\)', fix_legacy_video_constructor, content)
        if new_content != content:
            content = new_content
            fixes_applied.append("Migrated positional `Video(...)` constructors to named arguments (v16)")

        # 3. Ensure initialized = true in getAnimeDetails
        if "getAnimeDetails" in content and "initialized = true" not in content:
            if re.search(r'getAnimeDetails\([^)]*\)\s*:\s*SAnime\s*\{', content):
                content = re.sub(
                    r'(override\s+suspend\s+fun\s+getAnimeDetails\s*\(\s*([a-zA-Z0-9_]+)\s*:\s*SAnime\s*\)\s*:\s*SAnime\s*\{)',
                    r'\1\n        \2.initialized = true',
                    content
                )
                fixes_applied.append("Injected `initialized = true` inside `getAnimeDetails`")

        # 4. Ensure @Serializable data class constructor fields have default = null fallbacks
        def fix_dto_nullability(match):
            header = match.group(1)
            params_block = match.group(2)
            footer = match.group(3)

            params = split_params_depth_aware(params_block)
            fixed_params = []
            modified = False
            for p in params:
                p_clean = p.strip()
                if not p_clean:
                    continue
                # If param has a type and no default value: e.g. val name: String? or val name: String
                if ":" in p_clean and "=" not in p_clean:
                    parts = p_clean.split(":", 1)
                    val_var = parts[0].strip()
                    type_part = parts[1].strip()
                    if not type_part.endswith("?"):
                        type_part += "?"
                    fixed_params.append(f"\n    {val_var}: {type_part} = null")
                    modified = True
                elif ":" in p_clean and "= null" not in p_clean and "?" in p_clean:
                    fixed_params.append(f"\n    {p_clean} = null")
                    modified = True
                else:
                    fixed_params.append(f"\n    {p_clean}")

            if modified:
                return f"{header}{','.join(fixed_params)}\n{footer}"
            return match.group(0)

        # Match data classes under @Serializable
        dto_pattern = r'(@Serializable\s+(?:data\s+)?class\s+[A-Za-z0-9_]+(?:\s*<[^>]+>)?\s*\()([^()]+)(\))'
        new_content = re.sub(dto_pattern, fix_dto_nullability, content)
        if new_content != content:
            content = new_content
            fixes_applied.append("Enforced null-safe default values (`? = null`) on `@Serializable` DTOs")

        # 5. Fix string concatenation "$baseUrl" + attr("href") -> element.attr("abs:href")
        if re.search(r'(?:"\$baseUrl"|baseUrl)\s*\+\s*([a-zA-Z0-9_]+)\.attr\(["\']href["\']\)', content):
            content = re.sub(
                r'(?:"\$baseUrl"|baseUrl)\s*\+\s*([a-zA-Z0-9_]+)\.attr\(["\']href["\']\)',
                r'\1.attr("abs:href")',
                content
            )
            fixes_applied.append('Replaced `"$baseUrl" + attr("href")` with `attr("abs:href")`')

        # 6. Normalize line endings and whitespace
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
