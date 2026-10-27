package eu.kanade.tachiyomi.animeextension.all.animex

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Response

const val ANIMEX_API_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * Handles the _amx_id antibot session cookie for pp.animex.one API endpoints.
 */
class AnimexInterceptor(private val cookieJar: CookieJar) : Interceptor {

    companion object {
        private const val API_HOST = "pp.animex.one"

        private fun hasSession(cookies: List<Cookie>): Boolean =
            cookies.any { it.name == "_amx_id" || it.name == "animex_session" }

        private fun parseSessionCookie(header: String, url: okhttp3.HttpUrl): Cookie? =
            Cookie.parse(url, header)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val isApiCall = originalRequest.url.host == API_HOST &&
            originalRequest.url.encodedPath.contains("/api/")

        val request = if (isApiCall) {
            originalRequest.newBuilder()
                .header("User-Agent", ANIMEX_API_UA)
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(request)

        if (isApiCall && response.code == 403 && request.header("X-Animex-Retry") == null) {
            synchronized(this) {
                val setCookieHeaders = response.headers("Set-Cookie")
                val sessionHeaders = setCookieHeaders.filter {
                    it.contains("_amx_id") || it.contains("animex_session")
                }

                if (sessionHeaders.isNotEmpty()) {
                    val cookiesToSave = sessionHeaders.mapNotNull {
                        parseSessionCookie(it, request.url)
                    }
                    if (cookiesToSave.isNotEmpty()) {
                        try {
                            cookieJar.saveFromResponse(request.url, cookiesToSave)
                        } catch (_: Exception) {}
                    }

                    response.close()
                    val retryRequest = request.newBuilder()
                        .header("X-Animex-Retry", "true")
                        .build()
                    return chain.proceed(retryRequest)
                }

                val jarCookies = cookieJar.loadForRequest(request.url)
                if (hasSession(jarCookies)) {
                    response.close()
                    val retryRequest = request.newBuilder()
                        .header("X-Animex-Retry", "true")
                        .build()
                    return chain.proceed(retryRequest)
                }
            }
        }

        return response
    }
}
