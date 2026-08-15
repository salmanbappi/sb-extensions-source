package eu.kanade.tachiyomi.animeextension.en.meguanime

import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Meguanime :
    Source(),
    ConfigurableAnimeSource {

    override val name = "MeguAnime"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(4, 1.seconds)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val m3u8Integration by lazy { M3u8Integration(client) }

    override val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular / Latest ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = POPULAR_QUERY,
            variables = GraphQLVariables(page = page, perPage = 24),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(ANILIST_GRAPHQL_URL, headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeMediaPage(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = LATEST_QUERY,
            variables = GraphQLVariables(page = page, perPage = 24),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(ANILIST_GRAPHQL_URL, headers, body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeMediaPage(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var selectedSort = listOf("TRENDING_DESC")
        var selectedGenres: List<String>? = null
        var selectedFormats: List<String>? = null
        var selectedStatus: List<String>? = null
        var selectedSeason: String? = null
        var selectedYear: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is Filters.SortFilter -> {
                    selectedSort = listOf(filter.toValue())
                }

                is Filters.GenreFilter -> {
                    val genres = filter.getCheckedValues()
                    if (genres.isNotEmpty()) selectedGenres = genres
                }

                is Filters.FormatFilter -> {
                    val formats = filter.getCheckedValues()
                    if (formats.isNotEmpty()) selectedFormats = formats
                }

                is Filters.StatusFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) selectedStatus = listOf(value)
                }

                is Filters.SeasonFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) selectedSeason = value
                }

                is Filters.YearFilter -> {
                    val value = filter.state.trim()
                    if (value.isNotBlank()) selectedYear = value.toIntOrNull()
                }

                else -> {}
            }
        }

        val queryBody = GraphQLRequest(
            query = SEARCH_QUERY,
            variables = GraphQLVariables(
                page = page,
                perPage = 24,
                search = query.trim().takeIf { it.isNotBlank() },
                sort = selectedSort,
                genres = selectedGenres,
                format = selectedFormats,
                status = selectedStatus,
                season = selectedSeason,
                seasonYear = selectedYear,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(ANILIST_GRAPHQL_URL, headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeMediaPage(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.SortFilter(),
        Filters.FormatFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.YearFilter(),
        Filters.GenreFilter(),
    )

    private fun parseAnimeMediaPage(response: Response): AnimesPage {
        val anilistRes = response.parseAs<AnilistGraphQLResponse>()
        val page = anilistRes.data?.Page ?: return AnimesPage(emptyList(), false)

        val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT
        val animeList = page.media.map { media ->
            SAnime.create().apply {
                url = "/anime/${media.id}"
                title = resolveTitle(media.title, titleLang)
                thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large ?: media.coverImage?.medium
                genre = media.genres?.joinToString(", ") ?: ""
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = page.pageInfo?.hasNextPage ?: (animeList.size >= 24)
        return AnimesPage(animeList, hasNext)
    }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val anilistId = anime.url.substringAfter("/anime/").substringBefore("?").substringBefore("#").toInt()
        val queryBody = GraphQLRequest(
            query = DETAILS_QUERY,
            variables = GraphQLVariables(id = anilistId),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(ANILIST_GRAPHQL_URL, headers, body)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anilistRes = response.parseAs<AnilistGraphQLResponse>()
        val media = anilistRes.data?.Media ?: throw Exception("Anime details not found")

        val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT
        val scorePosition = preferences.getString(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT) ?: PREF_SCORE_POSITION_DEFAULT

        return SAnime.create().apply {
            title = resolveTitle(media.title, titleLang)
            thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large ?: media.coverImage?.medium
            genre = media.genres?.joinToString(", ") ?: ""
            author = media.studios?.nodes?.firstOrNull()?.name
            status = when (media.status) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "NOT_YET_RELEASED" -> SAnime.UNKNOWN
                "CANCELLED" -> SAnime.CANCELLED
                "HIATUS" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            initialized = true

            val score = media.averageScore?.let { it / 10.0 }
            val synopsis = media.description?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""

            description = buildString {
                if (scorePosition == "top" && score != null && score > 0.0) {
                    val full = (score / 2.0).toInt().coerceIn(0, 5)
                    append("${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.2f".format(score)}\n\n")
                }

                if (synopsis.isNotBlank()) {
                    append(synopsis)
                }

                if (media.format != null) {
                    append("\n\nFormat: ${media.format}")
                }
                if (media.duration != null && media.duration > 0) {
                    append("\nDuration: ${media.duration} mins")
                }
                if (media.studios?.nodes?.isNotEmpty() == true) {
                    val studios = media.studios.nodes.mapNotNull { it.name }.joinToString(", ")
                    if (studios.isNotBlank()) append("\nStudio: $studios")
                }
                if (media.trailer?.site.equals("youtube", ignoreCase = true) && !media.trailer?.id.isNullOrBlank()) {
                    append("\n\n[Trailer](https://www.youtube.com/watch?v=${media.trailer?.id})")
                }

                if (scorePosition == "bottom" && score != null && score > 0.0) {
                    val full = (score / 2.0).toInt().coerceIn(0, 5)
                    append("\n\n${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.2f".format(score)}")
                }
            }.trim()
        }
    }

    // ============================== Episode List ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val anilistId = anime.url.substringAfter("/anime/").substringBefore("?").substringBefore("#").toInt()
        val queryBody = GraphQLRequest(
            query = DETAILS_QUERY,
            variables = GraphQLVariables(id = anilistId),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = client.newCall(POST(ANILIST_GRAPHQL_URL, headers, body)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Failed to fetch episodes list: HTTP ${response.code}")
        }

        val anilistRes = response.parseAs<AnilistGraphQLResponse>()
        val media = anilistRes.data?.Media ?: throw Exception("Anime not found on AniList")

        val isMovie = media.format.equals("MOVIE", ignoreCase = true)
        val airedEps = media.airingSchedule?.nodes
            ?.filter { (it.airingAt ?: 0L) <= System.currentTimeMillis() / 1000 }
            ?.maxOfOrNull { it.episode ?: 0 }
            ?: (media.nextAiringEpisode?.episode?.let { it - 1 } ?: media.episodes ?: 0)

        val totalEps = if (isMovie) {
            1
        } else if (media.status == "RELEASING") {
            if (airedEps > 0) airedEps else (media.episodes ?: 1)
        } else {
            media.episodes ?: if (airedEps > 0) airedEps else 1
        }

        val kitsuMetadata = media.idMal?.let { fetchKitsuMetadata(it) } ?: emptyMap()
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)
        val streamingEps = media.streamingEpisodes ?: emptyList()

        val episodeList = (1..totalEps).map { i ->
            val streamEp = if (streamingEps.size == totalEps) {
                streamingEps.getOrNull(i - 1)
            } else {
                streamingEps.find { ep ->
                    val title = ep.title?.lowercase() ?: ""
                    title.startsWith("episode $i ") ||
                        title.startsWith("episode $i:") ||
                        title.startsWith("episode $i -") ||
                        title == "episode $i" ||
                        title.contains("ep $i ") ||
                        title.contains("episode ${"%02d".format(i)}")
                }
            }

            val kitsuEp = kitsuMetadata[i]

            SEpisode.create().apply {
                setUrlWithoutDomain("/anime/$anilistId?ep=$i")
                val cleanStreamTitle = streamEp?.title
                    ?.replace(Regex("(?i)^Episode\\s+$i\\s*[-:]\\s*"), "")
                    ?.trim()
                val candidateTitle = kitsuEp?.attributes?.canonicalTitle ?: cleanStreamTitle

                name = if (!candidateTitle.isNullOrBlank() && !candidateTitle.equals("Episode $i", ignoreCase = true)) {
                    "Episode $i: $candidateTitle"
                } else {
                    if (isMovie) "Movie" else "Episode $i"
                }
                episode_number = i.toFloat()
                summary = kitsuEp?.attributes?.synopsis ?: kitsuEp?.attributes?.description
                val kitsuThumb = kitsuEp?.attributes?.thumbnail?.let {
                    it.original ?: it.large ?: it.medium ?: it.small
                }
                preview_url = if (showThumbnails) (kitsuThumb ?: streamEp?.thumbnail) else null
                scanlator = "Sub / Dub"

                val airdateStr = kitsuEp?.attributes?.airdate
                if (!airdateStr.isNullOrBlank()) {
                    date_upload = runCatching {
                        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(airdateStr)?.time ?: 0L
                    }.getOrDefault(0L)
                }
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    // ============================== Video / Hoster List ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val anilistId = episode.url.substringAfter("/anime/").substringBefore("?").substringBefore("#")
        val epNum = episode.url.substringAfter("?ep=").substringBefore("&").ifBlank { "1" }

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        val hosters = mutableListOf<Hoster>()

        if ("Miruro" !in excludedServers) {
            hosters.add(Hoster(hosterName = "Miruro", hosterUrl = "miruro|$anilistId|$epNum"))
        }
        if ("Megaplay" !in excludedServers) {
            hosters.add(Hoster(hosterName = "Megaplay", hosterUrl = "megaplay|$anilistId|$epNum"))
        }
        if ("Kiwi" !in excludedServers) {
            hosters.add(Hoster(hosterName = "Kiwi", hosterUrl = "kiwi|$anilistId|$epNum"))
        }
        if ("Dub (AnimeGG)" !in excludedServers) {
            hosters.add(Hoster(hosterName = "Dub (AnimeGG)", hosterUrl = "dub|$anilistId|$epNum"))
        }

        return hosters.sortedByDescending { it.hosterName.contains(preferredServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 3) return emptyList()

        val hosterType = parts[0]
        val anilistId = parts[1]
        val epNum = parts[2]

        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val audioLangs = when (prefAudio) {
            "DUB" -> listOf("dub", "sub")
            "BOTH" -> listOf("sub", "dub")
            else -> listOf("sub", "dub")
        }

        val streamHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        val videos = audioLangs.parallelCatchingFlatMapBlocking { lang ->
            when (hosterType) {
                "miruro" -> {
                    val url = "$baseUrl/api/miruro?al=$anilistId&ep=$epNum&lang=$lang&all=1"
                    val response = client.newCall(GET(url, headers)).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        return@parallelCatchingFlatMapBlocking emptyList()
                    }
                    val data = runCatching { response.parseAs<MiruroResponse>() }.getOrNull()
                        ?: return@parallelCatchingFlatMapBlocking emptyList()

                    val subTracks = data.tracks?.mapNotNull { it.toTrack() } ?: emptyList()
                    val videoList = mutableListOf<Video>()

                    if (!data.source.isNullOrBlank()) {
                        val mainTag = if (lang == "dub") {
                            "[DUB]"
                        } else if (subTracks.isNotEmpty()) {
                            "[Soft Sub]"
                        } else {
                            "[Hard Sub]"
                        }
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                playlistUrl = data.source,
                                referer = "$baseUrl/",
                                videoNameGen = { qual -> "$mainTag Miruro - $qual" },
                                subtitleList = subTracks,
                            ),
                        )
                    }

                    data.providers?.forEach { provider ->
                        val pSource = provider.source
                        val pLabel = provider.label ?: provider.id ?: "Provider"
                        if (!pSource.isNullOrBlank()) {
                            val pTracks = provider.tracks?.mapNotNull { it.toTrack() } ?: subTracks
                            val isHard = provider.hard == true || (pTracks.isEmpty() && lang != "dub")
                            val pTag = if (lang == "dub") {
                                "[DUB]"
                            } else if (isHard) {
                                "[Hard Sub]"
                            } else {
                                "[Soft Sub]"
                            }
                            videoList.addAll(
                                playlistUtils.extractFromHls(
                                    playlistUrl = pSource,
                                    referer = "$baseUrl/",
                                    videoNameGen = { qual -> "$pTag Miruro ($pLabel) - $qual" },
                                    subtitleList = pTracks,
                                ),
                            )
                        }
                    }

                    videoList
                }

                "megaplay" -> {
                    val url = "$baseUrl/api/stream?ani=$anilistId&ep=$epNum&lang=$lang"
                    val response = client.newCall(GET(url, headers)).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        return@parallelCatchingFlatMapBlocking emptyList()
                    }
                    val data = runCatching { response.parseAs<MegaplayResponse>() }.getOrNull()
                        ?: return@parallelCatchingFlatMapBlocking emptyList()

                    val masterUrl = data.source ?: return@parallelCatchingFlatMapBlocking emptyList()
                    val subTracks = data.tracks?.mapNotNull { it.toTrack() } ?: emptyList()
                    val tag = if (lang == "dub") {
                        "[DUB]"
                    } else if (subTracks.isNotEmpty()) {
                        "[Soft Sub]"
                    } else {
                        "[Hard Sub]"
                    }

                    playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        referer = "$baseUrl/",
                        videoNameGen = { qual -> "$tag Megaplay - $qual" },
                        subtitleList = subTracks,
                    )
                }

                "kiwi" -> {
                    val url = "$baseUrl/api/kiwi?al=$anilistId&ep=$epNum&lang=$lang"
                    val response = client.newCall(GET(url, headers)).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        return@parallelCatchingFlatMapBlocking emptyList()
                    }
                    val data = runCatching { response.parseAs<KiwiResponse>() }.getOrNull()
                        ?: return@parallelCatchingFlatMapBlocking emptyList()

                    val subTracks = data.tracks?.mapNotNull { it.toTrack() } ?: emptyList()
                    val tag = if (lang == "dub") {
                        "[DUB]"
                    } else if (subTracks.isNotEmpty()) {
                        "[Soft Sub]"
                    } else {
                        "[Hard Sub]"
                    }
                    val videoList = mutableListOf<Video>()

                    if (data.qualities?.isNotEmpty() == true) {
                        data.qualities.forEach { q ->
                            val qSource = q.source ?: return@forEach
                            val qLabel = q.label ?: "Quality"
                            val res = qLabel.filter { it.isDigit() }.toIntOrNull() ?: 0
                            videoList.add(
                                Video(
                                    videoUrl = qSource,
                                    videoTitle = "$tag Kiwi - $qLabel",
                                    headers = streamHeaders,
                                    resolution = res,
                                    subtitleTracks = subTracks,
                                ),
                            )
                        }
                    } else if (!data.source.isNullOrBlank()) {
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                playlistUrl = data.source,
                                referer = "$baseUrl/",
                                videoNameGen = { qual -> "$tag Kiwi - $qual" },
                                subtitleList = subTracks,
                            ),
                        )
                    }

                    videoList
                }

                "dub" -> {
                    if (lang != "dub") return@parallelCatchingFlatMapBlocking emptyList()
                    val url = "$baseUrl/api/dub?al=$anilistId&ep=$epNum"
                    val response = client.newCall(GET(url, headers)).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        return@parallelCatchingFlatMapBlocking emptyList()
                    }
                    val data = runCatching { response.parseAs<DubResponse>() }.getOrNull()
                        ?: return@parallelCatchingFlatMapBlocking emptyList()

                    val videoList = mutableListOf<Video>()
                    val subTracks = data.tracks?.mapNotNull { it.toTrack() } ?: emptyList()

                    val sources = buildList {
                        if (!data.source.isNullOrBlank()) add(data.source)
                        data.sources?.forEach { s ->
                            if (!s.source.isNullOrBlank() && s.source !in this) add(s.source)
                        }
                    }

                    sources.forEach { src ->
                        if (src.contains(".m3u8", ignoreCase = true)) {
                            videoList.addAll(
                                playlistUtils.extractFromHls(
                                    playlistUrl = src,
                                    referer = "$baseUrl/",
                                    videoNameGen = { qual -> "[DUB] AnimeGG - $qual" },
                                    subtitleList = subTracks,
                                ),
                            )
                        } else {
                            videoList.add(
                                Video(
                                    videoUrl = src,
                                    videoTitle = "[DUB] AnimeGG",
                                    headers = streamHeaders,
                                    subtitleTracks = subTracks,
                                ),
                            )
                        }
                    }

                    videoList
                }

                else -> emptyList()
            }
        }

        return m3u8Integration.processVideoList(videos)
    }

    private fun MeguTrack.toTrack(): Track? {
        val fileUrl = file?.takeIf { it.isNotBlank() } ?: return null
        val trackLabel = label?.takeIf { it.isNotBlank() } ?: "Subtitles"
        return Track(fileUrl, trackLabel)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        val audioTag = when (prefAudio) {
            "DUB" -> "[DUB]"
            "HARD_SUB" -> "[Hard Sub]"
            else -> "[Soft Sub]"
        }

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(audioTag, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution },
        )
    }

    // ============================ Recommendations ========================

    fun relatedAnimeListRequest(anime: SAnime): Request {
        val anilistId = anime.url.substringAfter("/anime/").substringBefore("?").substringBefore("#").toInt()
        val queryBody = GraphQLRequest(
            query = RECOMMENDATIONS_QUERY,
            variables = GraphQLVariables(id = anilistId),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(ANILIST_GRAPHQL_URL, headers, body)
    }

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val anilistRes = response.parseAs<AnilistGraphQLResponse>()
        val mediaList = anilistRes.data?.Page?.media ?: return emptyList()

        val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT
        return mediaList.map { media ->
            SAnime.create().apply {
                url = "/anime/${media.id}"
                title = resolveTitle(media.title, titleLang)
                thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large ?: media.coverImage?.medium
                genre = media.genres?.joinToString(", ") ?: ""
                fetch_type = FetchType.Episodes
            }
        }
    }

    // ============================== Helpers ==============================

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    private fun resolveTitle(title: MediaTitle?, prefLang: String): String = when (prefLang) {
        "romaji" -> title?.romaji ?: title?.english ?: title?.native ?: "Unknown Title"
        "native" -> title?.native ?: title?.english ?: title?.romaji ?: "Unknown Title"
        else -> title?.english ?: title?.romaji ?: title?.native ?: "Unknown Title"
    }

    private fun fetchKitsuMetadata(malId: Int): Map<Int, KitsuEpisodeData> {
        val kitsuMap = mutableMapOf<Int, KitsuEpisodeData>()
        try {
            val mappingUrl = "https://kitsu.app/api/edge/mappings?filter[externalSite]=myanimelist/anime&filter[externalId]=$malId"
            val mapRequest = Request.Builder()
                .url(mappingUrl)
                .headers(Headers.Builder().add("Accept", "application/vnd.api+json").build())
                .build()
            val mapResponse = client.newCall(mapRequest).execute()
            if (!mapResponse.isSuccessful) {
                mapResponse.close()
                return emptyMap()
            }
            val mapJson = mapResponse.parseAs<KitsuMappingResponse>()
            val kitsuAnimeUrl = mapJson.data.firstOrNull()?.relationships?.item?.links?.related ?: return emptyMap()

            val animeRequest = Request.Builder()
                .url(kitsuAnimeUrl)
                .headers(Headers.Builder().add("Accept", "application/vnd.api+json").build())
                .build()
            val animeResponse = client.newCall(animeRequest).execute()
            if (!animeResponse.isSuccessful) {
                animeResponse.close()
                return emptyMap()
            }
            val animeJson = animeResponse.parseAs<KitsuAnimeResponse>()
            val kitsuId = animeJson.data?.id ?: return emptyMap()

            var offset = 0
            var hasMore = true
            while (hasMore) {
                val epUrl = "https://kitsu.app/api/edge/anime/$kitsuId/episodes?page[limit]=100&page[offset]=$offset"
                val epRequest = Request.Builder()
                    .url(epUrl)
                    .headers(Headers.Builder().add("Accept", "application/vnd.api+json").build())
                    .build()
                val epResponse = client.newCall(epRequest).execute()
                if (!epResponse.isSuccessful) {
                    epResponse.close()
                    break
                }
                val epJson = epResponse.parseAs<KitsuEpisodesResponse>()
                val episodesData = epJson.data ?: emptyList()
                if (episodesData.isEmpty()) {
                    break
                }
                for (ep in episodesData) {
                    val epNum = ep.attributes?.number ?: continue
                    kitsuMap[epNum] = ep
                }
                offset += episodesData.size
                hasMore = episodesData.size >= 100
            }
        } catch (_: Exception) {
            // Silently fallback if Kitsu API is unreachable
        }
        return kitsuMap
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred Audio / Subtitle Format",
            default = PREF_AUDIO_DEFAULT,
            summary = "%s",
            entries = listOf("Soft Sub (Multi-Language Tracks)", "Hard Sub (Embedded)", "Dub (English Audio)", "Both / Any"),
            entryValues = listOf("SOFT_SUB", "HARD_SUB", "DUB", "BOTH"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Miruro", "Megaplay", "Kiwi", "Dub (AnimeGG)"),
            entryValues = listOf("Miruro", "Megaplay", "Kiwi", "Dub"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred Title Language",
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
            entries = listOf("English", "Romaji", "Native"),
            entryValues = listOf("english", "romaji", "native"),
        )
        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            title = "Rating Score Position",
            default = PREF_SCORE_POSITION_DEFAULT,
            summary = "%s",
            entries = listOf("Top of Synopsis", "Bottom of Synopsis", "Hidden"),
            entryValues = listOf("top", "bottom", "hidden"),
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            title = "Show Episode Thumbnails",
            summary = "Fetch high-resolution episode preview thumbnails from Kitsu/AniList",
            default = true,
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from playback options",
            entries = listOf("Miruro", "Megaplay", "Kiwi", "Dub (AnimeGG)"),
            entryValues = listOf("Miruro", "Megaplay", "Kiwi", "Dub (AnimeGG)"),
            default = emptySet(),
        )
    }

    companion object {
        private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private const val PREF_DOMAIN_DEFAULT = "https://meguanime.com"

        private const val PREF_AUDIO_KEY = "pref_audio_key"
        private const val PREF_AUDIO_DEFAULT = "SOFT_SUB"

        private const val PREF_SERVER_KEY = "pref_server_key"
        private const val PREF_SERVER_DEFAULT = "Miruro"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TITLE_LANG_KEY = "pref_title_lang_key"
        private const val PREF_TITLE_LANG_DEFAULT = "english"

        private const val PREF_SCORE_POSITION_KEY = "pref_score_position_key"
        private const val PREF_SCORE_POSITION_DEFAULT = "top"

        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails_key"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers_key"

        private const val POPULAR_QUERY = """
            query(${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo {
                  hasNextPage
                }
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title {
                    romaji
                    english
                    native
                  }
                  coverImage {
                    extraLarge
                    large
                    medium
                  }
                  genres
                }
              }
            }
        """

        private const val LATEST_QUERY = """
            query(${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo {
                  hasNextPage
                }
                media(type: ANIME, status: RELEASING, sort: UPDATED_AT_DESC, isAdult: false) {
                  id
                  title {
                    romaji
                    english
                    native
                  }
                  coverImage {
                    extraLarge
                    large
                    medium
                  }
                  genres
                }
              }
            }
        """

        private const val SEARCH_QUERY = """
            query(
              ${'$'}page: Int,
              ${'$'}perPage: Int,
              ${'$'}search: String,
              ${'$'}sort: [MediaSort],
              ${'$'}genres: [String],
              ${'$'}format: [MediaFormat],
              ${'$'}status: [MediaStatus],
              ${'$'}season: MediaSeason,
              ${'$'}seasonYear: Int
            ) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo {
                  hasNextPage
                }
                media(
                  type: ANIME,
                  search: ${'$'}search,
                  sort: ${'$'}sort,
                  genre_in: ${'$'}genres,
                  format_in: ${'$'}format,
                  status_in: ${'$'}status,
                  season: ${'$'}season,
                  seasonYear: ${'$'}seasonYear,
                  isAdult: false
                ) {
                  id
                  title {
                    romaji
                    english
                    native
                  }
                  coverImage {
                    extraLarge
                    large
                    medium
                  }
                  genres
                }
              }
            }
        """

        private const val DETAILS_QUERY = """
            query(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                idMal
                title {
                  romaji
                  english
                  native
                }
                coverImage {
                  extraLarge
                  large
                  medium
                }
                bannerImage
                description(asHtml: false)
                genres
                averageScore
                episodes
                status
                format
                duration
                countryOfOrigin
                studios {
                  nodes {
                    name
                  }
                }
                trailer {
                  id
                  site
                }
                nextAiringEpisode {
                  episode
                  airingAt
                }
                airingSchedule {
                  nodes {
                    episode
                    airingAt
                  }
                }
                streamingEpisodes {
                  title
                  thumbnail
                  url
                  site
                }
              }
            }
        """

        private const val RECOMMENDATIONS_QUERY = """
            query(${'$'}id: Int) {
              Page(page: 1, perPage: 15) {
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title {
                    romaji
                    english
                    native
                  }
                  coverImage {
                    extraLarge
                    large
                    medium
                  }
                  genres
                }
              }
            }
        """
    }
}
