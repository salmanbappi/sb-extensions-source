package eu.kanade.tachiyomi.animeextension.en.subdubanime

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.Source
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

class Subdubanime : Source() {

    override val name = "SubDubAnime"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 3, period = 1.seconds)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Catalog Cache ==============================

    private var catalogCache: BlakiteData? = null
    private var catalogCacheTime: Long = 0L
    private val cacheTtlMs = 30 * 60 * 1000L // 30 minutes

    private fun fetchCatalog(): BlakiteData {
        val now = System.currentTimeMillis()
        catalogCache?.let {
            if (now - catalogCacheTime < cacheTtlMs) return it
        }
        val response = client.newCall(GET(API_URL, headers)).execute()
        val parsed = response.parseAs<BlakiteResponse>()
        val data = parsed.data ?: BlakiteData()
        catalogCache = data
        catalogCacheTime = now
        return data
    }

    private fun allEntries(): List<Pair<String, AnimeEntry>> {
        val data = fetchCatalog()
        return data.series.entries.map { it.toPair() } +
            data.movies.entries.map { it.toPair() } +
            data.dramas.entries.map { it.toPair() }
    }

    private fun Map.Entry<String, AnimeEntry>.toPair() = Pair(key, value)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val entries = allEntries()
            .sortedByDescending { it.second.tmdbData?.rating?.toDoubleOrNull() ?: 0.0 }
        return paginateEntries(entries, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val entries = allEntries()
            .sortedByDescending { it.second.updatedAt ?: it.second.createdAt ?: "" }
        return paginateEntries(entries, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        var entries = allEntries()

        // Apply text search
        if (query.isNotBlank()) {
            val lowerQuery = query.lowercase()
            entries = entries.filter { (_, entry) ->
                entry.title?.lowercase()?.contains(lowerQuery) == true
            }
        }

        // Apply filters
        var typeFilter = ""
        var languageFilter = ""
        var statusFilter = ""
        var sortFilter = "updated"
        var selectedGenres = emptyList<String>()

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> if (!filter.isDefault()) typeFilter = filter.toUriPart()
                is Filters.LanguageFilter -> if (!filter.isDefault()) languageFilter = filter.toUriPart()
                is Filters.StatusFilter -> if (!filter.isDefault()) statusFilter = filter.toUriPart()
                is Filters.SortFilter -> sortFilter = filter.toUriPart()
                is Filters.GenreFilter -> selectedGenres = filter.getIncluded()
                else -> {}
            }
        }

        if (typeFilter.isNotBlank()) {
            entries = entries.filter { (_, entry) ->
                entry.type.equals(typeFilter, ignoreCase = true)
            }
        }

        if (languageFilter.isNotBlank()) {
            entries = entries.filter { (_, entry) ->
                entry.language.equals(languageFilter, ignoreCase = true)
            }
        }

        if (statusFilter.isNotBlank()) {
            entries = entries.filter { (_, entry) ->
                val entryStatus = entry.status
                    ?: entry.seasons?.values?.firstOrNull()?.status
                    ?: ""
                entryStatus.equals(statusFilter, ignoreCase = true)
            }
        }

        if (selectedGenres.isNotEmpty()) {
            entries = entries.filter { (_, entry) ->
                val genres = entry.tmdbData?.genres ?: emptyList()
                selectedGenres.all { genre -> genres.any { it.equals(genre, ignoreCase = true) } }
            }
        }

        // Sort
        entries = when (sortFilter) {
            "updated" -> entries.sortedByDescending { it.second.updatedAt ?: it.second.createdAt ?: "" }
            "created" -> entries.sortedByDescending { it.second.createdAt ?: "" }
            "title_asc" -> entries.sortedBy { it.second.title?.lowercase() ?: "" }
            "title_desc" -> entries.sortedByDescending { it.second.title?.lowercase() ?: "" }
            "rating" -> entries.sortedByDescending { it.second.tmdbData?.rating?.toDoubleOrNull() ?: 0.0 }
            else -> entries
        }

        return paginateEntries(entries, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search filters by title"),
        Filters.TypeFilter(),
        Filters.LanguageFilter(),
        Filters.StatusFilter(),
        Filters.SortFilter(),
        Filters.GenreFilter(Filters.GENRES),
    )

    private fun paginateEntries(entries: List<Pair<String, AnimeEntry>>, page: Int): AnimesPage {
        val startIdx = (page - 1) * PAGE_SIZE
        val endIdx = minOf(startIdx + PAGE_SIZE, entries.size)
        if (startIdx >= entries.size) return AnimesPage(emptyList(), false)

        val animes = entries.subList(startIdx, endIdx).map { (tmdbId, entry) ->
            entryToSAnime(tmdbId, entry)
        }
        val hasNext = endIdx < entries.size
        return AnimesPage(animes, hasNext)
    }

    private fun entryToSAnime(tmdbId: String, entry: AnimeEntry): SAnime = SAnime.create().apply {
        title = entry.title ?: "Unknown"
        url = "/api/entry/$tmdbId/${entry.type ?: "Series"}"
        thumbnail_url = entry.images?.poster ?: entry.images?.backdrop
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (tmdbId, type) = parseAnimeUrl(anime.url)
        val data = fetchCatalog()
        val entry = findEntry(data, tmdbId, type) ?: return anime.apply { initialized = true }
        val tmdb = entry.tmdbData

        return SAnime.create().apply {
            title = entry.title ?: anime.title
            url = anime.url
            thumbnail_url = entry.images?.poster ?: entry.images?.backdrop ?: anime.thumbnail_url
            genre = tmdb?.genres?.joinToString()
            status = when {
                entry.status?.contains("Completed", ignoreCase = true) == true -> SAnime.COMPLETED
                entry.status?.contains("Released", ignoreCase = true) == true -> SAnime.COMPLETED
                entry.status?.contains("Ongoing", ignoreCase = true) == true -> SAnime.ONGOING
                entry.seasons?.values?.any { it.status?.contains("Ongoing", ignoreCase = true) == true } == true -> SAnime.ONGOING
                entry.seasons?.values?.all { it.status?.contains("Completed", ignoreCase = true) == true } == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                val rating = tmdb?.rating?.toDoubleOrNull()
                if (rating != null && rating > 0.0) {
                    val stars = (rating / 2).toInt().coerceIn(0, 5)
                    append("★".repeat(stars) + "☆".repeat(5 - stars) + " " + "%.1f".format(rating) + "\n\n")
                }
                entry.language?.let { append("Language: $it\n") }
                entry.type?.let { append("Type: $it\n") }
                val releaseDate = tmdb?.releaseDate
                if (!releaseDate.isNullOrBlank()) append("Release: $releaseDate\n")
                append("\n")
                val synopsis = tmdb?.synopsis ?: tmdb?.overview
                if (!synopsis.isNullOrBlank()) append(synopsis)
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (tmdbId, type) = parseAnimeUrl(anime.url)
        val data = fetchCatalog()
        val entry = findEntry(data, tmdbId, type)
            ?: return emptyList()

        if (type == "Movie") {
            return listOf(
                SEpisode.create().apply {
                    url = "${anime.url}#movie"
                    name = entry.title ?: "Movie"
                    episode_number = 1f
                    scanlator = entry.language
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        val seasons = entry.seasons ?: return emptyList()
        val sortedSeasons = seasons.entries.sortedBy { it.key.toIntOrNull() ?: 0 }

        for ((seasonKey, seasonInfo) in sortedSeasons) {
            val seasonNum = seasonKey.toIntOrNull() ?: continue
            val totalEps = seasonInfo.totalEpisodes ?: 0

            for (epNum in 1..totalEps) {
                episodes.add(
                    SEpisode.create().apply {
                        url = "${anime.url}#season=$seasonNum&ep=$epNum"
                        name = if (sortedSeasons.size > 1) {
                            "Season $seasonNum - Episode $epNum"
                        } else {
                            "Episode $epNum"
                        }
                        episode_number = if (sortedSeasons.size > 1) {
                            ((seasonNum - 1) * 100 + epNum).toFloat()
                        } else {
                            epNum.toFloat()
                        }
                        scanlator = entry.language
                    },
                )
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Hoster List =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterName = "SubDubAnime",
            hosterUrl = episode.url,
        ),
    )

    // ============================ Video List =============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val (tmdbId, type) = parseAnimeUrl(url.substringBefore("#"))
        val anchor = url.substringAfter("#", "")

        val isMovie = anchor == "movie" || type == "Movie"

        val apiUrl = if (isMovie) {
            "$SERVER_URL/api/get.php?tmdbId=$tmdbId"
        } else {
            val seasonNum = Regex("season=(\\d+)").find(anchor)?.groupValues?.get(1) ?: "1"
            val epNum = Regex("ep=(\\d+)").find(anchor)?.groupValues?.get(1) ?: "1"
            val uniqueId = "$seasonNum-$epNum"
            "$SERVER_URL/api/get.php?id=$uniqueId&tmdbId=$tmdbId"
        }

        val response = client.newCall(
            GET(apiUrl, headers.newBuilder().set("Referer", "$SERVER_URL/").build()),
        ).execute()
        val streamResponse = response.parseAs<StreamResponse>()

        if (streamResponse.success != true || streamResponse.data == null) {
            return emptyList()
        }

        val streamData = streamResponse.data
        val dataId = streamData.dataId ?: return emptyList()
        val format = streamData.format ?: "M3U8"

        // Parse available qualities from ranges
        val qualityList = mutableListOf<QualityInfo>()

        if (format == "M3U8" && !streamData.ranges.isNullOrBlank()) {
            val rangeLines = streamData.ranges.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            val rangeMap = mutableMapOf<String, String>()
            for (line in rangeLines) {
                val match = Regex("^(\\d+-\\d+)\\s*\\(([^)]+)\\)").find(line)
                if (match != null) {
                    rangeMap[match.groupValues[2].trim()] = match.groupValues[1]
                }
            }

            QUALITY_LABELS.forEachIndexed { i, label ->
                val range = rangeMap[label]
                if (range != null) {
                    qualityList.add(QualityInfo(label, QUALITY_CODES[i], range))
                }
            }

            // Fallback to qid-based if ranges parsing failed
            if (qualityList.isEmpty()) {
                val maxIdx = minOf((streamData.qid ?: QUALITY_LABELS.size) - 1, QUALITY_LABELS.size - 1)
                for (i in 0..maxIdx) {
                    qualityList.add(QualityInfo(QUALITY_LABELS[i], QUALITY_CODES[i], null))
                }
            }
        } else {
            val maxIdx = minOf((streamData.qid ?: QUALITY_LABELS.size) - 1, QUALITY_LABELS.size - 1)
            for (i in 0..maxIdx) {
                qualityList.add(QualityInfo(QUALITY_LABELS[i], QUALITY_CODES[i], null))
            }
        }

        val videoHeaders = headers.newBuilder().set("Referer", "$SERVER_URL/").build()

        val videoList = qualityList.map { qi ->
            val videoUrl = if (format == "M3U8") {
                val rangeParam = qi.range?.let { "&r_range=$it" } ?: ""
                "${CDN_BASE_URL}$dataId.${qi.code}.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl$rangeParam"
            } else {
                "${CDN_BASE_URL}$dataId.${qi.code}.mp4"
            }

            val resolutionNumber = qi.label.replace("p", "").toIntOrNull()

            Video(
                videoUrl = videoUrl,
                videoTitle = qi.label,
                headers = videoHeaders,
                resolution = resolutionNumber,
            )
        }

        return videoList.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================== Preferences ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p", "240p"),
            entryValues = listOf("1080p", "720p", "480p", "360p", "240p"),
        )
    }

    // ============================== Helpers ==============================

    private data class QualityInfo(val label: String, val code: String, val range: String?)

    private fun parseAnimeUrl(url: String): Pair<String, String> {
        // Format: /api/entry/{tmdbId}/{type}#...
        val cleanUrl = url.substringBefore("#")
        val parts = cleanUrl.removePrefix("/api/entry/").split("/")
        val tmdbId = parts.getOrElse(0) { "" }
        val type = parts.getOrElse(1) { "Series" }
        return Pair(tmdbId, type)
    }

    private fun findEntry(data: BlakiteData, tmdbId: String, type: String): AnimeEntry? = when (type) {
        "Movie" -> data.movies[tmdbId]
        "Drama" -> data.dramas[tmdbId]
        else -> data.series[tmdbId]
    }

    companion object {
        private const val API_URL = "https://blakiteapi.xyz/api/getAllAnime.php"
        private const val SERVER_URL = "https://blakiteapi.xyz"
        private const val CDN_BASE_URL = "https://hugh.cdn.rumble.cloud/video/"

        private val QUALITY_CODES = listOf("oaa", "baa", "caa", "gaa", "haa")
        private val QUALITY_LABELS = listOf("240p", "360p", "480p", "720p", "1080p")

        private const val PAGE_SIZE = 24

        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://www.subdubanime.site"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }
}
