#!/usr/bin/env python3
"""Ghost Browser (ghost_fetch v2) helper tests — no real browser needed.

These guard the things that actually broke during the v2 rewrite:

* **Challenge false positives.** Cloudflare injects `challenge-platform` and
  (on managed pages) `_cf_chl_opt` into perfectly healthy pages. AniList was
  reported as `challengeDetected: true` until the marker rules became
  "one interstitial phrase, or two co-occurring plumbing markers".
* **Profile/port collisions.** Two ghost runs sharing `~/.ghost-browser-profile`
  made one die on Chromium's ProcessSingleton; `claim_profile` must fork a
  sibling profile instead, and clear a stale lock left by a killed run.
* **The zero-dependency transport.** `_StdlibWS` is used whenever the
  `websockets` package is missing (common on Termux), so its framing has to be
  proven against a real handshake, not assumed.
"""

import asyncio
import base64
import hashlib
import os
import re
import socket
import tempfile
import unittest
from pathlib import Path

try:
    from scripts import ghost_fetch as gf
    _HAS_GHOST = True
except Exception:          # pragma: no cover - checkout without scripts/
    gf = None              # type: ignore
    _HAS_GHOST = False

skip_no_ghost = unittest.skipUnless(_HAS_GHOST, "ghost_fetch.py not present")


@skip_no_ghost
class ChallengeMarkerTests(unittest.TestCase):
    """A healthy Cloudflare page must never be reported as a gate."""

    def test_healthy_managed_page_with_plumbing_is_not_a_gate(self):
        body = (
            "<html><head><title>AniList</title></head><body>"
            "<script>window._cf_chl_opt={cRay:'abc',cType:'managed'};</script>"
            "<script src='/cdn-cgi/challenge-platform/h/b/orchestrate/"
            "chl_page/v1?ray=abc'></script>"
            "<div id='app'>"
            + "real catalogue content " * 40 +
            "</div></body></html>"
        )
        from scripts.ghost_fetch import GhostRun
        self.assertIsNone(GhostRun._body_challenge(body))

    def test_single_plumbing_marker_is_not_a_gate(self):
        from scripts.ghost_fetch import GhostRun
        self.assertIsNone(GhostRun._body_challenge(
            "<html><body><script>window._cf_chl_opt={};</script>"
            "<p>welcome</p></body></html>"))

    def test_interstitial_phrase_is_a_gate(self):
        from scripts.ghost_fetch import GhostRun
        hit = GhostRun._body_challenge(
            "<html><head><title>Just a moment...</title></head>"
            "<body></body></html>")
        self.assertIsNotNone(hit)

    def test_plumbing_pair_on_healthy_page_is_not_a_gate(self):
        """Regression: `_cf_chl_opt` + a /cdn-cgi/cf-chl-* URL is a healthy page.

        Cloudflare injects both into ordinary managed pages; a reviewer's
        fixture proved this used to be reported as `challengeDetected: true`.
        """
        from scripts.ghost_fetch import GhostRun
        body = (
            "<html><head><title>Store</title>"
            "<script src='/cdn-cgi/challenge-platform/h/b/orchestrate/"
            "chl_page/v1?ray=1'></script></head><body>"
            "<script>window._cf_chl_opt={cRay:'1',cType:'managed'};</script>"
            "<script src='/cdn-cgi/cf-chl-widget-abc.js'></script>"
            "<main>" + "product listing " * 60 + "</main></body></html>"
        )
        self.assertIsNone(GhostRun._body_challenge(body))

    def test_challenge_container_is_a_gate(self):
        from scripts.ghost_fetch import GhostRun
        hit = GhostRun._body_challenge(
            "<html><body><script>window._cf_chl_opt={};</script>"
            "<div id='challenge-form'></div></body></html>")
        self.assertIsNotNone(hit)

    def test_ddos_guard_shell_is_a_gate(self):
        from scripts.ghost_fetch import GhostRun
        self.assertIsNotNone(GhostRun._body_challenge(
            "<html><head><title>DDoS-Guard</title></head><body></body></html>"))

    def test_challenge_headers(self):
        from scripts.ghost_fetch import GhostRun
        f = GhostRun._header_challenge
        self.assertIsNotNone(f({"cf-mitigated": "challenge"}))
        self.assertIsNotNone(f({"CF-Chl-Bypass": "1"}))
        self.assertIsNotNone(f({"x-ddg-answer": "x"}))
        self.assertIsNone(f({"content-type": "text/html",
                             "server": "cloudflare"}))

    def test_widget_kinds(self):
        self.assertEqual(gf.widget_kind(
            "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/x"),
            "turnstile")
        self.assertEqual(gf.widget_kind("https://hcaptcha.com/1/api.js"),
                         "hcaptcha")
        self.assertEqual(gf.widget_kind(
            "https://www.google.com/recaptcha/api2/anchor"), "recaptcha")
        self.assertEqual(gf.widget_kind("https://geo.captcha-delivery.com/x"),
                         "datadome")
        self.assertEqual(gf.widget_kind(""), "unknown")


@skip_no_ghost
class UaMetadataTests(unittest.TestCase):
    """navigator.userAgentData must never disagree with the UA string."""

    def test_brands_follow_the_ua(self):
        md = gf._ua_metadata(gf.DEFAULT_UA, "Linux armv8l")
        self.assertEqual(md["fullVersion"], "149.0.0.0")
        self.assertIn({"brand": "Chromium", "version": "149"}, md["brands"])
        self.assertIn({"brand": "Chromium", "version": "149.0.0.0"},
                      md["fullVersionList"])
        self.assertEqual(md["platform"], "Linux")
        self.assertEqual(md["architecture"], "arm")
        self.assertFalse(md["mobile"])
        self.assertEqual(md["bitness"], "64")

    def test_x86_platform_maps_to_x86(self):
        md = gf._ua_metadata("Mozilla/5.0 (X11; Linux x86_64) Chrome/120.0.1.2",
                             "Linux x86_64")
        self.assertEqual(md["architecture"], "x86")
        self.assertEqual(md["fullVersion"], "120.0.1.2")

    def test_stealth_js_gets_its_placeholders_filled(self):
        js = gf._build_stealth_js(1280, 720, "__gtestkey")
        self.assertNotIn("__W__", js)
        self.assertNotIn("__H__", js)
        self.assertNotIn("__WATCH_KEY__", js)
        self.assertIn("1280", js)
        self.assertIn("__gtestkey", js)


@skip_no_ghost
class PortAndProfileTests(unittest.TestCase):
    """Concurrent runs must never collide on a port or on Chromium's lock."""

    def test_free_port_avoids_a_busy_port(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        busy = s.getsockname()[1]
        try:
            self.assertNotEqual(gf.free_port(busy), busy)
            self.assertGreater(gf.free_port(0), 1024)
            self.assertGreater(gf.free_port(1024), 0)   # never privileged
        finally:
            s.close()

    def test_stale_singleton_lock_is_cleared(self):
        with tempfile.TemporaryDirectory() as tmp:
            profile = Path(tmp) / "profile"
            profile.mkdir()
            lock = profile / "SingletonLock"
            os.symlink(f"{socket.gethostname()}-999999", lock)
            claimed, handle = gf.claim_profile(str(profile), 9555,
                                               lambda m: None)
            try:
                self.assertEqual(Path(claimed), profile)
                self.assertFalse(lock.is_symlink())
            finally:
                if handle:
                    handle.close()

    def test_second_run_forks_a_sibling_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            profile = Path(tmp) / "profile"
            first, h1 = gf.claim_profile(str(profile), 9561, lambda m: None)
            second, h2 = gf.claim_profile(str(profile), 9562, lambda m: None)
            try:
                self.assertEqual(Path(first), profile)
                self.assertNotEqual(second, first)
                self.assertIn("9562", second)
                self.assertTrue(Path(second).is_dir())
            finally:
                if h1:
                    h1.close()
                if h2:
                    h2.close()

    def test_live_owner_lock_is_respected(self):
        """A lock whose owner process is alive must NOT be treated as stale."""
        with tempfile.TemporaryDirectory() as tmp:
            profile = Path(tmp) / "profile"
            profile.mkdir()
            mine = os.getpid()
            os.symlink(f"{socket.gethostname()}-{mine}",
                       profile / "SingletonLock")
            self.assertFalse(gf._clear_stale_singleton(profile))
            self.assertTrue((profile / "SingletonLock").is_symlink())

    def test_out_path_creates_parents(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "deep" / "nested" / "env.json"
            resolved = gf.resolve_out_path(str(target))
            self.assertEqual(resolved, target)
            self.assertTrue(resolved.parent.is_dir())

    @unittest.skipIf(Path("/tmp").is_dir(),
                     "/tmp exists here, nothing to rescue")
    def test_bare_tmp_path_is_rescued_on_termux_like_hosts(self):
        with tempfile.TemporaryDirectory() as tmp:
            old = os.environ.get("TMPDIR")
            os.environ["TMPDIR"] = tmp
            try:
                resolved = gf.resolve_out_path("/tmp/ghost/x.json")
                self.assertTrue(str(resolved).startswith(tmp))
            finally:
                if old is None:
                    os.environ.pop("TMPDIR", None)
                else:
                    os.environ["TMPDIR"] = old

    def test_cookie_host_matching(self):
        self.assertTrue(gf._cookie_matches_host(".site.to", "www.site.to"))
        self.assertTrue(gf._cookie_matches_host("site.to", "site.to"))
        self.assertFalse(gf._cookie_matches_host("other.to", "site.to"))
        self.assertFalse(gf._cookie_matches_host("", "site.to"))


_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


async def _echo_ws_server(reader: asyncio.StreamReader,
                          writer: asyncio.StreamWriter) -> None:
    """Minimal RFC6455 echo server: handshake, then echo text frames."""
    try:
        request = b""
        while b"\r\n\r\n" not in request:
            chunk = await reader.read(1024)
            if not chunk:
                return
            request += chunk
        key = re.search(rb"Sec-WebSocket-Key: (\S+)", request)
        if not key:
            return
        accept = base64.b64encode(hashlib.sha1(
            key.group(1) + _WS_GUID.encode()).digest()).decode()
        writer.write((
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())
        await writer.drain()
        while True:
            head = await reader.readexactly(2)
            opcode = head[0] & 0x0F
            length = head[1] & 0x7F
            if length == 126:
                length = int.from_bytes(await reader.readexactly(2), "big")
            elif length == 127:
                length = int.from_bytes(await reader.readexactly(8), "big")
            mask = await reader.readexactly(4) if head[1] & 0x80 else b""
            payload = await reader.readexactly(length) if length else b""
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x8:                       # close
                writer.write(b"\x88\x00")
                await writer.drain()
                return
            if opcode == 0x9:                       # ping -> pong
                writer.write(bytes([0x8A, len(payload)]) + payload)
                await writer.drain()
                continue
            # echo text back unmasked
            if length < 126:
                header = bytes([0x81, length])
            elif length < 65536:
                header = bytes([0x81, 126]) + length.to_bytes(2, "big")
            else:
                header = bytes([0x81, 127]) + length.to_bytes(8, "big")
            writer.write(header + payload)
            await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


@skip_no_ghost
class StdlibWebSocketTests(unittest.IsolatedAsyncioTestCase):
    """The no-dependency CDP transport must frame correctly in both directions."""

    async def _server(self):
        server = await asyncio.start_server(_echo_ws_server, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        return server, port

    async def test_small_text_round_trip(self):
        server, port = await self._server()
        try:
            ws = await gf._StdlibWS.connect(f"ws://127.0.0.1:{port}/devtools/x")
            async with ws:
                await ws.send('{"id":1,"method":"Page.enable"}')
                got = await ws.recv()
                self.assertEqual(got, '{"id":1,"method":"Page.enable"}')
        finally:
            server.close()
            await server.wait_closed()

    async def test_large_payload_uses_64bit_length(self):
        server, port = await self._server()
        try:
            ws = await gf._StdlibWS.connect(f"ws://127.0.0.1:{port}/devtools/x")
            async with ws:
                big = "x" * 70000          # forces the 127-length path both ways
                await ws.send(big)
                self.assertEqual(await ws.recv(), big)
        finally:
            server.close()
            await server.wait_closed()

    async def test_refused_upgrade_raises(self):
        async def plain_http(reader, writer):
            await reader.read(1024)
            writer.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
            await writer.drain()
            writer.close()

        server = await asyncio.start_server(plain_http, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            with self.assertRaises(gf.GhostError):
                await gf._StdlibWS.connect(f"ws://127.0.0.1:{port}/x")
        finally:
            server.close()
            await server.wait_closed()


if __name__ == "__main__":
    unittest.main()
