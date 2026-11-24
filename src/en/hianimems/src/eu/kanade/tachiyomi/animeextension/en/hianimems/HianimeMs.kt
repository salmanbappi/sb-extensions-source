package eu.kanade.tachiyomi.animeextension.en.hianimems

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

class HianimeMs : Source() {

    override val name = "HiAnime (ms)"

    override val baseUrl = "https://hianime.ms"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val proxyServer by lazy { HianimeMsProxyServer(client) }
    private val secureRandom by lazy { SecureRandom() }

    @Volatile
    private var megaPlayKey: ByteArray = DEFAULT_MEGAPLAY_KEY

    @Volatile
    private var megaPlayIv: ByteArray = DEFAULT_MEGAPLAY_IV

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", USER_AGENT)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/most-popular?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/updates?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            "$baseUrl/search?keyword=$encodedQuery&page=$page"
        } else {
            "$baseUrl/browse?page=$page"
        }
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.hk-tile").mapNotNull { element ->
            val link = element.selectFirst("h3.hk-tile__name a") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img = element.selectFirst("div.hk-tile__art img")

            SAnime.create().apply {
                title = link.selectFirst("span.hk-dual")?.attr("data-en")
                    ?.takeIf { it.isNotBlank() }
                    ?: link.text().trim()
                setUrlWithoutDomain(href)
                thumbnail_url = img?.attr("src")?.takeIf { it.isNotBlank() } ?: img?.attr("data-src")
                fetch_type = FetchType.Episodes
            }
        }
        val hasNext = doc.selectFirst("ul.pagination li.page-item a[aria-label=Next]") != null
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()

        val titleText = doc.selectFirst("h1.hk-title__h")?.text()?.trim() ?: anime.title
        val coverImg = doc.selectFirst("div.hk-title__art img")?.attr("src") ?: anime.thumbnail_url
        val synopsis = doc.selectFirst("div.hk-clamp[data-hk-clamp], div[itemprop=description]")?.text()?.trim() ?: ""
        val genres = doc.select("div.hk-facts__genres a.hk-chip").joinToString { it.text().trim() }
        val statusText = doc.selectFirst("span.hk-tag--live, div.hk-pfbar")?.text() ?: ""

        return SAnime.create().apply {
            title = titleText
            thumbnail_url = coverImg
            description = synopsis
            genre = genres
            status = when {
                statusText.contains("Airing", ignoreCase = true) || statusText.contains("RELEASING", ignoreCase = true) -> SAnime.ONGOING
                statusText.contains("FINISHED", ignoreCase = true) || statusText.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val epElements = doc.select("section#hk-episodes a.hk-ep")

        if (epElements.isNotEmpty()) {
            return epElements.mapIndexed { idx, element ->
                SEpisode.create().apply {
                    val rawHref = element.attr("href").trim()
                    setUrlWithoutDomain(rawHref.ifBlank { "${anime.url}#ep=${idx + 1}" })

                    val epNumRaw = element.attr("data-ep").trim()
                    val epNum = epNumRaw.toFloatOrNull() ?: (idx + 1).toFloat()
                    episode_number = epNum

                    val epLabelNum = epNumRaw.ifBlank { (idx + 1).toString() }
                    val rawTitle = element.attr("title").trim()
                    val rawDataName = element.attr("data-name").trim()
                    name = when {
                        rawTitle.isNotBlank() -> rawTitle
                        rawDataName.isNotBlank() -> "Episode $epLabelNum: $rawDataName"
                        else -> "Episode $epLabelNum"
                    }

                    val isFiller = element.hasClass("hk-ep--filler")
                    val hasDub = element.selectFirst("span.hk-ep__dub") != null
                    scanlator = when {
                        isFiller && hasDub -> "Filler • Sub / Dub"
                        isFiller -> "Filler"
                        hasDub -> "Sub / Dub"
                        else -> "Sub"
                    }
                }
            }.sortedByDescending { it.episode_number }
        }

        // Movie handling
        val movieBtn = doc.selectFirst("div.hk-title__cta a.hk-btn[href*=/watch-]")
        if (movieBtn != null) {
            val ep = SEpisode.create().apply {
                setUrlWithoutDomain(movieBtn.attr("href").trim())
                name = "Movie"
                episode_number = 1F
            }
            return listOf(ep)
        }

        return emptyList()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.newCall(GET("$baseUrl${episode.url}", headers)).execute().asJsoup()
        val watchElement = doc.selectFirst("article.hk-watch") ?: return emptyList()
        val watchJsonRaw = watchElement.attr("data-hk-watch")
        if (watchJsonRaw.isBlank()) return emptyList()

        val watchData = runCatching { json.parseToJsonElement(watchJsonRaw).jsonObject }.getOrNull()
            ?: return emptyList()

        val token = watchData["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val aniListId = watchData["al"]?.jsonPrimitive?.intOrNull ?: 0
        val epNum = watchData["ep"]?.jsonPrimitive?.intOrNull ?: episode.episode_number.toInt()

        val hosterList = mutableListOf<Hoster>()

        if (token.isNotBlank()) {
            val versions = extractVersions(doc, "backup")
            hosterList.add(
                Hoster(
                    hosterName = "MegaPlay (Ryu)",
                    hosterUrl = "megaplay://$token?versions=$versions",
                ),
            )
        }

        if (aniListId > 0) {
            val versions = extractVersions(doc, "vidnest")
            hosterList.add(
                Hoster(
                    hosterName = "VidNest (Volt)",
                    hosterUrl = "vidnest://$aniListId/$epNum?versions=$versions",
                ),
            )
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosterList.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    private fun extractVersions(doc: Document, serverKey: String): String = doc.select("button.hk-srv[data-server=$serverKey]")
        .mapNotNull { it.attr("data-version").trim().lowercase().takeIf { v -> v == "sub" || v == "dub" } }
        .distinct()
        .ifEmpty { listOf("sub", "dub") }
        .joinToString(",")

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterUrl = hoster.hosterUrl
        val videos = when {
            hosterUrl.startsWith("megaplay://") -> {
                val payload = hosterUrl.removePrefix("megaplay://")
                val token = payload.substringBefore("?")
                val versions = parseVersionsParam(payload)
                extractMegaPlayVideos(token, versions)
            }

            hosterUrl.startsWith("vidnest://") -> {
                val payload = hosterUrl.removePrefix("vidnest://")
                val path = payload.substringBefore("?")
                val parts = path.split("/")
                val aniListId = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
                val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
                val versions = parseVersionsParam(payload)
                extractVidNestVideos(aniListId, epNum, versions)
            }

            else -> emptyList()
        }
        return videos.sortVideos()
    }

    private fun parseVersionsParam(payload: String): List<String> = payload.substringAfter("versions=", "sub,dub")
        .split(",")
        .filter { it.isNotBlank() }
        .ifEmpty { listOf("sub", "dub") }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefAudio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = listOf("MegaPlay (Ryu)", "VidNest (Volt)"),
            entryValues = listOf("megaplay", "vidnest"),
        )
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio",
            summary = "%s",
            entries = listOf("Subbed", "Dubbed"),
            entryValues = listOf("SUB", "DUB"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
    }

    // ============================ Extractors ==============================

    private fun extractMegaPlayVideos(token: String, versions: List<String>): List<Video> {
        val videoList = mutableListOf<Video>()
        for (lang in versions) {
            runCatching {
                val streamUrl = "$baseUrl/stream/$token/$lang"
                val resp = client.newCall(GET(streamUrl, headers)).execute()
                val finalUrl = resp.request.url.toString()
                val html = resp.body.string()
                if (!finalUrl.contains("megaplay.buzz")) return@runCatching

                val fileId = FILE_ID_REGEX.find(html)?.groupValues?.get(1)
                    ?: DATA_ID_REGEX.find(html)?.groupValues?.get(1)
                    ?: CID_REGEX.find(html)?.groupValues?.get(1)
                    ?: return@runCatching

                val sourcesUrl = "$MEGAPLAY_ORIGIN/stream/getSources?id=$fileId&id=$fileId"
                val sourcesHeaders = headers.newBuilder()
                    .set("Referer", finalUrl)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .build()

                val sourcesResp = client.newCall(GET(sourcesUrl, sourcesHeaders)).execute()
                val sourcesJson = json.parseToJsonElement(sourcesResp.body.string()).jsonObject

                val plainFile = sourcesJson["sources"]?.jsonObject?.get("file")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("http") }

                val masterUrl = plainFile ?: run {
                    val enc = sourcesJson["enc"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                    val decrypted = decryptMegaPlayWithFallback(enc) ?: return@runCatching
                    json.parseToJsonElement(decrypted).jsonObject["file"]?.jsonPrimitive?.contentOrNull
                        ?: return@runCatching
                }

                val cdnToken = generateMegaPlayToken(masterUrl)
                val signedMasterUrl = if (cdnToken.isNotBlank()) {
                    val sep = if ("?" in masterUrl) "&" else "?"
                    "$masterUrl${sep}t=$lang&token=${URLEncoder.encode(cdnToken, "UTF-8")}"
                } else {
                    masterUrl
                }

                val trackList = parseTracks(sourcesJson)
                videoList.addAll(
                    extractProxiedHls(
                        masterUrl = signedMasterUrl,
                        streamReferer = "$MEGAPLAY_ORIGIN/",
                        labelPrefix = "MegaPlay",
                        audioTag = lang.uppercase(),
                        trackList = trackList,
                    ),
                )
            }
        }
        return videoList
    }

    private fun extractVidNestVideos(aniListId: Int, epNum: Int, versions: List<String>): List<Video> {
        val videoList = mutableListOf<Video>()
        val apiHeaders = headers.newBuilder()
            .set("Referer", "$VIDNEST_ORIGIN/")
            .set("Origin", VIDNEST_ORIGIN)
            .build()

        for (lang in versions) {
            for (serverSlug in listOf("hd-2", "hd-1")) {
                val resolved = runCatching {
                    val nonce = generateNonce()
                    val url = "$VIDNEST_API/hianime/anime/$aniListId/$epNum/$lang/$serverSlug?_n=${URLEncoder.encode(nonce, "UTF-8")}"
                    val resp = client.newCall(GET(url, apiHeaders)).execute()
                    if (!resp.isSuccessful) return@runCatching null

                    val rawObj = json.parseToJsonElement(resp.body.string()).jsonObject
                    val dataObj = if (rawObj["v"]?.jsonPrimitive?.intOrNull == 2 && rawObj["d"] != null && rawObj["n"] != null) {
                        val encData = rawObj["d"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                        val encNonce = rawObj["n"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                        json.parseToJsonElement(decryptVidNestPayload(encData, encNonce)).jsonObject
                    } else {
                        rawObj
                    }

                    val sourceObj = dataObj["sources"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
                    val masterUrl = sourceObj["file"]?.jsonPrimitive?.contentOrNull
                        ?: sourceObj["url"]?.jsonPrimitive?.contentOrNull
                        ?: return@runCatching null
                    if (!masterUrl.startsWith("http")) return@runCatching null

                    val streamReferer = sourceObj["referer"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.startsWith("http") }
                        ?: "$MEGAPLAY_ORIGIN/"
                    val trackList = parseTracks(dataObj)

                    extractProxiedHls(
                        masterUrl = masterUrl,
                        streamReferer = streamReferer,
                        labelPrefix = "VidNest",
                        audioTag = lang.uppercase(),
                        trackList = trackList,
                    )
                }.getOrNull()

                if (!resolved.isNullOrEmpty()) {
                    videoList.addAll(resolved)
                    break
                }
            }
        }
        return videoList
    }

    private fun parseTracks(root: JsonObject): List<Track> = root["tracks"]?.jsonArray?.mapNotNull { trackElem ->
        val tObj = trackElem.jsonObject
        val kind = tObj["kind"]?.jsonPrimitive?.contentOrNull
        if (kind.equals("thumbnails", ignoreCase = true)) return@mapNotNull null
        val trackFile = tObj["file"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val trackLabel = tObj["label"]?.jsonPrimitive?.contentOrNull?.ifBlank { "Subtitle" } ?: "Subtitle"
        Track(trackFile, trackLabel)
    }.orEmpty()

    private fun extractProxiedHls(
        masterUrl: String,
        streamReferer: String,
        labelPrefix: String,
        audioTag: String,
        trackList: List<Track>,
    ): List<Video> {
        val proxiedMasterUrl = proxyServer.relay(masterUrl, streamReferer, USER_AGENT)
        val localHeaders = headers.newBuilder()
            .set("Referer", streamReferer)
            .build()

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = proxiedMasterUrl,
                referer = streamReferer,
                masterHeaders = localHeaders,
                videoHeaders = localHeaders,
                videoNameGen = { q -> "$labelPrefix - $q [$audioTag]" },
                subtitleList = trackList,
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = proxiedMasterUrl,
                    videoTitle = "$labelPrefix - Auto [$audioTag]",
                    headers = localHeaders,
                    subtitleTracks = trackList,
                ),
            )
        }
    }

    // ============================ Cryptography Helpers ====================

    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
    }

    private fun decryptVidNestPayload(encData: String, encNonce: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest("$encNonce|$VIDNEST_SECRET".toByteArray(Charsets.UTF_8))
        val raw = decodeBase64Url(encData)
        val iv = raw.copyOfRange(0, 12)
        val cipherBytes = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun decryptMegaPlayWithFallback(enc: String): String? {
        runCatching { decryptMegaPlayEnc(enc, megaPlayKey, megaPlayIv) }.getOrNull()?.let { return it }
        refreshMegaPlayKeys()
        return runCatching { decryptMegaPlayEnc(enc, megaPlayKey, megaPlayIv) }.getOrNull()
    }

    private fun refreshMegaPlayKeys() = runCatching {
        val js = client.newCall(GET("$MEGAPLAY_ORIGIN/lib/newclient.min.js", headers)).execute()
            .use { it.body.string() }
        KEY_REGEX.find(js)?.let { m ->
            val literal = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (literal.isNotBlank()) megaPlayKey = literal.toByteArray(Charsets.UTF_8).copyOf(32)
        }
        IV_REGEX.find(js)?.let { m ->
            val literal = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val bytes = literal.toByteArray(Charsets.UTF_8)
            if (bytes.size == 16) megaPlayIv = bytes
        }
    }.let { }

    private fun decryptMegaPlayEnc(enc: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        return String(cipher.doFinal(decodeBase64Url(enc)), Charsets.UTF_8)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val clean = input.replace("-", "+").replace("_", "/")
        val pad = (4 - (clean.length % 4)) % 4
        return Base64.decode(clean + "=".repeat(pad), Base64.DEFAULT)
    }

    private fun generateMegaPlayToken(masterUrl: String): String {
        val match = HASH_PATH_REGEX.find(masterUrl) ?: return ""
        val pathKey = "${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
        val exp = (System.currentTimeMillis() / 1000) + 90
        val payload = "$exp|$pathKey"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(MEGAPLAY_CDN_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val hmac = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
        return "${b64(payload.toByteArray(Charsets.UTF_8))}.${b64(hmac)}"
    }

    private companion object {
        const val MEGAPLAY_ORIGIN = "https://megaplay.buzz"
        const val VIDNEST_ORIGIN = "https://vidnest.fun"
        const val VIDNEST_API = "https://new.vidnest.fun"
        const val VIDNEST_SECRET = "K3p8vQ2mX9rL5wZ0fN7bG1sH4tJ6dC9_y8S3wM1nQ5pL2"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val DEFAULT_MEGAPLAY_KEY: ByteArray = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8).copyOf(32)
        val DEFAULT_MEGAPLAY_IV: ByteArray = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8).copyOf(16)
        const val MEGAPLAY_CDN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

        val FILE_ID_REGEX = Regex("""File\s+(\d+)\s+-\s+MegaPlay""")
        val DATA_ID_REGEX = Regex("""data-id=["'](\d+)["']""")
        val CID_REGEX = Regex(""""cid"\s*:\s*"?(\d+)""")
        val HASH_PATH_REGEX = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""")
        val KEY_REGEX = Regex("""\[\s*["']trustAesKey["']\s*,\s*["']TRUST_AES_KEY["']\s*\]\s*,\s*(?:"([^"]*)"|'([^']*)')""")
        val IV_REGEX = Regex("""\[\s*["']trustAesIv["']\s*,\s*["']TRUST_AES_IV["']\s*\]\s*,\s*(?:"([^"]*)"|'([^']*)')""")

        const val PREF_SERVER_KEY = "preferred_server"
        const val PREF_SERVER_DEFAULT = "megaplay"
        const val PREF_AUDIO_KEY = "preferred_audio"
        const val PREF_AUDIO_DEFAULT = "SUB"
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
    }
}
