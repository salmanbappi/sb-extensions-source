package eu.kanade.tachiyomi.animeextension.all.lunar

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.EpisodeMetadataFetcher
import extensions.utils.Source
import extensions.utils.parseAs
import extensions.utils.toJsonString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder

class Lunar : Source() {

    override val name = "Lunar"
    override val baseUrl = "https://lunarx.to"
    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val metadataFetcher by lazy { EpisodeMetadataFetcher(client, json) }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== POPULAR ANIME ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$API_BASE/api/animes/search?query=", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val searchData = response.parseAs<SearchResponse>(json)
        val allAnime = searchData.animes.map { item ->
            SAnime.create().apply {
                url = "/anime/${item.slug}"
                title = item.title.orEmpty()
                thumbnail_url = item.poster_url
            }
        }
        return paginateList(allAnime, 1)
    }

    // ============================== LATEST UPDATES ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/anime/latest-aired?limit=25", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val latestData = response.parseAs<LatestResponse>(json)
        val animeList = latestData.data.map { item ->
            SAnime.create().apply {
                url = "/anime/${item.anime_id}"
                title = item.title.orEmpty()
                thumbnail_url = item.cover
            }
        }
        return AnimesPage(animeList, latestData.has_more)
    }

    // ============================== SEARCH ANIME ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$API_BASE/api/animes/search?query=$encodedQuery", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchData = response.parseAs<SearchResponse>(json)
        val animes = searchData.animes.map { item ->
            SAnime.create().apply {
                url = "/anime/${item.slug}"
                title = item.title.orEmpty()
                thumbnail_url = item.poster_url
            }
        }
        return AnimesPage(animes, false)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // ============================== ANIME DETAILS ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = extractSlug(anime.url)
        return GET("$API_BASE/api/anime/$slug", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        val rawSlug = extractSlug(response.request.url.encodedPath)
        val cleanSlug = extractCleanSlug(rawSlug)

        runCatching {
            val res = response.parseAs<AnimeDetailResponse>(json)
            val item = res.data.firstOrNull()
            if (item != null) {
                anime.title = item.title.orEmpty()
                anime.thumbnail_url = item.poster_url
                anime.description = cleanHtml(item.description)
                anime.genre = item.genres.joinToString(", ")
                anime.status = when {
                    item.end_year != null -> SAnime.COMPLETED
                    else -> SAnime.ONGOING
                }
            }
        }

        // Fallback for description and details if empty
        if (anime.description.isNullOrBlank() && cleanSlug != rawSlug) {
            runCatching {
                val fallbackReq = GET("$API_BASE/api/anime/$cleanSlug", headers)
                val fallbackResp = client.newCall(fallbackReq).execute()
                val res = fallbackResp.parseAs<AnimeDetailResponse>(json)
                val item = res.data.firstOrNull()
                if (item != null) {
                    if (anime.title.isBlank()) anime.title = item.title.orEmpty()
                    if (anime.thumbnail_url.isNullOrBlank()) anime.thumbnail_url = item.poster_url
                    if (anime.description.isNullOrBlank()) anime.description = cleanHtml(item.description)
                    if (anime.genre.isNullOrBlank()) anime.genre = item.genres.joinToString(", ")
                    if (anime.status == SAnime.UNKNOWN) {
                        anime.status = when {
                            item.end_year != null -> SAnime.COMPLETED
                            else -> SAnime.ONGOING
                        }
                    }
                }
            }
        }

        if (anime.description.isNullOrBlank()) {
            runCatching {
                val searchReq = GET("$API_BASE/api/animes/search?query=${URLEncoder.encode(anime.title.ifBlank { cleanSlug.replace("-", " ") }, "UTF-8")}", headers)
                val searchResp = client.newCall(searchReq).execute()
                val searchData = searchResp.parseAs<SearchResponse>(json)
                val match = searchData.animes.firstOrNull()
                if (match != null) {
                    if (anime.title.isBlank()) anime.title = match.title.orEmpty()
                    if (anime.thumbnail_url.isNullOrBlank()) anime.thumbnail_url = match.poster_url
                    if (anime.description.isNullOrBlank()) anime.description = cleanHtml(match.description)
                    if (anime.genre.isNullOrBlank()) anime.genre = match.genres.joinToString(", ")
                    if (anime.status == SAnime.UNKNOWN) {
                        anime.status = when {
                            match.end_year != null -> SAnime.COMPLETED
                            else -> SAnime.ONGOING
                        }
                    }
                }
            }
        }

        anime.initialized = true
        return anime
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val details = super.getAnimeDetails(anime)

        // Enrich with AniList English metadata if description or genres are missing or in German
        if (details.description.isNullOrBlank() || details.genre.isNullOrBlank() || details.status == SAnime.UNKNOWN) {
            val cleanTitle = details.title.ifBlank {
                extractCleanSlug(anime.url).replace("-", " ")
            }
            val meta = fetchAniListMetadata(cleanTitle)
            if (meta != null) {
                if (details.title.isBlank()) details.title = meta.title
                if (details.thumbnail_url.isNullOrBlank() && !meta.cover.isNullOrBlank()) details.thumbnail_url = meta.cover
                if (details.description.isNullOrBlank() && !meta.description.isNullOrBlank()) details.description = meta.description
                if (details.genre.isNullOrBlank() && meta.genres.isNotBlank()) details.genre = meta.genres
                if (details.status == SAnime.UNKNOWN) details.status = meta.status
            }
        }

        details.description = cleanHtml(details.description)
        details.initialized = true
        return details
    }

    private fun fetchAniListMetadata(queryTitle: String): AniListMeta? {
        val query = """
            query (${'$'}search: String) {
                Media(search: ${'$'}search, type: ANIME) {
                    id
                    title { english romaji userPreferred }
                    description(asHtml: false)
                    genres
                    status
                    bannerImage
                    coverImage { extraLarge large medium }
                }
            }
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().put("search", queryTitle))
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = jsonBody.toString().toRequestBody(mediaType)
        val req = POST("https://graphql.anilist.co", headers, body)

        return runCatching {
            val resp = client.newCall(req).execute()
            val text = resp.body.string()
            val jsonObj = JSONObject(text)
            val media = jsonObj.optJSONObject("data")?.optJSONObject("Media") ?: return null

            val titleObj = media.optJSONObject("title")
            val englishTitle = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("userPreferred")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("romaji")?.takeIf { it.isNotBlank() }

            val rawDesc = media.optString("description")
            val cleanDesc = cleanHtml(rawDesc)

            val genresList = mutableListOf<String>()
            val genresArr = media.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    genresList.add(genresArr.getString(i))
                }
            }

            val coverObj = media.optJSONObject("coverImage")
            val cover = coverObj?.optString("extraLarge")?.takeIf { it.isNotBlank() }
                ?: coverObj?.optString("large")?.takeIf { it.isNotBlank() }
                ?: coverObj?.optString("medium")

            val statusStr = media.optString("status")
            val statusVal = when (statusStr) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }

            AniListMeta(
                title = englishTitle.orEmpty(),
                description = cleanDesc,
                genres = genresList.joinToString(", "),
                cover = cover,
                status = statusVal,
            )
        }.getOrNull()
    }

    private fun cleanHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return html
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace(Regex("<.*?>"), "")
            .trim()
    }

    private data class AniListMeta(
        val title: String,
        val description: String?,
        val genres: String,
        val cover: String?,
        val status: Int,
    )

    // ============================ RECOMMENDATIONS ============================

    fun relatedAnimeListRequest(anime: SAnime): Request {
        val query = """
            query (${'$'}search: String) {
                Media(search: ${'$'}search, type: ANIME) {
                    recommendations(page: 1, perPage: 12, sort: [RATING_DESC]) {
                        edges {
                            node {
                                mediaRecommendation {
                                    id
                                    title { english romaji }
                                    coverImage { large medium }
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().put("search", anime.title))
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = jsonBody.toString().toRequestBody(mediaType)
        return POST("https://graphql.anilist.co", headers, body)
    }

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val body = response.body.string()
        val jsonObj = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val edges = jsonObj.optJSONObject("data")
            ?.optJSONObject("Media")
            ?.optJSONObject("recommendations")
            ?.optJSONArray("edges") ?: return emptyList()

        val results = mutableListOf<SAnime>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node")
            val media = node?.optJSONObject("mediaRecommendation") ?: continue
            val titleObj = media.optJSONObject("title")
            val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("romaji")?.takeIf { it.isNotBlank() } ?: continue

            val coverObj = media.optJSONObject("coverImage")
            val cover = coverObj?.optString("large")?.takeIf { it.isNotBlank() }
                ?: coverObj?.optString("medium")

            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            results.add(
                SAnime.create().apply {
                    url = "/anime/$slug"
                    this.title = title
                    thumbnail_url = cover
                },
            )
        }
        return results
    }

    // ============================== EPISODE LIST ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = extractSlug(anime.url)
        return GET("$API_BASE/api/animes/seasons?slug=$slug", headers)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = super.getEpisodeList(anime)
        val loadThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)
        val loadTitles = preferences.getBoolean(PREF_LOAD_TITLES_KEY, true)
        val loadDescriptions = preferences.getBoolean(PREF_LOAD_DESCRIPTIONS_KEY, true)

        if (!loadThumbnails && !loadTitles && !loadDescriptions) return episodes

        return runCatching {
            val metadataMap = metadataFetcher.fetch(malId = "", animeTitle = anime.title, fallbackThumbnailUrl = anime.thumbnail_url)
            if (metadataMap.isEmpty()) return episodes

            episodes.map { episode ->
                val num = episode.episode_number.toInt()
                val meta = metadataMap[num] ?: return@map episode
                episode.apply {
                    if (loadThumbnails && !meta.thumbnailUrl.isNullOrEmpty() && (preview_url.isNullOrEmpty() || preview_url!!.contains("SiteTitle"))) {
                        preview_url = meta.thumbnailUrl
                    }
                    if (loadDescriptions && !meta.description.isNullOrEmpty()) {
                        summary = meta.description
                    }
                    if (loadTitles && !meta.title.isNullOrBlank()) {
                        val seasonPrefix = if (name.startsWith("Season")) name.substringBefore("Episode").trim() + " - " else ""
                        val epPad = num.toString().padStart(2, '0')
                        name = "${seasonPrefix}Ep. $epPad - ${meta.title}"
                    }
                }
            }
        }.getOrDefault(episodes)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val rawSlug = response.request.url.queryParameter("slug") ?: return emptyList()
        val cleanSlug = extractCleanSlug(rawSlug)

        var seasonsCount = runCatching {
            val seasonsData = response.parseAs<SeasonsResponse>(json)
            maxOf(1, seasonsData.seasons)
        }.getOrDefault(1)

        val slug = if (seasonsCount <= 0 && cleanSlug != rawSlug) {
            cleanSlug
        } else {
            rawSlug
        }

        val episodes = mutableListOf<SEpisode>()
        var globalEpisodeNum = 1F

        for (season in 1..seasonsCount) {
            var seasonReq = GET("$API_BASE/api/animes/episodes?slug=$slug&season=$season", headers)
            var epCount = runCatching {
                val seasonResp = client.newCall(seasonReq).execute()
                val countData = seasonResp.parseAs<EpisodesCountResponse>(json)
                countData.episodes
            }.getOrDefault(0)

            if (epCount == 0 && slug != cleanSlug) {
                seasonReq = GET("$API_BASE/api/animes/episodes?slug=$cleanSlug&season=$season", headers)
                epCount = runCatching {
                    val seasonResp = client.newCall(seasonReq).execute()
                    val countData = seasonResp.parseAs<EpisodesCountResponse>(json)
                    countData.episodes
                }.getOrDefault(0)
            }

            for (ep in 1..epCount) {
                val epData = EpisodeData(slug = slug, season = season, episode = ep)
                episodes.add(
                    SEpisode.create().apply {
                        url = epData.toJsonString(json)
                        name = if (seasonsCount > 1) "Season $season Episode $ep" else "Episode $ep"
                        episode_number = globalEpisodeNum++
                        scanlator = "ENG-DUB | ENG-SUB | GER-DUB | GER-SUB"
                    },
                )
            }
        }

        return episodes.reversed()
    }

    // ============================== HOSTER LIST (BY LANGUAGE FOLDERS) ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val data = runCatching {
            episode.url.parseAs<EpisodeData>(json)
        }.getOrDefault(EpisodeData(slug = extractSlug(episode.url), season = 1, episode = 1))

        val cleanSlug = extractCleanSlug(data.slug.orEmpty())

        var streamReq = GET("$API_BASE/api/stream?slug=${data.slug}&season=${data.season}&episode=${data.episode}", headers)
        var streamData = runCatching {
            val resp = client.newCall(streamReq).execute()
            resp.parseAs<StreamResponse>(json)
        }.getOrNull()

        if ((streamData == null || streamData.episodes.isEmpty()) && cleanSlug != data.slug) {
            streamReq = GET("$API_BASE/api/stream?slug=$cleanSlug&season=${data.season}&episode=${data.episode}", headers)
            streamData = runCatching {
                val resp = client.newCall(streamReq).execute()
                resp.parseAs<StreamResponse>(json)
            }.getOrNull()
        }

        val hosters = streamData?.episodes?.flatMap { it.hosters }.orEmpty()
        val availableLangs = hosters.mapNotNull { it.language?.lowercase()?.trim() }.filter { it.isNotBlank() }

        val hasGerman = availableLangs.any { it.startsWith("ger") || it == "de" }
        val otherLangs = availableLangs.filter { !it.startsWith("eng") && !it.startsWith("ger") && it != "en" && it != "de" }.distinct()

        val hosterList = mutableListOf<Hoster>()

        // Always provide English Folder (containing Sub, Dub, HSub, and all qualities)
        hosterList.add(Hoster(hosterName = "English", hosterUrl = "${data.slug}|${data.season}|${data.episode}|en"))

        if (hasGerman) {
            hosterList.add(Hoster(hosterName = "German", hosterUrl = "${data.slug}|${data.season}|${data.episode}|de"))
        }

        for (lang in otherLangs) {
            val displayName = when (lang) {
                "jap-sub", "jap-dub", "jap" -> "Japanese"
                "fra-sub", "fra-dub", "fra" -> "French"
                "spa-sub", "spa-dub", "spa" -> "Spanish"
                "ita-sub", "ita-dub", "ita" -> "Italian"
                else -> lang.replace("-", " ").uppercase()
            }
            hosterList.add(Hoster(hosterName = displayName, hosterUrl = "${data.slug}|${data.season}|${data.episode}|$lang"))
        }

        // Prioritize folders based on user's preferred language folder setting
        val preferredLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT
        return hosterList.sortedByDescending { it.hosterUrl.endsWith("|$preferredLang", ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 4) return emptyList()
        val slug = parts[0]
        val season = parts[1].toIntOrNull() ?: 1
        val episode = parts[2].toIntOrNull() ?: 1
        val targetLang = parts[3].lowercase()
        val cleanSlug = extractCleanSlug(slug)

        var streamReq = GET("$API_BASE/api/stream?slug=$slug&season=$season&episode=$episode", headers)
        var streamData = runCatching {
            val resp = client.newCall(streamReq).execute()
            resp.parseAs<StreamResponse>(json)
        }.getOrNull()

        if ((streamData == null || streamData.episodes.isEmpty()) && cleanSlug != slug) {
            streamReq = GET("$API_BASE/api/stream?slug=$cleanSlug&season=$season&episode=$episode", headers)
            streamData = runCatching {
                val resp = client.newCall(streamReq).execute()
                resp.parseAs<StreamResponse>(json)
            }.getOrNull()
        }

        val allHosters = streamData?.episodes?.flatMap { it.hosters }.orEmpty()

        val matchingHosters = when (targetLang) {
            "en", "english" -> allHosters.filter {
                val l = it.language.orEmpty().lowercase()
                l.startsWith("eng") || l == "en"
            }

            "de", "german" -> allHosters.filter {
                val l = it.language.orEmpty().lowercase()
                l.startsWith("ger") || l == "de"
            }

            else -> allHosters.filter {
                it.language.orEmpty().equals(targetLang, ignoreCase = true)
            }
        }

        val videos = matchingHosters.parallelCatchingFlatMap { hosterItem ->
            extractVideoFromHoster(hosterItem)
        }.toMutableList()

        if (targetLang == "en" || targetLang == "english") {
            videos.addAll(fetch3rdProviderVideos(slug, episode))
        }

        return videos.sortVideos()
    }

    // ============================== 3RD PROVIDER RESOLVER ==============================

    private fun fetch3rdProviderVideos(slug: String, episode: Int): List<Video> {
        val videos = mutableListOf<Video>()
        val cleanSlug = extractCleanSlug(slug)

        fun tryFetch(s: String) {
            val req = GET("$API_BASE/api/3rdprovider?slug=$s&episode=$episode&autoplay=true", headers)
            val resp = client.newCall(req).execute()
            val thirdData = resp.parseAs<ThirdPartyResponse>(json)
            for (item in thirdData.data) {
                val playerUrl = item.player_url.orEmpty()
                if (playerUrl.isBlank()) continue
                val serverName = when (item.server?.lowercase()) {
                    "sv-1" -> "Fast Cloud 1"
                    "sv-2" -> "Fast Cloud 2"
                    null -> "Fast Cloud"
                    else -> item.server
                }
                val audio = item.audio?.lowercase() ?: "dual"

                when (audio) {
                    "dual" -> {
                        val dubPrefix = "[Dub] [$serverName] "
                        val subPrefix = "[Sub] [$serverName] "
                        when {
                            playerUrl.contains(".m3u8") -> {
                                videos.addAll(playlistUtils.extractFromHls(playerUrl, videoNameGen = { q -> dubPrefix + q }))
                                videos.addAll(playlistUtils.extractFromHls(playerUrl, videoNameGen = { q -> subPrefix + q }))
                            }

                            else -> {
                                videos.addAll(universalExtractor.videosFromUrl(playerUrl, headers, prefix = dubPrefix))
                                videos.addAll(universalExtractor.videosFromUrl(playerUrl, headers, prefix = subPrefix))
                            }
                        }
                    }

                    "dub" -> {
                        val prefix = "[Dub] [$serverName] "
                        when {
                            playerUrl.contains(".m3u8") -> {
                                videos.addAll(playlistUtils.extractFromHls(playerUrl, videoNameGen = { q -> prefix + q }))
                            }

                            else -> {
                                videos.addAll(universalExtractor.videosFromUrl(playerUrl, headers, prefix = prefix))
                            }
                        }
                    }

                    "hsub" -> {
                        val prefix = "[HSub] [$serverName] "
                        when {
                            playerUrl.contains(".m3u8") -> {
                                videos.addAll(playlistUtils.extractFromHls(playerUrl, videoNameGen = { q -> prefix + q }))
                            }

                            else -> {
                                videos.addAll(universalExtractor.videosFromUrl(playerUrl, headers, prefix = prefix))
                            }
                        }
                    }

                    else -> {
                        val prefix = "[Sub] [$serverName] "
                        when {
                            playerUrl.contains(".m3u8") -> {
                                videos.addAll(playlistUtils.extractFromHls(playerUrl, videoNameGen = { q -> prefix + q }))
                            }

                            else -> {
                                videos.addAll(universalExtractor.videosFromUrl(playerUrl, headers, prefix = prefix))
                            }
                        }
                    }
                }
            }
        }

        runCatching { tryFetch(slug) }
        if (videos.isEmpty() && cleanSlug != slug) {
            runCatching { tryFetch(cleanSlug) }
        }

        return videos.sortVideos()
    }

    // ============================== VIDEO LIST (FALLBACK) ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val data = runCatching {
            episode.url.parseAs<EpisodeData>(json)
        }.getOrDefault(EpisodeData(slug = extractSlug(episode.url), season = 1, episode = 1))

        return GET("$API_BASE/api/stream?slug=${data.slug}&season=${data.season}&episode=${data.episode}", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val slug = response.request.url.queryParameter("slug") ?: extractSlug(response.request.url.encodedPath)
        val episode = response.request.url.queryParameter("episode")?.toIntOrNull() ?: 1

        val streamData = runCatching {
            response.parseAs<StreamResponse>(json)
        }.getOrNull()

        val hosters = streamData?.episodes?.flatMap { it.hosters }.orEmpty()

        val videos = hosters.parallelCatchingFlatMapBlocking { hosterItem ->
            extractVideoFromHoster(hosterItem)
        }.toMutableList()

        videos.addAll(fetch3rdProviderVideos(slug, episode))

        return videos.sortVideos()
    }

    private suspend fun extractVideoFromHoster(hosterItem: HosterItem): List<Video> {
        val rawHoster = hosterItem.hoster.orEmpty().trim().lowercase()
        val lang = hosterItem.language.orEmpty().lowercase()
        val uri = hosterItem.redirect_uri.orEmpty()
        if (uri.isBlank()) return emptyList()

        val typeTag = when {
            lang.contains("hsub") -> "[HSub]"
            lang.contains("dub") -> "[Dub]"
            else -> "[Sub]"
        }

        val hosterDisplayName = when (rawHoster) {
            "vidmoly" -> "Vidmoly"
            "voe" -> "VOE"
            "filemoon" -> "Filemoon"
            "doodstream", "dood" -> "DoodStream"
            "streamtape" -> "StreamTape"
            "luluvdo", "lulu" -> "LuluStream"
            "streamwish" -> "StreamWish"
            "vidguard" -> "Vidguard"
            "videzz" -> "Videzz"
            else -> rawHoster.replaceFirstChar { it.uppercase() }.ifBlank { "Stream" }
        }

        val hosterTag = "[$hosterDisplayName]"
        val prefix = "$typeTag $hosterTag "

        return when {
            rawHoster == "vidmoly" || uri.contains("vidmoly") -> {
                vidMolyExtractor.videosFromUrl(uri, prefix = prefix)
            }

            rawHoster == "voe" || uri.contains("voe.") -> {
                voeExtractor.videosFromUrl(uri, prefix = prefix)
            }

            rawHoster == "filemoon" || uri.contains("filemoon") -> {
                filemoonExtractor.videosFromUrl(uri, prefix = prefix)
            }

            rawHoster == "doodstream" || rawHoster == "dood" || uri.contains("dood") || uri.contains("ds2play") || uri.contains("bysezejataos") -> {
                doodExtractor.videosFromUrl(uri, quality = "$typeTag [$hosterDisplayName] DoodStream")
            }

            rawHoster == "streamtape" || uri.contains("streamtape") -> {
                streamTapeExtractor.videosFromUrl(uri, quality = "$typeTag [$hosterDisplayName] StreamTape")
            }

            rawHoster == "luluvdo" || rawHoster == "lulu" || uri.contains("luluvdo") -> {
                luluExtractor.videosFromUrl(uri, prefix = prefix)
            }

            rawHoster == "streamwish" || uri.contains("streamwish") || uri.contains("wishembed") || uri.contains("swish") -> {
                streamWishExtractor.videosFromUrl(uri, prefix = prefix)
            }

            rawHoster == "vidguard" || uri.contains("vidguard") || uri.contains("vgfplay") || uri.contains("vembed") -> {
                vidGuardExtractor.videosFromUrl(uri, prefix = prefix)
            }

            uri.contains(".m3u8") -> {
                playlistUtils.extractFromHls(uri, videoNameGen = { q -> "$typeTag $hosterTag $q" })
            }

            else -> {
                universalExtractor.videosFromUrl(uri, headers, prefix = prefix)
            }
        }
    }

    // ============================== PREFERENCES & SORTING ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT).orEmpty().lowercase()
        val preferredHoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).orEmpty().lowercase()
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT).orEmpty()

        return sortedWith(
            compareByDescending<Video> { video ->
                val q = video.videoTitle.lowercase()
                when {
                    preferredType.isNotBlank() && q.contains("[$preferredType]") -> 1
                    else -> 0
                }
            }.thenByDescending { video ->
                val q = video.videoTitle.lowercase()
                when {
                    preferredHoster.isNotBlank() && q.contains(preferredHoster) -> 1
                    else -> 0
                }
            }.thenByDescending { video ->
                when {
                    preferredQuality.isNotBlank() && video.videoTitle.contains(preferredQuality) -> 1
                    else -> 0
                }
            },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Preferred Language Folder"
            entries = arrayOf("English", "German", "All Languages")
            entryValues = arrayOf("en", "de", "all")
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = "Preferred Audio / Stream Type"
            entries = arrayOf("Dub (English / German Dub)", "Sub (Subbed)", "HSub (Hardsub)")
            entryValues = arrayOf("dub", "sub", "hsub")
            setDefaultValue(PREF_TYPE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Preferred Server / Hoster"
            entries = arrayOf("Fast Cloud", "VOE", "Vidmoly", "Filemoon", "DoodStream", "StreamTape", "LuluStream", "StreamWish", "Vidguard")
            entryValues = arrayOf("fast cloud", "voe", "vidmoly", "filemoon", "dood", "streamtape", "lulu", "streamwish", "vidguard")
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_THUMBNAILS_KEY
            title = "Show episode thumbnails"
            summary = "Display high quality episode thumbnail images in episode list"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LOAD_DESCRIPTIONS_KEY
            title = "Load episode summaries (spoilers)"
            summary = "Fetch and show episode descriptions in the episode info"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LOAD_TITLES_KEY
            title = "Load episode titles"
            summary = "Fetch canonical episode titles from metadata service"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== UTILITIES & DTOS ==============================

    private fun extractSlug(url: String): String = url.removePrefix("/anime/")
        .removePrefix("/")
        .substringBefore("?")
        .substringBefore("#")

    private fun extractCleanSlug(urlOrSlug: String): String {
        val slug = extractSlug(urlOrSlug)
        return slug.replace(Regex("-[a-z0-9]{6}$"), "")
    }

    private fun paginateList(list: List<SAnime>, page: Int, perPage: Int = 25): AnimesPage {
        val startIdx = (page - 1) * perPage
        val endIdx = minOf(startIdx + perPage, list.size)
        if (startIdx >= list.size) return AnimesPage(emptyList(), false)
        val sublist = list.subList(startIdx, endIdx)
        return AnimesPage(sublist, endIdx < list.size)
    }

    companion object {
        private const val API_BASE = "https://api.lunarx.to"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "en"

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "dub"

        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_DEFAULT = "fast cloud"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"
        private const val PREF_LOAD_DESCRIPTIONS_KEY = "pref_load_descriptions"
        private const val PREF_LOAD_TITLES_KEY = "pref_load_titles"
    }

    @Serializable
    data class LatestResponse(
        val data: List<LatestAnimeItem> = emptyList(),
        val has_more: Boolean = false,
        val next_cursor: String? = null,
    )

    @Serializable
    data class LatestAnimeItem(
        val anime_id: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val format: String? = null,
        val subbed: Int = 0,
        val dubbed: Int = 0,
        val episode_number: Int = 0,
        val episode_title: String? = null,
        val aired: String? = null,
    )

    @Serializable
    data class SearchResponse(
        val animes: List<AnimeItem> = emptyList(),
        val message: String? = null,
    )

    @Serializable
    data class AnimeItem(
        val slug: String? = null,
        val title: String? = null,
        val poster_url: String? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val alt_titles: List<String> = emptyList(),
        val start_year: Int? = null,
        val end_year: Int? = null,
        val tmdb_id: String? = null,
    )

    @Serializable
    data class AnimeDetailResponse(
        val data: List<AnimeDetailItem> = emptyList(),
    )

    @Serializable
    data class AnimeDetailItem(
        val slug: String? = null,
        val title: String? = null,
        val poster_url: String? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val alt_titles: List<String> = emptyList(),
        val start_year: Int? = null,
        val end_year: Int? = null,
        val movie: Boolean = false,
    )

    @Serializable
    data class SeasonsResponse(
        val seasons: Int = 1,
    )

    @Serializable
    data class EpisodesCountResponse(
        val episodes: Int = 0,
    )

    @Serializable
    data class StreamResponse(
        val episodes: List<StreamEpisodeItem> = emptyList(),
        val message: String? = null,
    )

    @Serializable
    data class StreamEpisodeItem(
        val episode: Int = 1,
        val season: Int = 1,
        val title: String? = null,
        val hosters: List<HosterItem> = emptyList(),
    )

    @Serializable
    data class HosterItem(
        val hoster: String? = null,
        val language: String? = null,
        val redirect_uri: String? = null,
        val owned: Boolean = false,
    )

    @Serializable
    data class EpisodeData(
        val slug: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    @Serializable
    data class ThirdPartyResponse(
        val data: List<ThirdPartyItem> = emptyList(),
        val success: Boolean = false,
    )

    @Serializable
    data class ThirdPartyItem(
        val server: String? = null,
        val audio: String? = null,
        val player_url: String? = null,
    )
}
