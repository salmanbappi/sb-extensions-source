package eu.kanade.tachiyomi.animeextension.en.animestream

import android.net.Uri
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.Source
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeStream : Source() {

    override val name = "AnimeStream"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(CloudflareInterceptor(network.client))
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.toString().contains("/api/v1/")) {
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=86400")
                    .removeHeader("Pragma")
                    .build()
            } else {
                response
            }
        }
        .build()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val query = """
            query (\$page: Int) {
              Page (page: \$page, perPage: 20) {
                pageInfo {
                  hasNextPage
                }
                media (type: ANIME, sort: POPULAR_DESC) {
                  title {
                    romaji
                    english
                  }
                  coverImage {
                    large
                  }
                  description
                  averageScore
                }
              }
            }
        """.trimIndent()

        val body = buildJsonObject {
            put("query", query)
            put(
                "variables",
                buildJsonObject {
                    put("page", page)
                },
            )
        }
        val requestBody = okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json"),
            body.toString(),
        )

        return Request.Builder()
            .url("https://graphql.anilist.co")
            .post(requestBody)
            .build()
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseData = response.body.string()
        val apiResponse = json.decodeFromString<AniListResponseDto>(responseData)
        val page = apiResponse.data?.Page ?: return AnimesPage(emptyList(), false)

        val animeList = page.media.orEmpty().map { media ->
            val titleStr = media.title?.english ?: media.title?.romaji ?: ""
            val fallbackStr = if (media.title?.english != null) media.title.romaji ?: "" else ""
            SAnime.create().apply {
                title = titleStr
                url = buildString {
                    append("/search_redirect?q=")
                    append(java.net.URLEncoder.encode(titleStr, "UTF-8"))
                    if (fallbackStr.isNotBlank()) {
                        append("&fallback=")
                        append(java.net.URLEncoder.encode(fallbackStr, "UTF-8"))
                    }
                }
                thumbnail_url = media.coverImage?.large
                description = media.description
            }
        }
        return AnimesPage(animeList, page.pageInfo?.hasNextPage ?: false)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request {
        val query = """
            query (\$page: Int) {
              Page (page: \$page, perPage: 20) {
                pageInfo {
                  hasNextPage
                }
                media (type: ANIME, sort: TRENDING_DESC) {
                  title {
                    romaji
                    english
                  }
                  coverImage {
                    large
                  }
                  description
                  averageScore
                }
              }
            }
        """.trimIndent()

        val body = buildJsonObject {
            put("query", query)
            put(
                "variables",
                buildJsonObject {
                    put("page", page)
                },
            )
        }
        val requestBody = okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json"),
            body.toString(),
        )

        return Request.Builder()
            .url("https://graphql.anilist.co")
            .post(requestBody)
            .build()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val graphqlQuery = """
                query (\$page: Int, \$search: String) {
                  Page (page: \$page, perPage: 20) {
                    pageInfo {
                      hasNextPage
                    }
                    media (type: ANIME, search: \$search) {
                      title {
                        romaji
                        english
                      }
                      coverImage {
                        large
                      }
                      description
                      averageScore
                    }
                  }
                }
            """.trimIndent()

            val body = buildJsonObject {
                put("query", graphqlQuery)
                put(
                    "variables",
                    buildJsonObject {
                        put("page", page)
                        put("search", query)
                    },
                )
            }
            val requestBody = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json"),
                body.toString(),
            )
            return Request.Builder()
                .url("https://graphql.anilist.co")
                .post(requestBody)
                .build()
        }

        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val audio = filters.filterIsInstance<AudioFilter>().firstOrNull()?.getSelectedValue() ?: ""

        val urlBuilder = "$baseUrl/api/v1/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "20")

            if (type.isNotBlank()) addQueryParameter("t", type)
            if (genre.isNotBlank()) addQueryParameter("genre", genre)
            if (year.isNotBlank()) addQueryParameter("year", year)
            if (status.isNotBlank()) addQueryParameter("status", status)
            if (audio.isNotBlank()) addQueryParameter("audio", audio)
        }
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.toString().contains("anilist.co")) {
            return popularAnimeParse(response)
        }

        val responseData = response.body.string()
        val searchResult = json.decodeFromString<SearchResponseDto>(responseData)
        val animeList = mutableListOf<SAnime>()

        searchResult.series?.forEach { item ->
            animeList.add(
                SAnime.create().apply {
                    title = item.title
                    url = "/series/${item.content_id}"
                    thumbnail_url = item.image
                },
            )
        }

        searchResult.movies?.forEach { item ->
            animeList.add(
                SAnime.create().apply {
                    title = item.title
                    url = "/content/${item.content_id}"
                    thumbnail_url = item.image
                },
            )
        }

        return AnimesPage(animeList, animeList.size >= 20)
    }

    // ============================== Details ==============================

    private suspend fun resolveRedirectUrl(anime: SAnime) {
        if (anime.url.startsWith("/search_redirect")) {
            val uri = Uri.parse("http://127.0.0.1" + anime.url)
            val query = uri.getQueryParameter("q") ?: ""
            val fallback = uri.getQueryParameter("fallback") ?: ""

            var matchedItem: PopularItemDto? = null

            if (query.isNotBlank()) {
                val searchUrl = "$baseUrl/api/v1/search?suggest=1&query=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=10"
                val response = client.newCall(GET(searchUrl, headers)).awaitSuccess()
                val searchData = json.decodeFromString<SearchResponseDto>(response.body.string())
                matchedItem = searchData.series?.firstOrNull() ?: searchData.movies?.firstOrNull()
            }

            if (matchedItem == null && fallback.isNotBlank()) {
                val searchUrl = "$baseUrl/api/v1/search?suggest=1&query=${java.net.URLEncoder.encode(fallback, "UTF-8")}&limit=10"
                val response = client.newCall(GET(searchUrl, headers)).awaitSuccess()
                val searchData = json.decodeFromString<SearchResponseDto>(response.body.string())
                matchedItem = searchData.series?.firstOrNull() ?: searchData.movies?.firstOrNull()
            }

            if (matchedItem == null) {
                throw Exception("Anime not found on website: $query")
            }

            anime.url = if (matchedItem.type == "movie") "/content/${matchedItem.content_id}" else "/series/${matchedItem.content_id}"
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        resolveRedirectUrl(anime)
        val detailsResponse = client.newCall(GET("$baseUrl/api/v1${anime.url}", headers)).awaitSuccess()
        return animeDetailsParse(detailsResponse).apply {
            this.url = anime.url
            initialized = true
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/api/v1${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val responseData = response.body.string()
        val details = json.decodeFromString<DetailsResponseDto>(responseData)
        val scoreVal = details.score?.let { it / 10.0 }
        val scorePosition = preferences.getString(PREF_SCORE_POSITION_KEY, "top") ?: "top"

        return SAnime.create().apply {
            title = details.title ?: ""
            description = buildDescription(details.description, scoreVal, scorePosition)
            thumbnail_url = details.image ?: details.images?.find { it.type == "poster_tall" }?.url
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    private fun formatScore(score: Double?): String? {
        if (score == null || score <= 0.0) return null
        val stars = buildString {
            val full = (score / 2).toInt().coerceIn(0, 5)
            repeat(full) { append("★") }
            repeat(5 - full) { append("☆") }
        }
        return "$stars ${"%.2f".format(score)}"
    }

    private fun buildDescription(raw: String?, score: Double?, position: String): String {
        val scoreStr = formatScore(score) ?: return raw.orEmpty()
        return when (position) {
            "top" -> "$scoreStr\n\n${raw.orEmpty()}"
            "bottom" -> "${raw.orEmpty()}\n\n$scoreStr"
            else -> raw.orEmpty()
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        resolveRedirectUrl(anime)
        val isMovie = anime.url.startsWith("/content/")
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)

        if (isMovie) {
            val episode = SEpisode.create().apply {
                url = anime.url
                name = "Movie"
                episode_number = 1.0f
                date_upload = 0L
            }
            return listOf(episode)
        } else {
            val seriesId = anime.url.substringAfterLast("/")
            val seriesResponse = client.newCall(GET("$baseUrl/api/v1/series/$seriesId", headers)).awaitSuccess()
            val details = json.decodeFromString<DetailsResponseDto>(seriesResponse.body.string())
            val episodes = details.seasons.orEmpty().parallelCatchingFlatMap { season ->
                val epCount = season.episode_count ?: 0
                val pagesCount = if (epCount > 0) (epCount + 19) / 20 else 1
                (1..pagesCount).toList().parallelCatchingFlatMap { page ->
                    val url = "$baseUrl/api/v1/season/${season.content_id}/episodes?order_by=desc&limit=20&page=$page"
                    val response = client.newCall(GET(url, headers)).awaitSuccess()
                    val seasonEpisodes = json.decodeFromString<List<EpisodeItemDto>>(response.body.string())
                    seasonEpisodes.map { ep ->
                        val epNumStr = ep.episode_number?.let {
                            if (it % 1f == 0f) it.toInt().toString() else it.toString()
                        } ?: "1"
                        val epTitle = ep.title
                        val nameFormatted = if (!epTitle.isNullOrBlank() && !epTitle.equals("Episode $epNumStr", ignoreCase = true)) {
                            "S${season.season_number} Ep. $epNumStr - $epTitle"
                        } else {
                            "Season ${season.season_number} Episode $epNumStr"
                        }
                        SEpisode.create().apply {
                            this.url = "/episode/${ep.content_id}"
                            this.name = nameFormatted
                            episode_number = ep.episode_number ?: 1.0f
                            date_upload = 0L
                            summary = ep.description
                            preview_url = if (showThumbnails) ep.image else null
                            scanlator = getScanlatorLabel(ep.audio_locales)
                        }
                    }
                }
            }.distinctBy { it.url }
            return episodes.sortedByDescending { it.episode_number }
        }
    }

    private fun getScanlatorLabel(locales: List<String>?): String? {
        if (locales.isNullOrEmpty()) return null
        val hasSub = locales.contains("ja-JP")
        val hasDub = locales.any { it != "ja-JP" }
        return when {
            hasSub && hasDub -> "Sub, Dub"
            hasDub -> "Dub"
            hasSub -> "Sub"
            else -> null
        }
    }

    // ============================== Hosters (Lazy Stream Resolution) ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val isMovie = episode.url.startsWith("/content/")
        val contentId = episode.url.substringAfterLast("/")
        val typePath = if (isMovie) "movie" else "episode"

        val response = client.newCall(GET("$baseUrl/api/v1/$typePath/$contentId/media/hls/ja-JP", headers)).awaitSuccess()
        val mediaData = json.decodeFromString<MediaResponseDto>(response.body.string())

        val hosters = mutableListOf<Hoster>()

        // 1. Process Main HLS Stream
        mediaData.hls?.let { hls ->
            val locale = hls.locale ?: "ja-JP"
            val playlist = hls.playlist ?: ""
            val data = HosterData(locale, playlist, hls.hard_subs ?: emptyList())
            hosters.add(
                Hoster(
                    hosterName = getLocaleName(locale),
                    hosterUrl = json.encodeToString(HosterData.serializer(), data),
                ),
            )
        }

        // 2. Process alternative dubbed streams
        mediaData.versions?.hls?.forEach { version ->
            val locale = version.locale ?: ""
            val playlist = version.playlist ?: ""
            val data = HosterData(locale, playlist, version.hard_subs ?: emptyList())
            hosters.add(
                Hoster(
                    hosterName = getLocaleName(locale),
                    hosterUrl = json.encodeToString(HosterData.serializer(), data),
                ),
            )
        }

        val excludedAudio = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()
        val filteredHosters = hosters.filter { hoster ->
            val hosterData = json.decodeFromString<HosterData>(hoster.hosterUrl)
            !excludedAudio.contains(hosterData.locale)
        }.distinctBy { it.hosterName }

        val prefAudio = preferences.getString(PREF_AUDIO_LANG_KEY, "ja-JP")!!
        return filteredHosters.sortedWith(
            compareByDescending { hoster ->
                val hosterData = json.decodeFromString<HosterData>(hoster.hosterUrl)
                hosterData.locale == prefAudio
            },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterData = json.decodeFromString<HosterData>(hoster.hosterUrl)
        val videoList = mutableListOf<Video>()

        if (proxy == null) {
            proxy = LocalProxyServer(client).apply { start() }
        }

        // Extract videos from main playlist
        if (hosterData.playlist.isNotBlank()) {
            val langName = getLocaleName(hosterData.locale)
            val playlistParts = hosterData.playlist.split("/")
            val mediaFolder = playlistParts.getOrNull(7) ?: ""
            val mediaId = mediaFolder.substringBefore("_")
            val proxiedPlaylistUrl = getProxyUrl(hosterData.playlist, mediaId)

            val extracted = playlistUtils.extractFromHls(
                playlistUrl = proxiedPlaylistUrl,
                masterHeaders = headers,
                videoHeaders = headers,
                videoNameGen = { quality -> "$langName - $quality" },
            )
            videoList.addAll(extracted)
        }

        val excludedSub = preferences.getStringSet(PREF_EXCLUDE_SUB_KEY, emptySet()) ?: emptySet()
        // Extract videos from hardsubs playlists
        hosterData.hardSubs.forEach { sub ->
            val subLocale = sub.locale ?: "en-US"
            if (!excludedSub.contains(subLocale)) {
                sub.playlist?.let { playlistUrl ->
                    val langName = getLocaleName(hosterData.locale)
                    val subLang = getLocaleName(subLocale)
                    val playlistParts = playlistUrl.split("/")
                    val mediaFolder = playlistParts.getOrNull(7) ?: ""
                    val mediaId = mediaFolder.substringBefore("_")
                    val proxiedPlaylistUrl = getProxyUrl(playlistUrl, mediaId)

                    val extracted = playlistUtils.extractFromHls(
                        playlistUrl = proxiedPlaylistUrl,
                        masterHeaders = headers,
                        videoHeaders = headers,
                        videoNameGen = { quality -> "$langName [Hardsub: $subLang] - $quality" },
                    )
                    videoList.addAll(extracted)
                }
            }
        }

        return videoList
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, "1080p")!!
        val prefSub = preferences.getString(PREF_SUB_LANG_KEY, "en-US")!!
        val prefSubName = getLocaleName(prefSub)

        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { video ->
                    val title = video.videoTitle
                    if (prefSub == "none") {
                        !title.contains("Hardsub")
                    } else {
                        title.contains("[Hardsub: $prefSubName]")
                    }
                }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================== Filter list ==============================

    override fun getFilterList() = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        AudioFilter(),
    )

    private fun getLocaleName(locale: String): String = when (locale.lowercase()) {
        "ja-jp" -> "Japanese (RAW)"
        "en-us" -> "English"
        "de-de" -> "German"
        "fr-fr" -> "French"
        "it-it" -> "Italian"
        "es-es" -> "Spanish (Spain)"
        "es-419" -> "Spanish (LATAM)"
        "pt-br" -> "Portuguese (Brazil)"
        "ru-ru" -> "Russian"
        "ar-sa" -> "Arabic"
        "hi-in" -> "Hindi"
        "te-in" -> "Telugu"
        "ta-in" -> "Tamil"
        "th-th" -> "Thai"
        "vi-vn" -> "Vietnamese"
        "id-id" -> "Indonesian"
        "ms-my" -> "Malay"
        "zh-cn" -> "Chinese (Simplified)"
        "zh-hk" -> "Chinese (Traditional)"
        else -> locale.uppercase()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_DOMAIN_DEFAULT,
            title = "Base URL",
            key = PREF_DOMAIN_KEY,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = "1080p",
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080p", "720p", "480p"),
        )
        screen.addListPreference(
            key = PREF_AUDIO_LANG_KEY,
            default = PREF_AUDIO_LANG_DEFAULT,
            title = "Preferred Audio Language",
            summary = "%s",
            entries = AUDIO_LANGS,
            entryValues = AUDIO_VALS,
        )
        screen.addListPreference(
            key = PREF_SUB_LANG_KEY,
            default = PREF_SUB_LANG_DEFAULT,
            title = "Preferred Subtitle Language",
            summary = "%s",
            entries = listOf("None") + AUDIO_LANGS,
            entryValues = listOf("none") + AUDIO_VALS,
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_AUDIO_KEY,
            title = "Exclude Audio Languages",
            summary = "Select audio languages to exclude from the list",
            entries = AUDIO_LANGS,
            entryValues = AUDIO_VALS,
            default = emptySet(),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SUB_KEY,
            title = "Exclude Subtitle Languages",
            summary = "Select subtitle/hardsub languages to exclude from the list",
            entries = AUDIO_LANGS,
            entryValues = AUDIO_VALS,
            default = emptySet(),
        )
        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            default = "top",
            title = "Score Display Position",
            summary = "%s",
            entries = listOf("Top of description", "Bottom of description", "Disabled"),
            entryValues = listOf("top", "bottom", "disabled"),
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            default = true,
            title = "Show episode thumbnails",
            summary = "Fetch and display thumbnail images in the episode list.",
        )
    }

    // ============================== Filters ==============================

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Type",
            TYPES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = TYPES[state].second
    }

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Genre",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = GENRES[state].second
    }

    private class YearFilter :
        AnimeFilter.Select<String>(
            "Year",
            YEARS.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = YEARS[state].second
    }

    private class StatusFilter :
        AnimeFilter.Select<String>(
            "Status",
            STATUS.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = STATUS[state].second
    }

    private class AudioFilter :
        AnimeFilter.Select<String>(
            "Audio",
            AUDIO.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = AUDIO[state].second
    }

    // ============================== DTOs & Helper Classes ==============================

    @Serializable
    private data class HosterData(
        val locale: String,
        val playlist: String,
        val hardSubs: List<SubtitleDto> = emptyList(),
    )

    @Serializable
    private data class PopularItemDto(
        val content_id: String,
        val title: String,
        val image: String? = null,
        val type: String? = null,
    )

    @Serializable
    private data class SearchResponseDto(
        val series: List<PopularItemDto>? = null,
        val movies: List<PopularItemDto>? = null,
    )

    @Serializable
    private data class ImageDto(
        val url: String,
        val type: String,
    )

    @Serializable
    private data class SeasonDto(
        val content_id: String,
        val title: String,
        val season_number: Int,
        val episode_count: Int? = null,
    )

    @Serializable
    private data class DetailsResponseDto(
        val content_id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val images: List<ImageDto>? = null,
        val image: String? = null,
        val score: Double? = null,
        val seasons: List<SeasonDto>? = null,
    )

    @Serializable
    private data class EpisodeItemDto(
        val content_id: String,
        val title: String? = null,
        val episode_number: Float? = null,
        val image: String? = null,
        val description: String? = null,
        val audio_locales: List<String>? = null,
    )

    @Serializable
    private data class SubtitleDto(
        val locale: String? = null,
        val playlist: String? = null,
    )

    @Serializable
    private data class HlsDto(
        val locale: String? = null,
        val playlist: String? = null,
        val hard_subs: List<SubtitleDto>? = null,
    )

    @Serializable
    private data class VersionsDto(
        val hls: List<HlsDto>? = null,
    )

    @Serializable
    private data class MediaResponseDto(
        val hls: HlsDto? = null,
        val versions: VersionsDto? = null,
    )

    @Serializable
    private data class AniListTitleDto(
        val romaji: String? = null,
        val english: String? = null,
    )

    @Serializable
    private data class AniListCoverImageDto(
        val large: String? = null,
    )

    @Serializable
    private data class AniListMediaDto(
        val title: AniListTitleDto? = null,
        val coverImage: AniListCoverImageDto? = null,
        val description: String? = null,
        val averageScore: Int? = null,
    )

    @Serializable
    private data class AniListPageInfoDto(
        val hasNextPage: Boolean? = null,
    )

    @Serializable
    private data class AniListPageDto(
        val pageInfo: AniListPageInfoDto? = null,
        val media: List<AniListMediaDto>? = null,
    )

    @Serializable
    private data class AniListDataDto(
        val Page: AniListPageDto? = null,
    )

    @Serializable
    private data class AniListResponseDto(
        val data: AniListDataDto? = null,
    )

    private fun getProxyUrl(targetUrl: String, mediaId: String): String {
        val port = proxy?.port ?: 0
        if (port == 0) return targetUrl
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "key.bin"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&media_id=$mediaId"
    }

    companion object {
        private var proxy: LocalProxyServer? = null

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://anime.uniquestream.net"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_SCORE_POSITION_KEY = "pref_score_position"
        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"

        private const val PREF_AUDIO_LANG_KEY = "pref_audio_lang"
        private const val PREF_AUDIO_LANG_DEFAULT = "ja-JP"

        private const val PREF_SUB_LANG_KEY = "pref_sub_lang"
        private const val PREF_SUB_LANG_DEFAULT = "en-US"

        private const val PREF_EXCLUDE_AUDIO_KEY = "pref_exclude_audio"
        private const val PREF_EXCLUDE_SUB_KEY = "pref_exclude_sub"

        private val AUDIO_LANGS = listOf(
            "Japanese", "English", "Spanish (LatAm)", "Spanish (Spain)", "Portuguese (Brazil)",
            "French", "German", "Italian", "Arabic", "Hindi", "Tamil", "Telugu", "Thai",
            "Vietnamese", "Russian", "Indonesian", "Malay", "Chinese (Simplified)", "Chinese (Traditional)",
        )
        private val AUDIO_VALS = listOf(
            "ja-JP", "en-US", "es-419", "es-ES", "pt-BR",
            "fr-FR", "de-DE", "it-IT", "ar-SA", "hi-IN", "ta-IN", "te-IN", "th-TH",
            "vi-VN", "ru-RU", "id-ID", "ms-MY", "zh-CN", "zh-HK",
        )

        private val GENRES = listOf(
            Pair("Any", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Fantasy", "fantasy"),
            Pair("Music", "music"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shojo", "shojo"),
            Pair("Shonen", "shonen"),
            Pair("Slice of Life", "slice of life"),
            Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
        )

        private val TYPES = listOf(
            Pair("Any", ""),
            Pair("Series", "series"),
            Pair("Movies", "movies"),
        )

        private val YEARS = listOf(Pair("Any", "")) + (2026 downTo 1966).map { Pair(it.toString(), it.toString()) }

        private val STATUS = listOf(
            Pair("Any", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
        )

        private val AUDIO = listOf(
            Pair("Any", ""),
            Pair("Subbed", "sub"),
            Pair("Dubbed", "dub"),
        )
    }
}

private class LocalProxyServer(private val client: okhttp3.OkHttpClient) {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start() {
        if (running.get()) return
        serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        running.set(true)
        executor.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket!!.accept()
                    executor.execute { handleClient(socket) }
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val firstLine = input.bufferedReader().readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                val path = parts[1]
                routeRequest(path, output)
            }
        }
    }

    private fun routeRequest(path: String, output: OutputStream) {
        val uri = Uri.parse("http://127.0.0.1$path")
        val encodedUrl = uri.getQueryParameter("url") ?: return
        val mediaId = uri.getQueryParameter("media_id") ?: ""
        val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

        try {
            if (path.contains("playlist.m3u8")) {
                servePlaylist(targetUrl, mediaId, output)
            } else if (path.contains("key.bin")) {
                serveKey(targetUrl, mediaId, output)
            }
        } catch (e: Exception) {
            try {
                output.write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n".toByteArray())
            } catch (_: Exception) {}
        }
    }

    private fun servePlaylist(targetUrl: String, mediaId: String, output: OutputStream) {
        val reqHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://anime.uniquestream.net/")
            .build()

        val response = client.newCall(GET(targetUrl, reqHeaders)).execute()
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }

        val content = response.body.string()
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = targetUrl.toHttpUrl().resolve(uriValue)?.toString() ?: uriValue
                        val proxiedUri = getProxyUrl(resolvedUri, mediaId)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                val resolvedUri = targetUrl.toHttpUrl().resolve(trimmed)?.toString() ?: trimmed
                if (resolvedUri.contains(".m3u8")) {
                    builder.append(getProxyUrl(resolvedUri, mediaId))
                } else {
                    builder.append(resolvedUri)
                }
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

    private fun serveKey(targetUrl: String, mediaId: String, output: OutputStream) {
        val reqHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://anime.uniquestream.net/")
            .add("x-am-media-id", mediaId)
            .build()

        val response = client.newCall(GET(targetUrl, reqHeaders)).execute()
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }

        val keyText = response.body.string().trim()
        val decryptedKey = decryptKey(keyText, mediaId)

        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${decryptedKey.size}\r\n".toByteArray())
        output.write("Content-Type: application/octet-stream\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(decryptedKey)
        output.flush()
    }

    private fun getProxyUrl(targetUrl: String, mediaId: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "key.bin"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&media_id=$mediaId"
    }

    private fun decryptKey(encryptedBase64: String, mediaId: String): ByteArray {
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val md = MessageDigest.getInstance("SHA-256")
        val keySalt = md.digest(("key" + mediaId).toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        md.reset()
        val ivSalt = md.digest(("iv" + mediaId).toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        val secretKey = SecretKeySpec(keySalt, "AES")
        val ivSpec = IvParameterSpec(ivSalt)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedFull = cipher.doFinal(encryptedBytes)
        return decryptedFull.copyOfRange(0, 16)
    }
}
