package eu.kanade.tachiyomi.animeextension.en.zanora

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt

class Zanora : Source() {

    override val name = "Zanora"

    override val baseUrl = "https://zanora.lol"

    override val lang = "en"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val megaCloudExtractor by lazy {
        MegaCloudExtractor(client, headers, "https://megacloud-decrypter.vercel.app/api/dec")
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    private fun ajaxHeaders(refererUrl: String): Headers = headers.newBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .set("Referer", refererUrl)
        .build()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/most-popular?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/home?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (query.isNotBlank()) {
        val response = client.newCall(GET("$baseUrl/search?keyword=$query&page=$page", headers)).execute()
        parseAnimeListPage(response)
    } else {
        val urlBuilder = "$baseUrl/filter".toHttpUrl().newBuilder()
        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("type", filter.toUriPart())

                is Filters.StatusFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())

                is Filters.RatedFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("rated", filter.toUriPart())

                is Filters.SeasonFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("season", filter.toUriPart())

                is Filters.LanguageFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("language", filter.toUriPart())

                is Filters.SortFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("sort", filter.toUriPart())

                is Filters.GenreFilter -> {
                    val selectedGenres = filter.toQueries()
                    if (selectedGenres.isNotEmpty()) {
                        urlBuilder.addQueryParameter("genre", selectedGenres.joinToString(","))
                    }
                }

                else -> {}
            }
        }
        urlBuilder.addQueryParameter("page", page.toString())
        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        parseAnimeListPage(response)
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Filters apply only when keyword is empty"),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.RatedFilter(),
        Filters.SeasonFilter(),
        Filters.LanguageFilter(),
        Filters.SortFilter(),
        Filters.GenreFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeElements = doc.select("div.film_list-wrap div.flw-item, div.film_list div.film-poster")

        val animeList = animeElements.mapNotNull { element ->
            val linkEl = element.selectFirst("a[href*=/watch/]") ?: return@mapNotNull null
            val titleEl = element.selectFirst("h3.film-name a, div.film-name a, a.dynamic-name") ?: linkEl

            SAnime.create().apply {
                title = titleEl.text().trim()
                setUrlWithoutDomain(linkEl.attr("href"))
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                    ?: element.selectFirst("img")?.attr("src")
            }
        }

        val hasNextPage = doc.select("ul.pagination li.active + li").isNotEmpty() ||
            doc.select("a[rel=next]").isNotEmpty()

        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val synopsis = doc.selectFirst("div.anid-info div.text, div.film-description div.text")?.text()?.trim().orEmpty()
        val genres = doc.select("div.anid-info a[href*=/genre/]").joinToString { it.text().trim() }

        val statusText = doc.select("div.anid-info:contains(Status), div.film-stats:contains(Status)").text()
        val animeStatus = when {
            statusText.contains("Currently Airing", ignoreCase = true) || statusText.contains("Airing", ignoreCase = true) -> SAnime.ONGOING
            statusText.contains("Finished Airing", ignoreCase = true) || statusText.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        val studio = doc.select("div.anid-info:contains(Studios) a, div.anid-info:contains(Studio) a").text().trim()

        return SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.thumbnail_url
            genre = genres.ifBlank { null }
            author = studio.ifBlank { null }
            status = animeStatus
            initialized = true

            description = buildString {
                if (synopsis.isNotBlank()) append(synopsis)
                if (statusText.isNotBlank()) append("\n\n").append(statusText)
                if (studio.isNotBlank()) append("\nStudio: ").append(studio)
            }.trim()
        }
    }

    // ============================== Episodes ==============================

    @Serializable
    private data class EpisodeResponseDto(
        val status: Boolean = false,
        val html: String = "",
    )

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val movieId = anime.url.substringAfter("/watch/").substringBefore("?")
        if (movieId.isBlank()) return emptyList()

        val epListUrl = "$baseUrl/ajax/v2/episode/list/$movieId"
        val req = GET(epListUrl, ajaxHeaders("$baseUrl${anime.url}"))
        val response = client.newCall(req).execute().body.string()

        val dto = runCatching {
            json.decodeFromString<EpisodeResponseDto>(response)
        }.getOrNull() ?: return emptyList()

        if (!dto.status || dto.html.isBlank()) return emptyList()

        val doc = org.jsoup.Jsoup.parse(dto.html)
        val epElements = doc.select("a.ep-item")

        return epElements.mapIndexed { index, element ->
            val epId = element.attr("data-id")
            val epNumStr = element.attr("data-number")
            val epName = element.attr("title").ifBlank { element.text().trim() }
            val epNum = epNumStr.toFloatOrNull() ?: (index + 1).toFloat()

            SEpisode.create().apply {
                name = "Episode $epNumStr: $epName".removeSuffix(":")
                episode_number = epNum
                setUrlWithoutDomain("${anime.url}?ep=$epId")
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    @Serializable
    private data class ServerResponseDto(
        val status: Boolean = false,
        val html: String = "",
    )

    @Serializable
    private data class SourceResponseDto(
        val success: Boolean = false,
        val type: String = "",
        val link: String = "",
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeId = episode.url.substringAfter("/watch/").substringBefore("?")
        val epId = episode.url.substringAfter("?ep=", "")
        if (animeId.isBlank() || epId.isBlank()) return emptyList()

        val serversUrl = "$baseUrl/ajax/v2/episode/servers?episodeId=$animeId-$epId"
        val req = GET(serversUrl, ajaxHeaders("$baseUrl${episode.url}"))
        val response = client.newCall(req).execute().body.string()

        val dto = runCatching {
            json.decodeFromString<ServerResponseDto>(response)
        }.getOrNull() ?: return emptyList()

        if (!dto.status || dto.html.isBlank()) return emptyList()

        val doc = org.jsoup.Jsoup.parse(dto.html)
        val serverElements = doc.select("div.server-item")

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()

        val hosters = serverElements.mapNotNull { el ->
            val serverDataId = el.attr("data-id")
            val audioType = el.attr("data-type").uppercase()
            val serverName = el.attr("data-server-id").ifBlank { "Server" }

            if (serverDataId.isBlank()) return@mapNotNull null
            if (serverName in excludedServers || audioType in excludedAudios) return@mapNotNull null

            val sourceUrl = "$baseUrl/ajax/v2/episode/sources?id=$serverDataId"
            val srcReq = GET(sourceUrl, ajaxHeaders("$baseUrl${episode.url}"))
            val srcRespStr = runCatching { client.newCall(srcReq).execute().body.string() }.getOrNull() ?: return@mapNotNull null

            val srcDto = runCatching { json.decodeFromString<SourceResponseDto>(srcRespStr) }.getOrNull() ?: return@mapNotNull null
            if (!srcDto.success || srcDto.link.isBlank()) return@mapNotNull null

            Hoster(
                hosterName = "$audioType - $serverName",
                hosterUrl = "$audioType|${srcDto.link}",
            )
        }

        return sortHostersByPreference(hosters)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        val audioType = parts.getOrNull(0) ?: "SUB"
        val embedUrl = parts.getOrNull(1) ?: return emptyList()

        return runCatching {
            val videos = mutableListOf<Video>()

            if (embedUrl.contains("player.zanora.lol") || embedUrl.contains("megacloud")) {
                val extracted = megaCloudExtractor.getVideosFromUrl(embedUrl, type = audioType, name = hoster.hosterName)
                videos.addAll(extracted)
            } else if (embedUrl.contains(".m3u8")) {
                val m3u8Videos = playlistUtils.extractFromHls(
                    embedUrl,
                    videoNameGen = { "${hoster.hosterName} - $it" },
                    referer = "$baseUrl/",
                )
                videos.addAll(m3u8Videos)
            }

            videos.sortVideos()
        }.getOrDefault(emptyList())
    }

    // ============================ Recommendations =============================

    fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        val relatedElements = doc.select("div.film-related div.flw-item, div.related-anime a")
        return relatedElements.mapNotNull { el ->
            val linkEl = el.selectFirst("a[href*=/watch/]") ?: el
            val titleEl = el.selectFirst("h3.film-name, div.film-name") ?: linkEl
            SAnime.create().apply {
                title = titleEl.text().trim()
                setUrlWithoutDomain(linkEl.attr("href"))
                thumbnail_url = el.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    // ============================== Settings ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefAudio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution },
        )
    }

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        if (prefServer == "auto") return hosters

        return hosters.sortedByDescending {
            it.hosterName.contains(prefServer, ignoreCase = true)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "Sorts videos so this quality is on top. Currently: %s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio type",
            summary = "Sub or Dub first. Currently: %s",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("SUB", "DUB"),
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "Which video server to try first. Currently: %s",
            entries = listOf("Auto", "Aika", "Levi", "Envy", "MegaCloud"),
            entryValues = listOf("auto", "Aika", "Levi", "Envy", "MegaCloud"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude Servers",
            summary = "Select servers to exclude from video list",
            entries = listOf("Aika", "Levi", "Envy", "MegaCloud"),
            entryValues = listOf("Aika", "Levi", "Envy", "MegaCloud"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_AUDIO_KEY,
            default = emptySet(),
            title = "Exclude Audio Type",
            summary = "Select audio formats to exclude",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("SUB", "DUB"),
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "SUB"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_AUDIO_KEY = "pref_exclude_audio"
    }
}
