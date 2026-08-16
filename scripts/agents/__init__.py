"""
SB Extensions Multi-Agent Engineering Architecture
Modular agent framework for Aniyomi API v16 extension development,
reverse engineering, AST remediation, and CI guardianship.
"""

from .orchestrator import (
    OrchestratorAgent,
    PipelineStage,
    WorkflowState,
    OrchestratorConfig,
    WorkflowResult,
)
from .recon_swarm import (
    ReconSwarmAgent,
    HarAnalyzer,
    RouteExplorer,
    SchemaInferer,
    SchemaField,
    SiteMap,
    NetworkEntry,
    DiscoveredRoute,
)
from .deobfuscator import (
    DeobfuscatorAgent,
    DeanEdwardsSolver,
    PlayerJsSolver,
    CipherSolver,
    KotlinDeobfuscatorGenerator,
    DeobfuscationResult,
)
from .kotlin_synthesizer import (
    KotlinSynthesizerAgent,
    DtoGenerator,
    SourceClassGenerator,
    SynthesizerConfig,
    SynthesizedExtension,
)
from .adversarial_critic import (
    AdversarialCriticAgent,
    CriticReport,
    CriticFinding,
    FindingSeverity,
    CodeSmellDetector,
    AstAuditor,
    FuzzingAudit,
)
from .ci_guardian import (
    CiGuardianAgent,
    CompilerLogParser,
    CompilerError,
    ErrorCategory,
    LogTriageResult,
    AstAutoPatcher,
    PatchSuggestion,
)

__all__ = [
    "OrchestratorAgent",
    "PipelineStage",
    "WorkflowState",
    "OrchestratorConfig",
    "WorkflowResult",
    "ReconSwarmAgent",
    "HarAnalyzer",
    "RouteExplorer",
    "SchemaInferer",
    "SchemaField",
    "SiteMap",
    "NetworkEntry",
    "DiscoveredRoute",
    "DeobfuscatorAgent",
    "DeanEdwardsSolver",
    "PlayerJsSolver",
    "CipherSolver",
    "KotlinDeobfuscatorGenerator",
    "DeobfuscationResult",
    "KotlinSynthesizerAgent",
    "DtoGenerator",
    "SourceClassGenerator",
    "SynthesizerConfig",
    "SynthesizedExtension",
    "AdversarialCriticAgent",
    "CriticReport",
    "CriticFinding",
    "FindingSeverity",
    "CodeSmellDetector",
    "AstAuditor",
    "FuzzingAudit",
    "CiGuardianAgent",
    "CompilerLogParser",
    "CompilerError",
    "ErrorCategory",
    "LogTriageResult",
    "AstAutoPatcher",
    "PatchSuggestion",
]
