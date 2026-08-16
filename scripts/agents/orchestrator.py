"""
Master Orchestrator Agent
Manages multi-agent pipeline workflows, state machines, subagent coordination,
micro-debates, remediation loops, and telemetry.
"""

import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional

from .adversarial_critic import AdversarialCriticAgent, CriticReport
from .ci_guardian import CiGuardianAgent, LogTriageResult
from .deobfuscator import DeobfuscationResult, DeobfuscatorAgent
from .kotlin_synthesizer import (
    KotlinSynthesizerAgent,
    SynthesizedExtension,
    SynthesizerConfig,
)
from .recon_swarm import ReconSwarmAgent, SiteMap


class PipelineStage(str, Enum):
    INIT = "INIT"
    RECON = "RECON"
    DEOBFUSCATION = "DEOBFUSCATION"
    SYNTHESIS = "SYNTHESIS"
    CRITIQUE = "CRITIQUE"
    REMEDIATION = "REMEDIATION"
    VALIDATION = "VALIDATION"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


@dataclass
class WorkflowState:
    current_stage: PipelineStage = PipelineStage.INIT
    history: List[Dict[str, Any]] = field(default_factory=list)
    stage_data: Dict[str, Any] = field(default_factory=dict)
    errors: List[str] = field(default_factory=list)
    is_success: bool = False

    def transition_to(self, stage: PipelineStage, metadata: Optional[Dict[str, Any]] = None):
        entry = {
            "from_stage": self.current_stage.value,
            "to_stage": stage.value,
            "timestamp": time.time(),
            "metadata": metadata or {},
        }
        self.history.append(entry)
        self.current_stage = stage


@dataclass
class OrchestratorConfig:
    max_debate_rounds: int = 3
    min_passing_score: int = 80
    auto_remediate: bool = True
    stop_on_blocker: bool = False


@dataclass
class WorkflowResult:
    state: WorkflowState
    site_map: Optional[SiteMap] = None
    deobfuscation_results: List[DeobfuscationResult] = field(default_factory=list)
    synthesized_extension: Optional[SynthesizedExtension] = None
    critic_report: Optional[CriticReport] = None
    triage_result: Optional[LogTriageResult] = None
    debate_rounds: int = 0


class OrchestratorAgent:
    """Master Orchestrator coordinating all specialized engineering agents."""

    def __init__(self, config: Optional[OrchestratorConfig] = None):
        self.config = config or OrchestratorConfig()
        self.recon_swarm = ReconSwarmAgent()
        self.deobfuscator = DeobfuscatorAgent()
        self.synthesizer = KotlinSynthesizerAgent()
        self.critic = AdversarialCriticAgent()
        self.ci_guardian = CiGuardianAgent()

    def run_full_pipeline(
        self,
        synth_config: SynthesizerConfig,
        har_data: Optional[str | dict] = None,
        html_samples: Optional[List[str]] = None,
        json_samples: Optional[Dict[str, Any]] = None,
        obfuscated_payloads: Optional[List[str]] = None,
        decryption_key: Optional[str] = None,
    ) -> WorkflowResult:
        """Executes the end-to-end multi-agent engineering lifecycle."""
        state = WorkflowState()
        result = WorkflowResult(state=state)

        # 1. RECON STAGE
        state.transition_to(PipelineStage.RECON)
        try:
            site_map = self.recon_swarm.run_recon(
                base_url=synth_config.base_url,
                name=synth_config.source_name,
                har_data=har_data,
                html_samples=html_samples,
                json_samples=json_samples,
            )
            result.site_map = site_map
            state.stage_data["site_map"] = site_map
        except Exception as e:
            state.errors.append(f"Recon failed: {e}")
            state.transition_to(PipelineStage.FAILED)
            return result

        # 2. DEOBFUSCATION STAGE
        state.transition_to(PipelineStage.DEOBFUSCATION)
        deobf_results: List[DeobfuscationResult] = []
        if obfuscated_payloads:
            for payload in obfuscated_payloads:
                res = self.deobfuscator.solve(payload, key=decryption_key)
                deobf_results.append(res)
                if res.generated_kotlin:
                    synth_config.helper_code.append(res.generated_kotlin)
        result.deobfuscation_results = deobf_results
        state.stage_data["deobfuscation"] = deobf_results

        # 3. SYNTHESIS STAGE
        state.transition_to(PipelineStage.SYNTHESIS)
        try:
            synthesized = self.synthesizer.synthesize(
                config=synth_config,
                schemas=site_map.detected_schemas,
            )
            result.synthesized_extension = synthesized
            state.stage_data["synthesized"] = synthesized
        except Exception as e:
            state.errors.append(f"Synthesis failed: {e}")
            state.transition_to(PipelineStage.FAILED)
            return result

        # 4. CRITIQUE & MICRO-DEBATE LOOP
        state.transition_to(PipelineStage.CRITIQUE)
        current_code = synthesized.full_combined_code
        debate_round = 0
        critic_report = None

        while debate_round < self.config.max_debate_rounds:
            debate_round += 1
            critic_report = self.critic.review(current_code)
            result.critic_report = critic_report
            result.debate_rounds = debate_round

            if critic_report.is_passing and critic_report.score >= self.config.min_passing_score:
                break

            # If not passing and auto_remediate enabled -> Remediation
            if self.config.auto_remediate:
                state.transition_to(
                    PipelineStage.REMEDIATION,
                    {"round": debate_round, "score": critic_report.score},
                )
                patched_code, fixes = self.ci_guardian.auto_patch_code(current_code)
                if fixes and patched_code != current_code:
                    current_code = patched_code
                    synthesized.source_code = current_code
                    synthesized.full_combined_code = current_code
                    state.transition_to(PipelineStage.CRITIQUE, {"remediated_fixes": fixes})
                else:
                    break
            else:
                break

        # 5. VALIDATION & COMPLETION
        state.transition_to(PipelineStage.VALIDATION)
        final_report = self.critic.review(current_code)
        result.critic_report = final_report

        if final_report.is_passing:
            state.is_success = True
            state.transition_to(PipelineStage.COMPLETED)
        else:
            state.is_success = False
            state.transition_to(PipelineStage.FAILED)

        return result

    def run_pipeline(
        self,
        url: str,
        name: Optional[str] = None,
        lang: str = "en",
    ) -> Dict[str, Any]:
        """Convenience method for CLI pipeline execution from a website URL."""
        import re
        from urllib.parse import urlparse

        clean_url = url if "://" in url else f"https://{url}"
        parsed = urlparse(clean_url)
        base_url = f"{parsed.scheme}://{parsed.netloc}"
        ext_name = name or parsed.netloc.split(".")[0].capitalize()
        pkg_name = f"eu.kanade.tachiyomi.animeextension.{lang}.{ext_name.lower().replace(' ', '').replace('-', '')}"
        class_name = "".join(w.capitalize() for w in re.sub(r'[^a-zA-Z0-9]', ' ', ext_name).split())

        synth_config = SynthesizerConfig(
            pkg_name=pkg_name,
            class_name=class_name,
            source_name=ext_name,
            base_url=base_url,
            lang=lang,
        )

        result = self.run_full_pipeline(synth_config=synth_config)
        return {
            "status": "SUCCESS" if result.state.is_success else "FAILED",
            "stage": result.state.current_stage.value,
            "debate_rounds": result.debate_rounds,
            "critic_score": result.critic_report.score if result.critic_report else 0,
            "critic_summary": result.critic_report.summary if result.critic_report else "",
            "errors": result.state.errors,
            "source_code_preview": (result.synthesized_extension.full_combined_code[:400] + "...") if result.synthesized_extension else None,
        }
