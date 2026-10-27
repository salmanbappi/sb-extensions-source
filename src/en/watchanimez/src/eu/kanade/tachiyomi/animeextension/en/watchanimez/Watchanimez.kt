package eu.kanade.tachiyomi.animeextension.en.watchanimez

import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.byseextractor.ByseExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.EpisodeMetadataFetcher
import extensions.utils.Source
import extensions.utils.UrlUtils
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class Watchanimez : Source() {

    override val name = "WatchAnimeZ"

    override val baseUrl = "https://watchanimez.me"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    private val byseExtractor by lazy {
        ByseExtractor(client, playlistUtils)
    }

    private val filemoonExtractor by lazy {
        FilemoonExtractor(client)
    }

    private val voeExtractor by lazy {
        VoeExtractor(client, headers)
    }

    private val vidMolyExtractor by lazy {
        VidMolyExtractor(client)
    }

    private val metadataFetcher by lazy {
        EpisodeMetadataFetcher(client, json)
    }

    private val episodeCache = mutableMapOf<String, EpisodeEmbedDto>()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun getAnimeUrl(anime: SAnime): String {
        val id = anime.url.substringBefore("#").trimStart('/')
        return "$baseUrl/anime/$id"
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val anilistId = episode.url.substringBefore("#").trimStart('/')
        val epNum = episode.url.substringAfter("#ep=", "").substringBefore("&")
        return if (epNum.isNotBlank()) "$baseUrl/watch/$anilistId?ep=$epNum" else "$baseUrl/anime/$anilistId"
    }

    private fun getCatalogBase(): String {
        val source = preferences.getString(PREF_CATALOG_KEY, PREF_CATALOG_DEFAULT) ?: PREF_CATALOG_DEFAULT
        return if (source == "anicore") "$baseUrl/api/v1/anicore" else "$baseUrl/api/v1"
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("${getCatalogBase()}/popular?page=$page&per_page=24", headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val container = dto.results ?: return AnimesPage(emptyList(), false)
        val animes = (container.results ?: emptyList()).map { it.toSAnime() }
        return AnimesPage(animes, container.hasNextPage ?: false)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("${getCatalogBase()}/recent?page=$page&per_page=24", headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val container = dto.results ?: return AnimesPage(emptyList(), false)
        val animes = (container.results ?: emptyList()).map { it.toSAnime() }
        return AnimesPage(animes, container.hasNextPage ?: false)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        var genreMode = "and"
        var selectedGenres = emptyList<String>()

        val url = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            "${getCatalogBase()}/search?query=$encodedQuery&page=$page&per_page=24"
        } else {
            val urlBuilder = "${getCatalogBase()}/filter?page=$page&per_page=24".toHttpUrl().newBuilder()
            for (filter in filters) {
                when (filter) {
                    is Filters.SortFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("sort", filter.toUriPart())
                    }

                    is Filters.GenreModeFilter -> {
                        genreMode = filter.toUriPart()
                    }

                    is Filters.GenreFilter -> {
                        selectedGenres = filter.getSelectedGenres()
                        if (selectedGenres.size == 1) {
                            urlBuilder.addQueryParameter("genre", selectedGenres.first())
                        } else if (selectedGenres.size > 1 && genreMode == "and") {
                            urlBuilder.addQueryParameter("genre", selectedGenres.first())
                        }
                    }

                    is Filters.FormatFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("format", filter.toUriPart())
                    }

                    is Filters.StatusFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())
                    }

                    is Filters.SeasonFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("season", filter.toUriPart())
                    }

                    is Filters.YearFilter -> {
                        if (filter.state.isNotBlank()) urlBuilder.addQueryParameter("year", filter.state.trim())
                    }

                    else -> {}
                }
            }
            urlBuilder.build().toString()
        }

        if (query.isNotBlank()) {
            for (filter in filters) {
                when (filter) {
                    is Filters.GenreModeFilter -> genreMode = filter.toUriPart()
                    is Filters.GenreFilter -> selectedGenres = filter.getSelectedGenres()
                    else -> {}
                }
            }
        }

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val container = dto.results ?: return AnimesPage(emptyList(), false)
        var items = container.results ?: emptyList()

        if (selectedGenres.isNotEmpty()) {
            items = if (genreMode == "or") {
                items.filter { item ->
                    selectedGenres.any { sel ->
                        item.genres?.any { it.equals(sel, ignoreCase = true) } == true
                    }
                }
            } else {
                items.filter { item ->
                    selectedGenres.all { sel ->
                        item.genres?.any { it.equals(sel, ignoreCase = true) } == true
                    }
                }
            }
        }

        val animes = items.map { it.toSAnime() }
        return AnimesPage(animes, container.hasNextPage ?: false)
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            Filters.SortFilter(),
            Filters.GenreModeFilter(),
            Filters.GenreFilter(),
            Filters.FormatFilter(),
            Filters.StatusFilter(),
            Filters.SeasonFilter(),
            Filters.YearFilter(),
        )
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val anilistId = anime.url.substringBefore("#").trimStart('/')
        val response = client.newCall(GET("$baseUrl/api/v1/info/$anilistId", headers)).execute()
        val dto = response.parseAs<AnimeDetailsResponseDto>(json)
        val item = dto.results ?: return anime

        return anime.apply {
            title = item.title?.userPreferred
                ?: item.title?.english
                ?: item.title?.romaji
                ?: anime.title
            thumbnail_url = item.coverImage?.extraLarge
                ?: item.coverImage?.large
                ?: anime.thumbnail_url
            description = item.description?.replace(Regex("<[^>]*>"), "")?.trim()
            genre = item.genres?.joinToString(", ")
            status = when (item.status?.uppercase()) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            val studios = item.studios?.filter { it.isAnimationStudio == true }?.mapNotNull { it.name }
            if (!studios.isNullOrEmpty()) {
                author = studios.joinToString(", ")
                artist = author
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val anilistId = anime.url.substringBefore("#").trimStart('/')
        val response = client.newCall(GET("$baseUrl/api/v1/info/$anilistId", headers)).execute()
        val dto = response.parseAs<AnimeDetailsResponseDto>(json)
        val item = dto.results ?: return emptyList()

        val malIdStr = item.idMal?.toString() ?: ""
        val animeTitle = item.title?.userPreferred
            ?: item.title?.english
            ?: item.title?.romaji
            ?: anime.title

        val loadThumbnails = preferences.getBoolean(PREF_LOAD_THUMBNAILS_KEY, true)
        val loadTitles = preferences.getBoolean(PREF_LOAD_TITLES_KEY, true)
        val loadDescriptions = preferences.getBoolean(PREF_LOAD_DESCRIPTIONS_KEY, true)

        val metadataMap = if (loadThumbnails || loadTitles || loadDescriptions) {
            runCatching {
                metadataFetcher.fetch(
                    malId = malIdStr,
                    animeTitle = animeTitle,
                    fallbackThumbnailUrl = anime.thumbnail_url,
                )
            }.getOrNull() ?: emptyMap()
        } else {
            emptyMap()
        }

        val streamingMap = item.streamingEpisodes?.associateBy { ep ->
            Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE).find(ep.title ?: "")?.groupValues?.get(1)?.toIntOrNull()
        } ?: emptyMap()

        val totalEpisodes = maxOf(
            item.episodes ?: 0,
            item.streamingEpisodes?.size ?: 0,
            metadataMap.size,
            1,
        )

        val episodes = (1..totalEpisodes).map { epNum ->
            val streamingEp = streamingMap[epNum]
            val meta = metadataMap[epNum]

            SEpisode.create().apply {
                val titleCandidate = if (loadTitles) meta?.title else null
                name = if (!titleCandidate.isNullOrBlank()) {
                    "Episode $epNum - $titleCandidate"
                } else {
                    streamingEp?.title ?: "Episode $epNum"
                }

                episode_number = epNum.toFloat()
                url = "$anilistId#ep=$epNum&mal=$malIdStr"

                if (loadThumbnails) {
                    val thumb = streamingEp?.thumbnail?.takeIf { it.isNotBlank() }
                        ?: meta?.thumbnailUrl?.takeIf { it.isNotBlank() }
                    if (!thumb.isNullOrBlank()) {
                        preview_url = thumb
                    }
                }

                if (loadDescriptions) {
                    val synopsis = meta?.description
                    if (!synopsis.isNullOrBlank()) {
                        summary = synopsis
                    }
                }
            }
        }

        // Return descending order (Ep N at top, Ep 1 at bottom)
        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Hoster Folders ==========================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val anilistId = episode.url.substringBefore("#").trimStart('/')
        val epNum = episode.url.substringAfter("#ep=").substringBefore("&")
        val malId = episode.url.substringAfter("&mal=", "")

        val hosters = mutableListOf<Hoster>()

        // 1. Selfhost (Preferred / Default)
        hosters.add(
            Hoster(
                hosterName = "Selfhost",
                hosterUrl = "type=selfhost&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        // 2. MegaPlay (Server 15)
        hosters.add(
            Hoster(
                hosterName = "MegaPlay (Server 15)",
                hosterUrl = "type=mega&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        // 3. Server 14 (Byse)
        hosters.add(
            Hoster(
                hosterName = "Server 14 (Byse)",
                hosterUrl = "type=byse&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        // 4. Server 22 (Voe)
        hosters.add(
            Hoster(
                hosterName = "Server 22 (Voe)",
                hosterUrl = "type=voe&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        // 5. Server 21 (Uqload)
        hosters.add(
            Hoster(
                hosterName = "Server 21 (Uqload)",
                hosterUrl = "type=uqload&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        // 6. Server 13 (Vidmoly)
        hosters.add(
            Hoster(
                hosterName = "Server 13 (Vidmoly)",
                hosterUrl = "type=vidmoly&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    // ============================ Video Streams ===========================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val params = hoster.hosterUrl.split("&").associate {
            val parts = it.split("=")
            parts[0] to parts.getOrElse(1) { "" }
        }

        val type = params["type"] ?: return emptyList()
        val anilistId = params["anilistId"] ?: ""
        val malId = params["malId"] ?: ""
        val epNum = params["ep"] ?: "1"

        val videos = when (type) {
            "selfhost" -> getSelfhostVideos(malId, epNum)
            "mega" -> getMegaPlayVideos(anilistId, epNum)
            "byse" -> getServer14Videos(anilistId, epNum)
            "voe" -> getServer22Videos(anilistId, epNum)
            "uqload" -> getServer21Videos(anilistId, epNum)
            "vidmoly" -> getServer13Videos(anilistId, epNum)
            "reanime" -> getReAnimeVideos(anilistId, epNum)
            "blakite" -> getBlakiteVideos(anilistId, epNum)
            else -> emptyList()
        }

        return videos.sortVideos()
    }

    // ---------------------------- Episode Embed Info ----------------------
    private fun getEpisodeEmbedInfo(anilistId: String, epNum: String): EpisodeEmbedDto? {
        val key = "$anilistId-$epNum"
        episodeCache[key]?.let { return it }

        return runCatching {
            val url = "https://watchanimexembed.anshwatch.workers.dev/api/episode/$anilistId/$epNum"
            val resp = client.newCall(GET(url, headersBuilder().build())).execute()
            if (resp.isSuccessful) {
                val dto = resp.parseAs<EpisodeEmbedDto>(json)
                if (dto.anilist_id != null) {
                    if (episodeCache.size > 100) episodeCache.clear()
                    episodeCache[key] = dto
                }
                dto
            } else {
                null
            }
        }.getOrNull()
    }

    // ---------------------------- Selfhost --------------------------------
    private fun getSelfhostVideos(malId: String, epNum: String): List<Video> {
        if (malId.isBlank()) return emptyList()
        val videos = mutableListOf<Video>()
        val streamHeaders = headersBuilder().build()

        try {
            val url = "https://edge1-frankfut.animetvplus.site/dlm/stream/$malId/$epNum"
            val resp = client.newCall(GET(url, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val dto = resp.parseAs<SelfhostStreamDto>(json)
                val masterUrl = dto.master ?: dto.index
                val aid = dto.aid

                if (!masterUrl.isNullOrBlank() && masterUrl.startsWith("http")) {
                    // Fetch subtitles and skip times from meta.json
                    val metaUrl = masterUrl.replace(Regex("""master\.txt(\?.*)?$"""), "meta.json$1")
                    val subTracks = runCatching {
                        val metaResp = client.newCall(GET(metaUrl, streamHeaders)).execute()
                        if (metaResp.isSuccessful) {
                            val meta = metaResp.parseAs<SelfhostMetaDto>(json)
                            meta.subs?.mapNotNull { sub ->
                                val file = sub.file ?: return@mapNotNull null
                                val fullSubUrl = UrlUtils.fixUrl(file, masterUrl)
                                Track(fullSubUrl, sub.label ?: "Subtitles")
                            }
                        } else {
                            null
                        }
                    }.getOrNull() ?: emptyList()

                    val extractedVideos = playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        referer = baseUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "Selfhost - $quality" },
                        subtitleList = subTracks,
                    )

                    for (video in extractedVideos) {
                        val audioTracks = video.audioTracks
                        val englishTrack = audioTracks.firstOrNull {
                            it.lang.contains("English", ignoreCase = true) ||
                                it.lang.equals("en", ignoreCase = true) ||
                                it.lang.contains("Dub", ignoreCase = true)
                        }
                        val japaneseTrack = audioTracks.firstOrNull {
                            it.lang.contains("Japanese", ignoreCase = true) ||
                                it.lang.equals("ja", ignoreCase = true) ||
                                it.lang.contains("日本語")
                        }

                        // Sub stream
                        videos.add(
                            Video(
                                videoUrl = video.videoUrl,
                                videoTitle = "${video.videoTitle} (Sub)",
                                headers = video.headers,
                                subtitleTracks = video.subtitleTracks,
                                audioTracks = if (japaneseTrack != null) {
                                    listOf(japaneseTrack) + (audioTracks - japaneseTrack)
                                } else {
                                    audioTracks
                                },
                            ),
                        )

                        // Dub stream (English audio prioritized)
                        if (englishTrack != null) {
                            videos.add(
                                Video(
                                    videoUrl = video.videoUrl,
                                    videoTitle = "${video.videoTitle} (Dub)",
                                    headers = video.headers,
                                    subtitleTracks = video.subtitleTracks,
                                    audioTracks = listOf(englishTrack) + (audioTracks - englishTrack),
                                ),
                            )
                        }
                    }
                }

                // Check external Dub availability fallback
                if (aid != null) {
                    val dubAvailUrl = "https://animetvplus-dub.b-cdn.net/dub/$aid/$epNum/available"
                    val isDubAvailable = runCatching {
                        val dubResp = client.newCall(GET(dubAvailUrl, streamHeaders)).execute()
                        if (dubResp.isSuccessful) {
                            dubResp.parseAs<DubAvailableDto>(json).dub_available == true
                        } else {
                            false
                        }
                    }.getOrDefault(false)

                    if (isDubAvailable) {
                        val dubUrl = "https://animetvplus-dub.b-cdn.net/dub/$aid/$epNum/index.txt"
                        val dubVideos = playlistUtils.extractFromHls(
                            playlistUrl = dubUrl,
                            referer = baseUrl,
                            masterHeaders = streamHeaders,
                            videoHeaders = streamHeaders,
                            videoNameGen = { quality -> "Selfhost - $quality (Dub)" },
                        )
                        videos.addAll(dubVideos)
                    }
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    // ---------------------------- MegaPlay (Server 15) --------------------
    private fun getMegaPlayVideos(anilistId: String, epNum: String): List<Video> {
        if (anilistId.isBlank()) return emptyList()
        val videos = mutableListOf<Video>()
        val streamHeaders = headersBuilder().build()

        // 1. Sub
        try {
            val subPageUrl = "https://gjheh8.bot-keep.xyz/watch/ani/$anilistId/$epNum/sub"
            val resp = client.newCall(GET(subPageUrl, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val html = resp.body.string()
                val src = Regex("""var\s+SRC\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trim()
                if (!src.isNullOrBlank()) {
                    val subVideos = playlistUtils.extractFromHls(
                        playlistUrl = src,
                        referer = baseUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "MegaPlay - $quality (Sub)" },
                    )
                    videos.addAll(subVideos)
                }
            }
        } catch (_: Exception) {}

        // 2. Dub
        try {
            val dubPageUrl = "https://gjheh8.bot-keep.xyz/watch/ani/$anilistId/$epNum/dub"
            val resp = client.newCall(GET(dubPageUrl, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val html = resp.body.string()
                val src = Regex("""var\s+SRC\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trim()
                if (!src.isNullOrBlank()) {
                    val dubVideos = playlistUtils.extractFromHls(
                        playlistUrl = src,
                        referer = baseUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "MegaPlay - $quality (Dub)" },
                    )
                    videos.addAll(dubVideos)
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    // ---------------------------- Server 14 (Byse) ------------------------
    private suspend fun getServer14Videos(anilistId: String, epNum: String): List<Video> {
        val info = getEpisodeEmbedInfo(anilistId, epNum) ?: return emptyList()
        val videos = mutableListOf<Video>()
        val byseUrl = info.byse_url ?: info.byse_download

        if (!byseUrl.isNullOrBlank()) {
            val byseVideos = runCatching {
                byseExtractor.videosFromUrl(byseUrl, prefix = "Server 14 - ")
            }.getOrNull() ?: emptyList()
            videos.addAll(byseVideos)

            if (videos.isEmpty() && byseUrl.contains("filemoon")) {
                val filemoonVideos = runCatching {
                    filemoonExtractor.videosFromUrl(byseUrl, prefix = "Server 14 - ")
                }.getOrNull() ?: emptyList()
                videos.addAll(filemoonVideos)
            }
        }

        if (videos.isEmpty() && !info.byse_code.isNullOrBlank()) {
            val byseCode = info.byse_code
            val proxies = listOf(
                "https://byse-proxy.watchanimez-s12.workers.dev",
                "https://byse-proxy.watchanimez-s12-2.workers.dev",
                "https://byse-proxy.watchanimez-s12-3.workers.dev",
            )
            for (proxy in proxies) {
                try {
                    val resp = client.newCall(GET("$proxy/byse/resolve?code=$byseCode", headersBuilder().build())).execute()
                    if (resp.isSuccessful) {
                        val dto = resp.parseAs<WorkerResolveDto>(json)
                        val streamUrl = dto.url ?: dto.master
                        if (!streamUrl.isNullOrBlank()) {
                            val hlsVideos = playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = "$baseUrl/",
                                masterHeaders = headersBuilder().build(),
                                videoHeaders = headersBuilder().build(),
                                videoNameGen = { quality -> "Server 14 - $quality" },
                            )
                            videos.addAll(hlsVideos)
                            if (videos.isNotEmpty()) break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return videos
    }

    // ---------------------------- Server 22 (Voe) -------------------------
    private fun getServer22Videos(anilistId: String, epNum: String): List<Video> {
        val info = getEpisodeEmbedInfo(anilistId, epNum) ?: return emptyList()
        val videos = mutableListOf<Video>()
        val voeCode = info.voe_code
        val voeUrl = info.voe_url

        if (!voeCode.isNullOrBlank()) {
            val proxies = listOf(
                "https://voe-proxy.watchanimez-s12.workers.dev",
                "https://voe-proxy.watchanimez-s12-2.workers.dev",
                "https://voe-proxy.watchanimez-s12-3.workers.dev",
            )
            for (proxy in proxies) {
                try {
                    val resp = client.newCall(GET("$proxy/voe/resolve?code=$voeCode", headersBuilder().build())).execute()
                    if (resp.isSuccessful) {
                        val dto = resp.parseAs<WorkerResolveDto>(json)
                        val streamUrl = dto.url ?: dto.master
                        if (!streamUrl.isNullOrBlank()) {
                            val hlsVideos = playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = "$baseUrl/",
                                masterHeaders = headersBuilder().build(),
                                videoHeaders = headersBuilder().build(),
                                videoNameGen = { quality -> "Server 22 - $quality" },
                            )
                            videos.addAll(hlsVideos)
                            if (videos.isNotEmpty()) break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (videos.isEmpty() && !voeUrl.isNullOrBlank()) {
            val voeVideos = runCatching {
                voeExtractor.videosFromUrl(voeUrl, prefix = "Server 22 - ")
            }.getOrNull() ?: emptyList()
            videos.addAll(voeVideos)
        }

        return videos
    }

    // ---------------------------- Server 21 (Uqload) ----------------------
    private fun getServer21Videos(anilistId: String, epNum: String): List<Video> {
        val info = getEpisodeEmbedInfo(anilistId, epNum) ?: return emptyList()
        val videos = mutableListOf<Video>()
        val uqloadCode = info.uqload_code

        if (!uqloadCode.isNullOrBlank()) {
            val proxies = listOf(
                "https://uqload-proxy.watchanimez-s12.workers.dev",
                "https://uqload-proxy.watchanimez-s12-2.workers.dev",
                "https://uqload-proxy.watchanimez-s12-3.workers.dev",
            )
            for (proxy in proxies) {
                try {
                    val resp = client.newCall(GET("$proxy/uqload/resolve?code=$uqloadCode", headersBuilder().build())).execute()
                    if (resp.isSuccessful) {
                        val dto = resp.parseAs<WorkerResolveDto>(json)
                        val streamUrl = dto.url ?: dto.master
                        if (!streamUrl.isNullOrBlank()) {
                            val hlsVideos = playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = "$baseUrl/",
                                masterHeaders = headersBuilder().build(),
                                videoHeaders = headersBuilder().build(),
                                videoNameGen = { quality -> "Server 21 - $quality" },
                            )
                            videos.addAll(hlsVideos)
                            if (videos.isNotEmpty()) break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return videos
    }

    // ---------------------------- Server 13 (Vidmoly) ---------------------
    private suspend fun getServer13Videos(anilistId: String, epNum: String): List<Video> {
        val info = getEpisodeEmbedInfo(anilistId, epNum) ?: return emptyList()
        val vmolyUrl = info.vmoly_url
        if (!vmolyUrl.isNullOrBlank()) {
            return runCatching {
                vidMolyExtractor.videosFromUrl(vmolyUrl, prefix = "Server 13 - ")
            }.getOrNull() ?: emptyList()
        }
        return emptyList()
    }

    // ---------------------------- ReAnime (Server 7) ----------------------
    private fun getReAnimeVideos(anilistId: String, epNum: String): List<Video> {
        if (anilistId.isBlank()) return emptyList()
        val videos = mutableListOf<Video>()
        val streamHeaders = headersBuilder().build()

        try {
            val apiUrl = "https://proxy.skillmds.tech/s6/servers/$anilistId/$epNum"
            val resp = client.newCall(GET(apiUrl, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val serverData = resp.parseAs<ReAnimeServersResponseDto>(json)

                // Sub streams
                serverData.sub?.forEach { server ->
                    val dataLink = server.dataLink ?: return@forEach
                    val linkResp = client.newCall(
                        GET("https://proxy.skillmds.tech/s6/stream-from-link?link=${URLEncoder.encode(dataLink, "UTF-8")}", streamHeaders),
                    ).execute()
                    if (linkResp.isSuccessful) {
                        val streamDto = linkResp.parseAs<ReAnimeStreamDto>(json)
                        val streamUrl = streamDto.url ?: streamDto.rawUrl
                        if (!streamUrl.isNullOrBlank()) {
                            val subVideos = playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = "https://reanime.to/",
                                masterHeaders = streamHeaders,
                                videoHeaders = streamHeaders,
                                videoNameGen = { quality -> "ReAnime - $quality (Sub)" },
                            )
                            videos.addAll(subVideos)
                        }
                    }
                }

                // Dub streams
                serverData.dub?.forEach { server ->
                    val dataLink = server.dataLink ?: return@forEach
                    val linkResp = client.newCall(
                        GET("https://proxy.skillmds.tech/s6/stream-from-link?link=${URLEncoder.encode(dataLink, "UTF-8")}", streamHeaders),
                    ).execute()
                    if (linkResp.isSuccessful) {
                        val streamDto = linkResp.parseAs<ReAnimeStreamDto>(json)
                        val streamUrl = streamDto.url ?: streamDto.rawUrl
                        if (!streamUrl.isNullOrBlank()) {
                            val dubVideos = playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = "https://reanime.to/",
                                masterHeaders = streamHeaders,
                                videoHeaders = streamHeaders,
                                videoNameGen = { quality -> "ReAnime - $quality (Dub)" },
                            )
                            videos.addAll(dubVideos)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    // ---------------------------- Blakite (Server 20) ---------------------
    private fun getBlakiteVideos(anilistId: String, epNum: String): List<Video> {
        if (anilistId.isBlank()) return emptyList()
        val videos = mutableListOf<Video>()
        val streamHeaders = headersBuilder().build()

        try {
            val url = "$baseUrl/api/blakite?tmdbId=$anilistId&season=1&episode=$epNum"
            val resp = client.newCall(GET(url, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val dto = resp.parseAs<BlakiteResponseDto>(json)
                dto.data?.let { data ->
                    val streamUrl = data.url ?: return@let
                    val multiVideos = playlistUtils.extractFromHls(
                        playlistUrl = streamUrl,
                        referer = baseUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "Server 20 - $quality (Multi-Audio)" },
                    )
                    videos.addAll(multiVideos)
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        // 1. Preferred Server
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_SERVER_KEY
                title = "Preferred Server"
                entries = arrayOf(
                    "Selfhost",
                    "MegaPlay (Server 15)",
                    "Server 14 (Byse)",
                    "Server 22 (Voe)",
                    "Server 21 (Uqload)",
                    "Server 13 (Vidmoly)",
                )
                entryValues = arrayOf(
                    "Selfhost",
                    "MegaPlay",
                    "Server 14",
                    "Server 22",
                    "Server 21",
                    "Server 13",
                )
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_SERVER_KEY, newValue as String).commit()
                }
            },
        )

        // 2. Preferred Audio inside Server
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_AUDIO_KEY
                title = "Preferred Audio Priority"
                entries = arrayOf("Sub", "Dub", "Multi-Audio")
                entryValues = arrayOf("Sub", "Dub", "Multi-Audio")
                setDefaultValue(PREF_AUDIO_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_AUDIO_KEY, newValue as String).commit()
                }
            },
        )

        // 3. Preferred Language for Multi-Audio
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_LANG_KEY
                title = "Preferred Language (Multi-Audio)"
                entries = arrayOf("English", "Hindi", "Japanese", "Tamil", "Telugu", "Spanish", "French", "German", "Portuguese")
                entryValues = arrayOf("en", "hi", "ja", "ta", "te", "es", "fr", "de", "pt")
                setDefaultValue(PREF_LANG_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_LANG_KEY, newValue as String).commit()
                }
            },
        )

        // 4. Preferred Quality
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_QUALITY_KEY
                title = "Preferred Quality"
                entries = arrayOf("1080p", "720p", "480p", "360p")
                entryValues = arrayOf("1080", "720", "480", "360")
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).commit()
                }
            },
        )

        // 5. Catalog Source
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_CATALOG_KEY
                title = "Catalog Source"
                entries = arrayOf("AniList (Default)", "AniCore")
                entryValues = arrayOf("anilist", "anicore")
                setDefaultValue(PREF_CATALOG_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_CATALOG_KEY, newValue as String).commit()
                }
            },
        )

        // 6. Episode Metadata
        PreferenceCategory(context).apply {
            title = "Episode Metadata"
            screen.addPreference(this)

            SwitchPreferenceCompat(context).apply {
                key = PREF_LOAD_THUMBNAILS_KEY
                title = "Load episode thumbnails"
                summaryOn = "Fetching preview images from external sources"
                summaryOff = "Episode thumbnails disabled (faster episode list loading)"
                setDefaultValue(true)
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putBoolean(key, newValue as Boolean).commit()
                }
            }.also { addPreference(it) }

            SwitchPreferenceCompat(context).apply {
                key = PREF_LOAD_TITLES_KEY
                title = "Load episode titles"
                summaryOn = "Fetching canonical episode titles from metadata service"
                summaryOff = "Using default episode numbers only"
                setDefaultValue(true)
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putBoolean(key, newValue as Boolean).commit()
                }
            }.also { addPreference(it) }

            SwitchPreferenceCompat(context).apply {
                key = PREF_LOAD_DESCRIPTIONS_KEY
                title = "Load episode summaries (spoilers)"
                summaryOn = "Fetching episode descriptions from external sources"
                summaryOff = "Episode summaries disabled"
                setDefaultValue(true)
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putBoolean(key, newValue as Boolean).commit()
                }
            }.also { addPreference(it) }
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val audioPref = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(audioPref, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(langPref, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(qualityPref) }
                .thenByDescending { getVideoQualityWeight(it.videoTitle) },
        )
    }

    private fun getVideoQualityWeight(title: String): Int {
        val lower = title.lowercase()
        return when {
            lower.contains("1080p") -> 1080
            lower.contains("720p") -> 720
            lower.contains("480p") -> 480
            lower.contains("360p") -> 360
            else -> 0
        }
    }

    companion object {
        private const val PREF_SERVER_KEY = "pref_server_key"
        private const val PREF_SERVER_DEFAULT = "Selfhost"

        private const val PREF_AUDIO_KEY = "pref_audio_key"
        private const val PREF_AUDIO_DEFAULT = "Sub"

        private const val PREF_LANG_KEY = "pref_lang_key"
        private const val PREF_LANG_DEFAULT = "en"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_CATALOG_KEY = "pref_catalog_key"
        private const val PREF_CATALOG_DEFAULT = "anilist"

        private const val PREF_LOAD_THUMBNAILS_KEY = "pref_load_thumbnails"
        private const val PREF_LOAD_TITLES_KEY = "pref_load_titles"
        private const val PREF_LOAD_DESCRIPTIONS_KEY = "pref_load_descriptions"
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class ApiResponseDto(
    val success: Boolean? = null,
    val results: ResultsContainerDto? = null,
)

@Serializable
data class ResultsContainerDto(
    val page: Int? = null,
    val perPage: Int? = null,
    val total: Int? = null,
    val hasNextPage: Boolean? = null,
    val results: List<AnimeItemDto>? = null,
)

@Serializable
data class AnimeTitleDto(
    val userPreferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
data class CoverImageDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class StudioItemDto(
    val id: Int? = null,
    val name: String? = null,
    val isAnimationStudio: Boolean? = null,
)

@Serializable
data class StreamingEpisodeDto(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val site: String? = null,
)

@Serializable
data class AnimeItemDto(
    val id: Int? = null,
    val idMal: Int? = null,
    val title: AnimeTitleDto? = null,
    val description: String? = null,
    val coverImage: CoverImageDto? = null,
    val bannerImage: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val genres: List<String>? = null,
    val averageScore: Double? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val studios: List<StudioItemDto>? = null,
    val streamingEpisodes: List<StreamingEpisodeDto>? = null,
) {
    fun toSAnime(): SAnime {
        val animeTitle = title?.userPreferred
            ?: title?.english
            ?: title?.romaji
            ?: "Anime"
        val cover = coverImage?.extraLarge ?: coverImage?.large ?: coverImage?.medium ?: ""
        val animeId = (id ?: 0).toString()

        return SAnime.create().apply {
            this.title = animeTitle
            thumbnail_url = cover
            url = animeId
        }
    }
}

@Serializable
data class AnimeDetailsResponseDto(
    val success: Boolean? = null,
    val results: AnimeItemDto? = null,
)

@Serializable
data class SelfhostStreamDto(
    val aid: Int? = null,
    val mal: Int? = null,
    val ep: Int? = null,
    val master: String? = null,
    val index: String? = null,
    val type: String? = null,
)

@Serializable
data class MetaSubtitleDto(
    val label: String? = null,
    val file: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class MetaAudioDto(
    val label: String? = null,
    val lang: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class MetaSkipTimeDto(
    val start: Double? = null,
    val end: Double? = null,
)

@Serializable
data class SelfhostMetaDto(
    val source: String? = null,
    val height: Int? = null,
    val intro: MetaSkipTimeDto? = null,
    val outro: MetaSkipTimeDto? = null,
    val subs: List<MetaSubtitleDto>? = null,
    val audio: List<MetaAudioDto>? = null,
)

@Serializable
data class DubAvailableDto(
    val dub_available: Boolean? = null,
)

@Serializable
data class EpisodeEmbedDto(
    val anilist_id: Int? = null,
    val title: String? = null,
    val episode: Int? = null,
    val variant: String? = null,
    val vmoly_code: String? = null,
    val vmoly_url: String? = null,
    val byse_code: String? = null,
    val byse_url: String? = null,
    val byse_download: String? = null,
    val uqload_code: String? = null,
    val uqload_url: String? = null,
    val voe_code: String? = null,
    val voe_url: String? = null,
)

@Serializable
data class WorkerResolveDto(
    val url: String? = null,
    val master: String? = null,
)

@Serializable
data class ReAnimeServerItemDto(
    val dataLink: String? = null,
    val dataType: String? = null,
    val serverName: String? = null,
)

@Serializable
data class ReAnimeServersResponseDto(
    val success: Boolean? = null,
    val sub: List<ReAnimeServerItemDto>? = null,
    val dub: List<ReAnimeServerItemDto>? = null,
)

@Serializable
data class ReAnimeStreamDto(
    val rawUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class BlakiteDataDto(
    val url: String? = null,
)

@Serializable
data class BlakiteResponseDto(
    val success: Boolean? = null,
    val data: BlakiteDataDto? = null,
)
