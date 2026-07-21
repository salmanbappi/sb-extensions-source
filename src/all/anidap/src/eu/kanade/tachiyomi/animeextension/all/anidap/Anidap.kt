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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Proxy (mimi / yuki / loli only) =======

    private fun getProxyUrl(url: String, sourceHeaders: Headers? = null): String {
        if (proxy == null) {
            proxy = LocalProxyServer(client, json).apply { start() }
        }
        val encodedUrl = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encodedHeaders = encodeHeaders(sourceHeaders)
        val path = if (url.contains(".m3u8")) "playlist.m3u8" else "segment.ts"
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
            json.decodeFromString<ServersResponse>(response.body.string())
        }.getOrNull() ?: ServersResponse()

        val disabledServers = preferences.getStringSet("pref_disabled_servers", emptySet()) ?: emptySet()
        val preferredType = preferences.getString("pref_audio_type", "sub") ?: "sub"

        val subProviders = serversData.data?.subProviders ?: serversData.subProviders ?: emptyList()
        val dubProviders = serversData.data?.dubProviders ?: serversData.dubProviders ?: emptyList()

        // Servers that get combined sub+dub into one hoster with soft/hard sub label
        val proxiedServers = setOf("mimi", "yuki", "loli")

        // --- Proxied servers: one combined hoster per server ---
        data class ServerInfo(val tip: String?, val hasSub: Boolean, val hasDub: Boolean)
        val proxiedMap = linkedMapOf<String, ServerInfo>()

        for (server in subProviders) {
            if (server.id !in proxiedServers || disabledServers.contains(server.id)) continue
            val existing = proxiedMap[server.id]
            proxiedMap[server.id] = ServerInfo(server.tip ?: existing?.tip, true, existing?.hasDub ?: false)
        }
        for (server in dubProviders) {
            if (server.id !in proxiedServers || disabledServers.contains(server.id)) continue
            val existing = proxiedMap[server.id]
            proxiedMap[server.id] = ServerInfo(server.tip ?: existing?.tip, existing?.hasSub ?: false, true)
        }

        val proxiedHosters = proxiedMap.map { (id, info) ->
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
            Hoster(
                hosterName = "${id.uppercase()} [$subType] [$audioLabel]",
                hosterUrl = "proxy|$animeId|$epNum|$id|${if (info.hasSub) "1" else "0"}|${if (info.hasDub) "1" else "0"}",
            )
        }

        // --- All other servers: separate sub/dub hosters as before ---
        val hosters = mutableListOf<Hoster>()

        val addSub = { providers: List<ServerItem> ->
            providers.forEach { server ->
                if (server.id !in proxiedServers && !disabledServers.contains(server.id)) {
                    hosters.add(Hoster(hosterName = "SUB - ${server.id.uppercase()}", hosterUrl = "plain|$animeId|$epNum|sub|${server.id}"))
                }
            }
        }
        val addDub = { providers: List<ServerItem> ->
            providers.forEach { server ->
                if (server.id !in proxiedServers && !disabledServers.contains(server.id)) {
                    hosters.add(Hoster(hosterName = "DUB - ${server.id.uppercase()}", hosterUrl = "plain|$animeId|$epNum|dub|${server.id}"))
                }
            }
        }

        if (preferredType == "dub") {
            addDub(dubProviders)
            addSub(subProviders)
        } else {
            addSub(subProviders)
            addDub(dubProviders)
        }

        return sortHostersByPreference(proxiedHosters + hosters)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        return when (parts.firstOrNull()) {
            "proxy" -> {
                // mimi / yuki / loli — local proxy path
                if (parts.size < 6) return emptyList()
                val animeId = parts[1]
                val epNum = parts[2]
                val providerId = parts[3]
                val hasSub = parts[4] == "1"
                val hasDub = parts[5] == "1"
                val videos = mutableListOf<Video>()
                if (hasSub) videos.addAll(fetchProxiedVideos(animeId, epNum, providerId, "sub"))
                if (hasDub) videos.addAll(fetchProxiedVideos(animeId, epNum, providerId, "dub"))
                videos.sortVideos()
            }

            "plain" -> {
                // All other servers — existing extractor logic
                if (parts.size < 5) return emptyList()
                val animeId = parts[1]
                val epNum = parts[2]
                val type = parts[3]
                val providerId = parts[4]
                fetchPlainVideos(animeId, epNum, type, providerId)
            }

            else -> emptyList()
        }
    }

    /** Fetch videos for mimi/yuki/loli through the local proxy (injects Referer per segment). */
    private fun fetchProxiedVideos(animeId: String, epNum: String, providerId: String, type: String): List<Video> {
        val requestUrl = "https://chad.anidap.lol/rest/api/sources?id=$animeId&epNum=$epNum&type=$type&providerId=$providerId"
        val response = client.newCall(GET(requestUrl, headers)).execute()
        val sourcesData = runCatching {
            json.decodeFromString<SourcesResponse>(response.body.string())
        }.getOrNull() ?: return emptyList()

        val sources = sourcesData.data?.sources ?: sourcesData.sources ?: emptyList()
        if (sources.isEmpty()) return emptyList()

        val rawSubs = sourcesData.data?.subtitles ?: sourcesData.subtitles
            ?: sourcesData.data?.tracks ?: sourcesData.tracks ?: emptyList()
        val subtitles = rawSubs.mapNotNull { track ->
            val trackUrl = track.url ?: return@mapNotNull null
            Track(url = trackUrl, lang = track.label ?: track.lang ?: "Sub")
        }

        // Referer from API's "headers" field — injected on every segment via the proxy
        val apiHeaders = sourcesData.data?.apiHeaders ?: sourcesData.apiHeaders ?: emptyMap()
        val referer = apiHeaders["Referer"] ?: apiHeaders["referer"]
        val sourceHeaders = headers.newBuilder().apply {
            referer?.let { set("Referer", it) }
        }.build()

        return sources.mapNotNull { src ->
            val rawUrl = src.url ?: return@mapNotNull null
            val finalUrl = transformSourceUrl(rawUrl, providerId)
            val titleLabel = "${type.uppercase()} - ${providerId.uppercase()} - ${src.quality ?: "Auto"}"
            if (finalUrl.contains(".m3u8")) {
                Video(
                    videoUrl = getProxyUrl(finalUrl, sourceHeaders),
                    videoTitle = titleLabel,
                    headers = headers,
                    subtitleTracks = subtitles,
                )
            } else {
                Video(
                    videoUrl = finalUrl,
                    videoTitle = titleLabel,
                    headers = sourceHeaders,
                    subtitleTracks = subtitles,
                )
            }
        }
    }

    /** Fetch videos for all other servers using existing extractors. */
    private fun fetchPlainVideos(animeId: String, epNum: String, type: String, providerId: String): List<Video> {
        val requestUrl = "https://chad.anidap.lol/rest/api/sources?id=$animeId&epNum=$epNum&type=$type&providerId=$providerId"
        val response = client.newCall(GET(requestUrl, headers)).execute()
        val sourcesData = runCatching {
            json.decodeFromString<SourcesResponse>(response.body.string())
        }.getOrNull() ?: return emptyList()

        val sources = sourcesData.data?.sources ?: sourcesData.sources ?: emptyList()
        if (sources.isEmpty()) return emptyList()

        val rawSubs = sourcesData.data?.subtitles ?: sourcesData.subtitles
            ?: sourcesData.data?.tracks ?: sourcesData.tracks ?: emptyList()
        val subtitles = rawSubs.mapNotNull { track ->
            val trackUrl = track.url ?: return@mapNotNull null
            Track(url = trackUrl, lang = track.label ?: track.lang ?: "Sub")
        }

        val videos = mutableListOf<Video>()
        for (src in sources) {
            val rawUrl = src.url ?: continue
            val finalUrl = transformSourceUrl(rawUrl, providerId)
            val titleLabel = "${type.uppercase()} - ${providerId.uppercase()} - ${src.quality ?: "Auto"}"

            when {
                providerId.equals("mp4upload", ignoreCase = true) -> {
                    videos.addAll(mp4uploadExtractor.videosFromUrl(finalUrl, headers))
                }

                providerId.equals("okru", ignoreCase = true) -> {
                    videos.addAll(okruExtractor.videosFromUrl(finalUrl))
                }

                finalUrl.contains(".m3u8") -> {
                    val playlistVideos = playlistUtils.extractFromHls(
                        playlistUrl = finalUrl,
                        masterHeaders = headers,
                        videoHeaders = headers,
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
                            headers = headers,
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            }
        }
        return videos.sortVideos()
    }

    private fun transformSourceUrl(url: String, providerId: String): String = when (providerId.lowercase()) {
        "shiro" -> "${b(url)}&origin=https://kem.clvd.xyz/"

        "kami" -> "${b(url)}&origin=https://krussdomi.com"

        "vee" -> if (url.startsWith("https://cdn.animeonsen.xyz")) url else "${b(url)}&origin=https://www.animeonsen.xyz/"

        "yuki" -> f(url, "https://megaplay.buzz")

        "uwu" -> f(url, "https://kwik.cx/")

        "miku" -> f(url, "https://allanime.uns.bio")

        "mochi" -> url.replace("https://tools.fast4speed.rsvp", "https://mp4.24stream.xyz/storage")

        "beep" -> when {
            url.startsWith("https://bd.24stream.xyz/media") -> url
            url.startsWith("/") -> "https://bd.24stream.xyz/media${url.replace("/r2", "")}"
            else -> "https://bd.24stream.xyz/media${url.replace(Regex("https?://[^/]+"), "").replace("/r2", "")}"
        }

        "mimi" -> url.replace("https://vivibebe.site/public/stream/", "https://hawk.aniwatchtv.site/media/")

        else -> url
    }

    private fun b(url: String): String {
        val bytes = url.toByteArray()
        val xored = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor 137).toByte() }
        val hex = xored.joinToString("") { "%02x".format(it) }
        return "https://crs.24stream.xyz/media/$hex"
    }

    private fun f(url: String, referer: String): String {
        val urlBytes = url.toByteArray()
        val refBytes = referer.toByteArray()
        val combined = ByteArray(urlBytes.size + 1 + refBytes.size)
        System.arraycopy(urlBytes, 0, combined, 0, urlBytes.size)
        combined[urlBytes.size] = 0
        System.arraycopy(refBytes, 0, combined, urlBytes.size + 1, refBytes.size)

        val key = "10b06cdc1ca48c9fb0b94af97cc040cf".toByteArray()
        for (i in combined.indices) {
            combined[i] = (combined[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        val base64 = Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val domain = SITES_DOMAINS[siteIndex % SITES_DOMAINS.size]
        siteIndex++
        return "$domain/uwu/$base64"
    }

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val preferredServer = preferences.getString("pref_preferred_server", "mimi") ?: "mimi"
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
            entries = arrayOf("Mimi", "Beep", "Yuki", "Kiwi", "Vee", "Miku", "Mochi", "Loli")
            entryValues = arrayOf("mimi", "beep", "yuki", "kiwi", "vee", "miku", "mochi", "loli")
            setDefaultValue("mimi")
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = "pref_audio_type"
            title = "Preferred Audio Category"
            summary = "Show Subbed, Dubbed, or Both servers"
            entries = arrayOf("Sub", "Dub", "Both")
            entryValues = arrayOf("sub", "dub", "both")
            setDefaultValue("sub")
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
            entries = arrayOf("Beep", "Mimi", "Vee", "Yuki", "Loli", "Uwu", "Kiwi", "Miku", "Mochi")
            entryValues = arrayOf("beep", "mimi", "vee", "yuki", "loli", "uwu", "kiwi", "miku", "mochi")
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
        val id: String,
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
    )

    @Serializable
    private data class SubtitleItem(
        val url: String? = null,
        val label: String? = null,
        val lang: String? = null,
    )

    companion object {
        private val SITES_DOMAINS = listOf(
            "https://cx.aniwatchtv.site",
            "https://nsx.aniwatchtv.site",
            "https://pro.aniwatchtv.site",
            "https://rl2.aniwatchtv.site",
            "https://rrl.aniwatchtv.site",
        )
        private var siteIndex = 0
    }
}

// ========================= Local Proxy Server =============================

private class LocalProxyServer(
    private val client: OkHttpClient,
    private val json: Json,
) {
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
            else -> "segment.ts"
        }
        val query = "url=$encoded" + if (!headersStr.isNullOrEmpty()) "&headers=$headersStr" else ""
        return "http://127.0.0.1:$port/$path?$query"
    }

    private fun fetchWithRetry(targetUrl: String, hdrs: okhttp3.Headers): okhttp3.Response {
        var response = client.newCall(GET(targetUrl, hdrs)).execute()
        if (response.code == 403) {
            response.close()
            val fallback = hdrs.newBuilder().set("Referer", "https://anidap.lol/").build()
            response = client.newCall(GET(targetUrl, fallback)).execute()
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
