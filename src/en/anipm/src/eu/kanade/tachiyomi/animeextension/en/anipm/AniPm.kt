package eu.kanade.tachiyomi.animeextension.en.anipm

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class AniPm : Source() {

    override val name = "Ani.pm"

    override val baseUrl = "https://ani.pm"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 6, period = 1.seconds)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular Anime ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/api/anime/catalog?page=$page&sort=popular"
        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<AnimeCatalogResponseDto>(json)
        val animeList = (dto.items ?: emptyList()).map { it.toSAnime(baseUrl) }
        return AnimesPage(animeList, dto.hasNextPage ?: false)
    }

    // ============================== Latest Updates =============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = "$baseUrl/api/anime/latest-episodes?page=$page"
        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<AnimeCatalogResponseDto>(json)
        val animeList = (dto.items ?: emptyList()).map { it.toSAnime(baseUrl) }
        return AnimesPage(animeList, dto.hasNextPage ?: false)
    }

    // =============================== Search Anime ==============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (query.isNotBlank()) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$baseUrl/api/anime/search?q=$encoded"
        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<AnimeListResponseDto>(json)
        val animeList = (dto.items ?: emptyList()).map { it.toSAnime(baseUrl) }
        AnimesPage(animeList, false)
    } else {
        var sort = "popular"
        var genre = ""
        var format = ""

        for (filter in filters) {
            when (filter) {
                is SortFilter -> sort = filter.toUriPart()
                is GenreFilter -> genre = filter.toUriPart()
                is FormatFilter -> format = filter.toUriPart()
                else -> {}
            }
        }

        val urlBuilder = "$baseUrl/api/anime/catalog?page=$page".toHttpUrl().newBuilder()
        if (sort.isNotBlank()) urlBuilder.addQueryParameter("sort", sort)
        if (genre.isNotBlank()) urlBuilder.addQueryParameter("genre", genre)
        if (format.isNotBlank()) urlBuilder.addQueryParameter("format", format)

        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        val dto = response.parseAs<AnimeCatalogResponseDto>(json)
        val animeList = (dto.items ?: emptyList()).map { it.toSAnime(baseUrl) }
        AnimesPage(animeList, dto.hasNextPage ?: false)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        GenreFilter(),
        FormatFilter(),
    )

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val path = anime.url.substringBefore("#")
        val apiUrl = if (path.startsWith("/ani/")) {
            val id = path.substringAfter("/ani/")
            "$baseUrl/api/anime/ani/$id?routes=e4"
        } else {
            val id = path.substringAfter("/anime/")
            "$baseUrl/api/anime/series/$id?routes=e4"
        }

        val response = client.newCall(GET(apiUrl, headers)).execute()
        val dto = response.parseAs<AnimeDetailDto>(json)
        return dto.toSAnime(baseUrl).apply {
            initialized = true
        }
    }

    // ============================== Episode List ===============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val path = anime.url.substringBefore("#")
        val apiUrl = if (path.startsWith("/ani/")) {
            val id = path.substringAfter("/ani/")
            "$baseUrl/api/anime/ani/$id?routes=e4"
        } else {
            val id = path.substringAfter("/anime/")
            "$baseUrl/api/anime/series/$id?routes=e4"
        }

        val response = client.newCall(GET(apiUrl, headers)).execute()
        val dto = response.parseAs<AnimeDetailDto>(json)

        val episodes = (dto.episodes ?: emptyList()).mapNotNull { ep ->
            val epNum = ep.number ?: return@mapNotNull null
            val sub = ep.sub == true
            val dub = ep.dub == true
            val epInt = if (epNum % 1f == 0f) epNum.toInt().toString() else epNum.toString()

            SEpisode.create().apply {
                name = if (!ep.title.isNullOrBlank()) "Episode $epInt: ${ep.title}" else "Episode $epInt"
                episode_number = epNum
                url = "${anime.url}#ep=$epInt&sub=$sub&dub=$dub"
                date_upload = parseDate(ep.aired)
                scanlator = when {
                    sub && dub -> "Sub, Dub"
                    dub -> "Dub"
                    else -> "Sub"
                }
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Hoster & Video Links =========================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hasSub = episode.url.contains("sub=true") || !episode.url.contains("sub=false")
        val hasDub = episode.url.contains("dub=true")
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

        val hosters = mutableListOf<Hoster>()
        if (hasSub) {
            hosters.add(Hoster(hosterName = "Ani.pm (Sub)", hosterUrl = "${episode.url}&channel=sub"))
        }
        if (hasDub) {
            hosters.add(Hoster(hosterName = "Ani.pm (Dub)", hosterUrl = "${episode.url}&channel=dub"))
        }
        if (hosters.isEmpty()) {
            hosters.add(Hoster(hosterName = "Ani.pm", hosterUrl = "${episode.url}&channel=sub"))
        }

        return if (prefAudio == "dub") {
            hosters.sortedByDescending { it.hosterName.contains("Dub", ignoreCase = true) }
        } else {
            hosters.sortedByDescending { it.hosterName.contains("Sub", ignoreCase = true) }
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterUrl = hoster.hosterUrl
        val path = hosterUrl.substringBefore("#")
        val source = if (path.startsWith("/ani/")) "anilist" else "settlar"
        val id = if (path.startsWith("/ani/")) path.substringAfter("/ani/") else path.substringAfter("/anime/")
        val ep = Regex("""[?&#]ep=([^&#]+)""").find(hosterUrl)?.groupValues?.get(1) ?: "1"
        val channel = Regex("""[?&#]channel=(sub|dub)""").find(hosterUrl)?.groupValues?.get(1) ?: "sub"

        // 1. Playback bootstrap
        val bootstrapUrl = "$baseUrl/api/anime/playback-bootstrap/$source/$id?ep=$ep&lang=$channel"
        val bootstrapRes = client.newCall(GET(bootstrapUrl, headers)).execute()
        val bootstrapDto = bootstrapRes.parseAs<PlaybackBootstrapDto>(json)
        val selection = bootstrapDto.settlarSelection
            ?: throw Exception("No streaming selection token available")

        // 2. Settlar session
        val sessionApiUrl = "$baseUrl/api/anime/settlar/session?selection=$selection&provider=anipm&ep=$ep&channel=$channel&telemetry=0"
        val sessionHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val sessionRes = client.newCall(GET(sessionApiUrl, sessionHeaders)).execute()
        val sessionDto = sessionRes.parseAs<SettlarSessionDto>(json)
        val embedUrl = sessionDto.embedUrl
            ?: throw Exception("No embed URL returned by session")

        // 3. Extract token t from embedUrl
        val token = Regex("""[?&]t=([^&#]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: throw Exception("No token found in embed URL")

        // 4. Settlar embed session
        val embedSessionUrl = "https://embed.settlar.io/api/embed/session?t=$token"
        val embedHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .build()
        val embedRes = client.newCall(GET(embedSessionUrl, embedHeaders)).execute()
        val embedDto = embedRes.parseAs<EmbedPlayerSessionDto>(json)
        val masterM3u8Url = embedDto.source
            ?: throw Exception("No stream source URL found")

        // Subtitles
        val subtitleTracks = embedDto.subtitles?.mapNotNull { sub ->
            val subUrl = sub.url ?: return@mapNotNull null
            Track(
                url = subUrl,
                lang = sub.label ?: "English",
            )
        } ?: emptyList()

        // 5. Extract videos from HLS master playlist
        val streamHeadersBuilder = headers.newBuilder()
            .set("User-Agent", DEFAULT_USER_AGENT)
            .set("Referer", "https://embed.settlar.io/")
            .set("Origin", "https://embed.settlar.io")

        embedDto.keyProof?.let {
            if (it.isNotBlank()) {
                streamHeadersBuilder.set("X-Settlar-Key-Proof", it)
            }
        }
        val streamHeaders = streamHeadersBuilder.build()

        val rawVideos = try {
            playlistUtils.extractFromHls(
                playlistUrl = masterM3u8Url,
                referer = "https://embed.settlar.io/",
                masterHeaders = streamHeaders,
                videoHeaders = streamHeaders,
                videoNameGen = { quality -> quality },
                subtitleList = subtitleTracks,
            )
        } catch (_: Exception) {
            emptyList()
        }

        val videos = if (rawVideos.isNotEmpty()) {
            rawVideos
        } else {
            listOf(
                Video(
                    videoUrl = masterM3u8Url,
                    videoTitle = "Auto",
                    headers = streamHeaders,
                    subtitleTracks = subtitleTracks,
                ),
            )
        }

        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================== Preferences ================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred audio language"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            format.parse(dateStr.substringBefore("T"))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "sub"
    }
}
