package eu.kanade.tachiyomi.animeextension.en.senshi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SenshiResolver(
    private val context: Context,
    private val baseUrl: String,
    private val userAgent: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var webView: WebView? = null

    private val pendingRequests = ConcurrentHashMap<String, RequestState>()

    private class RequestState {
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: String? = null
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onSuccess(reqId: String, json: String) {
            pendingRequests[reqId]?.let { state ->
                state.result = json
                state.latch.countDown()
            }
        }

        @JavascriptInterface
        fun onError(reqId: String, err: String) {
            pendingRequests[reqId]?.let { state ->
                state.error = err
                state.latch.countDown()
            }
        }
    }

    fun warmUp() {
        if (webView != null) return
        mainHandler.post {
            try {
                ensureWebViewInternal()
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebViewInternal() {
        if (webView != null) return
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }
        wv.addJavascriptInterface(AndroidBridge(), "androidBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                destroyWebView()
                return true
            }
        }
        wv.loadDataWithBaseURL(baseUrl, HTML_SHELL, "text/html", "UTF-8", null)
        webView = wv
    }

    fun resolve(remoteId: Int, timeoutSec: Long = 25): String? {
        val initLatch = CountDownLatch(1)
        synchronized(lock) {
            if (webView == null) {
                mainHandler.post {
                    try {
                        ensureWebViewInternal()
                    } finally {
                        initLatch.countDown()
                    }
                }
                initLatch.await(5, TimeUnit.SECONDS)
            }
        }

        val reqId = UUID.randomUUID().toString()
        val state = RequestState()
        pendingRequests[reqId] = state

        mainHandler.post {
            val wv = webView
            if (wv == null) {
                state.error = "WebView unavailable"
                state.latch.countDown()
                return@post
            }
            wv.evaluateJavascript("window.resolveSources('$reqId', $remoteId);", null)
        }

        val finished = try {
            state.latch.await(timeoutSec, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }

        pendingRequests.remove(reqId)

        if (!finished) {
            destroyWebView()
            return null
        }

        if (state.error != null) {
            return null
        }

        return state.result
    }

    private fun destroyWebView() {
        mainHandler.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Exception) {}
            webView = null
        }
    }

    companion object {
        private val HTML_SHELL = """
            <!DOCTYPE html>
            <html>
            <head>
                <script>
                    window.resolveSources = async function(reqId, id) {
                        var tries = 0;
                        while ((!window.__oct || typeof window.__oct.open !== 'function') && tries++ < 150) {
                            await new Promise(function(r) { setTimeout(r, 100); });
                        }
                        if (!window.__oct || typeof window.__oct.open !== 'function') {
                            androidBridge.onError(reqId, 'Security runtime (__oct) unavailable');
                            return;
                        }
                        try {
                            var res = await window.__oct.open(Number(id));
                            androidBridge.onSuccess(reqId, JSON.stringify(res));
                        } catch (e) {
                            androidBridge.onError(reqId, (e && e.message) ? e.message : String(e));
                        }
                    };
                </script>
                <script src="https://cdn.vidcloud.se/vjs/vendor.js" onerror="this.onerror=null;this.src='https://senshi.to/assets/vendor.js'"></script>
            </head>
            <body>
            </body>
            </html>
        """.trimIndent()
    }
}
