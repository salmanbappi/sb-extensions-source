package eu.kanade.tachiyomi.animeextension.en.anidb

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import extensions.utils.parseAs
import keiyoushi.utils.addListPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Serializable
data class EpisodeListDto(
    val episodes: List<EpisodeDto>,
)

@Serializable
data class EpisodeDto(
    val id: Long,
    val number: Float,
    val number2: Float? = null,
    val filler: Boolean = false,
)

@Serializable
data class LanguageListDto(
    val languages: List<LanguageDto>,
)

@Serializable
data class LanguageDto(
    val code: String,
    val name: String,
    val embed_url: String,
)

class AniDB : Source() {

    override val name = "AniDB"

    override val baseUrl = "https://anidb.app"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    private val m3u8Regex = Regex("""file:\s*['"](https?://[^'"]+master\.m3u8)['"]""")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?sort=order_popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse?sort=order_updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("type", filter.toUriPart())
                    }
                }

                is Filters.StatusFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("status", filter.toUriPart())
                    }
                }

                is Filters.SeasonFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("season", filter.toUriPart())
                    }
                }

                is Filters.YearFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("year", filter.toUriPart())
                    }
                }

                is Filters.GenreFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("genres", filter.toUriPart())
                    }
                }

                is Filters.SortFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("sort", filter.toUriPart())
                    }
                }

                else -> {}
            }
        }
        urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.YearFilter(),
        Filters.GenreFilter(),
        Filters.SortFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("img[src*=posters]")?.attr("abs:src")
            description = document.select("h2:contains(Synopsis) + div p").text()
            author = document.select("dt:contains(Studios) + dd a").text()
            val statusText = document.select("dt:contains(Status) + dd a").text()
            status = when {
                statusText.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                statusText.contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            val genresList = document.select("dt:contains(Themes) + dd a, a[href*=/genres/]").map { it.text() }
            genre = genresList.distinct().joinToString()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.trimEnd('/').substringAfterLast("-")
        return GET("$baseUrl/api/frontend/anime/$animeId/episodes", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<EpisodeListDto>()
        val minEpNumber = data.episodes.minOfOrNull { it.number } ?: 0f
        val offset = if (minEpNumber > 1f) minEpNumber - 1f else 0f
        return data.episodes.map { ep ->
            SEpisode.create().apply {
                val adjustedNumber = ep.number - offset
                val adjustedNumber2 = ep.number2?.let { it - offset }
                val label = if (adjustedNumber2 != null && adjustedNumber2 != 0f && adjustedNumber2 != adjustedNumber) {
                    "${adjustedNumber.toInt()}–${adjustedNumber2.toInt()}"
                } else {
                    adjustedNumber.toInt().toString()
                }
                name = "Episode $label"
                if (ep.filler) {
                    name += " (Filler)"
                }
                episode_number = adjustedNumber
                url = ep.id.toString()
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/api/frontend/episode/${episode.url}/languages", headers)

    override fun videoListParse(response: Response): List<Video> {
        val data = response.parseAs<LanguageListDto>()

        return data.languages.parallelCatchingFlatMapBlocking { lang ->
            val embedResponse = client.newCall(GET(lang.embed_url, headers)).execute()
            val html = embedResponse.body.string()
            val m3u8Url = m3u8Regex.find(html)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "$baseUrl/",
                masterHeaders = headers,
                videoHeaders = headers,
                videoNameGen = { quality -> "${lang.name} - $quality" },
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(quality) },
                {
                    if (lang == "eng") {
                        it.videoTitle.contains("English", ignoreCase = true)
                    } else {
                        it.videoTitle.contains("Japanese", ignoreCase = true)
                    }
                },
            ),
        ).reversed()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = PREF_LANG_TITLE,
            entries = PREF_LANG_ENTRIES,
            entryValues = PREF_LANG_VALUES,
            default = PREF_LANG_DEFAULT,
            summary = "%s",
        )
    }

    // ============================= Utilities ==============================

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select(".anime-grid a.anime-card")
        val animeList = cards.map { card ->
            SAnime.create().apply {
                val animeUrl = card.attr("href")
                setUrlWithoutDomain(animeUrl)
                title = card.selectFirst("p.text-xs, .card-overlay p")?.text() ?: card.attr("title") ?: ""
                thumbnail_url = card.selectFirst("img")?.attr("abs:src")
            }
        }

        val hasNextPage = document.select("a").any { it.text().contains("Next") }
        return AnimesPage(animeList, hasNextPage)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "360p")

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "jpn"
        private val PREF_LANG_ENTRIES = listOf("Japanese", "English")
        private val PREF_LANG_VALUES = listOf("jpn", "eng")
    }
}
