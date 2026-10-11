package eu.kanade.tachiyomi.animeextension.en.anilight

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Resolves the stream URLs AniLight's own player plays for the providers its
 * API keeps behind a Cloudflare Turnstile session.
 *
 * `/sources` (and `/sourceSpecial`) only answer when the request replays the
 * `sessionToken` their widget mints, which an OkHttp client cannot produce. So
 * instead of replicating that handshake, this drives the website itself: the
 * watch page is opened with `?server=<provider>` (a parameter the SPA's watch
 * state hook honours), its player solves whatever gate it needs, fetches its
 * sources and autoplays. Every media request it makes is captured, so what
 * comes back is exactly what a browser plays — the same CDN URLs, the same
 * rotating Cloudflare Workers, including the per-request hostnames and query
 * strings that cannot be constructed from the outside.
 *
 * Modelled on the repository's other WebView resolvers (nepu, cinemaos,
 * shuttletv, cinejoy).
 */
class WebViewStreamResolver {

    data class Resolved(
        val media: List<String>,
        val subtitles: List<String>,
    )

    /**
     * Loads [watchUrl] and returns the media/subtitle URLs its player requests.
     * Blocks the calling thread; returns empty lists when nothing shows up
     * within [timeoutMs].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(watchUrl: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Resolved {
        val media = Collections.synchronizedList(mutableListOf<String>())
        val subtitles = Collections.synchronizedList(mutableListOf<String>())
        val firstMedia = CountDownLatch(1)
        val settled = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var nudges = 0

        // WebView is UI-bound, so it is created on the main thread.
        main.post {
            val wv = WebView(applicationContext)
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            // The site autoplays by default; do not let the WebView hold it back.
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.settings.userAgentString = USER_AGENT

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.startsWith("blob:") || url.startsWith("data:")) return null
                    when {
                        isSubtitle(url) -> if (!subtitles.contains(url)) subtitles.add(url)

                        isMedia(url) && !media.contains(url) -> {
                            media.add(url)
                            firstMedia.countDown()
                            // Give the player a moment to fan out into
                            // renditions and subtitle tracks before the page
                            // is torn down.
                            main.postDelayed({ settled.countDown() }, GRACE_MS)
                        }
                    }
                    return null
                }
            }
            wv.loadUrl(watchUrl)
        }

        // Keep poking the player while we wait: autoplay usually starts on its
        // own, but a click recovers when an overlay or a paused element wins.
        val nudge = object : Runnable {
            override fun run() {
                if (nudges >= MAX_NUDGES) return
                nudges++
                webView?.evaluateJavascript(NUDGE_JS, null)
                main.postDelayed(this, NUDGE_INTERVAL_MS)
            }
        }
        main.postDelayed(nudge, NUDGE_INTERVAL_MS)

        if (firstMedia.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            settled.await(GRACE_MS + SETTLE_SLACK_MS, TimeUnit.MILLISECONDS)
        }

        main.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Exception) {
                // best effort cleanup
            }
        }

        return Resolved(media.toList(), subtitles.toList())
    }

    private fun isSubtitle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".vtt") || lower.contains("/proxy/captions")
    }

    private fun isMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** video.js / native elements: start whatever is loaded and paused. */
        const val NUDGE_JS = """
            (function() {
                try {
                    var v = document.querySelector('video');
                    if (v && v.paused) {
                        try { v.muted = true; } catch (e) {}
                        var p = v.play();
                        if (p && p.catch) p.catch(function() {});
                    }
                    var b = document.querySelector(
                        '.vjs-big-play-button, .play-button, .jw-icon-playback, button[aria-label*="lay" i]'
                    );
                    if (b) b.click();
                } catch (e) {}
                return 1;
            })()
        """

        const val DEFAULT_TIMEOUT_MS = 45_000L
        const val GRACE_MS = 2_000L
        const val SETTLE_SLACK_MS = 1_000L
        const val NUDGE_INTERVAL_MS = 2_500L
        const val MAX_NUDGES = 18
    }
}
