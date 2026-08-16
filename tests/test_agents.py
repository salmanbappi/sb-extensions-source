#!/usr/bin/env python3
"""
Unit and Integration Test Suite for Multi-Agent Engineering Architecture
Tests Orchestrator, Recon Swarm, Deobfuscator, Kotlin Synthesizer,
Adversarial Critic, and CI Guardian agents.
"""

import json
import unittest
from pathlib import Path
import sys

# Ensure repository root is on sys.path
REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.agents import (
    AdversarialCriticAgent,
    AstAuditor,
    AstAutoPatcher,
    CipherSolver,
    CiGuardianAgent,
    CodeSmellDetector,
    CompilerError,
    CompilerLogParser,
    DeanEdwardsSolver,
    DeobfuscatorAgent,
    DtoGenerator,
    ErrorCategory,
    FindingSeverity,
    HarAnalyzer,
    KotlinDeobfuscatorGenerator,
    KotlinSynthesizerAgent,
    LogTriageResult,
    OrchestratorAgent,
    OrchestratorConfig,
    PipelineStage,
    PlayerJsSolver,
    ReconSwarmAgent,
    RouteExplorer,
    SchemaField,
    SchemaInferer,
    SiteMap,
    SourceClassGenerator,
    SynthesizerConfig,
    WorkflowState,
)


class TestOrchestrator(unittest.TestCase):
    """Verifies Orchestrator state machine, transitions, pipelines, and debate loops."""

    def setUp(self):
        self.orchestrator = OrchestratorAgent()

    def test_workflow_state_transitions(self):
        state = WorkflowState()
        self.assertEqual(state.current_stage, PipelineStage.INIT)
        self.assertEqual(len(state.history), 0)

        state.transition_to(PipelineStage.RECON, {"note": "starting recon"})
        self.assertEqual(state.current_stage, PipelineStage.RECON)
        self.assertEqual(len(state.history), 1)
        self.assertEqual(state.history[0]["from_stage"], "INIT")
        self.assertEqual(state.history[0]["to_stage"], "RECON")

        state.transition_to(PipelineStage.SYNTHESIS)
        self.assertEqual(state.current_stage, PipelineStage.SYNTHESIS)
        self.assertEqual(len(state.history), 2)

    def test_full_pipeline_success(self):
        config = SynthesizerConfig(
            pkg_name="eu.kanade.tachiyomi.animeextension.en.testanime",
            class_name="TestAnime",
            source_name="Test Anime",
            base_url="https://testanime.example.com",
            lang="en",
        )

        sample_har = {
            "log": {
                "entries": [
                    {
                        "request": {
                            "url": "https://testanime.example.com/api/v1/anime",
                            "method": "GET",
                            "headers": [{"name": "User-Agent", "value": "Mozilla/5.0"}],
                        },
                        "response": {
                            "status": 200,
                            "content": {
                                "mimeType": "application/json",
                                "text": '{"data": [{"id": 1, "title": "Test", "score": 8.5}]}',
                            },
                            "headers": [],
                        },
                    },
                    {
                        "request": {
                            "url": "https://stream.example.com/hls/master.m3u8",
                            "method": "GET",
                            "headers": [{"name": "Referer", "value": "https://testanime.example.com"}],
                        },
                        "response": {
                            "status": 200,
                            "content": {
                                "mimeType": "application/vnd.apple.mpegurl",
                                "text": "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\n720p.m3u8",
                            },
                            "headers": [],
                        },
                    },
                ]
            }
        }

        sample_html = [
            '<div class="item"><a href="/watch/anime-1">Watch 1</a></div>'
            '<iframe src="https://stream.example.com/embed/123"></iframe>'
        ]

        sample_json = {
            "AnimeListResponse": {
                "items": [{"id": 100, "title": "Demon Slayer", "score": 9.1}],
                "has_next": True,
            }
        }

        result = self.orchestrator.run_full_pipeline(
            synth_config=config,
            har_data=sample_har,
            html_samples=sample_html,
            json_samples=sample_json,
        )

        self.assertTrue(result.state.is_success)
        self.assertEqual(result.state.current_stage, PipelineStage.COMPLETED)
        self.assertIsNotNone(result.site_map)
        self.assertEqual(len(result.site_map.media_streams), 1)
        self.assertIn("https://stream.example.com/hls/master.m3u8", result.site_map.media_streams)
        self.assertIsNotNone(result.synthesized_extension)
        self.assertTrue(result.critic_report.is_passing)
        self.assertGreaterEqual(result.critic_report.score, 80)

    def test_orchestrator_remediation_loop(self):
        config = SynthesizerConfig(
            pkg_name="eu.kanade.tachiyomi.animeextension.en.badtest",
            class_name="BadTest",
            source_name="Bad Test",
            base_url="https://badtest.example.com",
            helper_code=[
                """
                fun sampleBadCode() {
                    val list = listOf(Video("https://example.com/video.mp4", "1080p"))
                    val q = list[0].quality
                }
                """
            ],
        )

        result = self.orchestrator.run_full_pipeline(synth_config=config)
        self.assertTrue(result.state.is_success)
        self.assertEqual(result.state.current_stage, PipelineStage.COMPLETED)
        self.assertGreaterEqual(result.debate_rounds, 1)
        # Verify that quality was patched out
        self.assertNotIn("list[0].quality", result.synthesized_extension.source_code)
        self.assertIn("list[0].videoTitle", result.synthesized_extension.source_code)

    def test_orchestrator_cli_run_pipeline(self):
        result = self.orchestrator.run_pipeline("https://animetest.example.com", name="AnimeTest", lang="en")
        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["stage"], "COMPLETED")
        self.assertGreaterEqual(result["critic_score"], 80)
        self.assertIsNotNone(result["source_code_preview"])


class TestReconSwarm(unittest.TestCase):
    """Verifies Recon Swarm HAR analysis, route exploration, and schema inference."""

    def setUp(self):
        self.agent = ReconSwarmAgent()

    def test_har_stream_and_api_extraction(self):
        har_data = {
            "log": {
                "entries": [
                    {
                        "request": {
                            "url": "https://cdn.host.com/stream/index.m3u8?token=xyz",
                            "method": "GET",
                            "headers": [
                                {"name": "Cookie", "value": "cf_clearance=abcd1234; session=xyz"},
                                {"name": "Authorization", "value": "Bearer token123"},
                            ],
                        },
                        "response": {
                            "status": 200,
                            "content": {"mimeType": "application/x-mpegurl", "text": "#EXTM3U"},
                            "headers": [],
                        },
                    },
                    {
                        "request": {
                            "url": "https://api.host.com/v2/search?q=naruto",
                            "method": "GET",
                            "headers": [],
                        },
                        "response": {
                            "status": 200,
                            "content": {"mimeType": "application/json", "text": "[]"},
                            "headers": [],
                        },
                    },
                ]
            }
        }

        site_map = self.agent.run_recon(
            base_url="https://host.com",
            name="HostSource",
            har_data=har_data,
        )

        self.assertEqual(len(site_map.media_streams), 1)
        self.assertIn("https://cdn.host.com/stream/index.m3u8?token=xyz", site_map.media_streams)
        self.assertIn("https://api.host.com/v2/search", site_map.api_endpoints)
        self.assertEqual(site_map.cookies.get("cf_clearance"), "abcd1234")
        self.assertEqual(site_map.auth_tokens.get("Authorization"), "Bearer token123")

    def test_route_explorer(self):
        html_doc = """
        <html>
            <body>
                <form action="/search" method="GET">
                    <input name="keyword" />
                </form>
                <a href="/watch/solo-leveling-episode-1">Ep 1</a>
                <a href="/watch/solo-leveling-episode-2">Ep 2</a>
                <iframe src="https://embed.stream/e/player99"></iframe>
            </body>
        </html>
        """
        routes = self.agent.route_explorer.discover_routes_from_html(html_doc, "https://host.com")
        route_types = [r.route_type for r in routes]
        self.assertIn("search", route_types)
        self.assertIn("episode", route_types)
        self.assertIn("stream", route_types)

    def test_schema_inferer(self):
        inferer = SchemaInferer()
        payload = {
            "id": 42,
            "title": "Attack on Titan",
            "score": 9.0,
            "vote_count": 15000,
            "is_completed": True,
            "genres": ["Action", "Drama"],
            "producer": {"name": "Wit Studio", "established": 2012},
        }

        schemas = inferer.infer_schema("AnimeDetailDto", payload)
        self.assertIn("AnimeDetailDto", schemas)
        self.assertIn("ProducerDto", schemas)

        fields_map = {f.name: f for f in schemas["AnimeDetailDto"]}
        # Score and vote_count must be Double? per v16 invariants
        self.assertEqual(fields_map["score"].kotlin_type, "Double?")
        self.assertEqual(fields_map["vote_count"].kotlin_type, "Double?")
        self.assertEqual(fields_map["id"].kotlin_type, "Int?")
        self.assertEqual(fields_map["title"].kotlin_type, "String?")
        self.assertEqual(fields_map["is_completed"].kotlin_type, "Boolean?")

    def test_recon_swarm_explore_site(self):
        res = self.agent.explore_site("https://nonexistent-site-test.example.com")
        self.assertEqual(res["base_url"], "https://nonexistent-site-test.example.com")
        self.assertEqual(res["name"], "Nonexistent-site-test")
        self.assertIsInstance(res["discovered_routes"], list)


class TestDeobfuscator(unittest.TestCase):
    """Verifies Dean Edwards unpacking, PlayerJS decoding, RC4, and AES cipher solvers."""

    def setUp(self):
        self.agent = DeobfuscatorAgent()

    def test_dean_edwards_unpacker(self):
        packed = (
            "eval(function(p,a,c,k,e,r){e=String;if(!''.replace(/^/,String)){while(c--)r[c]=k[c]||c;"
            "k=[function(e){return r[e]}];e=function(){return'\\\\w+'};c=1};while(c--)if(k[c])"
            "p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c]);return p}('0 1=\"2\";',"
            "3,3,'var|hello|world'.split('|')))"
        )
        unpacked = DeanEdwardsSolver.unpack(packed)
        self.assertIn('var hello="world"', unpacked)

        # Test standard (p,a,c,k,e,d) format with double quotes and trailing params
        packed_alt = (
            "eval(function(p,a,c,k,e,d){e=function(c){return c};if(!''.replace(/^/,String)){while(c--){"
            "d[c]=k[c]||c}k=[function(e){return d[e]}];e=function(){return'\\\\w+'};c=1};while(c--){"
            "if(k[c]){p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c])}}return p}('0 1=\"2\";',"
            "3,3,'var|hello|world'.split('|'),0,{}))"
        )
        unpacked_alt = DeanEdwardsSolver.unpack(packed_alt)
        self.assertIn('var hello="world"', unpacked_alt)

    def test_playerjs_decoder(self):
        # Base64 of 'https://cdn.stream.com/master.m3u8' = 'aHR0cHM6Ly9jZG4uc3RyZWFtLmNvbS9tYXN0ZXIubTN1OA=='
        raw_url = "https://cdn.stream.com/master.m3u8"
        encoded = "#0" + "aHR0cHM6Ly9jZG4uc3RyZWFtLmNvbS9tYXN0ZXIubTN1OA=="
        decoded = PlayerJsSolver.decode(encoded)
        self.assertEqual(decoded, raw_url)

        # Test reversed base64
        b64_rev = "aHR0cHM6Ly9jZG4uc3RyZWFtLmNvbS9tYXN0ZXIubTN1OA=="[::-1]
        encoded_rev = "//_//" + b64_rev
        decoded_rev = PlayerJsSolver.decode(encoded_rev)
        self.assertEqual(decoded_rev, raw_url)

    def test_rc4_cipher(self):
        data = b"https://secure-stream.org/playlist.m3u8"
        key = b"secret-passphrase"
        encrypted = CipherSolver.rc4_crypt(data, key)
        self.assertNotEqual(encrypted, data)
        decrypted = CipherSolver.rc4_crypt(encrypted, key)
        self.assertEqual(decrypted, data)

    def test_aes_openssl_evp_key_derivation(self):
        key, iv = CipherSolver.evp_bytes_to_key(b"mySecretPassword", b"12345678", key_len=32, iv_len=16)
        self.assertEqual(len(key), 32)
        self.assertEqual(len(iv), 16)

    def test_deobfuscator_auto_solve(self):
        # Auto-solve PlayerJS
        res_pjs = self.agent.solve("#0aHR0cHM6Ly9jZG4uZXhhbXBsZS5jb20vbWFzdGVyLm0zdTg=")
        self.assertTrue(res_pjs.success)
        self.assertEqual(res_pjs.engine, "PlayerJs")
        self.assertEqual(res_pjs.deobfuscated, "https://cdn.example.com/master.m3u8")
        self.assertIsNotNone(res_pjs.generated_kotlin)

        # Auto-solve Base64 URL
        res_b64 = self.agent.solve("aHR0cHM6Ly9zdHJlYW0ub3JnL3BsYXlsaXN0Lm0zdTg=")
        self.assertTrue(res_b64.success)
        self.assertEqual(res_b64.deobfuscated, "https://stream.org/playlist.m3u8")

    def test_rot13(self):
        text = "Hello World! 123"
        rot = CipherSolver.rot13(text)
        self.assertEqual(rot, "Uryyb Jbeyq! 123")
        self.assertEqual(CipherSolver.rot13(rot), text)

    def test_kotlin_deobfuscator_generator(self):
        playerjs_kt = KotlinDeobfuscatorGenerator.generate_playerjs_helper()
        self.assertIn("decodePlayerJs", playerjs_kt)
        self.assertIn("Base64.decode", playerjs_kt)

        rc4_kt = KotlinDeobfuscatorGenerator.generate_rc4_helper()
        self.assertIn("decryptRc4", rc4_kt)
        self.assertIn("IntArray(256)", rc4_kt)


class TestKotlinSynthesizer(unittest.TestCase):
    """Verifies Kotlin Synthesizer compliance with API v16 invariants."""

    def setUp(self):
        self.synthesizer = KotlinSynthesizerAgent()

    def test_dto_generation_null_safety(self):
        fields = [
            SchemaField(name="id", kotlin_type="Int?", default_value="null", json_key="anime_id"),
            SchemaField(name="title", kotlin_type="String?", default_value="null", json_key="anime_title"),
            SchemaField(name="score", kotlin_type="Double?", default_value="null", json_key="rating"),
        ]
        dto_code = DtoGenerator.generate_dto_class("AnimeDto", fields)
        self.assertIn("@Serializable", dto_code)
        self.assertIn("data class AnimeDto(", dto_code)
        self.assertIn('@SerialName("anime_id") val id: Int? = null,', dto_code)
        self.assertIn('@SerialName("rating") val score: Double? = null,', dto_code)

    def test_synthesized_extension_v16_invariants(self):
        config = SynthesizerConfig(
            pkg_name="eu.kanade.tachiyomi.animeextension.en.lunar",
            class_name="Lunar",
            source_name="Lunar Anime",
            base_url="https://lunar.example.com",
        )
        synth = self.synthesizer.synthesize(config)
        code = synth.full_combined_code

        # Invariant 1: Inherits from Source()
        self.assertIn("class Lunar : Source(), ConfigurableAnimeSource", code)
        self.assertNotIn("ParsedAnimeHttpSource", code)

        # Invariant 2: initialized = true in getAnimeDetails
        self.assertIn("override suspend fun getAnimeDetails(anime: SAnime): SAnime", code)
        self.assertIn("initialized = true", code)

        # Invariant 3: Named Video(...) constructor
        self.assertIn("Video(", code)
        self.assertIn("videoUrl =", code)
        self.assertIn("videoTitle =", code)

        # Invariant 4: UrlUtils.fixUrl
        self.assertIn("UrlUtils.fixUrl", code)

        # Invariant 5: Companion object preference keys
        self.assertIn("private const val PREF_KEY_SERVER", code)


class TestAdversarialCritic(unittest.TestCase):
    """Verifies Adversarial Critic detecting AST smells, rule violations, and fuzzing."""

    def setUp(self):
        self.critic = AdversarialCriticAgent()

    def test_critic_flags_parsedanimehttpsource(self):
        bad_code = """
        package eu.kanade.tachiyomi.animeextension.en.bad
        import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
        class BadSource : ParsedAnimeHttpSource() {
        }
        """
        report = self.critic.review(bad_code)
        self.assertFalse(report.is_passing)
        blocker_rule_ids = [b.rule_id for b in report.blockers]
        self.assertIn("RULE_001_INVALID_BASE_CLASS", blocker_rule_ids)

    def test_critic_flags_missing_initialized_true(self):
        bad_code = """
        package eu.kanade.tachiyomi.animeextension.en.bad
        import extensions.utils.Source
        class BadSource : Source() {
            override suspend fun getAnimeDetails(anime: SAnime): SAnime {
                anime.title = "Hello"
                return anime
            }
        }
        """
        report = self.critic.review(bad_code)
        self.assertFalse(report.is_passing)
        blocker_rule_ids = [b.rule_id for b in report.blockers]
        self.assertIn("RULE_003_MISSING_INITIALIZED_TRUE", blocker_rule_ids)

    def test_critic_flags_positional_video_and_quality(self):
        bad_code = """
        package eu.kanade.tachiyomi.animeextension.en.bad
        import extensions.utils.Source
        class BadSource : Source() {
            override fun videoListParse(response: Response): List<Video> {
                val v = Video("https://stream.mp4", "1080p")
                println(v.quality)
                return listOf(v)
            }
        }
        """
        report = self.critic.review(bad_code)
        self.assertFalse(report.is_passing)
        blocker_rule_ids = [b.rule_id for b in report.blockers]
        self.assertIn("RULE_004_LEGACY_VIDEO_CONSTRUCTOR", blocker_rule_ids)
        self.assertIn("RULE_005_DEPRECATED_VIDEO_QUALITY_PROP", blocker_rule_ids)

    def test_critic_flags_dto_missing_default_and_fuzzing(self):
        dto_code = """
        @Serializable
        data class IncompleteDto(
            val id: Int,
            val score: Float
        )
        """
        report = self.critic.review(dto_code, sample_payload={"score": "9.5"})
        warning_rule_ids = [w.rule_id for w in report.warnings]
        self.assertIn("RULE_008_DTO_MISSING_DEFAULT_FALLBACK", warning_rule_ids)
        self.assertIn("RULE_009_RATING_NOT_DOUBLE", warning_rule_ids)
        self.assertIn("FUZZ_001_STRING_SCORE_TYPE", warning_rule_ids)


class TestCiGuardian(unittest.TestCase):
    """Verifies CI Guardian log parsing, error triage, and auto-patching."""

    def setUp(self):
        self.guardian = CiGuardianAgent()

    def test_compiler_log_parser(self):
        log_sample = """
        > Task :src:en:mysource:compileDebugKotlin FAILED
        e: /home/workspace/src/en/mysource/MySource.kt: (45, 12): Unresolved reference: quality
        e: /home/workspace/src/en/mysource/MySource.kt: (88, 20): No value passed for parameter 'videoUrl'
        e: /home/workspace/src/en/mysource/MySource.kt: (102, 5): Unresolved reference: UrlUtils
        """
        triage = self.guardian.triage_log(log_sample)
        self.assertTrue(triage.has_errors)
        self.assertEqual(triage.total_errors, 3)
        self.assertIn(ErrorCategory.MODEL_V16_MISMATCH, triage.categorized)
        self.assertIn(ErrorCategory.UNRESOLVED_REFERENCE, triage.categorized)
        self.assertEqual(len(triage.patch_suggestions), 3)

    def test_ast_auto_patcher(self):
        broken_code = """
        class MySource : ParsedAnimeHttpSource() {
            override suspend fun getAnimeDetails(anime: SAnime): SAnime {
                anime.title = "Test"
                return anime
            }

            fun parseVideos() {
                val list = listOf(Video("https://stream.mp4", "720p"))
                val q = list[0].quality
            }
        }
        """
        patched, fixes = self.guardian.auto_patch_code(broken_code)
        self.assertIn("class MySource : Source()", patched)
        self.assertIn("initialized = true", patched)
        self.assertIn("Video(videoUrl = \"https://stream.mp4\", videoTitle = \"720p\")", patched)
        self.assertIn("list[0].videoTitle", patched)
        self.assertGreaterEqual(len(fixes), 3)


if __name__ == "__main__":
    unittest.main()
