package eu.kanade.tachiyomi.animeextension.en.moviesmod

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class RedirectorBypasser(private val client: OkHttpClient, private val headers: Headers) {

    fun bypass(url: String): String? {
        val lastDoc = runCatching {
            client.newCall(GET(url, headers)).execute().let { recursiveDoc(it.asJsoup()) }
        }.getOrNull() ?: return null

        val script = lastDoc.selectFirst("script:containsData(/?go=):containsData(href)")?.data()
            ?: lastDoc.selectFirst("script:containsData(s_343)")?.data()
            ?: return null

        val nextUrl = script.substringAfter("\"href\",\"", "").substringBefore('"', "")
            .ifBlank {
                val cName = Regex("""s_343\s*\(\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.get(1)
                if (cName != null) "https://cloud.unblockedgames.world/?go=$cName" else null
            } ?: return null

        val httpUrl = nextUrl.toHttpUrlOrNull() ?: return null
        val cookieName = httpUrl.queryParameter("go") ?: return null
        val cookieValue = Regex("""s_343\s*\(\s*['"][^'"]+['"]\s*,\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.get(1)
            ?: script.substringAfter("'$cookieName', '").substringBefore("'")

        val cookie = Cookie.parse(httpUrl, "$cookieName=$cookieValue") ?: return null
        val requestHeaders = headers.newBuilder().set("Referer", lastDoc.location()).build()

        val doc = runBlocking(Dispatchers.IO) {
            MUTEX.withLock {
                client.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
                client.newCall(GET(nextUrl, requestHeaders)).execute().asJsoup()
            }
        }

        return doc.selectFirst("meta[http-equiv]")?.attr("content")
            ?.substringAfter("url=", "")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun recursiveDoc(doc: Document): Document {
        val form = doc.selectFirst("form#landing") ?: doc.selectFirst("form[action]") ?: return doc
        val url = form.attr("abs:action").ifBlank { form.attr("action") }
        if (url.isBlank()) return doc

        val body = FormBody.Builder().apply {
            form.select("input[name]").forEach {
                add(it.attr("name"), it.attr("value"))
            }
        }.build()

        val requestHeaders = headers.newBuilder()
            .set("Referer", doc.location())
            .set("Origin", "https://cloud.unblockedgames.world")
            .build()

        val nextResp = client.newCall(POST(url, requestHeaders, body)).execute()
        val nextDoc = nextResp.asJsoup()

        // If nextDoc has script with redirect or cookie, stop recursion
        if (nextDoc.selectFirst("script:containsData(s_343)") != null || nextDoc.selectFirst("script:containsData(/?go=)") != null) {
            return nextDoc
        }

        return recursiveDoc(nextDoc)
    }

    companion object {
        private val MUTEX by lazy { Mutex() }
    }
}
