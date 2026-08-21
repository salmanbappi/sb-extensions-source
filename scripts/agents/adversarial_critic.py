"""
Adversarial Critic Agent
Conducts automated AST rule auditing, code smell detection, null-safety fuzzing,
and micro-debate evaluations against Aniyomi API v16 invariants.
"""

import json
import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional

class FindingSeverity(str, Enum):
    BLOCKER = "BLOCKER"
    WARNING = "WARNING"
    INFO = "INFO"

@dataclass
class CriticFinding:
    rule_id: str
    message: str
    severity: FindingSeverity
    line_number: Optional[int] = None
    suggested_fix: Optional[str] = None

@dataclass
class CriticReport:
    is_passing: bool
    score: int  # 0 to 100
    blockers: List[CriticFinding] = field(default_factory=list)
    warnings: List[CriticFinding] = field(default_factory=list)
    infos: List[CriticFinding] = field(default_factory=list)
    summary: str = ""

    @property
    def total_findings(self) -> int:
        return len(self.blockers) + len(self.warnings) + len(self.infos)

class CodeSmellDetector:
    """Detects anti-patterns and API v16 violations in Kotlin source code."""

    @classmethod
    def audit_source_code(cls, code: str) -> List[CriticFinding]:
        findings: List[CriticFinding] = []
        lines = code.splitlines()

        # 1. Base Class Check: Must NOT inherit from ParsedAnimeHttpSource
        if "ParsedAnimeHttpSource" in code:
            findings.append(
                CriticFinding(
                    rule_id="RULE_001_INVALID_BASE_CLASS",
                    message="Found `ParsedAnimeHttpSource`. Extensions must inherit from `extensions.utils.Source`.",
                    severity=FindingSeverity.BLOCKER,
                    suggested_fix="Inherit from `Source()` instead.",
                )
            )

        # 2. Base Class Check: Must inherit from Source()
        if "class " in code and ": Source()" not in code and "Source" not in code:
            findings.append(
                CriticFinding(
                    rule_id="RULE_002_MISSING_SOURCE_INHERITANCE",
                    message="Class does not inherit from `Source()`.",
                    severity=FindingSeverity.BLOCKER,
                    suggested_fix="Extend `extensions.utils.Source`.",
                )
            )

        # 3. initialized = true invariant in getAnimeDetails
        if "getAnimeDetails" in code:
            if "initialized = true" not in code and "initialized=true" not in code:
                findings.append(
                    CriticFinding(
                        rule_id="RULE_003_MISSING_INITIALIZED_TRUE",
                        message="`getAnimeDetails` is missing `initialized = true` assignment.",
                        severity=FindingSeverity.BLOCKER,
                        suggested_fix="Set `anime.initialized = true` inside `getAnimeDetails`.",
                    )
                )

        # 4. Positional Video constructors check
        # Match Video(url, quality, ...) without named videoUrl =
        for idx, line in enumerate(lines, start=1):
            if re.search(r'\bVideo\s*\(\s*[^)]+', line):
                call = line.strip()
                if "Video(" in call and "videoUrl" not in call and "videoTitle" not in call:
                    findings.append(
                        CriticFinding(
                            rule_id="RULE_004_LEGACY_VIDEO_CONSTRUCTOR",
                            message=f"Line {idx}: Legacy positional `Video(...)` constructor used.",
                            severity=FindingSeverity.BLOCKER,
                            line_number=idx,
                            suggested_fix="Use named parameters: `Video(videoUrl = ..., videoTitle = ..., headers = ...)`.",
                        )
                    )

            # 5. .quality check (deprecated property on Video)
            if re.search(r'\b[a-zA-Z0-9_]+\.quality\b', line):
                findings.append(
                    CriticFinding(
                        rule_id="RULE_005_DEPRECATED_VIDEO_QUALITY_PROP",
                        message=f"Line {idx}: `.quality` property accessed on Video model.",
                        severity=FindingSeverity.BLOCKER,
                        line_number=idx,
                        suggested_fix="Use `.videoTitle` instead of `.quality` in API v16.",
                    )
                )

            # 6. Raw string URL concatenation instead of UrlUtils.fixUrl
            if re.search(r'baseUrl\s*\+\s*["\']|"\$baseUrl[/\$]', line):
                if "popularAnimeRequest" not in line and "latestUpdatesRequest" not in line and "searchAnimeRequest" not in line:
                    findings.append(
                        CriticFinding(
                            rule_id="RULE_006_MANUAL_URL_CONCAT",
                            message=f"Line {idx}: Manual URL concatenation detected.",
                            severity=FindingSeverity.WARNING,
                            line_number=idx,
                            suggested_fix="Use `UrlUtils.fixUrl(relativeUrl, baseUrl)`.",
                        )
                    )

            # 7. Inline preference string literals in setupPreferenceScreen
            if "key =" in line and '"' in line and "setupPreferenceScreen" in code:
                if re.search(r'key\s*=\s*"[^"]+"', line):
                    findings.append(
                        CriticFinding(
                            rule_id="RULE_007_INLINE_PREFERENCE_KEY",
                            message=f"Line {idx}: Inline preference key string found.",
                            severity=FindingSeverity.WARNING,
                            line_number=idx,
                            suggested_fix="Define preference key in `companion object` as `private const val`.",
                        )
                    )

        # 8. DTO nullability & default fallback audits
        dto_classes = re.findall(
            r'(@(?:kotlinx\.serialization\.)?Serializable\s+(?:data\s+)?class\s+[A-Za-z0-9_]+\s*\([^)]*\))',
            code,
            re.DOTALL,
        )
        for dto in dto_classes:
            params = dto.split("(", 1)[1].rsplit(")", 1)[0].split(",")
            for p in params:
                p_clean = p.strip()
                if not p_clean or p_clean.startswith("//"):
                    continue
                if "=" not in p_clean:
                    findings.append(
                        CriticFinding(
                            rule_id="RULE_008_DTO_MISSING_DEFAULT_FALLBACK",
                            message=f"DTO field without default fallback: `{p_clean}`.",
                            severity=FindingSeverity.WARNING,
                            suggested_fix="Add `= null` default value to prevent JsonDecodingException.",
                        )
                    )
                # Check score/rating fields
                p_lower = p_clean.lower()
                if any(kw in p_lower for kw in ("score", "rating", "vote")) and "double?" not in p_lower:
                    findings.append(
                        CriticFinding(
                            rule_id="RULE_009_RATING_NOT_DOUBLE",
                            message=f"Rating/score field not typed as `Double?`: `{p_clean}`.",
                            severity=FindingSeverity.WARNING,
                            suggested_fix="Change type to `Double? = null`.",
                        )
                    )

        return findings

class FuzzingAudit:
    """Fuzzes JSON payloads against DTO rules to detect deserialization vulnerabilities."""

    @staticmethod
    def fuzz_json_payload(sample_json: Dict[str, Any]) -> List[CriticFinding]:
        findings: List[CriticFinding] = []

        def check_field_types(d: Dict[str, Any], path: str = ""):
            for k, v in d.items():
                curr_path = f"{path}.{k}" if path else k
                k_lower = k.lower()

                # Rating/Score fields with string or int values that need flexible coercion
                if any(term in k_lower for term in ("rating", "score", "vote")):
                    if isinstance(v, str):
                        try:
                            float(v)
                            findings.append(
                                CriticFinding(
                                    rule_id="FUZZ_001_STRING_SCORE_TYPE",
                                    message=f"Score field `{curr_path}` returned as string in JSON.",
                                    severity=FindingSeverity.WARNING,
                                    suggested_fix="Ensure DTO parser uses `isLenient = true` or custom deserializer.",
                                )
                            )
                        except ValueError:
                            pass

                if isinstance(v, dict):
                    check_field_types(v, curr_path)

        check_field_types(sample_json)
        return findings

class AstAuditor:
    """Performs deep AST-like structural evaluation of synthesized Kotlin code."""

    def __init__(self):
        self.detector = CodeSmellDetector()
        self.fuzzer = FuzzingAudit()

    def audit(self, code: str, sample_payload: Optional[Dict[str, Any]] = None) -> CriticReport:
        findings = self.detector.audit_source_code(code)

        if sample_payload:
            findings.extend(self.fuzzer.fuzz_json_payload(sample_payload))

        blockers = [f for f in findings if f.severity == FindingSeverity.BLOCKER]
        warnings = [f for f in findings if f.severity == FindingSeverity.WARNING]
        infos = [f for f in findings if f.severity == FindingSeverity.INFO]

        # Score calculation: 100 - (25 * blockers) - (5 * warnings)
        penalty = (len(blockers) * 25) + (len(warnings) * 5)
        score = max(0, 100 - penalty)
        is_passing = (len(blockers) == 0 and score >= 80)

        summary = (
            f"Audit Complete: Score={score}/100 | Blockers={len(blockers)}, "
            f"Warnings={len(warnings)}, Info={len(infos)}"
        )

        return CriticReport(
            is_passing=is_passing,
            score=score,
            blockers=blockers,
            warnings=warnings,
            infos=infos,
            summary=summary,
        )

class AdversarialCriticAgent:
    """Adversarial Critic agent for micro-debates and AST compliance validation."""

    def __init__(self):
        self.auditor = AstAuditor()

    def review(self, code: str, sample_payload: Optional[Dict[str, Any]] = None) -> CriticReport:
        """Evaluates code and outputs strict critique report."""
        return self.auditor.audit(code, sample_payload)

    def debate(self, current_code: str, previous_report: Optional[CriticReport] = None) -> tuple[bool, CriticReport, str]:
        """Conducts a micro-debate evaluation round, returning (is_acceptable, report, critique_feedback)."""
        report = self.review(current_code)
        if report.is_passing:
            return True, report, "Code passes all AST compliance and v16 model invariants."

        critique_points = []
        for b in report.blockers:
            critique_points.append(f"- [BLOCKER] {b.message} Fix: {b.suggested_fix}")
        for w in report.warnings:
            critique_points.append(f"- [WARNING] {w.message} Fix: {w.suggested_fix}")

        feedback = "Critic rejects code with following findings:\n" + "\n".join(critique_points)
        return False, report, feedback
