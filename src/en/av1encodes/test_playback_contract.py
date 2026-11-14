#!/usr/bin/env python3
"""Offline source-contract guards; Kotlin runtime verification happens via CI/ADB."""

import re
import unittest
from pathlib import Path

MODULE = Path(__file__).resolve().parent
SOURCE = (MODULE / "src/eu/kanade/tachiyomi/animeextension/en/av1encodes/AV1Encodes.kt").read_text()
MODELS = (MODULE / "src/eu/kanade/tachiyomi/animeextension/en/av1encodes/Av1EncodesModels.kt").read_text()


def function(name):
    match = re.search(r"    private (?:suspend )?fun " + name + r"\(", SOURCE)
    assert match, name
    end = SOURCE.find("\n    private ", match.end())
    return SOURCE[match.start():end if end != -1 else len(SOURCE)]


class PlaybackContractTests(unittest.TestCase):
    def test_new_episodes_use_permanent_anchors(self):
        self.assertIn('url = "$urlPath#season=${p.seasonNum}&ep=${p.epNum}"', SOURCE)
        self.assertNotIn("setUrlWithoutDomain(p.href)", SOURCE)

    def test_season_numbering_is_stable(self):
        self.assertIn("((p.seasonNum - 1) * 100 + p.epNum).toFloat()", SOURCE)
        self.assertIn("return episodes.reversed()", SOURCE)

    def test_old_download_entries_receive_a_fresh_token(self):
        body = function("freshDownloadUrl")
        self.assertIn('episode.encodedPath.startsWith("/download/")', body)
        self.assertIn('"$baseUrl/get_token"', body)
        self.assertIn('setQueryParameter("token", token)', body)

    def test_adcheck_posts_the_actual_form(self):
        body = function("loadDownloadPage")
        self.assertIn('"adcheck" ->', body)
        self.assertIn('form#ddl-adcheck-form', body)
        self.assertIn('input[name]:not([disabled])', body)
        self.assertIn("POST(", body)
        self.assertIn('set("Referer", downloadPageUrl).set("Origin", baseUrl)', body)

    def test_preparing_and_adcheck_are_bounded(self):
        body = function("loadDownloadPage")
        self.assertIn("++adChecks > MAX_ADCHECK_SUBMITS", body)
        self.assertIn("++reloads > MAX_PREPARE_RELOADS", body)
        self.assertIn("delay(DOWNLOAD_PREPARE_DELAY_MS)", body)

    def test_ready_download_button_and_dash_player_are_supported(self):
        self.assertIn("#download-now-btn[data-href]", SOURCE)
        self.assertIn("dash-video[src], video source[src], video[src]", SOURCE)

    def test_source_migration_does_not_replace_cdn_substrings(self):
        self.assertIn('url.host == "av1encodes.com" || url.host == "av1please.com"', function("siteUrl"))
        self.assertNotIn('.replace("av1please.com"', SOURCE)
        self.assertNotIn('.replace("av1encodes.com"', SOURCE)

    def test_real_endpoints_and_manifest_queries_are_preserved(self):
        self.assertNotIn("toDlUrl(", SOURCE)
        self.assertNotIn("toStreamUrl(", SOURCE)
        self.assertNotIn('substringBefore("/manifest.mpd")', SOURCE)
        self.assertIn('parsed.encodedPath.endsWith(".mpd")', function("withMediaHint"))

    def test_relative_links_resolve_against_the_correct_origin(self):
        self.assertIn("base.toHttpUrlOrNull()?.resolve(path)", function("resolveUrl"))
        self.assertIn("response.request.url.resolve(it)", function("resolveRedirect"))
        self.assertIn('resolveUrl(it.attr("src"), doc.location())', function("streamUrls"))

    def test_redirects_do_not_buffer_complete_media(self):
        body = function("resolveRedirect")
        self.assertIn("response.peekBody(HTML_PEEK_BYTES)", body)
        self.assertIn(".await().use", body)
        self.assertNotIn("bodyString()", body)
        self.assertIn("visited.add(next)", body)
        self.assertIn("repeat(MAX_REDIRECTS)", body)

    def test_html_cannot_be_returned_as_a_direct_download(self):
        self.assertIn("if (link.isHtml) continue", SOURCE)
        self.assertIn("if (!link.isHtml) return listOf(link.url)", function("streamUrls"))

    def test_parallel_hosters_share_a_short_lived_extraction(self):
        self.assertIn("playbackMutex.withLock", SOURCE)
        self.assertIn("SystemClock.elapsedRealtime() - cached.second < PLAYBACK_CACHE_MS", SOURCE)
        self.assertIn('val cacheKey = "$baseUrl|$prefQuality|', SOURCE)
        self.assertIn("cached.first == cacheKey", SOURCE)

    def test_fresh_tokens_bypass_host_http_caching(self):
        self.assertIn("cache = CacheControl.FORCE_NETWORK", function("freshDownloadUrl"))

    def test_cancellation_and_verification_errors_are_not_hidden(self):
        self.assertIn("if (e is CancellationException) throw e", function("streamUrls"))
        self.assertIn("script#anubis_challenge", function("checkWebsiteVerification"))
        self.assertIn('"/home/blocked"', function("checkWebsiteVerification"))

    def test_token_dto_is_nullable_and_matches_the_parser(self):
        self.assertIn("data class Av1TokenResponseDto", MODELS)
        self.assertIn('val token: String? = null', MODELS)
        self.assertIn("parseAs<Av1TokenResponseDto>()", SOURCE)


if __name__ == "__main__":
    unittest.main()
