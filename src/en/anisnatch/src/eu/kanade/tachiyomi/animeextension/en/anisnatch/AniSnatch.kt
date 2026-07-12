package eu.kanade.tachiyomi.animeextension.en.anisnatch

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.multisrc.anikototheme.EpisodeListResponse
import eu.kanade.tachiyomi.multisrc.anikototheme.EpisodeMeta
import eu.kanade.tachiyomi.multisrc.anikototheme.EpisodeMetadataFetcher
import eu.kanade.tachiyomi.multisrc.anikototheme.GenreFilter
import eu.kanade.tachiyomi.multisrc.anikototheme.LanguageFilter
import eu.kanade.tachiyomi.multisrc.anikototheme.RatingFilter
import eu.kanade.tachiyomi.multisrc.anikototheme.SeasonFilter
import eu.kanade.tachiyomi.multisrc.anikototheme.SortFilter
import eu.kanade.tachiyomi.multisrc.anikototheme.SourceFilter
import eu.kanade.tachiyomi.multisrc.anikototheme.StatusFilter
import eu.kanade.tachiyomi.multisrc.anikototheme.TypeFilter
import eu.kanade.tachiyomi.multisrc.anikototheme.YearFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.addListPreference
import extensions.utils.addSwitchPreference
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.net.URLEncoder

class AniSnatch : AnikotoTheme() {

    override val name = "AniSnatch"
    override val baseUrl = "https://anisnatch.top"
    override val lang = "en"

    // AniSnatch uses the same AnikotoTheme VRF key as the default RC4 "simple-hash"
    // Override with site-specific key if needed after live testing
    override fun getVrf(animeId: String): String {
        val key = "simple-hash"
        val s = IntArray(256) { it }
        var a = 0
        for (n in 0..255) {
            a = (s[n] + a + key[n % key.length].code) % 256
            val tmp = s[n]
            s[n] = s[a]
            s[a] = tmp
        }
        val out = StringBuilder(animeId.length)
        var n2 = 0
        var a2 = 0
        for (r in animeId.indices) {
            n2 = (n2 + 1) % 256
            a2 = (s[n2] + a2) % 256
            val tmp2 = s[n2]
            s[n2] = s[a2]
            s[a2] = tmp2
            val k = s[(s[n2] + s[a2]) % 256]
            out.append((animeId[r].code xor k).toChar())
        }
        val bytes = out.toString().toByteArray(Charsets.ISO_8859_1)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return URLEncoder.encode(b64, "UTF-8")
    }

    private val myMetadataFetcher by lazy { EpisodeMetadataFetcher(client, json, null) }

    private val loadThumbnails: Boolean
        get() = preferences.getBoolean(PREF_LOAD_THUMBNAILS_KEY, true)

    private val loadTitles: Boolean
        get() = preferences.getBoolean(PREF_LOAD_TITLES_KEY, true)

    private val loadDescriptions: Boolean
        get() = preferences.getBoolean(PREF_LOAD_DESCRIPTIONS_KEY, true)

    companion object {
        private val epRegex = Regex("/ep-\\d+$")
        private val mangaIdRegex = Regex("""mangaId\s*=\s*(\d+)""")
        private const val PREF_LOAD_THUMBNAILS_KEY = "pref_load_thumbnails"
        private const val PREF_LOAD_TITLES_KEY = "pref_load_titles"
        private const val PREF_LOAD_DESCRIPTIONS_KEY = "pref_load_descriptions"
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/most-viewed?page=$page", headers)).execute()
        return parseAnimePage(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/latest-updated?page=$page", headers)).execute()
        return parseAnimePage(response.asJsoup())
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = "$baseUrl/filter".toHttpUrl().newBuilder()
        if (query.isNotBlank()) urlBuilder.addQueryParameter("keyword", query)
        for (filter in filters) {
            when (filter) {
                is SortFilter -> filter.toQuery()?.let { urlBuilder.addQueryParameter("sort", it) }
                is GenreFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("genre[]", it) }
                is TypeFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("term_type[]", it) }
                is StatusFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("status[]", it) }
                is LanguageFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("language[]", it) }
                is SeasonFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("season[]", it) }
                is YearFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("year[]", it) }
                is RatingFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("rating[]", it) }
                is SourceFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("source[]", it) }
                else -> {}
            }
        }
        urlBuilder.addQueryParameter("page", page.toString())
        val response = client.newCall(GET(urlBuilder.build().toString(), headers)).execute()
        return parseAnimePage(response.asJsoup())
    }

    private fun parseAnimePage(doc: org.jsoup.nodes.Document): AnimesPage {
        val elements = doc.select("div.ani.items > div.item, div.items > div.item, div.item")
        val animes = elements.mapNotNull { el ->
            val linkEl = el.selectFirst("div.name a") ?: el.selectFirst("a.poster") ?: return@mapNotNull null
            var href = linkEl.attr("href")
            if (href.startsWith("http")) href = href.substringAfter(baseUrl)
            val slug = href.replace(epRegex, "").trimStart('/')
            val titleText = linkEl.text().trim()
                .ifEmpty { el.selectFirst("img")?.attr("alt")?.trim() ?: "Unknown" }
            val thumb = el.selectFirst("img")?.absUrl("data-src")?.ifEmpty { null }
                ?: el.selectFirst("img")?.absUrl("src")
            SAnime.create().apply {
                url = slug
                title = titleText
                thumbnail_url = thumb
            }
        }.distinctBy { it.url }
        val hasNext = doc.select("a[rel=next], li.page-item.next:not(.disabled)").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // ── Details ───────────────────────────────────────────────────────────────

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val path = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        val doc = client.newCall(GET("$baseUrl/watch$path/ep-1", headers)).execute().asJsoup()

        val titleText = doc.selectFirst("h1.title, h2.title")?.text()?.trim() ?: anime.title
        val thumbnail = doc.selectFirst("#w-info .poster img, div.poster img")?.absUrl("src")

        val descFull = doc.selectFirst(".description .full div, .synopsis .content")?.text()?.trim()
        val descShort = doc.selectFirst(".description .short div, .synopsis p")?.text()?.trim()
        val synopsis = descFull ?: descShort ?: ""

        val metaMap = mutableMapOf<String, String>()
        doc.select(".bmeta > div, .meta > div").forEach { el ->
            val label = el.selectFirst("div")?.text()?.removeSuffix(":")?.trim() ?: ""
            val value = el.selectFirst("span")?.text()?.trim() ?: ""
            if (label.isNotEmpty() && value.isNotEmpty()) metaMap[label] = value
        }

        val genresText = doc.select(".bmeta div:contains(Genre) span a, .meta div:contains(Genre) span a")
            .eachText().joinToString(", ")
        val studiosText = doc.select(".bmeta div:contains(Studios) span a, .bmeta div:contains(Studio) span a")
            .eachText().joinToString(", ")
        val statusText = metaMap["Status"] ?: metaMap["Aired"] ?: ""

        // Score injection per skill
        val scoreRaw = metaMap["MAL"]?.toDoubleOrNull() ?: metaMap["Score"]?.toDoubleOrNull()
        val scoreStr = formatScore(scoreRaw)

        val desc = buildString {
            if (scoreStr != null) {
                append(scoreStr)
                append("\n\n")
            }
            if (synopsis.isNotEmpty()) append(synopsis)
            metaMap["Type"]?.takeIf { it.isNotBlank() }?.let { append("\nType: $it") }
            metaMap["Premiered"]?.takeIf { it.isNotBlank() }?.let { append("\nPremiered: $it") }
            metaMap["Duration"]?.takeIf { it.isNotBlank() }?.let { append("\nDuration: $it") }
            if (studiosText.isNotBlank()) append("\nStudio: $studiosText")
        }

        val animeStatus = when {
            statusText.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
            statusText.contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
            statusText.contains("Not yet aired", ignoreCase = true) -> SAnime.LICENSED
            else -> SAnime.UNKNOWN
        }

        return SAnime.create().apply {
            url = anime.url
            title = titleText
            thumbnail_url = thumbnail
            description = desc
            genre = genresText
            status = animeStatus
            author = if (studiosText.isNotBlank()) studiosText else null
            artist = author
            initialized = true
        }
    }

    private fun formatScore(score: Double?): String? {
        if (score == null || score <= 0.0) return null
        val full = (score / 2).toInt().coerceIn(0, 5)
        val stars = "★".repeat(full) + "☆".repeat(5 - full)
        return "$stars ${"%.2f".format(score)}"
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url
        val path = if (slug.startsWith("/")) slug else "/$slug"
        val detailDoc = client.newCall(GET("$baseUrl/watch$path/ep-1", headers)).execute().asJsoup()

        val animeId = detailDoc.selectFirst("#watch-page, #watch-main, .watch-wrap")?.attr("data-id")
            ?: mangaIdRegex.find(detailDoc.html())?.groupValues?.get(1)
            ?: detailDoc.selectFirst(".favourite[data-id], [data-id]")?.attr("data-id")
            ?: return emptyList()

        if (animeId.isEmpty()) return emptyList()

        val vrf = getVrf(animeId)
        val ajaxUrl = "$baseUrl/ajax/episode/list/$animeId?vrf=$vrf&style=default"
        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("Referer", "$baseUrl/watch$path/ep-1")
            .build()
        val ajaxResponse = client.newCall(GET(ajaxUrl, ajaxHeaders)).execute()
        val ajaxJson = json.decodeFromString<EpisodeListResponse>(ajaxResponse.body.string())

        if (ajaxJson.status != 200 || ajaxJson.result.isEmpty()) return emptyList()

        val epDoc = Jsoup.parse(ajaxJson.result)
        val elements = epDoc.select("ul.ep-range a, .ep-range a, .range a, a[data-ids]")

        val episodes = elements.mapNotNull { element ->
            val num = element.attr("data-num").ifEmpty { element.text().trim() }
            if (num.isEmpty()) return@mapNotNull null
            val malId = element.attr("data-mal")
            val timestamp = element.attr("data-timestamp")
            val dataIds = element.attr("data-ids")
            val hasSub = element.attr("data-sub") == "1"
            val hasDub = element.attr("data-dub") == "1"
            var title = element.attr("title").trim()
            if (title.isBlank()) title = "Episode $num"

            val meta = EpisodeMeta(slug, num, malId, timestamp, dataIds, hasSub, hasDub, title)
            SEpisode.create().apply {
                url = meta.encode()
                name = title
                episode_number = num.toFloatOrNull() ?: 0.0f
                date_upload = (timestamp.toLongOrNull() ?: 0L) * 1000L
                val scanlatorList = mutableListOf<String>()
                if (hasSub) scanlatorList.add("Sub")
                if (hasDub) scanlatorList.add("Dub")
                scanlator = if (scanlatorList.isEmpty()) "Raw" else scanlatorList.joinToString(" / ")
            }
        }.reversed()

        return enrichEpisodesWithMetadata(episodes, detailDoc)
    }

    private suspend fun enrichEpisodesWithMetadata(
        episodes: List<SEpisode>,
        detailDoc: org.jsoup.nodes.Document,
    ): List<SEpisode> {
        if (!loadThumbnails && !loadTitles && !loadDescriptions) return episodes

        val firstMeta = episodes.firstOrNull()?.let {
            runCatching { EpisodeMeta.decode(it.url) }.getOrNull()
        }
        val malId = firstMeta?.malId?.takeIf { it.isNotBlank() } ?: return episodes

        val animeCoverUrl = detailDoc.selectFirst("#w-info .poster img, div.poster img")?.absUrl("src")

        return try {
            val metadataMap = myMetadataFetcher.fetch(malId, animeCoverUrl)
            if (metadataMap.isEmpty()) return episodes
            episodes.map { episode ->
                val epNum = episode.episode_number.toInt()
                val episodeMeta = metadataMap[epNum] ?: return@map episode
                episode.apply {
                    if (loadThumbnails && !episodeMeta.thumbnailUrl.isNullOrEmpty()) {
                        preview_url = episodeMeta.thumbnailUrl
                    }
                    if (loadDescriptions && !episodeMeta.description.isNullOrEmpty()) {
                        summary = episodeMeta.description
                    }
                    if (loadTitles && !episodeMeta.title.isNullOrBlank()) {
                        val epNumStr = if (episode_number % 1 == 0f) {
                            episode_number.toInt().toString()
                        } else {
                            episode_number.toString()
                        }
                        name = "Episode $epNumStr: ${episodeMeta.title}"
                    }
                }
            }
        } catch (e: Exception) {
            episodes
        }
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val path = EpisodeMeta.extractUrlPath(episode.url)
        return baseUrl + path
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        try {
            // Delegate to AnikotoTheme base preferences (quality, audio, server, buffer, etc.)
            super.setupPreferenceScreen(screen)

            // Episode metadata extras
            screen.addSwitchPreference(
                key = PREF_LOAD_THUMBNAILS_KEY,
                default = true,
                title = "Metadata: Load episode thumbnails",
                summary = "Fetch preview images for episodes from external sources",
            )
            screen.addSwitchPreference(
                key = PREF_LOAD_TITLES_KEY,
                default = true,
                title = "Metadata: Load episode titles",
                summary = "Fetch episode titles from external sources",
            )
            screen.addSwitchPreference(
                key = PREF_LOAD_DESCRIPTIONS_KEY,
                default = true,
                title = "Metadata: Load episode descriptions",
                summary = "Fetch episode descriptions from external sources",
            )
        } catch (e: Exception) {
            // Ignore pref screen errors
        }
    }
}
