package eu.kanade.tachiyomi.animeextension.en.anilight

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mints the `turnstile_session` token AniLight's API demands on `/sources`
 * and `/sourceSpecial` since 2026-10: without it every non-embed provider
 * answers `403 {"error":"Missing Turnstile session token"}`, and the token
 * can only come from a solved Cloudflare Turnstile widget (the site POSTs it
 * to `/sources/verify` and replays the returned `sessionToken` as the
 * `x-source-session` header).
 *
 * An OkHttp client cannot solve a Turnstile widget, so this drives the
 * website's own flow inside a WebView instead: opening the watch page with
 * `?server=<non-meg provider>` makes the SPA mount its own Turnstile widget
 * (sitekey `0x4AAAAAAFJzgmgjVOeHNQBb`, action `fetch_sources`), the widget
 * solves as it would in any mobile browser, the site verifies it, and the
 * fresh token lands in `localStorage.turnstile_session` — which is exactly
 * what this class reads back.
 *
 * Modelled on the repository's other in-app WebView solvers (animepahe's
 * `CloudflareBypass`, nepu, shuttletv, cinejoy).
 */
class TurnstileSessionMinter {

    /**
     * Runs the site's Turnstile flow for [watchUrl] and returns the fresh
     * session token, or null when the widget cannot be solved in time.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun mint(watchUrl: String): String? {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        val cancelled = AtomicBoolean(false)
        var webView: WebView? = null

        // WebView is UI-bound, so it must be created on the main thread.
        Handler(Looper.getMainLooper()).post {
            val wv = WebView(applicationContext)
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = USER_AGENT
            wv.webViewClient = object : WebViewClient() {
                private var clearedStale = false

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!clearedStale) {
                        // An expired token would otherwise be read back before
                        // the site re-verifies; wipe it and reload so the
                        // widget flow produces a fresh one.
                        clearedStale = true
                        view.evaluateJavascript(CLEAR_STALE_JS) { view.reload() }
                        return
                    }
                    pollForSession(wv, cancelled) { token ->
                        result[0] = token
                        latch.countDown()
                    }
                }
            }
            wv.loadUrl(watchUrl)
        }

        try {
            latch.await(MINT_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            cancelled.set(true)
            Handler(Looper.getMainLooper()).post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {
                    // best effort cleanup
                }
            }
        }
        return result[0]
    }

    private fun pollForSession(
        webView: WebView,
        cancelled: AtomicBoolean,
        onDone: (String) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                if (System.currentTimeMillis() - started >= POLL_TIMEOUT_MS) return

                webView.evaluateJavascript(READ_SESSION_JS) { raw ->
                    if (cancelled.get()) return@evaluateJavascript
                    val token = runCatching {
                        JSONTokener(raw ?: "null").nextValue() as? String
                    }.getOrNull()?.takeIf { it.isNotBlank() }

                    if (token != null) {
                        onDone(token)
                    } else {
                        handler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }
        }
        handler.post(runnable)
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** One-time stale-token wipe before the real solve attempt. */
        const val CLEAR_STALE_JS =
            "(function(){var v=localStorage.getItem('turnstile_session')||'';" +
                "localStorage.removeItem('turnstile_session');return v;})()"

        const val READ_SESSION_JS = "localStorage.getItem('turnstile_session')||''"

        const val MINT_TIMEOUT_S = 60L
        const val POLL_TIMEOUT_MS = 55_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
