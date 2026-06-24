package eu.kanade.tachiyomi.animeextension.en.reanime

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ReanimeCloudflareResult(
    val cookies: String,
    val userAgent: String,
)

class ReanimeCloudflareBypass {

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/137.0.0.0 Mobile Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun getCookies(pageUrl: String, userAgent: String = DEFAULT_UA): ReanimeCloudflareResult? {
        clearCookiesForUrl(pageUrl)

        val latch = CountDownLatch(1)
        var result: ReanimeCloudflareResult? = null
        var webView: WebView? = null
        val cancelled = AtomicBoolean(false)

        Handler(Looper.getMainLooper()).post {
            webView = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = userAgent

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        pollForClearance(pageUrl, userAgent, cancelled) { bypassResult ->
                            result = bypassResult
                            latch.countDown()
                        }
                    }
                }
                loadUrl(pageUrl)
            }
        }

        try {
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            cancelled.set(true)
            Handler(Looper.getMainLooper()).post {
                webView?.stopLoading()
                webView?.destroy()
            }
        }

        return result
    }

    private fun pollForClearance(
        url: String,
        userAgent: String,
        cancelled: AtomicBoolean,
        onComplete: (ReanimeCloudflareResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startTime = System.currentTimeMillis()
        val timeoutMs = 30_000L

        val runnable = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                if (System.currentTimeMillis() - startTime >= timeoutMs) return

                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies?.contains("cf_clearance=") == true) {
                    onComplete(ReanimeCloudflareResult(cookies, userAgent))
                } else {
                    handler.postDelayed(this, 500L)
                }
            }
        }

        handler.post(runnable)
    }

    private fun clearCookiesForUrl(pageUrl: String) {
        val domain = Uri.parse(pageUrl).host ?: return
        val cookieManager = CookieManager.getInstance()

        listOf("https://$domain", "https://www.$domain").forEach { url ->
            cookieManager.getCookie(url)?.split(";")?.forEach { cookieStr ->
                val cookieName = cookieStr.substringBefore("=").trim()
                if (cookieName.isNotEmpty()) {
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/")
                    cookieManager.setCookie(url, "$cookieName=; Max-Age=0; path=/; domain=.$domain")
                }
            }
        }
        cookieManager.flush()
    }
}
