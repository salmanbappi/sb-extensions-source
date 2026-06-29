package eu.kanade.tachiyomi.animeextension.all.toonhub4u

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.buzzheavierextractor.BuzzheavierExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

class Toonhub4u :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Toonhub4u"
    override val baseUrl = "https://toonhub4u.co"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5182749372810482937L

    private val localProxy by lazy { LocalProxy(client) }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/home/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("li.post-item").mapNotNull { element ->
            try {
                val titleEl = element.selectFirst("h2.post-title a") ?: return@mapNotNull null
                val titleText = titleEl.text().trim()
                val cleanTitle = titleText.substringBefore("[").trim()

                SAnime.create().apply {
                    title = cleanTitle
                    setUrlWithoutDomain(titleEl.attr("href"))

                    val img = element.selectFirst("a.post-thumb img")
                    thumbnail_url = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                null
            }
        }
        val hasNextPage = document.select("ul.pages-numbers li.the-next-page").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        GET("$baseUrl/page/$page/?s=$query", headers)
    } else {
        var path = ""
        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state > 0) {
                        path = categoryPaths[filter.state]
                        break
                    }
                }

                is GenreFilter -> {
                    if (filter.state > 0) {
                        path = genrePaths[filter.state]
                        break
                    }
                }

                is LanguageFilter -> {
                    if (filter.state > 0) {
                        path = languagePaths[filter.state]
                        break
                    }
                }

                is QualityFilter -> {
                    if (filter.state > 0) {
                        path = qualityPaths[filter.state]
                        break
                    }
                }

                is OttFilter -> {
                    if (filter.state > 0) {
                        path = ottPaths[filter.state]
                        break
                    }
                }

                else -> {}
            }
        }
        if (path.isNotBlank()) {
            GET("$baseUrl/$path/page/$page/", headers)
        } else {
            GET("$baseUrl/page/$page/", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            val titleText = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1.entry-title")?.text()
                ?: ""
            title = titleText.substringBefore("[").trim()

            description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?: document.select(".entry-content p").firstOrNull()?.text()

            val ogImg = document.selectFirst("meta[property=og:image]")?.attr("content")
            val mainImg = document.selectFirst(".entry-content img")?.attr("src")
            thumbnail_url = ogImg ?: mainImg

            genre = document.select(".post-cats a").joinToString(", ") { it.text() }
            status = SAnime.COMPLETED
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val entryContent = document.selectFirst(".entry-content") ?: return emptyList()
        val pTags = entryContent.select("p, h4, h3, h2")
        val hasEpisodes = pTags.any { it.text().contains("Episode", ignoreCase = true) }

        if (hasEpisodes) {
            var episodeCount = 1
            pTags.forEach { pTag ->
                val text = pTag.text().trim()
                val episodeMatch = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
                if (episodeMatch != null) {
                    val episodeNumber = episodeMatch.groupValues[1].toFloatOrNull() ?: episodeCount.toFloat()
                    val episodeLinks = mutableListOf<String>()

                    var nextSibling = pTag.nextElementSibling()
                    while (nextSibling != null && nextSibling.tagName() != "hr") {
                        nextSibling.select("a[href]").forEach { aTag ->
                            val href = aTag.attr("href")
                            if (href.contains("gdmirrorbot") || href.contains("iqsmartgames")) {
                                episodeLinks.add(href.replace("/file/", "/embed/"))
                            }
                        }
                        nextSibling = nextSibling.nextElementSibling()
                    }

                    if (episodeLinks.isNotEmpty()) {
                        episodes.add(
                            SEpisode.create().apply {
                                name = text
                                episode_number = episodeNumber
                                url = episodeLinks.joinToString(",")
                            },
                        )
                        episodeCount++
                    }
                }
            }
        } else {
            val movieLinks = entryContent.select("div.mks_toggle_content a[href], .entry-content p a[href]").mapNotNull { aTag ->
                val href = aTag.attr("href")
                if (href.contains("gdmirrorbot") || href.contains("iqsmartgames")) {
                    href.replace("/file/", "/embed/")
                } else {
                    null
                }
            }.distinct()

            if (movieLinks.isNotEmpty()) {
                episodes.add(
                    SEpisode.create().apply {
                        name = "Movie"
                        episode_number = 1f
                        url = movieLinks.joinToString(",")
                    },
                )
            }
        }

        return episodes.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val embedUrls = episode.url.split(",")

        return embedUrls.parallelCatchingFlatMap { embedUrl ->
            val videoList = mutableListOf<Video>()
            try {
                val embedResponse = client.newCall(GET(embedUrl, headers)).execute()
                val finalUrl = embedResponse.request.url.toString()
                embedResponse.close()

                val sid = embedUrl.substringAfterLast("embed/").substringBefore("?").substringBefore("/")
                val hostUri = Uri.parse(finalUrl)
                val host = "${hostUri.scheme}://${hostUri.host}"

                val formBody = FormBody.Builder()
                    .add("sid", sid)
                    .build()

                val helperRequest = Request.Builder()
                    .url("$host/embedhelper.php")
                    .post(formBody)
                    .header("User-Agent", headers["User-Agent"] ?: "")
                    .header("Referer", finalUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()

                val helperResponse = client.newCall(helperRequest).execute()
                val helperBody = helperResponse.body.string()
                helperResponse.close()

                val jsonObject = Json.parseToJsonElement(helperBody).jsonObject
                val siteUrls = jsonObject["siteUrls"]?.jsonObject ?: emptyMap()
                val siteFriendlyNames = jsonObject["siteFriendlyNames"]?.jsonObject ?: emptyMap()
                val mresultElement = jsonObject["mresult"]

                val mresultString = when {
                    mresultElement == null -> null

                    mresultElement is JsonPrimitive && mresultElement.isString -> {
                        val base64Str = mresultElement.content
                        try {
                            String(Base64.decode(base64Str, Base64.DEFAULT), Charsets.UTF_8)
                        } catch (e: Exception) {
                            base64Str
                        }
                    }

                    else -> mresultElement.toString()
                }

                val mresultObject = if (!mresultString.isNullOrBlank()) {
                    Json.parseToJsonElement(mresultString).jsonObject
                } else {
                    null
                }

                if (mresultObject != null) {
                    for ((key, pathElement) in mresultObject) {
                        val path = pathElement.jsonPrimitive.content.trimStart('/')
                        val base = siteUrls[key]?.jsonPrimitive?.content?.trimEnd('/') ?: continue
                        val fullUrl = "$base/$path"
                        val friendlyName = siteFriendlyNames[key]?.jsonPrimitive?.content ?: key

                        try {
                            when {
                                friendlyName.equals("FileMoon", ignoreCase = true) || friendlyName.equals("Fmoon", ignoreCase = true) -> {
                                    videoList.addAll(FilemoonExtractor(client).videosFromUrl(fullUrl, "FileMoon - "))
                                }

                                friendlyName.contains("Streamwish", ignoreCase = true) || friendlyName.contains("Cdnwish", ignoreCase = true) || friendlyName.contains("Wish", ignoreCase = true) -> {
                                    videoList.addAll(StreamWishExtractor(client, headers).videosFromUrl(fullUrl, "StreamWish"))
                                }

                                friendlyName.contains("Vidhide", ignoreCase = true) || friendlyName.contains("Animezia", ignoreCase = true) || friendlyName.contains("StreamHG", ignoreCase = true) || friendlyName.contains("EarnVids", ignoreCase = true) -> {
                                    videoList.addAll(VidHideExtractor(client, headers).videosFromUrl(fullUrl) { "VidHide - $it" })
                                }

                                friendlyName.equals("Buzzheavier", ignoreCase = true) -> {
                                    videoList.addAll(BuzzheavierExtractor(client, headers).videosFromUrl(fullUrl, "Buzzheavier - "))
                                }

                                friendlyName.contains("StreamP2p", ignoreCase = true) || friendlyName.contains("RpmShare", ignoreCase = true) || friendlyName.contains("UpnShare", ignoreCase = true) -> {
                                    videoList.addAll(StreamP2PExtractor(client, headers).videosFromUrl(fullUrl, friendlyName))
                                }
                            }
                        } catch (e: Exception) {
                            // ignore error
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore error
            }
            videoList
        }.map { video ->
            if (video.videoUrl.contains(".m3u8") || video.videoUrl.contains("mpegurl")) {
                Video(
                    videoUrl = localProxy.getProxyUrl(video.videoUrl, video.headers),
                    videoTitle = video.videoTitle,
                    headers = video.headers,
                    subtitleTracks = video.subtitleTracks,
                )
            } else {
                video
            }
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(quality) },
                { it.videoTitle.contains("In-House") },
                { it.videoTitle.contains("Cloudflare") },
                { it.videoTitle.contains("Tiktok") },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
        GenreFilter(),
        LanguageFilter(),
        QualityFilter(),
        OttFilter(),
    )

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Category/Type",
            arrayOf(
                "All",
                "Animated",
                "Animated Series",
                "Animated Movies",
                "Anime Series",
                "Anime Movies",
                "Cartoon Network",
                "Disney XD India",
                "Disney",
                "Disney Channel India",
                "Hungama",
                "Just Kids Sahara TV",
                "Marvel HQ",
                "Zee Cafe",
                "Sony Yay",
                "Nick India",
                "Sonic Nickelodeon",
                "ETV Bal Bharat",
                "Big Magic",
                "Kids Zone Plus",
            ),
        )

    private val categoryPaths = arrayOf(
        "",
        "category/animated",
        "category/animated/animated-series",
        "category/animated/animation-movies",
        "category/anime/anime-series",
        "category/anime/anime-movies",
        "category/channel-list/cartoon-network",
        "category/channel-list/disney-xd-india",
        "category/channel-list/disney",
        "category/channel-list/disny-channel-india",
        "category/channel-list/hungama",
        "category/channel-list/just-kids-sahara-tv",
        "category/channel-list/marvel-hq",
        "category/channel-list/zee-cafe",
        "category/channel-list/sony-yay",
        "category/channel-list/nick-india",
        "category/channel-list/sonic-nickelodean",
        "category/channel-list/etv-bal-bharat",
        "category/gener/big-magic",
        "category/channel-list/kinds-zone-pluse",
    )

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Genre",
            arrayOf(
                "All",
                "Action",
                "Adventure",
                "Family",
                "Ecchi",
                "Shounen",
                "Supernatural",
                "Romance",
                "Sci-Fi",
                "Thriller",
                "Fantasy",
                "Comedy",
                "Drama",
                "Horror",
                "Magical Animated",
                "Martial Arts",
                "Mystery",
                "Harem",
                "18+",
                "Hentai",
            ),
        )

    private val genrePaths = arrayOf(
        "",
        "category/gener/action",
        "category/gener/advanture",
        "category/gener/family",
        "category/gener/ecchi",
        "category/gener/shounen",
        "category/gener/supernatural",
        "category/gener/romance",
        "category/gener/sci-fic",
        "category/gener/thriller",
        "category/gener/fantasy",
        "category/gener/comedy",
        "category/gener/drama-gener",
        "category/gener/horror",
        "category/gener/magical-animated",
        "category/gener/martial-arts",
        "category/gener/mystery",
        "category/gener/harem",
        "category/gener/18",
        "category/gener/hentai",
    )

    private class LanguageFilter :
        AnimeFilter.Select<String>(
            "Language",
            arrayOf(
                "All",
                "Hindi",
                "Tamil",
                "Telugu",
                "Malayalam",
                "Kannada",
                "Urdu Dub",
                "Hindi Sub",
                "Fan Dub",
                "English",
                "Dual Audio",
                "Multi Audio",
            ),
        )

    private val languagePaths = arrayOf(
        "",
        "category/language/hindi",
        "category/language/tamil",
        "category/language/telugu",
        "category/language/malayalam",
        "category/language/kannada",
        "category/language/urdu-dub",
        "category/language/hindi-sub",
        "category/language/fan-dub",
        "category/language/english",
        "category/language/dual-audio",
        "category/language/multi-audio",
    )

    private class QualityFilter :
        AnimeFilter.Select<String>(
            "Quality",
            arrayOf(
                "All",
                "1080p",
                "720p",
                "480p",
                "576p",
                "360p",
            ),
        )

    private val qualityPaths = arrayOf(
        "",
        "category/quality/1080p",
        "category/quality/720p",
        "category/quality/480p",
        "category/quality/576p",
        "category/quality/360p",
    )

    private class OttFilter :
        AnimeFilter.Select<String>(
            "OTT Network",
            arrayOf(
                "All",
                "Crunchyroll",
                "AnimeTimes",
                "Ani-One India",
                "Amazon Prime Video",
                "Netflix",
                "Jio Cinema",
                "Zee5",
                "Apple TV",
                "Hotstar",
            ),
        )

    private val ottPaths = arrayOf(
        "",
        "category/ott-network/crunchyroll",
        "category/ott-network/animetimes",
        "category/ott-network/ani-one-india",
        "category/ott-network/amazon-prime-video",
        "category/ott-network/netflix",
        "category/ott-network/jio-cinema",
        "category/ott-network/zee5",
        "category/ott-network/apple-tv",
        "category/ott-network/hotstar-2",
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }

    class StreamP2PExtractor(private val client: OkHttpClient, private val headers: Headers) {
        fun videosFromUrl(url: String, prefix: String = "StreamP2P"): List<Video> {
            val strmp2Id = url.substringAfterLast("embed/").substringAfterLast("/").substringBefore("?").substringBefore("#")
            val apiHost = "https://cloudy.p2pplay.pro"
            val apiUrl = "$apiHost/api/v1/video?id=$strmp2Id&w=1920&h=1080&r=pro.iqsmartgames.com"

            val reqHeaders = headers.newBuilder()
                .set("Referer", "https://clswine.strp2p.com/")
                .set("Origin", "https://clswine.strp2p.com")
                .build()

            val response = client.newCall(GET(apiUrl, reqHeaders)).execute()
            if (response.code != 200) {
                response.close()
                return emptyList()
            }
            val encryptedHex = response.body.string().trim()
            response.close()

            val decryptedJson = tryDecrypt(encryptedHex) ?: return emptyList()
            val jsonObject = Json.parseToJsonElement(decryptedJson).jsonObject
            val streamingConfigStr = jsonObject["streamingConfig"]?.jsonPrimitive?.content ?: return emptyList()
            val streamingConfig = Json.parseToJsonElement(streamingConfigStr).jsonObject
            val order = streamingConfig["order"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val adjust = streamingConfig["adjust"]?.jsonObject ?: emptyMap()

            val videoList = mutableListOf<Video>()

            fun addVideo(streamPath: String, hostName: String, params: Map<String, String>) {
                val base = if (streamPath.startsWith("//")) {
                    "https:$streamPath"
                } else if (streamPath.startsWith("http")) {
                    streamPath
                } else {
                    "$apiHost/${streamPath.trimStart('/')}"
                }

                val builder = base.toHttpUrlOrNull()?.newBuilder() ?: return
                params.forEach { (k, v) ->
                    builder.setQueryParameter(k, v)
                }
                val finalUrl = builder.build().toString()

                val subtitleTracks = mutableListOf<Track>()
                jsonObject["subtitle"]?.jsonObject?.forEach { (lang, subPathElement) ->
                    val subPath = subPathElement.jsonPrimitive.content.substringBefore("#")
                    val subUrl = if (subPath.startsWith("http")) subPath else "$apiHost/${subPath.trimStart('/')}"
                    subtitleTracks.add(Track(subUrl, lang))
                }

                videoList.add(
                    Video(
                        videoUrl = finalUrl,
                        videoTitle = "$prefix - $hostName",
                        headers = reqHeaders,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }

            order.forEach { host ->
                val hostConfig = adjust[host]?.jsonObject
                val disabled = hostConfig?.get("disabled")?.jsonPrimitive?.booleanOrNull ?: false
                if (disabled) return@forEach

                val rawParams = hostConfig?.get("params")
                val params = mutableMapOf<String, String>()
                if (rawParams != null && rawParams is JsonObject) {
                    rawParams.forEach { (k, v) ->
                        params[k] = v.jsonPrimitive.content
                    }
                }

                when (host) {
                    "Cloudflare" -> {
                        val cfPath = jsonObject["cf"]?.jsonPrimitive?.contentOrNull
                        if (!cfPath.isNullOrBlank()) {
                            addVideo(cfPath, "Cloudflare", params)
                        }
                    }

                    "Tiktok" -> {
                        val tiktokPath = jsonObject["hlsVideoTiktok"]?.jsonPrimitive?.contentOrNull
                        if (!tiktokPath.isNullOrBlank()) {
                            addVideo(tiktokPath, "Tiktok", params)
                        }
                    }

                    "Google" -> {
                        val googlePath = jsonObject["hlsVideoGoogle"]?.jsonPrimitive?.contentOrNull
                        if (!googlePath.isNullOrBlank()) {
                            addVideo(googlePath, "Google", params)
                        }
                    }

                    "In-House" -> {
                        val sourcePath = jsonObject["source"]?.jsonPrimitive?.contentOrNull
                        if (!sourcePath.isNullOrBlank()) {
                            addVideo(sourcePath, "In-House", params)
                        }
                    }
                }
            }

            return videoList
        }

        private fun tryDecrypt(encryptedHex: String): String? {
            val key = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
            val ivs = listOf("1234567890oiuytr", "0123456789abcdef")

            for (ivStr in ivs) {
                try {
                    val iv = ivStr.toByteArray(Charsets.UTF_8)
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
                    val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)

                    val encryptedBytes = encryptedHex.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()

                    val decryptedBytes = cipher.doFinal(encryptedBytes)
                    val decrypted = String(decryptedBytes, Charsets.UTF_8)
                    if (decrypted.contains("streamingConfig")) {
                        return decrypted
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
            return null
        }
    }
}

class LocalProxy(private val client: okhttp3.OkHttpClient) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    var port: Int = 0
        private set

    private val segmentCache = ConcurrentHashMap<String, ByteArray>()
    private val cacheOrder = Collections.synchronizedList(mutableListOf<String>())
    private val fetching = ConcurrentHashMap<String, Boolean>()
    private val playlistSegments = ConcurrentHashMap<String, List<String>>()

    init {
        try {
            serverSocket = ServerSocket(0)
            port = serverSocket!!.localPort
            executor.execute {
                while (serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket!!.accept()
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    fun getProxyUrl(targetUrl: String, headers: okhttp3.Headers?): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val headersStr = headers?.let { h ->
            val sb = StringBuilder()
            for (i in 0 until h.size) {
                sb.append(h.name(i)).append(":").append(h.value(i)).append("\n")
            }
            sb.toString()
        } ?: ""
        val encodedHeaders = Base64.encodeToString(headersStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun buildHeaders(encodedHeaders: String): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        if (encodedHeaders.isNotEmpty()) {
            val headersStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            headersStr.split("\n").forEach { line ->
                val headerParts = line.split(":", limit = 2)
                if (headerParts.size == 2) {
                    builder.set(headerParts[0].trim(), headerParts[1].trim())
                }
            }
        }
        return builder.build()
    }

    private fun cacheSegment(key: String, data: ByteArray) {
        while (segmentCache.size >= 100) {
            val oldest = synchronized(cacheOrder) {
                if (cacheOrder.isNotEmpty()) cacheOrder.removeAt(0) else null
            } ?: break
            segmentCache.remove(oldest)
        }
        segmentCache[key] = data
        synchronized(cacheOrder) {
            cacheOrder.remove(key)
            cacheOrder.add(key)
        }
    }

    private fun triggerPrefetch(playlistUrl: String, currentIndex: Int, encodedHeaders: String) {
        val segments = playlistSegments[playlistUrl] ?: return
        val prefetchAhead = 5
        val maxIndex = min(currentIndex + prefetchAhead, segments.size - 1)
        val targetHeaders = buildHeaders(encodedHeaders)

        for (i in (currentIndex + 1)..maxIndex) {
            val segmentUrl = segments[i]
            val cacheKey = segmentUrl
            if (!segmentCache.containsKey(cacheKey) && fetching[cacheKey] != true) {
                fetching[cacheKey] = true
                executor.execute {
                    try {
                        val request = Request.Builder().url(segmentUrl).headers(targetHeaders).build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val rawBytes = response.body?.bytes()
                                if (rawBytes != null) {
                                    val stripped = stripPngHeader(rawBytes)
                                    cacheSegment(cacheKey, stripped)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        fetching.remove(cacheKey)
                    }
                }
            }
        }
    }

    private fun findPlaylistAndTriggerPrefetch(segmentUrl: String, encodedHeaders: String) {
        for ((playlistUrl, segments) in playlistSegments) {
            val idx = segments.indexOf(segmentUrl)
            if (idx != -1) {
                triggerPrefetch(playlistUrl, idx, encodedHeaders)
                break
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            if (!path.startsWith("/proxy")) {
                sendError(socket, 404, "Not Found")
                return
            }

            val httpUrl = ("http://127.0.0.1$path").toHttpUrl()
            val encodedUrl = httpUrl.queryParameter("url")
            val encodedHeaders = httpUrl.queryParameter("headers") ?: ""

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val isM3u8Request = targetUrl.contains(".m3u8") || path.contains("playlist.m3u8")

            if (!isM3u8Request) {
                val cached = segmentCache[targetUrl]
                if (cached != null) {
                    sendCachedResponse(socket, cached)
                    findPlaylistAndTriggerPrefetch(targetUrl, encodedHeaders)
                    return
                }

                if (fetching[targetUrl] == true) {
                    var waited = 0
                    while (fetching[targetUrl] == true && waited < 10000) {
                        Thread.sleep(50L)
                        waited += 50
                    }
                    val waitedCached = segmentCache[targetUrl]
                    if (waitedCached != null) {
                        sendCachedResponse(socket, waitedCached)
                        findPlaylistAndTriggerPrefetch(targetUrl, encodedHeaders)
                        return
                    }
                }
            }

            val targetHeaders = buildHeaders(encodedHeaders).newBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val name = headerParts[0].trim()
                    val value = headerParts[1].trim()
                    if (name.equals("Range", ignoreCase = true) && !isM3u8Request) {
                        targetHeaders.set(name, value)
                    }
                }
            }

            fetching[targetUrl] = true
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .headers(targetHeaders.build())
                    .build()

                client.newCall(request).execute().use { response ->
                    sendResponse(socket, response, targetUrl, encodedHeaders)
                }
            } finally {
                fetching.remove(targetUrl)
            }
        } catch (e: Exception) {
            try {
                sendError(socket, 500, e.message ?: "Internal Error")
            } catch (_: Exception) {}
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendCachedResponse(socket: Socket, bytes: ByteArray) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        out.write("Content-Type: video/mp2t\r\n".toByteArray())
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun sendResponse(socket: Socket, response: Response, targetUrl: String, encodedHeaders: String) {
        val out = socket.getOutputStream()
        val isM3u8 = targetUrl.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl") == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            val bodyString = response.body.string()
            val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
            modifiedContentBytes = modifiedContent.toByteArray()
        }

        out.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())

        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                (name.equals("Content-Length", ignoreCase = true) && isM3u8)
            ) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray())
        }

        if (isM3u8 && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray())
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(modifiedContentBytes)
        } else {
            out.write("Content-Type: video/mp2t\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())

            val rawBytes = response.body.bytes()
            val stripped = stripPngHeader(rawBytes)
            cacheSegment(targetUrl, stripped)
            out.write(stripped)
            findPlaylistAndTriggerPrefetch(targetUrl, encodedHeaders)
        }
        out.flush()
    }

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)
        val segmentsList = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = resolveUrl(playlistUrl, uriValue)
                        val proxiedUri = getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                val resolvedUri = resolveUrl(playlistUrl, trimmed)
                segmentsList.add(resolvedUri)
                builder.append(getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders))
            }
            builder.append("\n")
        }

        if (segmentsList.isNotEmpty()) {
            playlistSegments[playlistUrl] = segmentsList
            triggerPrefetch(playlistUrl, -1, encodedHeaders)
        }

        return builder.toString()
    }

    private fun getProxyUrlWithEncodedHeaders(targetUrl: String, encodedHeaders: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun stripPngHeader(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        val isPng = data[0] == (-119).toByte() && data[1] == 80.toByte() && data[2] == 78.toByte() && data[3] == 71.toByte()
        if (!isPng) return data
        var videoStart = -1
        val length = data.size - 4
        for (i in 0 until length) {
            if (data[i] == 73.toByte() && data[i + 1] == 69.toByte() && data[i + 2] == 78.toByte() && data[i + 3] == 68.toByte()) {
                videoStart = i + 8
                break
            }
        }
        if (videoStart < 0 || videoStart >= data.size) return data
        val tsData = data.copyOfRange(videoStart, data.size)
        val iMin = min(tsData.size - 188, 400)
        for (offset in 0 until iMin) {
            if (tsData[offset] == 0x47.toByte() && tsData[offset + 188] == 0x47.toByte()) {
                return tsData.copyOfRange(offset, tsData.size)
            }
        }
        return tsData
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray())
        out.write("Content-Type: text/plain\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write(message.toByteArray())
        out.flush()
    }
}
