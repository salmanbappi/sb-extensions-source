package eu.kanade.tachiyomi.animeextension.en.hianimems

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
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
    private val m3u8Integration by lazy { M3u8Integration(client) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

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

                    val epNum = element.attr("data-ep").toFloatOrNull() ?: (idx + 1).toFloat()
                    episode_number = epNum

                    val epName = element.attr("data-name").takeIf { it.isNotBlank() }
                        ?: element.attr("title").takeIf { it.isNotBlank() }
                        ?: "Episode $epNum"
                    name = epName

                    if (element.hasClass("hk-ep--filler")) {
                        scanlator = "Filler"
                    }
                }
            }
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

        val token = watchData["token"]?.jsonPrimitive?.contentOrNull ?: ""
        val aniListId = watchData["al"]?.jsonPrimitive?.intOrNull ?: 0
        val epNum = watchData["ep"]?.jsonPrimitive?.intOrNull ?: episode.episode_number.toInt()

        val hosterList = mutableListOf<Hoster>()

        // 1. Primary: MegaPlay / Backup
        if (token.isNotBlank()) {
            hosterList.add(
                Hoster(
                    hosterName = "MegaPlay (Backup)",
                    hosterUrl = "megaplay://$token",
                ),
            )
        }

        // 2. TryEmbed & VidNest mirrors
        if (aniListId > 0) {
            hosterList.add(
                Hoster(
                    hosterName = "TryEmbed",
                    hosterUrl = "tryembed://$aniListId/$epNum",
                ),
            )
            hosterList.add(
                Hoster(
                    hosterName = "VidNest",
                    hosterUrl = "https://vidnest.fun/anime/$aniListId/$epNum/sub",
                ),
            )
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosterList.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterUrl = hoster.hosterUrl
        val videos = when {
            hosterUrl.startsWith("megaplay://") -> {
                val token = hosterUrl.removePrefix("megaplay://")
                extractMegaPlayVideos(token)
            }

            hosterUrl.startsWith("tryembed://") -> {
                val parts = hosterUrl.removePrefix("tryembed://").split("/")
                val aniListId = parts.getOrNull(0) ?: ""
                val epNum = parts.getOrNull(1) ?: "1"
                extractTryEmbedVideos(aniListId, epNum)
            }

            hosterUrl.startsWith("https://vidnest.fun") -> {
                val subVideos = playlistUtils.extractFromHls(hosterUrl, hosterUrl, videoNameGen = { q -> "VidNest - $q [SUB]" })
                val dubUrl = hosterUrl.replace("/sub", "/dub")
                val dubVideos = playlistUtils.extractFromHls(dubUrl, dubUrl, videoNameGen = { q -> "VidNest - $q [DUB]" })
                subVideos + dubVideos
            }

            else -> emptyList()
        }
        return videos.sortVideos()
    }

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
            entries = listOf("MegaPlay (Backup)", "TryEmbed", "VidNest"),
            entryValues = listOf("megaplay", "tryembed", "vidnest"),
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

    private fun extractMegaPlayVideos(token: String): List<Video> {
        val videoList = mutableListOf<Video>()
        for (lang in listOf("sub", "dub")) {
            runCatching {
                val streamUrl = "$baseUrl/stream/$token/$lang"
                val req = GET(streamUrl, headers)
                val resp = client.newCall(req).execute()
                val finalUrl = resp.request.url.toString()
                if (!finalUrl.contains("megaplay.buzz")) return@runCatching

                val html = resp.body.string()
                val fileIdMatch = Regex("""File\s+(\d+)\s+-\s+MegaPlay""").find(html)
                    ?: Regex(""""cid"\s*:\s*"?(\d+)""").find(html)
                val fileId = fileIdMatch?.groupValues?.get(1) ?: return@runCatching

                val sourcesUrl = "https://megaplay.buzz/stream/getSources?id=$fileId"
                val sourcesHeaders = headers.newBuilder()
                    .set("Referer", finalUrl)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .build()

                val sourcesResp = client.newCall(GET(sourcesUrl, sourcesHeaders)).execute()
                val sourcesJson = json.parseToJsonElement(sourcesResp.body.string()).jsonObject

                val enc = sourcesJson["enc"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val decrypted = decryptMegaPlayEnc(enc)
                val decJson = json.parseToJsonElement(decrypted).jsonObject
                val masterUrl = decJson["file"]?.jsonPrimitive?.contentOrNull ?: return@runCatching

                val cdnToken = generateMegaPlayToken(masterUrl)
                val signedMasterUrl = if (cdnToken.isNotBlank()) {
                    "$masterUrl?t=$lang&token=${URLEncoder.encode(cdnToken, "UTF-8")}"
                } else {
                    masterUrl
                }

                // Subtitle tracks
                val trackList = mutableListOf<Track>()
                sourcesJson["tracks"]?.jsonArray?.forEach { trackElem ->
                    val tObj = trackElem.jsonObject
                    val trackFile = tObj["file"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val trackLabel = tObj["label"]?.jsonPrimitive?.contentOrNull ?: "Subtitle"
                    trackList.add(Track(trackFile, trackLabel))
                }

                // MegaPlay segments carry fake .jpg/.html/.js extensions; route through local M3U8 proxy.
                val megaHeaders = headers.newBuilder()
                    .set("Referer", "https://megaplay.buzz/")
                    .build()

                val videos = playlistUtils.extractFromHls(
                    playlistUrl = signedMasterUrl,
                    referer = "https://megaplay.buzz/",
                    masterHeaders = megaHeaders,
                    videoHeaders = megaHeaders,
                    videoNameGen = { q -> "MegaPlay - $q [${lang.uppercase()}]" },
                    subtitleList = trackList,
                )
                videoList.addAll(m3u8Integration.processVideoList(videos))
            }
        }
        return videoList
    }

    private fun extractTryEmbedVideos(alId: String, epNum: String): List<Video> {
        val videoList = mutableListOf<Video>()
        for (lang in listOf("sub", "dub")) {
            val embedUrl = "https://tryembed.us.cc/embed/anime/$alId/$epNum/$lang"
            runCatching {
                val req = GET(embedUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())
                val resp = client.newCall(req).execute()
                val cookies = resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
                val html = resp.body.string()

                val keyMatch = Regex("""__TE_KEY="([a-f0-9]{64})"""").find(html) ?: return@runCatching
                val ticketMatch = Regex("""BOOTSTRAP_TICKET="([^"]+)"""").find(html) ?: return@runCatching
                val teKey = keyMatch.groupValues[1]
                val ticket = ticketMatch.groupValues[1]

                val bootstrapUrl = "https://tryembed.us.cc/api/bootstrap"
                val bootstrapHeadersBuilder = headers.newBuilder()
                    .set("Referer", embedUrl)
                    .set("X-TryEmbed-Bootstrap", ticket)
                if (cookies.isNotBlank()) {
                    bootstrapHeadersBuilder.set("Cookie", cookies)
                }

                val bootResp = client.newCall(POST(bootstrapUrl, bootstrapHeadersBuilder.build(), "".toRequestBody())).execute()
                val bootJson = json.parseToJsonElement(bootResp.body.string()).jsonObject

                val iv = bootJson["iv"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val data = bootJson["data"]?.jsonPrimitive?.contentOrNull ?: return@runCatching

                val decJsonStr = decryptTryEmbed(teKey, iv, data)
                val decObj = json.parseToJsonElement(decJsonStr).jsonObject
                val providers = decObj["providers"]?.jsonArray ?: return@runCatching

                val streamHeadersBuilder = headers.newBuilder().set("Referer", embedUrl)
                if (cookies.isNotBlank()) {
                    streamHeadersBuilder.set("Cookie", cookies)
                }
                val streamHeaders = streamHeadersBuilder.build()

                providers.forEach { pElem ->
                    val pObj = pElem.jsonObject
                    val pName = pObj["name"]?.jsonPrimitive?.contentOrNull ?: "TryEmbed"
                    pObj["qualities"]?.jsonArray?.forEach { qElem ->
                        val qObj = qElem.jsonObject
                        val token = qObj["token"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val streamUrl = "https://tryembed.us.cc/s/$token.m3u8"
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = embedUrl,
                                masterHeaders = streamHeaders,
                                videoHeaders = streamHeaders,
                                videoNameGen = { q -> "$pName - $q [${lang.uppercase()}]" },
                            ),
                        )
                    }
                }
            }
        }
        return videoList
    }

    // ============================ Cryptography Helpers ====================

    private fun decryptMegaPlayEnc(enc: String): String {
        val key = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8).copyOf(32)
        val iv = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8).copyOf(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val safeBase64 = enc.replace("-", "+").replace("_", "/")
        val decoded = Base64.decode(safeBase64, Base64.DEFAULT)
        return String(cipher.doFinal(decoded), Charsets.UTF_8)
    }

    private fun generateMegaPlayToken(masterUrl: String): String {
        val regex = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""")
        val match = regex.find(masterUrl) ?: return ""
        val pathKey = "${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"
        val exp = (System.currentTimeMillis() / 1000) + 90
        val payload = "$exp|$pathKey"
        val secret = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val hmac = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
        return "${b64(payload.toByteArray(Charsets.UTF_8))}.${b64(hmac)}"
    }

    private fun decryptTryEmbed(keyHex: String, ivB64: String, dataB64: String): String {
        val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ivBytes = Base64.decode(ivB64, Base64.DEFAULT)
        val cipherBytes = Base64.decode(dataB64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        }
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private companion object {
        const val PREF_SERVER_KEY = "preferred_server"
        const val PREF_SERVER_DEFAULT = "megaplay"
        const val PREF_AUDIO_KEY = "preferred_audio"
        const val PREF_AUDIO_DEFAULT = "SUB"
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
    }
}
