package eu.kanade.tachiyomi.animeextension.all.anikoto

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class WebViewFetcher(
    private val context: Context = Injekt.get<Application>(),
    private val originUrl: String = "https://megaplay.buzz/"
) {
    private val TAG = "WebViewFetcher"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val atomicId = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, RequestState>()
    private val fetchLock = Any()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var webViewReady = false

    private open class RequestState {
        var error: String? = null
        val latch = CountDownLatch(1)
    }

    private class TextRequestState : RequestState() {
        var textResult: String? = null
    }

    private class ByteRequestState : RequestState() {
        val chunks = mutableListOf<ByteArray>()
    }

    private inner class JSInterface {
        @JavascriptInterface
        fun onBytesComplete(id: String, totalSize: Int) {
            val state = pendingRequests[id] as? ByteRequestState ?: return
            state.latch.countDown()
        }

        @JavascriptInterface
        fun onChunk(id: String, index: Int, total: Int, base64data: String) {
            val state = pendingRequests[id] as? ByteRequestState ?: return
            synchronized(state.chunks) {
                state.chunks.add(Base64.decode(base64data, 0))
            }
        }

        @JavascriptInterface
        fun onError(id: String, error: String) {
            Log.e(TAG, "JS error for request $id: $error")
            val state = pendingRequests[id] ?: return
            state.error = error
            state.latch.countDown()
        }

        @JavascriptInterface
        fun onResult(id: String, text: String) {
            val state = pendingRequests[id] as? TextRequestState ?: return
            state.textResult = text
            state.latch.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null && webViewReady) return
        synchronized(fetchLock) {
            if (webView != null && webViewReady) return
            mainHandler.post {
                try {
                    val wv = WebView(context)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.blockNetworkImage = true
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.i(TAG, "origin page loaded: $url")
                            webViewReady = true
                        }
                    }
                    wv.addJavascriptInterface(JSInterface(), "Android")
                    webView = wv
                    Log.i(TAG, "loading origin: $originUrl")
                    wv.loadUrl(originUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "failed to create WebView", e)
                    webViewReady = true
                }
            }
            val timeout = System.currentTimeMillis() + 30000
            while (!webViewReady && System.currentTimeMillis() < timeout) {
                Thread.sleep(200)
            }
            if (!webViewReady) {
                Log.e(TAG, "timeout waiting for origin page load")
            }
        }
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun buildFetchBytesJs(id: String, url: String): String {
        return """
            (async function() {
                try {
                    const response = await fetch('${escapeJsString(url)}');
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

    private fun buildFetchTextJs(id: String, url: String): String {
        return """
            (async function() {
                try {
                    const response = await fetch('${escapeJsString(url)}');
                    if (!response.ok) { Android.onError('$id', 'HTTP ' + response.status); return; }
                    const text = await response.text();
                    Android.onResult('$id', text);
                } catch(e) { Android.onError('$id', e.message); }
            })();
        """.trimIndent()
    }

    private fun buildPostJsonJs(id: String, url: String, jsonBody: String): String {
        return """
            (async function() {
                try {
                    const response = await fetch('${escapeJsString(url)}', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                        body: '${escapeJsString(jsonBody)}'
                    });
                    if (!response.ok) { Android.onError('$id', 'HTTP ' + response.status); return; }
                    const text = await response.text();
                    Android.onResult('$id', text);
                } catch(e) { Android.onError('$id', e.message); }
            })();
        """.trimIndent()
    }

    fun destroy() {
        webViewReady = false
        mainHandler.post {
            try {
                webView?.destroy()
            } catch (e: Exception) {
            }
            webView = null
        }
        pendingRequests.clear()
    }

    fun fetchBytes(url: String, timeoutMs: Long = 60000): ByteArray {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = ByteRequestState()
        pendingRequests[id] = state
        
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
            var total = ByteArray(0)
            for (chunk in state.chunks) {
                total += chunk
            }
            return total
        }
    }

    fun fetchText(url: String, timeoutMs: Long = 30000): String {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = TextRequestState()
        pendingRequests[id] = state
        
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
        return state.textResult ?: throw RuntimeException("WebViewFetcher: no text result")
    }

    fun postJson(url: String, jsonBody: String, timeoutMs: Long = 30000): String {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = TextRequestState()
        pendingRequests[id] = state
        
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
        return state.textResult ?: throw RuntimeException("WebViewFetcher: no postJson result")
    }
}
