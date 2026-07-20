package eu.kanade.tachiyomi.animeextension.en.zorotv.extractor

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CloudFlareBypassResult(
    val cookies: String,
    val userAgent: String,
)

class CloudflareBypass {

    private val defaultUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun getCookies(pageUrl: String, customUserAgent: String? = null): CloudFlareBypassResult? {
        clearCookiesForUrl(pageUrl)

        val latch = CountDownLatch(1)
        var result: CloudFlareBypassResult? = null
        var webView: WebView? = null
        val cancelled = AtomicBoolean(false)

        val userAgentToUse = customUserAgent ?: defaultUA

        Handler(Looper.getMainLooper()).post {
            webView = WebView(applicationContext)
            webView?.settings?.javaScriptEnabled = true
            webView?.settings?.domStorageEnabled = true
            webView?.settings?.userAgentString = userAgentToUse

            webView?.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    pollForClearance(pageUrl, userAgentToUse, cancelled) { bypassResult ->
                        result = bypassResult
                        latch.countDown()
                    }
                }
            }

            CookieManager.getInstance().setCookie(pageUrl, "")
            webView?.loadUrl(pageUrl)
        }

        try {
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            cancelled.set(true)
            Handler(Looper.getMainLooper()).post {
                webView?.destroy()
            }
        }

        return result
    }

    private fun pollForClearance(
        url: String,
        userAgent: String,
        cancelled: AtomicBoolean,
        onComplete: (CloudFlareBypassResult) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startTime = System.currentTimeMillis()
        val maxDurationMs = 30_000L
        val pollIntervalMs = 500L

        val runnable = object : Runnable {
            override fun run() {
                if (cancelled.get()) return

                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= maxDurationMs) return

                val cookies = CookieManager.getInstance().getCookie(url)

                if (cookies?.contains("cf_clearance=") == true) {
                    val finalResult = CloudFlareBypassResult(cookies, userAgent)
                    onComplete(finalResult)
                } else {
                    handler.postDelayed(this, pollIntervalMs)
                }
            }
        }
        handler.post(runnable)
    }

    private fun clearCookiesForUrl(pageUrl: String) {
        val domain = Uri.parse(pageUrl).host ?: return
        val cookieManager = CookieManager.getInstance()

        listOf("https://\$domain", "https://www.\$domain").forEach { url ->
            cookieManager.getCookie(url)?.split(";")?.forEach { cookieStr ->
                val cookieName = cookieStr.substringBefore("=").trim()
                if (cookieName.isNotEmpty()) {
                    cookieManager.setCookie(url, "\$cookieName=; Max-Age=0; path=/")
                    cookieManager.setCookie(url, "\$cookieName=; Max-Age=0; path=/; domain=.\$domain")
                }
            }
        }
        cookieManager.flush()
    }
}
