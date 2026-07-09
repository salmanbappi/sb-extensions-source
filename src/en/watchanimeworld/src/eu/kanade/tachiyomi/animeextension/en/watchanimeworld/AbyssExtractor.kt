package eu.kanade.tachiyomi.animeextension.en.watchanimeworld

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Extractor for the Abyss Player (abyssplayer.com).
 *
 * Flow:
 *  1. Fetch the embed page HTML (OkHttp).
 *  2. Extract the inline base64 `datas` value.
 *  3. Inject a JS snippet into the embed page loaded inside a headless WebView that:
 *       – calls window.SoTrym(JSON.parse(atob(datas)))
 *       – waits for CONFIG to be populated
 *       – calls Android.passPayload(JSON.stringify(CONFIG))
 *  4. Parse CONFIG → file (m3u8) + subtitles.
 */
class AbyssExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val playlistUtils: PlaylistUtils,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // ── JS interface received by the WebView ──────────────────────────────

    private class JsInterface(private val latch: CountDownLatch) {
        var result: String? = null

        @JavascriptInterface
        fun passPayload(payload: String) {
            result = payload
            latch.countDown()
        }
    }

    // ── Public entry-point ────────────────────────────────────────────────

    /**
     * @param embedUrl  Full URL of the abyssplayer.com embed, e.g.
     *                  https://abyssplayer.com/embed/qkKinmdc5?md5_id=…
     * @param referer   The watch-page URL (for Referer header).
     * @param serverName  Label shown in quality picker, e.g. "Server 1".
     */
    fun videosFromEmbed(
        embedUrl: String,
        referer: String,
        serverName: String = "Abyss",
    ): List<Video> {
        // 1. Fetch embed HTML
        val embedHtml = try {
            client.newCall(
                GET(embedUrl, headers.newBuilder().set("Referer", referer).build()),
            ).execute().body?.string() ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        // 2. Extract `datas` value from the inline script
        val datasValue = DATAS_REGEX.find(embedHtml)?.groupValues?.get(1)
            ?: return emptyList()

        // 3. Use WebView to decrypt via window.SoTrym
        val configJson = decryptViaBrowser(embedUrl, datasValue) ?: return emptyList()

        // 4. Parse CONFIG
        val config = try {
            kotlinx.serialization.json.Json.decodeFromString<AbyssConfig>(configJson)
        } catch (_: Exception) {
            return emptyList()
        }

        val m3u8Url = config.file?.takeIf { it.isNotBlank() } ?: return emptyList()

        val subtitles = config.subtitles?.mapNotNull { sub ->
            val src = sub.src ?: return@mapNotNull null
            val label = sub.label ?: "Unknown"
            Track(src, label)
        } ?: emptyList()

        val streamHeaders = headers.newBuilder()
            .set("Referer", "https://abyssplayer.com/")
            .set("Origin", "https://abyssplayer.com")
            .build()

        return if (m3u8Url.contains(".m3u8")) {
            try {
                playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    referer = "https://abyssplayer.com/",
                    masterHeaders = streamHeaders,
                    videoHeaders = streamHeaders,
                    videoNameGen = { quality -> "$serverName - $quality" },
                    subtitleList = subtitles,
                )
            } catch (_: Exception) {
                listOf(
                    Video(
                        videoUrl = m3u8Url,
                        videoTitle = "$serverName - Auto",
                        headers = streamHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }
        } else {
            listOf(
                Video(
                    videoUrl = m3u8Url,
                    videoTitle = "$serverName - Auto",
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }

    // ── WebView decryption ────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun decryptViaBrowser(embedUrl: String, datasValue: String): String? {
        val latch = CountDownLatch(1)
        val jsi = JsInterface(latch)
        var webView: WebView? = null

        handler.post {
            val wv = WebView(context)
            webView = wv
            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = headers["User-Agent"]
            }
            wv.addJavascriptInterface(jsi, INTERFACE_NAME)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    // Intercept the embed page load so we can inject our extraction script
                    if (reqUrl.equals(embedUrl, ignoreCase = true)) {
                        return buildPatchedResponse(reqUrl, datasValue)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            wv.loadUrl(embedUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return jsi.result
    }

    /**
     * Fetches the real embed HTML, injects a script that:
     *  - waits for SoTrym to be defined
     *  - calls SoTrym(JSON.parse(atob(datas)))
     *  - polls for window.CONFIG until populated
     *  - sends CONFIG JSON to the Android interface
     */
    private fun buildPatchedResponse(url: String, datasValue: String): WebResourceResponse? {
        val html = try {
            client.newCall(GET(url, headers)).execute().body?.string() ?: return null
        } catch (_: Exception) {
            return null
        }

        val injectedScript = """
            <script>
            (function() {
                var _datas = "$datasValue";
                function tryExtract() {
                    if (typeof window.SoTrym !== 'function') {
                        setTimeout(tryExtract, 200);
                        return;
                    }
                    var parsed;
                    try { parsed = JSON.parse(atob(_datas)); } catch(e) { return; }
                    window.SoTrym(parsed).then(function() {
                        var interval = setInterval(function() {
                            if (window.CONFIG && window.CONFIG.file) {
                                clearInterval(interval);
                                window.$INTERFACE_NAME.passPayload(JSON.stringify(window.CONFIG));
                            }
                        }, 100);
                        // Timeout after 10s
                        setTimeout(function() { clearInterval(interval); }, 10000);
                    }).catch(function(e) {});
                }
                document.addEventListener('DOMContentLoaded', tryExtract);
                setTimeout(tryExtract, 500);
            })();
            </script>
        """.trimIndent()

        // Inject before closing </head> or at start of <body>
        val patched = when {
            html.contains("</head>", ignoreCase = true) ->
                html.replace("</head>", "$injectedScript</head>", ignoreCase = true)

            html.contains("<body", ignoreCase = true) ->
                html.replace(Regex("(<body[^>]*>)"), "$1$injectedScript")

            else -> injectedScript + html
        }

        return WebResourceResponse(
            "text/html",
            "utf-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8)),
        )
    }

    // ── Companion ─────────────────────────────────────────────────────────

    companion object {
        private const val INTERFACE_NAME = "AbyssAndroid"
        private const val TIMEOUT_SEC = 30L

        /**
         * Matches: var datas = "BASE64STRING";
         * or:      datas="BASE64STRING"
         */
        private val DATAS_REGEX = Regex(
            """(?:var\s+)?datas\s*=\s*["']([A-Za-z0-9+/=]+)["']""",
        )
    }
}

// ── Serializable models ───────────────────────────────────────────────────────

@kotlinx.serialization.Serializable
private data class AbyssConfig(
    val file: String? = null,
    val title: String? = null,
    val image: String? = null,
    val subtitles: List<AbyssSubtitle>? = null,
)

@kotlinx.serialization.Serializable
private data class AbyssSubtitle(
    val label: String? = null,
    val src: String? = null,
    val srclang: String? = null,
)
