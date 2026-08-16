"""
CI Guardian Agent
Triages CI compiler logs, parses Kotlin compiler diagnostics, categorizes error patterns,
and generates automated AST patch suggestions to remediate build failures.
"""

import re
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Dict, List, Optional, Tuple


class ErrorCategory(str, Enum):
    MODEL_V16_MISMATCH = "MODEL_V16_MISMATCH"
    NULLABILITY_MISMATCH = "NULLABILITY_MISMATCH"
    UNRESOLVED_REFERENCE = "UNRESOLVED_REFERENCE"
    TYPE_MISMATCH = "TYPE_MISMATCH"
    MISSING_PARAMETER = "MISSING_PARAMETER"
    SERIALIZATION_ERROR = "SERIALIZATION_ERROR"
    UNKNOWN = "UNKNOWN"


@dataclass
class CompilerError:
    file_path: str
    line_number: int
    column_number: int
    message: str
    category: ErrorCategory
    raw_line: str


@dataclass
class PatchSuggestion:
    file_path: str
    description: str
    line_number: Optional[int] = None
    original_snippet: Optional[str] = None
    replacement_snippet: Optional[str] = None


@dataclass
class LogTriageResult:
    has_errors: bool
    total_errors: int
    errors: List[CompilerError] = field(default_factory=list)
    categorized: Dict[ErrorCategory, List[CompilerError]] = field(default_factory=dict)
    patch_suggestions: List[PatchSuggestion] = field(default_factory=list)
    summary: str = ""


class CompilerLogParser:
    """Parses raw Kotlin compiler log outputs and Gradle CI logs into structured diagnostics."""

    # Patterns matching standard kotlinc diagnostics:
    # e: /path/to/File.kt: (12, 34): Unresolved reference: foo
    # e: file.kt:12:34: error: message
    # e: /path/to/File.kt:12:34 message
    KOTLINC_PATTERN = re.compile(
        r'e:\s+(?:file://)?(?P<file>[^\s:]+)(?::\s*\(?(?P<line>\d+)[,:]\s*(?P<col>\d+)\)?):\s*(?P<msg>.+)'
    )

    @classmethod
    def categorize_error(cls, message: str) -> ErrorCategory:
        msg_lower = message.lower()
        if "quality" in msg_lower or "parsedanimehttpsource" in msg_lower or "no value passed for parameter 'videourl'" in msg_lower:
            return ErrorCategory.MODEL_V16_MISMATCH
        if "null" in msg_lower or "nullable" in msg_lower or "inferred type is" in msg_lower and "?" in message:
            return ErrorCategory.NULLABILITY_MISMATCH
        if "unresolved reference" in msg_lower:
            return ErrorCategory.UNRESOLVED_REFERENCE
        if "type mismatch" in msg_lower:
            return ErrorCategory.TYPE_MISMATCH
        if "no value passed for parameter" in msg_lower or "too many arguments" in msg_lower:
            return ErrorCategory.MISSING_PARAMETER
        if "serialization" in msg_lower or "serializable" in msg_lower:
            return ErrorCategory.SERIALIZATION_ERROR
        return ErrorCategory.UNKNOWN

    @classmethod
    def parse_log(cls, log_text: str) -> List[CompilerError]:
        errors: List[CompilerError] = []
        for line in log_text.splitlines():
            line_str = line.strip()
            match = cls.KOTLINC_PATTERN.search(line_str)
            if match:
                file_path = match.group("file")
                line_no = int(match.group("line"))
                col_no = int(match.group("col"))
                msg = match.group("msg").strip()
                cat = cls.categorize_error(msg)

                errors.append(
                    CompilerError(
                        file_path=file_path,
                        line_number=line_no,
                        column_number=col_no,
                        message=msg,
                        category=cat,
                        raw_line=line_str,
                    )
                )
        return errors


class AstAutoPatcher:
    """Applies AST-aware code fixes based on compiler diagnostics and v16 invariants."""

    @classmethod
    def generate_patches(cls, errors: List[CompilerError], source_code_map: Dict[str, str]) -> List[PatchSuggestion]:
        patches: List[PatchSuggestion] = []

        for err in errors:
            if err.category == ErrorCategory.MODEL_V16_MISMATCH:
                if "quality" in err.message.lower():
                    patches.append(
                        PatchSuggestion(
                            file_path=err.file_path,
                            description="Rename deprecated `.quality` property to `.videoTitle` (v16)",
                            line_number=err.line_number,
                            original_snippet=".quality",
                            replacement_snippet=".videoTitle",
                        )
                    )
                elif "parsedanimehttpsource" in err.message.lower():
                    patches.append(
                        PatchSuggestion(
                            file_path=err.file_path,
                            description="Replace legacy `ParsedAnimeHttpSource` with `extensions.utils.Source`",
                            line_number=err.line_number,
                            original_snippet=": ParsedAnimeHttpSource()",
                            replacement_snippet=": Source()",
                        )
                    )
                elif "videourl" in err.message.lower() or "no value passed for parameter" in err.message.lower():
                    patches.append(
                        PatchSuggestion(
                            file_path=err.file_path,
                            description="Migrate positional `Video(...)` constructor to named arguments",
                            line_number=err.line_number,
                            original_snippet="Video(",
                            replacement_snippet="Video(videoUrl = ",
                        )
                    )
            elif err.category == ErrorCategory.UNRESOLVED_REFERENCE:
                if "UrlUtils" in err.message:
                    patches.append(
                        PatchSuggestion(
                            file_path=err.file_path,
                            description="Import `extensions.utils.UrlUtils`",
                            line_number=1,
                            original_snippet="",
                            replacement_snippet="import extensions.utils.UrlUtils",
                        )
                    )
                elif "Source" in err.message:
                    patches.append(
                        PatchSuggestion(
                            file_path=err.file_path,
                            description="Import `extensions.utils.Source`",
                            line_number=1,
                            original_snippet="",
                            replacement_snippet="import extensions.utils.Source",
                        )
                    )

        return patches

    @classmethod
    def patch_code_string(cls, code: str) -> Tuple[str, List[str]]:
        """Directly applies automated remediations on a Kotlin source string."""
        modified = code
        applied_fixes: List[str] = []

        # 1. Fix it.quality / video.quality / any.quality -> .videoTitle
        if re.search(r'\.quality\b', modified):
            modified = re.sub(r'(\b[a-zA-Z0-9_\]]+)\.quality\b', r'\1.videoTitle', modified)
            applied_fixes.append("Migrated `.quality` property to `.videoTitle`")

        # 2. Fix ParsedAnimeHttpSource -> Source
        if "ParsedAnimeHttpSource" in modified:
            modified = modified.replace("ParsedAnimeHttpSource", "Source")
            applied_fixes.append("Migrated `ParsedAnimeHttpSource` base class to `Source`")

        # 3. Inject initialized = true in getAnimeDetails if missing
        if "getAnimeDetails" in modified and "initialized = true" not in modified and "initialized=true" not in modified:
            pattern = re.compile(
                r'(override\s+suspend\s+fun\s+getAnimeDetails\s*\(\s*([a-zA-Z0-9_]+)\s*:\s*SAnime\s*\)\s*:\s*SAnime\s*\{)'
            )
            if pattern.search(modified):
                modified = pattern.sub(r'\1\n        \2.initialized = true', modified)
                applied_fixes.append("Injected `initialized = true` inside `getAnimeDetails`")

        # 4. Migrate positional Video(url, quality, ...) to named Video(videoUrl = ..., videoTitle = ...)
        # Simple regex replacer for standard 2 or 3 positional params
        def fix_video_call(m):
            args_content = m.group(1).strip()
            if "videoUrl" in args_content or "videoTitle" in args_content:
                return m.group(0)
            parts = [p.strip() for p in args_content.split(",")]
            if len(parts) == 2:
                applied_fixes.append("Migrated positional Video(...) constructor to named arguments")
                return f"Video(videoUrl = {parts[0]}, videoTitle = {parts[1]})"
            elif len(parts) == 3:
                applied_fixes.append("Migrated positional Video(...) constructor to named arguments")
                return f"Video(videoUrl = {parts[0]}, videoTitle = {parts[1]}, headers = {parts[2]})"
            return m.group(0)

        modified = re.sub(r'\bVideo\s*\(([^)]+)\)', fix_video_call, modified)

        return modified, applied_fixes


class CiGuardianAgent:
    """Guardian agent overseeing CI logs, triaging compiler diagnostics, and auto-patching."""

    def __init__(self):
        self.parser = CompilerLogParser()
        self.patcher = AstAutoPatcher()

    def triage_log(self, log_content: str) -> LogTriageResult:
        """Parses and triages build / compiler logs."""
        errors = self.parser.parse_log(log_content)
        categorized: Dict[ErrorCategory, List[CompilerError]] = {}
        for err in errors:
            categorized.setdefault(err.category, []).append(err)

        patches = self.patcher.generate_patches(errors, {})
        has_errors = len(errors) > 0
        summary = (
            f"Triage Complete: Found {len(errors)} compiler errors across "
            f"{len(categorized)} categories. Generated {len(patches)} patch suggestions."
        )

        return LogTriageResult(
            has_errors=has_errors,
            total_errors=len(errors),
            errors=errors,
            categorized=categorized,
            patch_suggestions=patches,
            summary=summary,
        )

    def auto_patch_code(self, source_code: str) -> Tuple[str, List[str]]:
        """Applies immediate AST fixes to source code."""
        return self.patcher.patch_code_string(source_code)
