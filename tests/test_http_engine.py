#!/usr/bin/env python3
"""
Regression tests for the universal tiered HTTP transport engine
(scripts/http_client.py).

Everything here is offline: engine rungs are monkeypatched, so the suite
verifies the *decision logic* — challenge detection, ladder escalation, cache
isolation and error semantics — without depending on any live site.
"""

import sys
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts import http_client as hc  # noqa: E402


def _result(status=200, body=b"", headers=None, via="test"):
    return hc.FetchResult(url="https://example.test/", status=status,
                          headers=headers or {}, body=body, via=via)


class ChallengeDetectionTests(unittest.TestCase):
    """looks_blocked() must separate real content from WAF/interstitial shells."""

    def test_cloudflare_200_interstitial_is_blocked(self):
        res = _result(200, b"<title>Just a moment...</title>_cf_chl_opt = {}",
                      {"Server": "cloudflare", "Content-Type": "text/html"})
        self.assertTrue(hc.looks_blocked(res))

    def test_cloudflare_error_status_is_blocked(self):
        res = _result(403, b"denied", {"Server": "cloudflare"})
        self.assertTrue(hc.looks_blocked(res))

    def test_ddos_guard_shell_is_blocked(self):
        res = _result(200, b"<html>DDoS-Guard challenge js-relay</html>",
                      {"Content-Type": "text/html"})
        self.assertTrue(hc.looks_blocked(res))

    def test_healthy_cloudflare_page_is_not_blocked(self):
        """Regression: AniList's real SPA shell references challenge-platform
        and a Turnstile widget, but is ordinary content. Those markers were
        previously treated as proof of a block, flagging healthy sites."""
        body = (b"<!DOCTYPE html><html><head><title>AniList</title>"
                b"<script>window.al_token = \"x\";</script>"
                b"<link rel=preload href=\"https://challenges.cloudflare.com/turnstile/v0/api.js\">"
                b"<script src=\"/cdn-cgi/challenge-platform/scripts/jsd/main.js\"></script>"
                b"</head><body><div id=app></div></body></html>")
        res = _result(200, body, {"Server": "cloudflare", "Content-Type": "text/html; charset=UTF-8"})
        self.assertFalse(hc.looks_blocked(res))

    def test_plain_errors_are_not_blocks(self):
        for status in (400, 404, 410, 500):
            self.assertFalse(hc.looks_blocked(_result(status, b"nope")),
                             f"status {status} must not read as a challenge")

    def test_antibot_markers_absent_but_server_cloudflare(self):
        # A CF-fronted 503 with no recognisable body is still a block.
        self.assertTrue(hc.looks_blocked(_result(503, b"", {"Server": "cloudflare"})))


class LadderResolutionTests(unittest.TestCase):
    """_resolve_ladder() decides which rungs are attempted."""

    def test_pinned_engine_returns_single_rung(self):
        self.assertEqual(hc._resolve_ladder("urllib", True), ["urllib"])

    def test_comma_list_preserves_order(self):
        with mock.patch.dict(hc._PROBES, {
            "curl_cffi": lambda: {"available": True},
            "cloudscraper": lambda: {"available": True},
        }):
            self.assertEqual(hc._resolve_ladder("curl_cffi,cloudscraper", True),
                             ["curl_cffi", "cloudscraper"])

    def test_unavailable_engines_are_dropped(self):
        with mock.patch.dict(hc._PROBES, {
            "curl_cffi": lambda: {"available": False, "reason": "missing"},
            "cloudscraper": lambda: {"available": True},
        }):
            self.assertEqual(hc._resolve_ladder("curl_cffi,cloudscraper", True),
                             ["cloudscraper"])

    def test_unknown_engine_raises_config_error(self):
        with self.assertRaises(hc.HttpError) as ctx:
            hc._resolve_ladder("telepathy", True)
        self.assertEqual(ctx.exception.kind, "config")

    def test_allow_stealth_false_strips_browser_rungs(self):
        with mock.patch.dict(hc._PROBES, {
            "scrapling": lambda: {"available": True},
            "ghost": lambda: {"available": True},
        }):
            ladder = hc._resolve_ladder("scrapling,ghost,urllib", False)
            self.assertEqual(ladder, ["urllib"])

    def test_no_usable_engine_raises(self):
        with mock.patch.dict(hc._PROBES, {"urllib": lambda: {"available": False}}):
            with self.assertRaises(hc.HttpError) as ctx:
                hc._resolve_ladder("urllib", True)
            self.assertEqual(ctx.exception.kind, "config")


class CacheTests(unittest.TestCase):
    """Cache identity must include the engine variant, so pinning an engine
    never returns another engine's bytes."""

    def setUp(self):
        hc._MEM_CACHE.clear()

    def test_variant_changes_cache_key(self):
        a = hc._cache_key("GET", "https://x.test/", None, None, "urllib")
        b = hc._cache_key("GET", "https://x.test/", None, None, "curl_cffi")
        self.assertNotEqual(a, b)

    def test_same_inputs_are_stable(self):
        a = hc._cache_key("GET", "https://x.test/", None, {"A": "b"}, "urllib")
        b = hc._cache_key("GET", "https://x.test/", None, {"A": "b"}, "urllib")
        self.assertEqual(a, b)

    def test_memory_cache_roundtrip(self):
        key = hc._cache_key("GET", "https://x.test/", None, None, "urllib")
        hc._cache_store(key, _result(200, b"payload", via="urllib"), ttl=60)
        hit = hc._cache_lookup(key, 60)
        self.assertIsNotNone(hit)
        self.assertEqual(hit.body, b"payload")
        self.assertEqual(hit.via, "cache-mem")

    def test_explicit_ttl_applies_to_disk_as_well_as_memory(self):
        """Regression: `--cache` set the read TTL but _cache_store still used
        the module default (disk off), so nothing was ever written and every
        run re-hit the network."""
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(hc, "_DISK_CACHE_DIR", Path(tmp)):
                key = hc._cache_key("GET", "https://x.test/disk", None, None, "urllib")
                hc._cache_store(key, _result(200, b"disk-payload", via="urllib"), ttl=300)
                self.assertTrue(list(Path(tmp).glob("*.json")),
                                "an explicit ttl must produce a disk entry")
                # Drop memory so only the disk layer can answer.
                hc._MEM_CACHE.clear()
                hit = hc._cache_lookup(key, 300)
                self.assertIsNotNone(hit)
                self.assertEqual(hit.body, b"disk-payload")
                self.assertEqual(hit.via, "cache-disk")

    def test_zero_ttl_disables_caching(self):
        key = hc._cache_key("GET", "https://x.test/off", None, None, "urllib")
        hc._cache_store(key, _result(200, b"nope"), ttl=0)
        self.assertIsNone(hc._cache_lookup(key, 0))

    def test_resolve_ttls_semantics(self):
        mem, disk = hc._resolve_ttls(None)
        self.assertEqual(mem, hc._MEM_TTL)
        self.assertEqual(disk, hc._DISK_TTL)
        mem, disk = hc._resolve_ttls(45)
        self.assertEqual((mem, disk), (45.0, 45.0))


try:
    from scripts.ghost_fetch import find_record_list as _find_record_list  # noqa: F401
    _HAS_GHOST_FETCH = True
except Exception:
    # ghost_fetch.py lives in the scripts checkout; when that is absent this
    # suite must still pass rather than fail on an import it cannot satisfy.
    _HAS_GHOST_FETCH = False


@unittest.skipUnless(_HAS_GHOST_FETCH, "ghost_fetch.py not present in this checkout")
class RecordListTests(unittest.TestCase):
    """find_record_list powers `fetch --shape` and ghost-fetch --discover."""

    def _find(self, payload):
        from scripts.ghost_fetch import find_record_list
        return find_record_list(payload)

    def test_finds_nested_list(self):
        found = self._find({"slideshow": {"slides": [{"title": "a"}, {"title": "b"}]}})
        self.assertEqual(found["path"], "slideshow.slides")
        self.assertEqual(found["count"], 2)

    def test_finds_deeply_nested_list(self):
        found = self._find({"data": {"animeList": [{"id": i} for i in range(4)]}})
        self.assertEqual(found["path"], "data.animeList")
        self.assertEqual(found["count"], 4)

    def test_prefers_larger_list(self):
        found = self._find({"data": {"items": [{"a": 1}] * 9, "meta": {"notes": [{"z": 1}]}}})
        self.assertEqual(found["path"], "data.items")

    def test_root_array(self):
        found = self._find([{"id": 1}, {"id": 2}])
        self.assertEqual(found["path"], "<root array>")

    def test_single_object_has_no_record_list(self):
        self.assertIsNone(self._find({"id": 1, "title": "x"}))

    def test_scalar_list_is_reported_but_flagged(self):
        found = self._find({"tags": ["a", "b", "c"]})
        self.assertEqual(found["path"], "tags")
        self.assertFalse(found["is_object_list"])


class FetchSemanticsTests(unittest.TestCase):
    """fetch() must escalate on blocks, never on definitive client errors."""

    def setUp(self):
        hc._MEM_CACHE.clear()
        self.url = "https://example.test/"

    def test_rejects_non_http_scheme(self):
        with self.assertRaises(hc.HttpError) as ctx:
            hc.fetch("file:///etc/passwd", engine="urllib")
        self.assertEqual(ctx.exception.kind, "unsafe")

    def test_escalates_when_first_rung_is_blocked(self):
        calls = []

        def blocked(*a, **kw):
            calls.append("first")
            return _result(403, b"<title>Just a moment...</title>", {"Server": "cloudflare"},
                           via="urllib")

        def good(*a, **kw):
            calls.append("second")
            return _result(200, b"<html>real</html>", via="curl_cffi:chrome136")

        with mock.patch.dict(hc._PROBES, {
            "urllib": lambda: {"available": True},
            "curl_cffi": lambda: {"available": True},
        }), mock.patch.object(hc, "_fetch_urllib", blocked), \
                mock.patch.object(hc, "_fetch_curl_cffi", good):
            res = hc.fetch(self.url, engine="urllib,curl_cffi", cache_ttl=0)

        self.assertEqual(calls, ["first", "second"], "must try both rungs in order")
        self.assertEqual(res.status, 200)
        self.assertEqual(res.body, b"<html>real</html>")
        # Each engine self-reports a rich `via` (fingerprint included), and the
        # orchestrator must preserve it rather than flattening it to a name.
        self.assertEqual(res.via, "curl_cffi:chrome136")

    def test_all_rungs_blocked_raises_antibot_with_body_attached(self):
        def blocked(*a, **kw):
            return _result(403, b"<title>Just a moment...</title>", {"Server": "cloudflare"})

        with mock.patch.dict(hc._PROBES, {
            "urllib": lambda: {"available": True},
            "curl_cffi": lambda: {"available": True},
        }), mock.patch.object(hc, "_fetch_urllib", blocked), \
                mock.patch.object(hc, "_fetch_curl_cffi", blocked):
            with self.assertRaises(hc.HttpError) as ctx:
                hc.fetch(self.url, engine="urllib,curl_cffi", cache_ttl=0)

        err = ctx.exception
        self.assertEqual(err.kind, "antibot")
        self.assertEqual(err.status, 403)
        # The challenge body stays inspectable for diagnostics.
        self.assertIsNotNone(err.response)
        self.assertIn(b"Just a moment", err.response.body)

    def test_definitive_404_does_not_escalate(self):
        calls = []

        def not_found(*a, **kw):
            calls.append("urllib")
            return _result(404, b"missing")

        def should_not_run(*a, **kw):
            calls.append("curl_cffi")
            return _result(200, b"unexpected")

        with mock.patch.dict(hc._PROBES, {
            "urllib": lambda: {"available": True},
            "curl_cffi": lambda: {"available": True},
        }), mock.patch.object(hc, "_fetch_urllib", not_found), \
                mock.patch.object(hc, "_fetch_curl_cffi", should_not_run):
            with self.assertRaises(hc.HttpError) as ctx:
                hc.fetch(self.url, engine="urllib,curl_cffi", cache_ttl=0)

        self.assertEqual(calls, ["urllib"])
        self.assertEqual(ctx.exception.kind, "http")
        self.assertEqual(ctx.exception.status, 404)

    def test_ok_response_is_cached_and_replayed(self):
        calls = []

        def good(*a, **kw):
            calls.append(1)
            return _result(200, b"cached-body")

        with mock.patch.dict(hc._PROBES, {"urllib": lambda: {"available": True}}), \
                mock.patch.object(hc, "_fetch_urllib", good):
            first = hc.fetch(self.url, engine="urllib")
            second = hc.fetch(self.url, engine="urllib")

        self.assertEqual(len(calls), 1, "second identical GET must hit the cache")
        self.assertEqual(first.body, second.body)
        self.assertEqual(second.via, "cache-mem")

    def test_failing_rung_does_not_kill_the_ladder(self):
        def broken(*a, **kw):
            raise ValueError("engine exploded")

        def good(*a, **kw):
            return _result(200, b"<html>ok</html>")

        with mock.patch.dict(hc._PROBES, {
            "urllib": lambda: {"available": True},
            "curl_cffi": lambda: {"available": True},
        }), mock.patch.object(hc, "_fetch_urllib", broken), \
                mock.patch.object(hc, "_fetch_curl_cffi", good):
            res = hc.fetch(self.url, engine="urllib,curl_cffi", cache_ttl=0)
        self.assertEqual(res.status, 200)


class FingerprintTests(unittest.TestCase):
    """The impersonating engine must own its own identity headers."""

    def test_alternate_fingerprint_actually_differs(self):
        for fp in hc._IMPERSONATE_POOL:
            self.assertNotEqual(hc._alternate_impersonate(fp), fp)

    def test_impersonation_drops_conflicting_identity_headers(self):
        """curl_cffi sets UA + sec-ch-ua from the fingerprint. Forwarding our
        own values on top produces an incoherent identity, which is exactly
        what fingerprinting walls look for."""
        headers = hc._curl_headers(
            "https://x.test/", "text/html", None, None,
            {"User-Agent": "python-urllib/3", "sec-ch-ua": '"Chromium";v="131"',
             "Accept-Language": "de-DE", "X-Custom": "keep-me"},
            impersonating=True,
        )
        self.assertNotIn("User-Agent", headers)
        self.assertNotIn("sec-ch-ua", headers)
        self.assertEqual(headers["X-Custom"], "keep-me")
        self.assertEqual(headers["Accept-Language"], "de-DE")

    def test_forced_user_agent_is_still_honoured(self):
        """An explicit user_agent wins even while impersonating."""
        headers = hc._curl_headers(
            "https://x.test/", "text/html", None, "Mozilla/5.0 (Custom)",
            None, impersonating=True,
        )
        self.assertEqual(headers["User-Agent"], "Mozilla/5.0 (Custom)")

    def test_non_impersonating_engine_gets_a_user_agent(self):
        headers = hc._curl_headers(
            "https://x.test/", "text/html", None, None, None, impersonating=False,
        )
        self.assertIn("User-Agent", headers)


class EnginesStatusTests(unittest.TestCase):
    def test_status_reports_every_rung_and_the_auto_ladder(self):
        report = hc.engines_status(refresh=True)
        self.assertEqual(set(report["engines"]), set(hc.ENGINE_ORDER))
        self.assertTrue(report["engines"]["urllib"]["available"])
        self.assertIsInstance(report["auto_ladder"], list)
        # The browser rung is not entered implicitly.
        self.assertNotIn("ghost", report["auto_ladder"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
