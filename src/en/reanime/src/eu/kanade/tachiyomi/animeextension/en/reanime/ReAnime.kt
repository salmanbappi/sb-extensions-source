package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.getValue

@Serializable
data class SearchResponseDto(
    val results: List<AnimeDto> = emptyList(),
)

@Serializable
data class AnimeDto(
    val anime_id: String,
    val title: TitleDto,
    val cover_image: CoverImageDto,
)

@Serializable
data class TitleDto(
    val user_preferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
data class CoverImageDto(
    val extra_large: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class FlixResponseDto(
    val success: Boolean,
    val servers: List<FlixServerDto> = emptyList(),
)

@Serializable
data class FlixServerDto(
    val serverName: String,
    val dataLink: String,
    val dataType: String,
)

@Serializable
data class LatestResponseDto(
    val data: List<AnimeDto> = emptyList(),
)

@Serializable
data class StudioDto(
    val name: String? = null,
    val is_main: Boolean? = null,
)

@Serializable
data class DetailAnimeDto(
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<StudioDto> = emptyList(),
    val cover_image: CoverImageDto? = null,
    val anilist_id: Int? = null,
    val subbed: Int? = null,
    val dubbed: Int? = null,
)

@Serializable
data class EpisodeDto(
    val episode_number: Float,
    val title: String? = null,
    val aired: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val is_filler: Boolean? = null,
)

class ReAnime : Source() {

    override val name = "ReAnime"

    override val baseUrl = "https://reanime.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(CloudflareInterceptor(network.client))
        .build()

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    private val m3u8Integration by lazy {
        M3u8Integration(client)
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val offset = (page - 1) * 20
        return GET("$baseUrl/api/v1/search?q=&limit=20&offset=$offset", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/v1/home/latest-aired?limit=20", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<LatestResponseDto>()
        val animes = data.data.map {
            SAnime.create().apply {
                url = it.anime_id
                title = it.title.english ?: it.title.user_preferred ?: it.title.romaji ?: ""
                thumbnail_url = it.cover_image.large
            }
        }
        return AnimesPage(animes, hasNextPage = false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val offset = (page - 1) * 20
        val urlBuilder = "$baseUrl/api/v1/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query)
            filters.forEach { filter ->
                when (filter) {
                    is FormatFilter -> {
                        val value = filter.toUriPart()
                        if (value.isNotBlank()) addQueryParameter("format", value)
                    }

                    is GenreFilter -> {
                        filter.getSelected().forEach { genre ->
                            if (genre.isNotBlank()) addQueryParameter("genre", genre)
                        }
                    }

                    is StatusFilter -> {
                        val value = filter.toUriPart()
                        if (value.isNotBlank()) addQueryParameter("status", value)
                    }

                    is SeasonFilter -> {
                        val value = filter.toUriPart()
                        if (value.isNotBlank()) addQueryParameter("season", value)
                    }

                    is YearFilter -> {
                        val value = filter.state.trim()
                        if (value.isNotBlank()) addQueryParameter("year", value)
                    }

                    else -> {}
                }
            }
            addQueryParameter("limit", "20")
            addQueryParameter("offset", offset.toString())
        }
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<SearchResponseDto>()
        val animes = data.results.map {
            SAnime.create().apply {
                url = it.anime_id
                title = it.title.english ?: it.title.user_preferred ?: it.title.romaji ?: ""
                thumbnail_url = it.cover_image.large
            }
        }
        return AnimesPage(animes, hasNextPage = animes.size == 20)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Search filters"),
        FormatFilter(),
        GenreFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(),
    )

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/api/v1/anime/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.parseAs<DetailAnimeDto>()
        return SAnime.create().apply {
            description = anime.description?.replace("<br>", "\n")?.replace("<BR>", "\n")?.replace(Regex("<[^>]*>"), "")
            status = when (anime.status?.lowercase()) {
                "finished" -> SAnime.COMPLETED
                "releasing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            genre = anime.genres.joinToString(", ")
            author = anime.studios.firstOrNull { it.is_main == true }?.name ?: anime.studios.firstOrNull()?.name
            thumbnail_url = anime.cover_image?.large
        }
    }

    // =============================== Relation / Suggestions ===============================

    fun relatedAnimeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val jsonElement = response.parseAs<JsonElement>()
        val obj = jsonElement.jsonObject
        val relationsArray = obj["relations"] as? JsonArray ?: return emptyList()

        return relationsArray.mapNotNull { element ->
            val item = element.jsonObject
            val animeId = item["anime_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val titleObj = item["title"]?.jsonObject
            val titleStr = titleObj?.get("english")?.jsonPrimitive?.content
                ?: titleObj?.get("user_preferred")?.jsonPrimitive?.content
                ?: titleObj?.get("romaji")?.jsonPrimitive?.content
                ?: return@mapNotNull null

            val coverObj = item["cover_image"]?.jsonObject
            val imgUrl = coverObj?.get("large")?.jsonPrimitive?.content
                ?: coverObj?.get("extra_large")?.jsonPrimitive?.content

            SAnime.create().apply {
                url = animeId
                title = titleStr
                thumbnail_url = imgUrl
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val episodesRequest = GET("$baseUrl/api/v1/anime/${anime.url}/episodes?limit=2000", headers)
        val episodesResponse = client.newCall(episodesRequest).execute()

        val jsonElement = episodesResponse.parseAs<JsonElement>()
        val epList = if (jsonElement is JsonArray) {
            json.decodeFromJsonElement<List<EpisodeDto>>(jsonElement)
        } else {
            jsonElement.jsonObject["data"]?.let {
                json.decodeFromJsonElement<List<EpisodeDto>>(it)
            } ?: emptyList()
        }

        val prefNaming = preferences.getString(PREF_EPISODE_TITLE_FORMAT_KEY, PREF_EPISODE_TITLE_FORMAT_DEFAULT)
            ?: PREF_EPISODE_TITLE_FORMAT_DEFAULT
        val showThumbnails = preferences.getBoolean(PREF_SHOW_EPISODE_THUMBNAILS_KEY, true)

        epList.map { ep ->
            SEpisode.create().apply {
                url = "${anime.url}?ep=${ep.episode_number}"
                val numStr = if (ep.episode_number % 1 == 0f) ep.episode_number.toInt().toString() else ep.episode_number.toString()
                val title = ep.title?.trim()?.takeIf { it.isNotBlank() }

                name = when (prefNaming) {
                    PREF_EPISODE_TITLE_FORMAT_NUMBER_AND_TITLE -> {
                        if (title != null && !title.startsWith("Episode ", ignoreCase = true)) {
                            "Episode $numStr: $title"
                        } else {
                            title ?: "Episode $numStr"
                        }
                    }

                    else -> "Episode $numStr"
                }

                episode_number = ep.episode_number
                date_upload = parseDate(ep.aired)
                if (showThumbnails) {
                    preview_url = ep.thumbnail?.takeIf { it.isNotBlank() }
                }
                summary = ep.description?.takeIf { it.isNotBlank() }
                fillermark = ep.is_filler ?: false
            }
        }.reversed() // Ascending order
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            if (dateStr.contains(".")) {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(dateStr)?.time ?: 0L
            } else {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(dateStr)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val requestUrl = episode.url
        val slug = requestUrl.substringBefore("?ep=")
        val epNumStr = requestUrl.substringAfter("?ep=")

        // Fetch watch page to get the details & servers
        val watchRequest = GET("$baseUrl/api/v1/anime/$slug", headers)
        val watchResponse = client.newCall(watchRequest).execute()
        val anime = watchResponse.parseAs<DetailAnimeDto>()

        // Find anilist ID
        var anilistId = anime.anilist_id ?: 0
        if (anilistId == 0) {
            for (url in listOf(anime.cover_image?.extra_large, anime.cover_image?.large, anime.cover_image?.medium)) {
                if (url != null) {
                    val m = Regex("""/bx(\d+)-""").find(url)
                    if (m != null) {
                        anilistId = m.groupValues[1].toInt()
                        break
                    }
                }
            }
        }

        if (anilistId == 0) {
            throw Exception("Could not find anilist_id")
        }

        // Fetch Flix API
        val flixRequest = GET("$baseUrl/api/flix/$anilistId/$epNumStr", headersBuilder().set("Referer", "$baseUrl/watch/$slug?ep=$epNumStr").build())
        val flixResponse = client.newCall(flixRequest).execute()
        val flixData = flixResponse.parseAs<FlixResponseDto>()

        val servers = mutableListOf<FlixServerDto>()
        flixData.servers.forEach { server ->
            if (servers.none { it.dataLink == server.dataLink }) {
                servers.add(server)
            }
        }
        servers.sortByDescending { it.dataLink.contains("v=2") }

        val exceptions = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val videos = withContext(Dispatchers.IO) {
            servers.map { server ->
                async {
                    try {
                        val embedReferer = "$baseUrl/watch/$slug?ep=$epNumStr"
                        val embedRequest = GET(server.dataLink, headersBuilder().set("Referer", embedReferer).build())
                        val embedResponse = client.newCall(embedRequest).execute()
                        val embedHtml = embedResponse.body.string()

                        val seed = Regex("""obfuscation_seed\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("obfuscation_seed not found in embed (${server.serverName}) — possible CF block or changed format")
                        val wPayload = Regex("""w_payload\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("w_payload not found in embed (${server.serverName})")

                        val mappings = resolveMappings(seed)

                        val w = Regex(""""?${mappings.tokenField}"?\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("tokenField (${mappings.tokenField}) not found — seed=$seed")

                        val frag2B64 = Regex(""""?${mappings.keyFrag2Field}"?\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("keyFrag2Field (${mappings.keyFrag2Field}) not found")

                        // Fetch session token
                        val m3u8ApiUrl = "https://flixcloud.cc/api/m3u8/$w"
                        val tokenHeaders = headersBuilder()
                            .set("Referer", server.dataLink)
                            .set("Origin", "https://flixcloud.cc")
                            .build()
                        val tokenResponse = client.newCall(GET(m3u8ApiUrl, tokenHeaders)).execute()
                        val tokenBody = tokenResponse.body.string()

                        val kField = getSha256(w + "vid").substring(0, 10)
                        val pField = getSha256(w + "key").substring(0, 10)

                        val jsonObject = json.parseToJsonElement(tokenBody).jsonObject
                        val v = jsonObject[kField]?.jsonPrimitive?.content
                            ?: error("kField ($kField) not in m3u8 response: ${tokenBody.take(200)}")
                        val t = jsonObject[pField]?.jsonPrimitive?.content
                            ?: error("pField ($pField) not in m3u8 response")

                        // Dynamic keys resolution via container parsing
                        val frag1B64 = Regex(""""?${mappings.keyField}"?\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("keyField (${mappings.keyField}) not found")
                        val ivB64 = Regex(""""?${mappings.ivField}"?\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("ivField (${mappings.ivField}) not found")

                        // Decryption
                        val frag1 = safeDecodeB64(frag1B64)
                        val frag2 = safeDecodeB64(frag2B64)
                        val keyPart = safeDecodeB64(t)
                        val seedInt = seed.substring(0, 8).toLong(16).toInt()

                        val wasmBytes = safeDecodeB64(wPayload)
                        val interpreter = MiniWasmInterpreter(wasmBytes)
                        val funcs = interpreter.parseWasm()
                        val derivedBaseKey = interpreter.executeWasm(funcs, frag1, frag2, keyPart, seedInt)

                        val salt = seed.toByteArray(Charsets.UTF_8)
                        val pbkdf2Key = pbkdf2(derivedBaseKey, salt, 1000)

                        val finalKey = ByteArray(32)
                        for (idx in 0 until 32) {
                            finalKey[idx] = (pbkdf2Key[idx].toInt() xor seed[idx % seed.length].code).toByte()
                        }

                        val aesKey = getSha256Bytes(finalKey)
                        val iv = safeDecodeB64(ivB64)
                        val ciphertext = safeDecodeB64(v)

                        val decryptedUrl = decryptAes(ciphertext, aesKey, iv)
                        val playHeaders = buildPlaybackHeaders(decryptedUrl, server.dataLink)

                        // PK key from the WASM interpreter — the local proxy uses it to
                        // decrypt manifests (base64 + XOR) and unwrap image-wrapped segments.
                        val pk = interpreter.getPkBytes(funcs)

                        // HD-1 segments are served from slopnet.site which has Cloudflare
                        // protection. Warm up CF clearance on the main client (which has a
                        // WebView-backed solver) before the proxy starts fetching segments.
                        // We use a subtitle URL as the warmup target — it never carries a
                        // video token, so it cannot invalidate the session.
                        // HD-2 (dataLink contains "v=2") uses a different CDN — skip.
                        if (!server.dataLink.contains("v=2")) {
                            val slopnetOrigin = Regex("""(https://[^/]*slopnet\.site)""").find(embedHtml)?.groupValues?.get(1)
                            if (slopnetOrigin != null) {
                                try {
                                    client.newCall(
                                        GET("$slopnetOrigin/", headersBuilder().set("Referer", server.dataLink).build()),
                                    ).execute().close()
                                } catch (_: Throwable) {}
                            }
                        }

                        ReAnimeProxyServer.ensureStarted(client)

                        val subtitleTracks = Regex("""\burl:"([^"]+)",\s*language:"([^"]+)",\s*format:"([^"]+)"""")
                            .findAll(embedHtml)
                            .map {
                                Track(
                                    url = ReAnimeProxyServer.subtitleProxyUrl(it.groupValues[1], server.dataLink),
                                    lang = it.groupValues[2],
                                )
                            }
                            .toList()

                        val masterProxyUrl = ReAnimeProxyServer.proxyUrl(decryptedUrl, pk, server.dataLink)

                        playlistUtils.extractFromHls(
                            playlistUrl = masterProxyUrl,
                            referer = server.dataLink,
                            masterHeaders = playHeaders,
                            videoHeaders = playHeaders,
                            subtitleList = subtitleTracks,
                            videoNameGen = { quality -> "${server.serverName} (${server.dataType}) - $quality" },
                        ).map { video ->
                            Video(
                                videoUrl = masterProxyUrl,
                                videoTitle = video.videoTitle,
                                subtitleTracks = video.subtitleTracks,
                                audioTracks = emptyList(),
                                headers = video.headers,
                            )
                        }
                    } catch (e: Throwable) {
                        exceptions.add(e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        if (videos.isEmpty() && exceptions.isNotEmpty()) {
            throw exceptions.first()
        }
        return videos
    }

    // ============================ Utilities ===============================

    private fun getSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        val hexChars = CharArray(hash.size * 2)
        for (i in hash.indices) {
            val v = hash[i].toInt() and 0xFF
            hexChars[i * 2] = "0123456789abcdef"[v ushr 4]
            hexChars[i * 2 + 1] = "0123456789abcdef"[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun getSha256Bytes(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    private data class Mappings(
        val videoField: String,
        val keyField: String,
        val ivField: String,
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val tokenField: String,
        val keyFrag2Field: String,
    )

    private fun resolveMappings(seed: String): Mappings {
        var e = seed
        for (o in 0 until 3) e = getSha256(e + o.toString())
        var s = e
        for (o in 0 until 3) s = getSha256(s + o.toString())

        return Mappings(
            videoField = "vf_${e.substring(0, 8)}",
            keyField = "kf_${e.substring(8, 16)}",
            ivField = "ivf_${e.substring(16, 24)}",
            containerName = "cd_${e.substring(24, 32)}",
            arrayName = "ad_${e.substring(32, 40)}",
            objectName = "od_${e.substring(40, 48)}",
            tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}",
            keyFrag2Field = "${s.substring(0, 16)}_${s.substring(16, 24)}",
        )
    }

    private fun pbkdf2(key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKey)

        val input = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, input, 0, salt.size)
        input[input.size - 1] = 1

        var u = mac.doFinal(input)
        val result = u.clone()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return result
    }

    private fun decryptAes(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun safeDecodeB64(str: String): ByteArray {
        val clean = str.trim()
        return try {
            Base64.decode(clean, Base64.DEFAULT)
        } catch (_: Exception) {
            try {
                Base64.decode(clean, Base64.URL_SAFE)
            } catch (_: Exception) {
                val normalized = clean.replace('-', '+').replace('_', '/')
                Base64.decode(normalized, Base64.DEFAULT)
            }
        }
    }

    private fun buildPlaybackHeaders(videoUrl: String, embedUrl: String): Headers {
        val videoHttpUrl = runCatching { videoUrl.toHttpUrl() }.getOrNull()
        val origin = videoHttpUrl?.let { "${it.scheme}://${it.host}" } ?: "https://fetch.flixcloud.cc"

        return headersBuilder()
            .set("Accept", "*/*")
            .set("Origin", origin)
            .set("Referer", embedUrl)
            .build()
    }

    private fun Headers.Builder.applyPlaybackHeaders(playHeaders: Headers): Headers.Builder {
        for (index in 0 until playHeaders.size) {
            val name = playHeaders.name(index)
            if (name.lowercase(Locale.US) != "referer") {
                set(name, playHeaders.value(index))
            }
        }
        set("Referer", playHeaders["Referer"]!!)
        return this
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        val qualityOrder = listOf("1080p", "720p", "480p", "360p")

        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(type, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(server, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(quality, ignoreCase = true) }
                .thenBy { video ->
                    val index = qualityOrder.indexOfFirst { video.videoTitle.contains(it) }
                    if (index == -1) qualityOrder.size else index
                },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Type",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("sub", "dub"),
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = listOf("HD-1", "HD-2"),
            entryValues = listOf("HD-1", "HD-2"),
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_EPISODE_TITLE_FORMAT_KEY,
            title = "Episode Title Format",
            entries = listOf("Episode 1, Episode 2...", "Episode 1: Title"),
            entryValues = listOf(
                PREF_EPISODE_TITLE_FORMAT_NUMBER_ONLY,
                PREF_EPISODE_TITLE_FORMAT_NUMBER_AND_TITLE,
            ),
            default = PREF_EPISODE_TITLE_FORMAT_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_EPISODE_THUMBNAILS_KEY,
            title = "Show episode thumbnails",
            summary = "Display preview images in the episode list.",
            default = true,
        )
    }

    // ============================== Filter Classes ==============================

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private open class CheckBoxFilterList(name: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { CheckBoxVal(it.first) }) {
        fun getSelected(): List<String> = state.mapIndexedNotNull { index, filter ->
            if (filter.state) vals[index].second else null
        }
    }

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
    }

    private class FormatFilter :
        UriPartFilter(
            "Format",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "TV"),
                Pair("Movie", "MOVIE"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
                Pair("Special", "SPECIAL"),
                Pair("Music", "MUSIC"),
                Pair("TV Short", "TV_SHORT"),
            ),
        )

    private class GenreFilter : CheckBoxFilterList("Genres", GENRES)

    private class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Finished", "FINISHED"),
                Pair("Releasing", "RELEASING"),
                Pair("Not Yet Released", "NOT_YET_RELEASED"),
                Pair("Cancelled", "CANCELLED"),
                Pair("Hiatus", "HIATUS"),
            ),
        )

    private class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("All", ""),
                Pair("Winter", "WINTER"),
                Pair("Spring", "SPRING"),
                Pair("Summer", "SUMMER"),
                Pair("Fall", "FALL"),
            ),
        )

    private class YearFilter : AnimeFilter.Text("Year", "")

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "sub"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "HD-1"

        private const val PREF_EPISODE_TITLE_FORMAT_KEY = "pref_episode_title_format"
        private const val PREF_EPISODE_TITLE_FORMAT_DEFAULT = "number_only"
        private const val PREF_EPISODE_TITLE_FORMAT_NUMBER_ONLY = "number_only"
        private const val PREF_EPISODE_TITLE_FORMAT_NUMBER_AND_TITLE = "number_and_title"

        private const val PREF_SHOW_EPISODE_THUMBNAILS_KEY = "pref_show_episode_thumbnails"

        private val GENRES = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Horror", "Horror"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Mecha", "Mecha"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Sports", "Sports"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
        )
    }
}
