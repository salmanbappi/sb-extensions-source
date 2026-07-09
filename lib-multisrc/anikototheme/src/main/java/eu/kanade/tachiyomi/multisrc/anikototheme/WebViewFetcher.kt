package eu.kanade.tachiyomi.multisrc.anikototheme

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * WebView-based HTTP fetcher that uses Chrome's TLS stack.
 */
class WebViewFetcher(
    private val context: Context,
    private val originUrl: String = "https://megaplay.buzz/",
) {
    private val logTag = "WebViewFetcher"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null

    @Volatile private var webViewReady = false
    private val atomicId = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, RequestState>()
    private val fetchLock = Any()

    private open inner class RequestState {
        val latch = CountDownLatch(1)
        var error: String? = null
    }

    private inner class TextRequestState : RequestState() {
        var textResult: String? = null
    }

    private inner class ByteRequestState : RequestState() {
        val chunks = mutableListOf<ByteArray>()
    }

    inner class JSInterface {
        @JavascriptInterface
        fun onResult(id: String, text: String) {
            pendingRequests[id]?.let { state ->
                (state as? TextRequestState)?.let {
                    it.textResult = text
                    it.latch.countDown()
                }
            }
        }

        @JavascriptInterface
        fun onChunk(id: String, index: Int, total: Int, base64data: String) {
            pendingRequests[id]?.let { state ->
                (state as? ByteRequestState)?.let {
                    synchronized(it.chunks) {
                        it.chunks.add(Base64.decode(base64data, Base64.DEFAULT))
                    }
                }
            }
        }

        @JavascriptInterface
        fun onBytesComplete(id: String, totalSize: Int) {
            pendingRequests[id]?.let { state ->
                (state as? ByteRequestState)?.latch?.countDown()
            }
        }

        @JavascriptInterface
        fun onError(id: String, error: String) {
            Log.e(logTag, "JS error for request $id: $error")
            pendingRequests[id]?.let { state ->
                state.error = error
                state.latch.countDown()
            }
        }
    }

    fun warmUp() {
        if (webView != null && webViewReady) return
        Thread {
            try {
                mainHandler.post {
                    try {
                        if (webView == null) {
                            webView = WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.blockNetworkImage = true
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        Log.i(logTag, "warmUp page loaded: $url")
                                        webViewReady = true
                                    }
                                }
                                addJavascriptInterface(JSInterface(), "Android")
                            }
                            Log.i(logTag, "warmUp loading origin: $originUrl")
                            webView?.loadUrl(originUrl)
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "warmUp WebView creation failed", e)
                        webViewReady = true
                    }
                }
                val deadline = System.currentTimeMillis() + 10_000
                while (!webViewReady && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                Log.i(logTag, "warmUp ${if (webViewReady) "complete" else "timed out"}")
            } catch (e: Exception) {
                Log.e(logTag, "warmUp failed", e)
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null && webViewReady) return
        synchronized(fetchLock) {
            if (webView != null && webViewReady) return
            mainHandler.post {
                try {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.blockNetworkImage = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                Log.i(logTag, "origin page loaded: $url")
                                webViewReady = true
                            }
                        }
                        addJavascriptInterface(JSInterface(), "Android")
                    }
                    Log.i(logTag, "loading origin: $originUrl")
                    webView?.loadUrl(originUrl)
                } catch (e: Exception) {
                    Log.e(logTag, "failed to create WebView", e)
                    webViewReady = true
                }
            }
            val deadline = System.currentTimeMillis() + 30_000
            while (!webViewReady && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
            }
            if (!webViewReady) {
                Log.e(logTag, "timeout waiting for origin page load")
            }
        }
    }

    fun fetchText(url: String, timeoutMs: Long = 30_000): String {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = TextRequestState()
        pendingRequests[id] = state
        val startTime = System.currentTimeMillis()
        Log.d(logTag, "fetchText id=$id url=${url.take(80)}")

        synchronized(fetchLock) {
            mainHandler.post {
                webView?.evaluateJavascript(buildFetchTextJs(id, url), null)
            }
            if (!state.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pendingRequests.remove(id)
                throw RuntimeException("WebViewFetcher: text fetch timeout for ${url.take(60)}")
            }
        }
        pendingRequests.remove(id)
        state.error?.let { throw RuntimeException("WebViewFetcher: $it") }
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(logTag, "fetchText id=$id DONE in ${elapsed}ms")
        return state.textResult ?: throw RuntimeException("WebViewFetcher: no text result")
    }

    fun postJson(url: String, jsonBody: String, timeoutMs: Long = 30_000): String {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = TextRequestState()
        pendingRequests[id] = state
        val startTime = System.currentTimeMillis()
        Log.d(logTag, "postJson id=$id url=${url.take(60)}")

        synchronized(fetchLock) {
            mainHandler.post {
                webView?.evaluateJavascript(buildPostJsonJs(id, url, jsonBody), null)
            }
            if (!state.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pendingRequests.remove(id)
                throw RuntimeException("WebViewFetcher: postJson timeout for ${url.take(60)}")
            }
        }
        pendingRequests.remove(id)
        state.error?.let { throw RuntimeException("WebViewFetcher: $it") }
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(logTag, "postJson id=$id DONE in ${elapsed}ms")
        return state.textResult ?: throw RuntimeException("WebViewFetcher: no postJson result")
    }

    fun fetchBytes(url: String, timeoutMs: Long = 60_000): ByteArray {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = ByteRequestState()
        pendingRequests[id] = state
        val startTime = System.currentTimeMillis()
        Log.d(logTag, "fetchBytes id=$id url=${url.take(80)}")

        synchronized(fetchLock) {
            mainHandler.post {
                webView?.evaluateJavascript(buildFetchBytesJs(id, url), null)
            }
            if (!state.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pendingRequests.remove(id)
                throw RuntimeException("WebViewFetcher: bytes fetch timeout for ${url.take(60)}")
            }
        }
        pendingRequests.remove(id)
        state.error?.let { throw RuntimeException("WebViewFetcher: $it") }
        synchronized(state.chunks) {
            if (state.chunks.isEmpty()) throw RuntimeException("WebViewFetcher: no bytes received")
            val result = if (state.chunks.size == 1) state.chunks[0] else state.chunks.reduce { acc, chunk -> acc + chunk }
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(logTag, "fetchBytes id=$id DONE in ${elapsed}ms size=${result.size}")
            return result
        }
    }

    fun destroy() {
        webViewReady = false
        mainHandler.post {
            try {
                webView?.destroy()
            } catch (_: Exception) {}
            webView = null
        }
        pendingRequests.clear()
        destroyGoogleWebView()
    }

    // ── Google AI Search WebView (Smart Search) ──
    @Volatile private var googleWebView: WebView? = null

    @Volatile private var googleWebViewReady = false
    private val googleLock = Any()
    private val googleGenCounter = AtomicInteger(0)

    fun warmUpGoogleWebView() {
        if (googleWebView != null && googleWebViewReady) return
        Log.i(logTag, "SmartSearch: warmUpGoogleWebView — pre-creating Google WebView")
        Thread {
            try {
                ensureGoogleWebView()
            } catch (e: Exception) {
                Log.e(logTag, "SmartSearch: warmUpGoogleWebView failed", e)
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureGoogleWebView() {
        if (googleWebView != null && googleWebViewReady) return
        synchronized(googleLock) {
            if (googleWebView != null && googleWebViewReady) return
            Log.d(logTag, "SmartSearch: creating Google WebView")
            mainHandler.post {
                try {
                    googleWebView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.blockNetworkImage = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    }
                    googleWebViewReady = true
                    Log.i(logTag, "SmartSearch: Google WebView created and ready")
                } catch (e: Exception) {
                    Log.e(logTag, "SmartSearch: failed to create Google WebView", e)
                    googleWebViewReady = true
                }
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (!googleWebViewReady && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
        }
    }

    fun fetchRenderedText(url: String, timeoutMs: Long = 20_000): String {
        ensureGoogleWebView()
        if (googleWebView == null) {
            Log.e(logTag, "SmartSearch: fetchRenderedText — Google WebView not available")
            return ""
        }

        val startTime = System.currentTimeMillis()
        Log.i(logTag, "SmartSearch: scraping ${url.take(100)}")

        val latch = CountDownLatch(1)
        val resultHolder = arrayOfNulls<String>(1)
        val retryUsed = java.util.concurrent.atomic.AtomicBoolean(false)

        synchronized(googleLock) {
            mainHandler.post {
                try {
                    googleWebView?.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            Log.d(logTag, "SmartSearch: onPageFinished: ${loadedUrl?.take(80) ?: "?"}")
                            val myGen = googleGenCounter.incrementAndGet()

                            mainHandler.postDelayed({
                                if (googleGenCounter.get() != myGen) {
                                    Log.d(logTag, "SmartSearch: extraction gen $myGen stale (current=${googleGenCounter.get()}), skipping")
                                    return@postDelayed
                                }
                                doExtract(view, myGen)
                            }, 1500)
                        }

                        private fun doExtract(view: WebView?, myGen: Int) {
                            if (googleGenCounter.get() != myGen) return
                            view?.evaluateJavascript("(function(){ return document.body.innerText; })()") { result ->
                                if (googleGenCounter.get() != myGen) return@evaluateJavascript

                                val text = parseJsStringResult(result)
                                Log.d(logTag, "SmartSearch: extracted ${text.length} chars (first 200: ${text.take(200)})")

                                if (text.length < 200 && !retryUsed.get()) {
                                    Log.d(logTag, "SmartSearch: content short (${text.length} < 200), retrying in 2s")
                                    retryUsed.set(true)
                                    mainHandler.postDelayed({
                                        if (googleGenCounter.get() != myGen) return@postDelayed
                                        view?.evaluateJavascript("(function(){ return document.body.innerText; })()") { result2 ->
                                            if (googleGenCounter.get() != myGen) return@evaluateJavascript
                                            val text2 = parseJsStringResult(result2)
                                            Log.d(logTag, "SmartSearch: retry extracted ${text2.length} chars")
                                            resultHolder[0] = text2
                                            latch.countDown()
                                        }
                                    }, 2000)
                                } else {
                                    resultHolder[0] = text
                                    latch.countDown()
                                }
                            }
                        }
                    }

                    googleGenCounter.set(0)
                    googleWebView?.loadUrl(url)
                } catch (e: Exception) {
                    Log.e(logTag, "SmartSearch: failed to load URL", e)
                    latch.countDown()
                }
            }

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.e(logTag, "SmartSearch: scrape timeout after ${timeoutMs}ms")
                return ""
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val text = resultHolder[0] ?: ""
        Log.i(logTag, "SmartSearch: scrape DONE in ${elapsed}ms, ${text.length} chars")
        return text
    }

    fun destroyGoogleWebView() {
        googleWebViewReady = false
        mainHandler.post {
            try {
                googleWebView?.destroy()
            } catch (_: Exception) {}
            googleWebView = null
        }
        Log.d(logTag, "SmartSearch: Google WebView destroyed")
    }

    private fun parseJsStringResult(result: String?): String {
        if (result.isNullOrBlank() || result == "null") return ""
        return try {
            if (result.startsWith("\"") && result.endsWith("\"")) {
                val inner = result.substring(1, result.length - 1)
                inner
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\'", "'")
            } else {
                result
            }
        } catch (e: Exception) {
            result
        }
    }

    // ── JavaScript builders ──────────────────────────────────────────────────

    private fun buildPostJsonJs(id: String, url: String, jsonBody: String): String {
        val escapedUrl = escapeJsString(url)
        val escapedBody = escapeJsString(jsonBody)
        return """
            (async function() {
                try {
                    const response = await fetch('$escapedUrl', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                        body: '$escapedBody'
                    });
                    if (!response.ok) { Android.onError('$id', 'HTTP ' + response.status); return; }
                    const text = await response.text();
                    Android.onResult('$id', text);
                } catch(e) { Android.onError('$id', e.message); }
            })();
        """.trimIndent()
    }

    private fun buildFetchTextJs(id: String, url: String): String {
        val escapedUrl = escapeJsString(url)
        return """
            (async function() {
                try {
                    const response = await fetch('$escapedUrl');
                    if (!response.ok) { Android.onError('$id', 'HTTP ' + response.status); return; }
                    const text = await response.text();
                    Android.onResult('$id', text);
                } catch(e) { Android.onError('$id', e.message); }
            })();
        """.trimIndent()
    }

    private fun buildFetchBytesJs(id: String, url: String): String {
        val escapedUrl = escapeJsString(url)
        return """
            (async function() {
                try {
                    const response = await fetch('$escapedUrl');
                    if (!response.ok) { Android.onError('$id', 'HTTP ' + response.status); return; }
                    const buf = await response.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    var chunkSize = 700000;
                    var numChunks = Math.ceil(bytes.length / chunkSize);
                    for (var i = 0; i < numChunks; i++) {
                        var start = i * chunkSize;
                        var end = Math.min(start + chunkSize, bytes.length);
                        var chunk = bytes.subarray(start, end);
                        var base64 = await new Promise(function(resolve) {
                            var reader = new FileReader();
                            reader.onload = function() { resolve(reader.result.split(',')[1]); };
                            reader.readAsDataURL(new Blob([chunk]));
                        });
                        Android.onChunk('$id', i, numChunks, base64);
                    }
                    Android.onBytesComplete('$id', bytes.length);
                } catch(e) { Android.onError('$id', e.message); }
            })();
        """.trimIndent()
    }

    private fun escapeJsString(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
}
