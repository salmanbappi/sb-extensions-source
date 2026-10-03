package eu.kanade.tachiyomi.animeextension.all.anidap

import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Anidap :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Anidap"

    override val baseUrl = "https://anidap.lol"

    override val lang = "all"

    override val supportsLatest = true

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private var proxy: LocalProxyServer? = null

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Proxy =================================
    //
    // Every provider except the ones listed in PLAIN_PROVIDERS plays through a
    // loopback server that re-issues each playlist / key / segment request with
    // the headers the web player would have sent. It is needed because
    //   * sora segments only answer with `Origin: https://krussdomi.com`,
    //   * uwu is an AES-128 playlist whose key and segments need `Referer: kwik.cx`,
    //   * yuki / momo segments are `.jpg`-named transport streams,
    // and players do not forward the video headers to every child request.

    private fun getProxyUrl(url: String, sourceHeaders: Headers? = null, fileName: String? = null): String {
        if (proxy == null) {
            proxy = LocalProxyServer(client, json).apply { start() }
        }
        val encodedUrl = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encodedHeaders = encodeHeaders(sourceHeaders)
        val path = fileName ?: if (url.contains(".m3u8")) "playlist.m3u8" else "segment.ts"
        val query = "url=$encodedUrl" + if (encodedHeaders != null) "&headers=$encodedHeaders" else ""
        return "http://127.0.0.1:${proxy!!.port}/$path?$query"
    }

    private fun encodeHeaders(hdrs: Headers?): String? {
        if (hdrs == null || hdrs.size == 0) return null
        val map = mutableMapOf<String, String>()
        for (i in 0 until hdrs.size) {
            map[hdrs.name(i)] = hdrs.value(i)
        }
        return try {
            Base64.encodeToString(json.encodeToString(map).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) {
            null
        }
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/anime/advanced-search?sort=POPULARITY_DESC&page=$page", headers)
        val response = client.newCall(request).execute()
        return parseAnimePage(response)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/anime/advanced-search?sort=START_DATE_DESC&page=$page", headers)
        val response = client.newCall(request).execute()
        return parseAnimePage(response)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/anime/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimePage(response)
        }

        val urlBuilder = "$baseUrl/api/anime/advanced-search".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("format", filter.toUriPart())

                is Filters.StatusFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())

                is Filters.SeasonFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("season", filter.toUriPart())

                is Filters.YearFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("year", filter.toUriPart())

                is Filters.SortFilter -> filter.toUriPart()?.let { urlBuilder.addQueryParameter("sort", it) }

                is Filters.GenreFilter -> {
                    val selected = filter.toQueries()
                    if (selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("genres", selected.joinToString(","))
                    }
                }

                else -> {}
            }
        }

        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        return parseAnimePage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply when text search is blank"),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.YearFilter(),
        Filters.SortFilter(),
        Filters.GenreFilter(),
    )

    private fun parseAnimePage(response: Response): AnimesPage {
        val body = response.body.string()
        val jsonElement = json.parseToJsonElement(body).jsonObject
        val dataElement = jsonElement["data"] ?: jsonElement

        val itemsArray = when (dataElement) {
            is JsonArray -> dataElement
            is JsonObject -> dataElement["results"]?.jsonArray ?: dataElement["data"]?.jsonArray
            else -> jsonElement["results"]?.jsonArray
        } ?: JsonArray(emptyList())

        val animes = itemsArray.mapNotNull { element ->
            runCatching {
                val item = json.decodeFromJsonElement<AnimeItem>(element)
                val idStr = item.id?.content ?: return@mapNotNull null
                val animeTitle = item.title?.english ?: item.title?.userPreferred ?: item.title?.romaji ?: "Anime"
                val animeThumb = item.coverImage?.extraLarge ?: item.coverImage?.large ?: item.coverImage?.medium ?: item.image

                SAnime.create().apply {
                    url = idStr
                    title = animeTitle
                    thumbnail_url = animeThumb
                }
            }.getOrNull()
        }

        val hasNext = (dataElement as? JsonObject)?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull
            ?: (jsonElement["hasNextPage"]?.jsonPrimitive?.booleanOrNull ?: (animes.isNotEmpty()))

        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val idOrSlug = anime.url.removePrefix("/").substringBefore("?")
        val resolved = resolveSlugNative(idOrSlug)
        if (resolved != null) {
            val obj = resolved.second
            return SAnime.create().apply {
                url = resolved.first
                title = obj["titleEnglish"]?.jsonPrimitive?.content
                    ?: obj["titleRomaji"]?.jsonPrimitive?.content
                    ?: anime.title
                thumbnail_url = (obj["coverImage"] as? JsonObject)
                    ?.let { c -> c["extraLarge"]?.jsonPrimitive?.content ?: c["large"]?.jsonPrimitive?.content ?: c["medium"]?.jsonPrimitive?.content }
                    ?: obj["coverImage"]?.jsonPrimitive?.content
                    ?: anime.thumbnail_url
                genre = obj["genres"]?.jsonArray
                    ?.mapNotNull { el ->
                        when (el) {
                            is JsonObject -> el["name"]?.jsonPrimitive?.content
                            else -> el.jsonPrimitive.content.takeIf { it.isNotBlank() }
                        }
                    }
                    ?.joinToString()
                status = when (obj["status"]?.jsonPrimitive?.content?.uppercase()) {
                    "RELEASING" -> SAnime.ONGOING
                    "FINISHED" -> SAnime.COMPLETED
                    "NOT_YET_RELEASED" -> SAnime.LICENSED
                    else -> SAnime.UNKNOWN
                }
                initialized = true
                description = buildString {
                    obj["description"]?.jsonPrimitive?.content
                        ?.let { append(it.replace(Regex("<[^>]*>"), "")) }
                    obj["season"]?.jsonPrimitive?.content?.let {
                        append("\n\nSeason: $it ${obj["seasonYear"]?.jsonPrimitive?.content ?: ""}")
                    }
                    obj["format"]?.jsonPrimitive?.content?.let { append("\nFormat: $it") }
                }.trim()
            }
        }
        return anime
    }

    private fun resolveSlugNative(idOrSlug: String): Pair<String, JsonObject>? {
        return runCatching {
            val response = client.newCall(GET("$baseUrl/api/anime/$idOrSlug", headers)).execute()
            val obj = json.parseToJsonElement(response.body.string()).jsonObject
            val data = obj["data"]?.jsonObject ?: return null
            val slug = data["id"]?.jsonPrimitive?.content ?: return null
            Pair(slug, data)
        }.getOrNull()
    }

    private fun resolveSlug(idOrSlug: String): String = resolveSlugNative(idOrSlug)?.first ?: idOrSlug

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val rawId = anime.url.removePrefix("/").substringBefore("?")
        val slug = if (rawId.toIntOrNull() != null) resolveSlug(rawId) else rawId

        val request = GET("https://chad.anidap.lol/rest/api/episodes?id=$slug", headers)
        val response = client.newCall(request).execute()
        val body = response.body.string()

        val episodes = runCatching {
            val jsonElement = json.parseToJsonElement(body)
            when {
                jsonElement is JsonArray -> json.decodeFromJsonElement<List<EpisodeItem>>(jsonElement)
                jsonElement is JsonObject && jsonElement["data"] is JsonArray -> json.decodeFromJsonElement<List<EpisodeItem>>(jsonElement["data"]!!)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

        val loadThumbnails = preferences.getBoolean("pref_load_thumbnails", true)
        val loadTitles = preferences.getBoolean("pref_load_titles", true)
        val loadDescriptions = preferences.getBoolean("pref_load_descriptions", true)

        val episodeList = episodes.map { ep ->
            SEpisode.create().apply {
                val num = ep.number ?: ep.episodeNumber ?: 1f
                episode_number = num
                // Integer display: Episode 1 not Episode 1.0
                val epStr = if (num == num.toLong().toFloat()) num.toLong().toString() else num.toString()
                name = if (loadTitles && !ep.title.isNullOrBlank()) {
                    "Episode $epStr: ${ep.title}"
                } else {
                    "Episode $epStr"
                }
                url = "$slug?ep=$epStr"
                if (loadThumbnails && !ep.img.isNullOrBlank()) {
                    preview_url = ep.img
                }
                if (loadDescriptions && !ep.description.isNullOrBlank()) {
                    summary = ep.description
                }
                scanlator = when {
                    ep.hasSub == true && ep.hasDub == true -> "Sub / Dub"
                    ep.hasDub == true -> "Dub"
                    ep.hasSub == true -> "Sub"
                    else -> null
                }
            }
        }

        // Descending: Episode 12 at top, Episode 1 at bottom
        return episodeList.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeId = episode.url.substringBefore("?")
        val epNum = episode.url.substringAfter("ep=").substringBefore("&")

        val serversRequest = GET("https://chad.anidap.lol/rest/api/servers?id=$animeId&epNum=$epNum", headers)
        val response = client.newCall(serversRequest).execute()
        val serversData = runCatching {
            response.parseAs<ServersResponse>(json)
        }.getOrNull() ?: ServersResponse()

        val disabledServers = preferences.getStringSet("pref_disabled_servers", emptySet()) ?: emptySet()

        val subProviders = serversData.data?.subProviders ?: serversData.subProviders ?: emptyList()
        val dubProviders = serversData.data?.dubProviders ?: serversData.dubProviders ?: emptyList()

        // Combine sub & dub for all servers into single hoster per server
        data class ServerInfo(val tip: String?, val hasSub: Boolean, val hasDub: Boolean)
        val serverMap = linkedMapOf<String, ServerInfo>()

        for (server in subProviders) {
            val id = server.id?.takeIf { it.isNotBlank() } ?: continue
            if (disabledServers.contains(id)) continue
            val existing = serverMap[id]
            serverMap[id] = ServerInfo(server.tip ?: existing?.tip, true, existing?.hasDub ?: false)
        }
        for (server in dubProviders) {
            val id = server.id?.takeIf { it.isNotBlank() } ?: continue
            if (disabledServers.contains(id)) continue
            val existing = serverMap[id]
            serverMap[id] = ServerInfo(server.tip ?: existing?.tip, existing?.hasSub ?: false, true)
        }

        val hosters = serverMap.map { (id, info) ->
            val subType = when {
                info.tip?.contains("Hard sub", ignoreCase = true) == true -> "Hard Sub"
                info.tip?.contains("Soft sub", ignoreCase = true) == true -> "Soft Sub"
                else -> "Sub"
            }
            val audioLabel = when {
                info.hasSub && info.hasDub -> "Sub/Dub"
                info.hasDub -> "Dub"
                else -> "Sub"
            }
            // Unknown providers default to the proxy: it injects whatever headers
            // the API advertised, which is strictly safer than the plain path.
            val prefix = if (id.lowercase() in PLAIN_PROVIDERS) "plain" else "proxy"
            Hoster(
                hosterName = "${id.uppercase()} [$subType] [$audioLabel]",
                hosterUrl = "$prefix|$animeId|$epNum|$id|${if (info.hasSub) "1" else "0"}|${if (info.hasDub) "1" else "0"}",
            )
        }

        return sortHostersByPreference(hosters)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 6) return emptyList()
        val mode = parts[0]
        val animeId = parts[1]
        val epNum = parts[2]
        val providerId = parts[3]
        val hasSub = parts[4] == "1"
        val hasDub = parts[5] == "1"

        val videos = mutableListOf<Video>()
        if (mode == "proxy") {
            if (hasSub) videos.addAll(fetchProxiedVideos(animeId, epNum, providerId, "sub"))
            if (hasDub) videos.addAll(fetchProxiedVideos(animeId, epNum, providerId, "dub"))
        } else {
            if (hasSub) videos.addAll(fetchPlainVideos(animeId, epNum, providerId, "sub"))
            if (hasDub) videos.addAll(fetchPlainVideos(animeId, epNum, providerId, "dub"))
        }
        return videos.sortVideos()
    }

    /** Fetch videos for proxied servers through the local proxy (injects CDN headers per request). */
    private fun fetchProxiedVideos(animeId: String, epNum: String, providerId: String, type: String): List<Video> {
        val sourcesData = fetchSources(animeId, epNum, providerId, type) ?: return emptyList()
        val sources = sourcesData.sourceList()
        if (sources.isEmpty()) return emptyList()

        val sourceHeaders = buildSourceHeaders(providerId, sourcesData.headerMap())
        val subtitles = proxySubtitles(sourcesData.subtitleList(), sourceHeaders)

        val videos = mutableListOf<Video>()
        for (src in sources) {
            val rawUrl = src.url ?: continue
            val finalUrl = applySourceRewrites(rawUrl, providerId)
            val titleLabel = "${type.uppercase()} - ${providerId.uppercase()} - ${src.quality ?: "Auto"}"

            if (isHls(src, finalUrl)) {
                // Parse the master through the proxy so every quality becomes its own
                // entry while the player keeps talking to loopback.
                videos.addAll(
                    playlistUtils.extractFromHls(
                        playlistUrl = getProxyUrl(finalUrl, sourceHeaders),
                        masterHeaders = headers,
                        videoHeaders = headers,
                        videoNameGen = { quality -> "$titleLabel - $quality" },
                        subtitleList = subtitles,
                    ),
                )
            } else {
                videos.add(
                    Video(
                        videoUrl = finalUrl,
                        videoTitle = titleLabel,
                        headers = sourceHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }
        }
        return videos
    }

    /** Fetch videos for all other servers using existing extractors. */
    private fun fetchPlainVideos(animeId: String, epNum: String, providerId: String, type: String): List<Video> {
        val sourcesData = fetchSources(animeId, epNum, providerId, type) ?: return emptyList()
        val sources = sourcesData.sourceList()
        if (sources.isEmpty()) return emptyList()

        val sourceHeaders = buildSourceHeaders(providerId, sourcesData.headerMap())
        val subtitles = proxySubtitles(sourcesData.subtitleList(), sourceHeaders)
        val videos = mutableListOf<Video>()

        for (src in sources) {
            val rawUrl = src.url ?: continue
            val finalUrl = applySourceRewrites(rawUrl, providerId)
            val titleLabel = "${type.uppercase()} - ${providerId.uppercase()} - ${src.quality ?: "Auto"}"

            when {
                providerId.equals("mp4upload", ignoreCase = true) -> {
                    videos.addAll(mp4uploadExtractor.videosFromUrl(finalUrl, sourceHeaders))
                }

                providerId.equals("okru", ignoreCase = true) -> {
                    videos.addAll(okruExtractor.videosFromUrl(finalUrl))
                }

                isHls(src, finalUrl) -> {
                    val playlistVideos = playlistUtils.extractFromHls(
                        playlistUrl = finalUrl,
                        masterHeaders = sourceHeaders,
                        videoHeaders = sourceHeaders,
                        videoNameGen = { quality -> "$titleLabel - $quality" },
                        subtitleList = subtitles,
                    )
                    videos.addAll(playlistVideos)
                }

                else -> {
                    videos.add(
                        Video(
                            videoUrl = finalUrl,
                            videoTitle = titleLabel,
                            headers = sourceHeaders,
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            }
        }
        return videos
    }

    private fun fetchSources(animeId: String, epNum: String, providerId: String, type: String): SourcesResponse? {
        val requestUrl = "https://chad.anidap.lol/rest/api/sources" +
            "?id=$animeId&epNum=$epNum&type=$type&providerId=$providerId"
        return runCatching {
            val response = client.newCall(GET(requestUrl, headers)).execute()
            response.parseAs<SourcesResponse>(json)
        }.getOrNull()
    }

    /**
     * Headers for the provider CDNs. Starts from what the API advertises and adds the
     * origin/referer that the provider's segment hosts actually enforce.
     */
    private fun buildSourceHeaders(providerId: String, apiHeaders: Map<String, String>): Headers {
        val builder = Headers.Builder()
            .set("User-Agent", USER_AGENT)
            .set("Accept", "*/*")
        for ((key, value) in apiHeaders) {
            if (key.isNotBlank() && value.isNotBlank()) builder.set(key, value)
        }

        when (providerId.lowercase()) {
            "yuki", "momo" -> {
                builder.set("Origin", "https://megaplay.buzz")
                builder.set("Referer", "https://megaplay.buzz/")
            }

            // st1.*.xyz only answers when Origin is the krussdomi player.
            "sora" -> {
                builder.set("Origin", "https://krussdomi.com")
                builder.set("Referer", "https://krussdomi.com/")
            }

            "uwu" -> builder.set("Referer", "https://kwik.cx/")

            "kiwi" -> builder.set("Referer", "https://anidb.app/")

            "miku" -> builder.set("Referer", "https://allanime.uns.bio")

            "zuna" -> builder.set("Referer", "https://zokoanime.video/")

            "mimi" -> builder.set("Referer", "https://hawk.aniwatchtv.site/")

            "shiro" -> builder.set("Referer", "https://kem.clvd.xyz/")

            else -> {}
        }

        // PlaylistUtils does this too; some CDNs reject a mismatched Origin.
        if (builder.get("Origin") == null) {
            builder.get("Referer")
                ?.toHttpUrlOrNull()
                ?.let { builder.set("Origin", "${it.scheme}://${it.host}") }
        }
        return builder.build()
    }

    private fun proxySubtitles(tracks: List<SubtitleItem>, sourceHeaders: Headers): List<Track> = tracks.mapNotNull { track ->
        val trackUrl = track.url ?: return@mapNotNull null
        // The subtitle CDNs sit behind the same hotlink checks as the segments.
        Track(
            url = getProxyUrl(trackUrl, sourceHeaders, fileName = "subtitle.vtt"),
            lang = track.label ?: track.lang ?: "Sub",
        )
    }

    private fun isHls(src: SourceItem, url: String): Boolean = src.type?.contains("mpegurl", ignoreCase = true) == true ||
        url.contains(".m3u8") ||
        url.contains("index.txt")

    private fun SourcesResponse.sourceList(): List<SourceItem> = data?.sources ?: sources ?: emptyList()

    private fun SourcesResponse.subtitleList(): List<SubtitleItem> = data?.subtitles ?: subtitles ?: data?.tracks ?: tracks ?: emptyList()

    private fun SourcesResponse.headerMap(): Map<String, String> = data?.apiHeaders ?: apiHeaders ?: emptyMap()

    /**
     * Rewrites a provider source URL onto the host/path that currently serves it,
     * mirroring the web player's `HOST_HANDLERS` map. Providers that only need
     * request headers are covered by [buildSourceHeaders] instead.
     */
    private fun applySourceRewrites(url: String, providerId: String): String {
        var result = url
            .replace("https://vivibebe.site/public/stream/", "https://hawk.aniwatchtv.site/media/")

        val playEngPrefix = "https://playeng.animeapps.top"
        if (result.startsWith("$playEngPrefix/r2/")) {
            result = "https://bd.aniwatchtv.site/media" +
                result.removePrefix(playEngPrefix).replaceFirst("/r2", "")
        }

        return when (providerId.lowercase()) {
            "shiro" -> "${hexEncodedMediaUrl(result)}&origin=https://kem.clvd.xyz/"

            "beep" -> when {
                result.startsWith("https://bd.24stream.xyz/media") -> result

                result.startsWith("https://bd.aniwatchtv.site/media") -> result

                result.startsWith("/") -> "https://bd.aniwatchtv.site/media${result.replace("/r2", "")}"

                else ->
                    "https://bd.aniwatchtv.site/media" +
                        result.replace(Regex("""https?://[^/]+"""), "").replace("/r2", "")
            }

            "mochi" -> result.replace("https://tools.fast4speed.rsvp", "https://mp4.24stream.xyz/storage")

            else -> result
        }
    }

    /** shiro hides the real URL behind a byte-XORed hex blob served by its own media host. */
    private fun hexEncodedMediaUrl(url: String): String {
        val hex = url.toByteArray().joinToString("") { byte ->
            "%02x".format((byte.toInt() and 0xFF) xor 137)
        }
        return "$SHIRO_MEDIA_BASE/media/$hex"
    }

    private fun String.toHttpUrlOrNull(): okhttp3.HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val preferredServer = preferences.getString("pref_preferred_server", "yuki") ?: "yuki"
        return hosters.sortedWith(
            compareBy { hoster ->
                val name = hoster.hosterName.lowercase()
                !name.contains(preferredServer.lowercase())
            },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString("pref_quality", "1080p") ?: "1080p"
        return this.sortedWith(
            compareBy { video ->
                val title = video.videoTitle.lowercase()
                !title.contains(quality.lowercase())
            },
        )
    }

    // =============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "pref_preferred_server"
            title = "Preferred Server"
            summary = "Preferred video server hoster"
            entries = KNOWN_SERVERS.map { it.second }.toTypedArray()
            entryValues = KNOWN_SERVERS.map { it.first }.toTypedArray()
            setDefaultValue("yuki")
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = "pref_quality"
            title = "Preferred Quality"
            summary = "Quality variant shown first"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Auto")
            entryValues = arrayOf("1080p", "720p", "480p", "360p", "auto")
            setDefaultValue("1080p")
        }.also { screen.addPreference(it) }

        MultiSelectListPreference(screen.context).apply {
            key = "pref_disabled_servers"
            title = "Disabled Servers"
            summary = "Select servers to exclude from video list"
            entries = KNOWN_SERVERS.map { it.second }.toTypedArray()
            entryValues = KNOWN_SERVERS.map { it.first }.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_load_thumbnails"
            title = "Load Episode Thumbnails"
            summary = "Fetch preview images for episode items"
            setDefaultValue(true)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_load_titles"
            title = "Load Episode Titles"
            summary = "Fetch custom names for episode items"
            setDefaultValue(true)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_load_descriptions"
            title = "Load Episode Descriptions"
            summary = "Fetch synopsis descriptions for episodes"
            setDefaultValue(true)
        }.also { screen.addPreference(it) }
    }

    // ================================ Models ================================

    @Serializable
    private data class AnimeItem(
        val id: JsonPrimitive? = null,
        val malId: Long? = null,
        val title: TitleItem? = null,
        val image: String? = null,
        val coverImage: CoverImageItem? = null,
    )

    @Serializable
    private data class TitleItem(
        val english: String? = null,
        val romaji: String? = null,
        val userPreferred: String? = null,
    )

    @Serializable
    private data class CoverImageItem(
        val extraLarge: String? = null,
        val large: String? = null,
        val medium: String? = null,
    )

    @Serializable
    private data class EpisodeItem(
        val number: Float? = null,
        val episodeNumber: Float? = null,
        val title: String? = null,
        val img: String? = null,
        val description: String? = null,
        val isFiller: Boolean? = false,
        val hasSub: Boolean? = false,
        val hasDub: Boolean? = false,
    )

    @Serializable
    private data class ServersResponse(
        val data: ServersData? = null,
        val subProviders: List<ServerItem>? = null,
        val dubProviders: List<ServerItem>? = null,
    )

    @Serializable
    private data class ServersData(
        val subProviders: List<ServerItem>? = null,
        val dubProviders: List<ServerItem>? = null,
    )

    @Serializable
    private data class ServerItem(
        val id: String? = null,
        val tip: String? = null,
    )

    @Serializable
    private data class SourcesResponse(
        val data: SourcesData? = null,
        val sources: List<SourceItem>? = null,
        val subtitles: List<SubtitleItem>? = null,
        val tracks: List<SubtitleItem>? = null,
        @SerialName("headers")
        val apiHeaders: Map<String, String>? = null,
    )

    @Serializable
    private data class SourcesData(
        val sources: List<SourceItem>? = null,
        val subtitles: List<SubtitleItem>? = null,
        val tracks: List<SubtitleItem>? = null,
        @SerialName("headers")
        val apiHeaders: Map<String, String>? = null,
    )

    @Serializable
    private data class SourceItem(
        val url: String? = null,
        val quality: String? = null,
        val type: String? = null,
    )

    @Serializable
    private data class SubtitleItem(
        val url: String? = null,
        val label: String? = null,
        val lang: String? = null,
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"

        /** shiro's media host, which serves the XOR-hex encoded stream URL. */
        private const val SHIRO_MEDIA_BASE = "https://hls.dramavideo.se"

        /**
         * Providers that play straight from the API URL. Everything else goes through
         * the local proxy, so a provider the API adds later still gets its headers.
         */
        private val PLAIN_PROVIDERS = setOf("zuna")

        /** Provider ids currently returned by the API, plus legacy ids, for the settings screen. */
        private val KNOWN_SERVERS = listOf(
            "yuki" to "Yuki",
            "momo" to "Momo",
            "zuna" to "Zuna",
            "sora" to "Sora",
            "uwu" to "Uwu",
            "loli" to "Loli",
            "mimi" to "Mimi",
            "beep" to "Beep",
            "vee" to "Vee",
            "kiwi" to "Kiwi",
            "miku" to "Miku",
            "mochi" to "Mochi",
            "shiro" to "Shiro",
            "neko" to "Neko",
        )
    }
}

// ========================= Local Proxy Server =============================

private class LocalProxyServer(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val http1Client by lazy {
        client.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.let { if (it.isClosed) 0 else it.localPort } ?: 0

    fun start() {
        if (running.get() && serverSocket?.isClosed == false) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            executor.execute {
                while (running.get() && serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(socket) }
                    } catch (_: Exception) {
                        if (serverSocket?.isClosed == true || !running.get()) break
                    }
                }
            }
        } catch (_: Exception) {
            running.set(false)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val firstLine = input.bufferedReader().readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                routeRequest(parts[1], output)
            }
        }
    }

    private fun routeRequest(path: String, output: OutputStream) {
        val uri = Uri.parse("http://127.0.0.1$path")
        val encodedUrl = uri.getQueryParameter("url") ?: return
        val encodedHeaders = uri.getQueryParameter("headers")
        val targetUrl = try {
            String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } catch (_: Exception) {
            return
        }
        val hdrs = decodeHeaders(encodedHeaders)

        try {
            when {
                path.contains("playlist.m3u8") -> servePlaylist(targetUrl, hdrs, encodedHeaders, output)
                path.contains("key.bin") -> serveKey(targetUrl, hdrs, output)
                path.contains("subtitle.vtt") -> serveText(targetUrl, hdrs, output, "text/vtt")
                else -> serveSegment(targetUrl, hdrs, output)
            }
        } catch (_: Exception) {
            try {
                output.write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n".toByteArray())
            } catch (_: Exception) {}
        }
    }

    private fun decodeHeaders(encoded: String?): okhttp3.Headers {
        val fallback = okhttp3.Headers.Builder()
            .set("User-Agent", UA)
            .set("Referer", "https://anidap.lol/")
            .build()
        if (encoded.isNullOrEmpty()) return fallback
        return try {
            val jsonStr = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val map = json.decodeFromString<Map<String, String>>(jsonStr)
            okhttp3.Headers.Builder().apply { for ((k, v) in map) set(k, v) }.build()
        } catch (_: Exception) {
            fallback
        }
    }

    private fun getProxyUrl(url: String, headersStr: String?, isKey: Boolean = false): String {
        val encoded = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val path = when {
            isKey || url.contains(".key") || url.contains("key.bin") -> "key.bin"
            url.contains(".m3u8") -> "playlist.m3u8"
            url.contains(".vtt") || url.contains(".srt") || url.contains("subtitle") -> "subtitle.vtt"
            else -> "segment.ts"
        }
        val query = "url=$encoded" + if (!headersStr.isNullOrEmpty()) "&headers=$headersStr" else ""
        return "http://127.0.0.1:$port/$path?$query"
    }

    private fun fetchWithRetry(targetUrl: String, hdrs: okhttp3.Headers): okhttp3.Response {
        val isKrussdomi = targetUrl.contains("krussdomi.com") || targetUrl.contains("subst")
        val clientToUse = if (isKrussdomi) http1Client else client
        var response = runCatching {
            clientToUse.newCall(GET(targetUrl, hdrs)).execute()
        }.getOrElse {
            http1Client.newCall(GET(targetUrl, hdrs)).execute()
        }
        if (response.code == 403) {
            response.close()
            val fallback = if (isKrussdomi) {
                hdrs.newBuilder()
                    .set("Origin", "https://krussdomi.com")
                    .set("Referer", "https://krussdomi.com/")
                    .build()
            } else {
                hdrs.newBuilder().set("Referer", "https://anidap.lol/").build()
            }
            response = http1Client.newCall(GET(targetUrl, fallback)).execute()
        }
        return response
    }

    private fun servePlaylist(targetUrl: String, hdrs: okhttp3.Headers, encodedHeaders: String?, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, hdrs)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }
        val content = response.body.string()
        response.close()
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }
            if (trimmed.startsWith("#")) {
                val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                uriRegex.find(trimmed)?.let { match ->
                    val uriValue = match.groupValues[1]
                    val resolved = when {
                        uriValue.startsWith("//") -> "https:$uriValue"
                        else -> targetUrl.toHttpUrl().resolve(uriValue)?.toString() ?: uriValue
                    }
                    val isKeyLine = trimmed.contains("#EXT-X-KEY") || resolved.contains(".key")
                    builder.append(trimmed.replace(uriValue, getProxyUrl(resolved, encodedHeaders, isKey = isKeyLine)))
                } ?: builder.append(trimmed)
            } else {
                val resolved = when {
                    trimmed.startsWith("//") -> "https:$trimmed"
                    else -> targetUrl.toHttpUrl().resolve(trimmed)?.toString() ?: trimmed
                }
                builder.append(getProxyUrl(resolved, encodedHeaders, isKey = resolved.contains(".key")))
            }
            builder.append("\n")
        }

        val bodyBytes = builder.toString().toByteArray()
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
        output.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bodyBytes)
        output.flush()
    }

    private fun serveText(targetUrl: String, hdrs: okhttp3.Headers, output: OutputStream, contentType: String) {
        var response = runCatching { fetchWithRetry(targetUrl, hdrs) }.getOrNull()
        if (response == null || !response.isSuccessful) {
            response?.close()
            if (targetUrl.contains("krussdomi.com") || targetUrl.contains("subst")) {
                val uwuUrl = "https://cdnx.aniwatchtv.site/uwu/" + encodeUwu(targetUrl, "https://krussdomi.com")
                response = runCatching { fetchWithRetry(uwuUrl, hdrs) }.getOrNull()
            }
        }
        if (response == null || !response.isSuccessful) {
            val code = response?.code ?: 500
            output.write("HTTP/1.1 $code Error\r\nConnection: close\r\n\r\n".toByteArray())
            response?.close()
            return
        }
        val bytes = response.body.bytes()
        response.close()
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Content-Type: $contentType\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun encodeUwu(url: String, origin: String): String {
        val r = url.toByteArray(Charsets.UTF_8)
        val s = origin.toByteArray(Charsets.UTF_8)
        val n = ByteArray(r.size + 1 + s.size)
        System.arraycopy(r, 0, n, 0, r.size)
        n[r.size] = 0
        System.arraycopy(s, 0, n, r.size + 1, s.size)
        val c = "10b06cdc1ca48c9fb0b94af97cc040cf".toByteArray(Charsets.UTF_8)
        for (i in n.indices) {
            n[i] = (n[i].toInt() xor c[i % c.size].toInt()).toByte()
        }
        return Base64.encodeToString(n, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun serveKey(targetUrl: String, hdrs: okhttp3.Headers, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, hdrs)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }
        val bytes = response.body.bytes()
        response.close()
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Content-Type: application/octet-stream\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun serveSegment(targetUrl: String, hdrs: okhttp3.Headers, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, hdrs)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }
        val body = response.body
        val inputStream = body.byteStream()

        val headerBuffer = ByteArray(131072)
        var totalRead = 0
        while (totalRead < headerBuffer.size) {
            val read = inputStream.read(headerBuffer, totalRead, headerBuffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }

        val sample = if (totalRead == headerBuffer.size) headerBuffer else headerBuffer.copyOf(totalRead)
        val skipBytes = detectSkipBytes(sample)
        val contentLength = body.contentLength()
        val payloadLength = if (contentLength > 0) contentLength - skipBytes else -1L

        val headerBuilder = StringBuilder("HTTP/1.1 200 OK\r\n")
        if (payloadLength >= 0) headerBuilder.append("Content-Length: $payloadLength\r\n")
        headerBuilder.append("Content-Type: video/mp2t\r\n")
        headerBuilder.append("Connection: close\r\n\r\n")
        output.write(headerBuilder.toString().toByteArray())

        if (totalRead > skipBytes) output.write(sample, skipBytes, totalRead - skipBytes)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        output.flush()
        response.close()
    }

    private fun detectSkipBytes(data: ByteArray): Int {
        if (data.size < 4) return 0
        val isPng = data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        val isJpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        val isGif = data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte()
        if (!isPng && !isJpeg && !isGif) return 0

        val maxScan = minOf(data.size, 131072)
        if (isPng) {
            val iend = byteArrayOf(0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte())
            val maxIend = minOf(data.size - iend.size, maxScan)
            for (i in 0..maxIend) {
                if (data[i] == iend[0] && data[i + 1] == iend[1] && data[i + 2] == iend[2] && data[i + 3] == iend[3]) {
                    if (i + 8 <= data.size) return i + 8
                }
            }
        }
        val maxTs = minOf(data.size - 188 * 2, maxScan)
        for (i in 0..maxTs) {
            if (data[i] == 0x47.toByte()) {
                var validCount = 0
                val limit = minOf(data.size, i + 188 * 4)
                var j = i
                while (j < limit) {
                    if (data[j] == 0x47.toByte()) validCount++
                    j += 188
                }
                if (validCount >= 3) return i
            }
        }
        return 0
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
    }
}
