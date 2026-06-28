package eu.kanade.tachiyomi.animeextension.en.anineko

import android.net.Uri
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class AniNeko : Source() {

    override val name = "AniNeko"

    override val baseUrl = "https://anineko.to"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular / Latest ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browser?sort=recently_updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/browser".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("keyword", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("genre[]", it)
                    }
                }

                is TypeFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("type[]", it)
                    }
                }

                is StatusFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("status[]", it)
                    }
                }

                is LanguageFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("language[]", it)
                    }
                }

                is YearFilter -> {
                    filter.getCheckedUriParts().forEach {
                        urlBuilder.addQueryParameter("year[]", it)
                    }
                }

                is SortFilter -> {
                    if (!filter.isDefault()) {
                        urlBuilder.addQueryParameter("sort", filter.toUriPart())
                    }
                }

                else -> {}
            }
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val cards = document.select("article.nv-anime-card.nv-browse-card")

        val animes = cards.map { card ->
            SAnime.create().apply {
                val linkEl = card.selectFirst("a.nv-anime-thumb") ?: card.selectFirst("a")!!
                url = linkEl.attr("href")
                title = card.selectFirst("h3.nv-anime-title a")?.text()
                    ?: linkEl.selectFirst("img")?.attr("alt")
                    ?: ""
                thumbnail_url = linkEl.selectFirst("img")?.attr("src")
            }
        }

        val hasNextPage = document.selectFirst("li.page-item.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)!!
            val mainTitle = document.selectFirst("h1")?.text() ?: ""
            val altTitle = document.selectFirst("div.nv-info-alt-title")?.text() ?: ""
            title = if (titleLang == "Romaji/Japanese" && altTitle.isNotBlank()) {
                altTitle
            } else {
                mainTitle
            }

            genre = document.select("div.nv-info-genres span").joinToString { it.text() }

            val statusStr = document.selectFirst("div.nv-info-list div:contains(Status) strong, div.nv-info-stats div:contains(Status) strong")?.text() ?: ""
            status = when {
                statusStr.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                statusStr.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

            author = document.selectFirst("div.nv-info-list div:contains(Studios) strong a")?.text()
            thumbnail_url = document.selectFirst("aside.nv-info-poster img")?.attr("src")

            val baseDesc = document.selectFirst("p.nv-info-desc, div.nv-info-synopsis p")?.text() ?: ""
            description = if (altTitle.isNotBlank()) {
                "$baseDesc\n\nAlternative Title: $altTitle"
            } else {
                baseDesc
            }
        }
    }

    // ============================== Episode List ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = document.select("div.nv-info-episode-grid article.nv-info-episode-item")

        val list = episodes.map { element ->
            SEpisode.create().apply {
                val linkEl = element.selectFirst("a.nv-info-episode-main") ?: element.selectFirst("a")!!
                url = linkEl.attr("href")

                val titleEl = linkEl.selectFirst("strong")
                name = titleEl?.text() ?: linkEl.text()

                episode_number = name.substringAfter("Episode").trim().toFloatOrNull() ?: 1.0f
            }
        }
        // Episodes are listed oldest first, reverse to show newest first.
        return list.reversed()
    }

    // ============================== Video List ==============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val buttons = document.select("button.server-video")

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()

        val videos = buttons.parallelCatchingFlatMapBlocking { button ->
            val iframeUrl = button.attr("data-video")
            if (iframeUrl.isBlank()) return@parallelCatchingFlatMapBlocking emptyList<Video>()

            val serverName = button.text().substringBefore("Sub").substringBefore("Dub").trim()
            val rawType = button.selectFirst("span")?.text() ?: ""
            val versionType = when {
                rawType.contains("Sort Sub", ignoreCase = true) -> "Soft Sub"
                rawType.contains("Hard Sub", ignoreCase = true) -> "Hard Sub"
                rawType.contains("Dub", ignoreCase = true) -> "Dub"
                else -> rawType
            }

            // Extract soft subtitles from query parameters of iframeUrl if present
            val subtitleTracks = mutableListOf<Track>()
            runCatching {
                val uri = Uri.parse(iframeUrl)
                val subUrl = uri.getQueryParameter("sub")
                    ?: uri.getQueryParameter("caption_1")
                    ?: uri.getQueryParameter("c1_file")
                if (!subUrl.isNullOrBlank()) {
                    val subLabel = uri.getQueryParameter("sub_1")
                        ?: uri.getQueryParameter("c1_label")
                        ?: "English"
                    subtitleTracks.add(Track(subUrl, subLabel))
                }
            }

            when {
                iframeUrl.contains("vivibebe.site") || iframeUrl.contains("vibevibe.workers.dev") || iframeUrl.contains("bibiemb.xyz") -> {
                    val iframeHtml = client.newCall(GET(iframeUrl, headers)).execute().body.string()
                    val m3u8Url = vibeRegex.find(iframeHtml)?.groupValues?.get(1)
                    if (m3u8Url != null) {
                        playlistUtils.extractFromHls(
                            m3u8Url,
                            referer = iframeUrl,
                            videoNameGen = { quality -> "$serverName ($versionType) - $quality" },
                            subtitleList = subtitleTracks,
                        )
                    } else {
                        emptyList()
                    }
                }

                iframeUrl.contains("otakuhg.site") || iframeUrl.contains("otakuvid.online") -> {
                    val extractor = VidHideExtractor(client, headers)
                    extractor.videosFromUrl(iframeUrl) { quality -> "$serverName ($versionType) - $quality" }.map { video ->
                        Video(
                            videoUrl = video.videoUrl,
                            videoTitle = video.videoTitle,
                            headers = video.headers,
                            subtitleTracks = video.subtitleTracks + subtitleTracks,
                        )
                    }
                }

                iframeUrl.contains("playmogo.com") || iframeUrl.contains("dood") -> {
                    val extractor = DoodExtractor(client)
                    extractor.videosFromUrl(iframeUrl, quality = "$serverName ($versionType)").map { video ->
                        Video(
                            videoUrl = video.videoUrl,
                            videoTitle = video.videoTitle,
                            headers = video.headers,
                            subtitleTracks = video.subtitleTracks + subtitleTracks,
                        )
                    }
                }

                else -> emptyList()
            }
        }

        return videos.filter { video ->
            val matchesServer = excludedServers.any { video.videoTitle.contains(it, ignoreCase = true) }
            val matchesAudio = excludedAudios.any { video.videoTitle.contains(it, ignoreCase = true) }
            !matchesServer && !matchesAudio
        }
    }

    // ============================== Video Sorting ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!
        val host = preferences.getString(PREF_HOST_KEY, PREF_HOST_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { !it.videoTitle.contains(host, ignoreCase = true) },
                { !it.videoTitle.contains(quality, ignoreCase = true) },
                { !it.videoTitle.contains(type, ignoreCase = true) },
            ),
        )
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080p", "720p", "480p", "360p"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Audio Type",
            entries = listOf("Soft Sub", "Hard Sub", "Dub"),
            entryValues = listOf("Soft Sub", "Hard Sub", "Dub"),
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_HOST_KEY,
            title = "Preferred Host",
            entries = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream"),
            entryValues = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream"),
            default = PREF_HOST_DEFAULT,
            summary = "%s",
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude Host",
            summary = "Select servers to exclude from the video list",
            entries = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream"),
            entryValues = listOf("HD-1", "HD-2", "StreamHG", "Earnvids", "Doodstream"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_AUDIO_KEY,
            default = emptySet(),
            title = "Exclude Audio Types",
            summary = "Select audio formats to exclude from the video list",
            entries = listOf("Soft Sub", "Hard Sub", "Dub"),
            entryValues = listOf("Soft Sub", "Hard Sub", "Dub"),
        )
        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred Title Language",
            entries = listOf("English", "Romaji/Japanese"),
            entryValues = listOf("English", "Romaji/Japanese"),
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            title = "Show episode thumbnails",
            summary = "Fetch and display images in the episode list.",
            default = true,
        )
    }

    // ============================== Filters ==============================

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxFilterList(name: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { CheckBoxVal(it.first, false) }) {
        fun getCheckedUriParts(): List<String> = state.mapIndexedNotNull { index, checkbox ->
            if (checkbox.state) vals[index].second else null
        }
    }

    class GenreFilter : CheckBoxFilterList("Genres", GENRES)
    class TypeFilter : CheckBoxFilterList("Types", TYPES)
    class StatusFilter : CheckBoxFilterList("Status", STATUSES)
    class LanguageFilter : CheckBoxFilterList("Language/Version", LANGUAGES)
    class YearFilter : CheckBoxFilterList("Years", YEARS)
    class SortFilter : UriPartFilter("Sort By", SORT_BY)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        AnimeFilter.Separator(),
        TypeFilter(),
        AnimeFilter.Separator(),
        StatusFilter(),
        AnimeFilter.Separator(),
        LanguageFilter(),
        AnimeFilter.Separator(),
        YearFilter(),
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "Soft Sub"

        private const val PREF_HOST_KEY = "preferred_host"
        private const val PREF_HOST_DEFAULT = "HD-1"

        private const val PREF_EXCLUDE_SERVERS_KEY = "exclude_servers"
        private const val PREF_EXCLUDE_AUDIO_KEY = "exclude_audio"

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"

        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"

        private val vibeRegex = Regex("""const src\s*=\s*"([^"]+)"""")

        private val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Cars", "cars"),
            Pair("Comedy", "comedy"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Parody", "parody"),
            Pair("Police", "police"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
            Pair("Vampire", "vampire"),
        )

        private val TYPES = arrayOf(
            Pair("TV", "1"),
            Pair("Movie", "2"),
            Pair("OVA", "3"),
            Pair("ONA", "4"),
            Pair("Special", "5"),
            Pair("Music", "6"),
            Pair("TV_SHORT", "7"),
        )

        private val STATUSES = arrayOf(
            Pair("Ongoing", "Ongoing"),
            Pair("Completed", "Completed"),
            Pair("Upcoming", "info"),
        )

        private val LANGUAGES = arrayOf(
            Pair("Subbed", "sub"),
            Pair("Dubbed", "dub"),
        )

        private val YEARS = (2026 downTo 2000).map { Pair(it.toString(), it.toString()) }.toTypedArray()

        private val SORT_BY = arrayOf(
            Pair("Latest Update", "recently_updated"),
            Pair("Release Date", "release_date"),
            Pair("Recently Added", "recently_added"),
            Pair("Title A-Z", "title_az"),
        )
    }
}
