package eu.kanade.tachiyomi.animeextension.en.animesuge

import android.util.Base64
import androidx.preference.MultiSelectListPreference
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
import extensions.utils.addEditTextPreference
import extensions.utils.addListPreference
import extensions.utils.addSwitchPreference
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.net.URLEncoder

class AnimeSuge : AnikotoTheme() {

    override val name = "AnimeSuge"
    override val baseUrl = "https://animesuge.cz"
    override val lang = "en"

    override val popularAnimeSelector = "div.main-card > div.item, div.items > div.item, div.item"

    private val myMetadataFetcher by lazy { EpisodeMetadataFetcher(client, json, null) }

    private val loadThumbnails: Boolean
        get() = preferences.getBoolean("pref_load_thumbnails", true)

    private val loadTitles: Boolean
        get() = preferences.getBoolean("pref_load_titles", true)

    private val loadDescriptions: Boolean
        get() = preferences.getBoolean("pref_load_descriptions", true)

    companion object {
        private val epRegex = Regex("/ep-\\d+$")
        private val mangaIdRegex = Regex("""mangaId\s*=\s*(\d+)""")
    }

    // ── VRF Helpers ──────────────────────────────────────────────────────────

    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        var i = 0
        j = 0
        val out = ByteArray(input.size)
        for (x in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            out[x] = ((input[x].toInt() and 0xFF) xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    private fun shiftCharcode(t: String): ByteArray {
        val result = ByteArray(t.length)
        for (r in t.indices) {
            var s = t[r].code
            when (r % 8) {
                0 -> s -= 3
                1 -> s += 3
                2 -> s -= 4
                3 -> s += 2
                4 -> s -= 2
                5 -> s += 5
                6 -> s += 4
                7 -> s += 5
            }
            result[r] = s.toByte()
        }
        return result
    }

    private fun rot13(s: String) = s.map { c ->
        when (c) {
            in 'a'..'z' -> ((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
            in 'A'..'Z' -> ((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
            else -> c
        }
    }.joinToString("")

    override fun getVrf(animeId: String): String {
        val encoded = URLEncoder.encode(animeId, "UTF-8").replace("+", "%20")
        val key = "ysJhV6U27FVIjjuk".toByteArray(Charsets.UTF_8)
        val rc4Bytes = rc4(key, encoded.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(rc4Bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        val shifted = shiftCharcode(b64)
        val b64Shifted = Base64.encodeToString(shifted, Base64.URL_SAFE or Base64.NO_WRAP)
        return rot13(b64Shifted)
    }

    // ── Search & Browse Overrides ────────────────────────────────────────────

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/most-viewed?page=$page")).execute()
        return parseAnimePage(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/latest-updated?page=$page")).execute()
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
        val response = client.newCall(GET(urlBuilder.build().toString())).execute()
        return parseAnimePage(response.asJsoup())
    }

    private fun parseAnimePage(doc: org.jsoup.nodes.Document): AnimesPage {
        val elements = doc.select(popularAnimeSelector)
        val animes = elements.mapNotNull { el ->
            val linkEl = el.selectFirst("div.name a") ?: el.selectFirst("a.poster") ?: return@mapNotNull null
            var href = linkEl.attr("href")
            if (href.startsWith("http")) {
                href = href.substringAfter(baseUrl)
            }
            val slug = href.replace(epRegex, "").trimStart('/')
            val titleText = linkEl.text().trim().ifEmpty { el.selectFirst("img")?.attr("alt")?.trim() ?: "Unknown" }
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

    // ── Details & Episodes Overrides ─────────────────────────────────────────

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val path = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        val response = client.newCall(GET(baseUrl + path)).execute()
        val doc = response.asJsoup()

        val titleText = doc.selectFirst("h1.title")?.text()?.trim() ?: anime.title
        val thumbnail = doc.selectFirst("#media-info .poster img")?.absUrl("src")

        val descFull = doc.selectFirst(".description .full div")?.text()?.trim()
        val descShort = doc.selectFirst(".description .short div")?.text()?.trim()
        val synopsis = descFull ?: descShort ?: ""

        val metaMap = mutableMapOf<String, String>()
        doc.select(".meta > div").forEach { el ->
            val label = el.selectFirst("div")?.text()?.removeSuffix(":")?.trim() ?: ""
            val value = el.selectFirst("span")?.text()?.trim() ?: ""
            if (label.isNotEmpty() && value.isNotEmpty()) {
                metaMap[label] = value
            }
        }

        val genresText = doc.select(".meta div:contains(Genre) span a").eachText().joinToString(", ")
        val studiosText = doc.select(".meta div:contains(Studios) span a").eachText().joinToString(", ")
        val statusText = metaMap["Status"] ?: ""

        val desc = buildString {
            if (synopsis.isNotEmpty()) append(synopsis)
            metaMap["MAL"]?.takeIf { it.isNotBlank() }?.let { append("\n\nMAL Score: $it") }
            metaMap["Type"]?.takeIf { it.isNotBlank() }?.let { append("\nType: $it") }
            metaMap["Premiered"]?.takeIf { it.isNotBlank() }?.let { append("\nPremiered: $it") }
            metaMap["Aired"]?.takeIf { it.isNotBlank() }?.let { append("\nAired: $it") }
            metaMap["Duration"]?.takeIf { it.isNotBlank() }?.let { append("\nDuration: $it") }
            if (studiosText.isNotBlank()) append("\nStudio: $studiosText")
        }

        val animeStatus = when {
            statusText.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
            statusText.contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
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

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url
        val path = if (slug.startsWith("/")) slug else "/$slug"
        val detailResponse = client.newCall(GET(baseUrl + path)).execute()
        val detailDoc = detailResponse.asJsoup()

        val animeId = detailDoc.selectFirst(".favourite[data-id], [data-id]")?.attr("data-id")
            ?: mangaIdRegex.find(detailDoc.html())?.groupValues?.get(1)
            ?: return emptyList()

        val vrf = getVrf(animeId)
        val ajaxUrl = "$baseUrl/ajax/episode/list/$animeId?vrf=$vrf"
        val headers = headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .build()
        val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
        val ajaxJson = json.decodeFromString<EpisodeListResponse>(ajaxResponse.body.string())
        if (ajaxJson.result.isEmpty()) {
            return emptyList()
        }

        val epDoc = Jsoup.parse(ajaxJson.result)
        val elements = epDoc.select("a[data-ids]")
        val episodes = elements.mapNotNull { element ->
            val slugNum = element.attr("data-slug")
            val num = element.text().trim().ifEmpty { slugNum }.toIntOrNull() ?: slugNum.toIntOrNull() ?: 1
            val malId = element.attr("data-mal")
            val timestamp = element.attr("data-timestamp")
            val dataIds = element.attr("data-ids")
            val hasSub = element.attr("data-sub") == "1"
            val hasDub = element.attr("data-dub") == "1"
            var title = element.attr("title").trim()
            if (title.isBlank()) {
                title = element.attr("data-num").trim()
            }
            if (title.isBlank()) {
                title = "Episode $num"
            }

            val meta = EpisodeMeta(slug, num.toString(), malId, timestamp, dataIds, hasSub, hasDub, title)
            SEpisode.create().apply {
                url = "/watch/${getCleanSlug(slug)}/ep-$num"
                name = title
                episode_number = num.toFloat()
                date_upload = (timestamp.toLongOrNull() ?: 0L) * 1000L
                val scanlatorList = mutableListOf<String>()
                if (hasSub) scanlatorList.add("Sub")
                if (hasDub) scanlatorList.add("Dub")
                scanlator = if (scanlatorList.isEmpty()) "Raw" else scanlatorList.joinToString(" / ")
            }
        }.reversed()

        val malId = elements.firstNotNullOfOrNull { it.attr("data-mal").takeIf { mal -> mal.isNotEmpty() } } ?: ""
        return enrichEpisodesWithMetadata(episodes, detailDoc, malId)
    }

    private suspend fun enrichEpisodesWithMetadata(
        episodes: List<SEpisode>,
        detailDoc: org.jsoup.nodes.Document,
        malId: String,
    ): List<SEpisode> {
        val showThumbs = loadThumbnails
        val showTitles = loadTitles
        val showDescs = loadDescriptions
        if (!showThumbs && !showTitles && !showDescs) return episodes
        if (malId.isBlank()) return episodes

        val animeCoverUrl = detailDoc.selectFirst("#media-info .poster img")?.absUrl("src")

        return try {
            val metadataMap = myMetadataFetcher.fetch(malId, animeCoverUrl)
            if (metadataMap.isEmpty()) return episodes

            episodes.map { episode ->
                val epNum = episode.episode_number.toInt()
                val episodeMeta = metadataMap[epNum] ?: return@map episode
                episode.apply {
                    if (showThumbs && !episodeMeta.thumbnailUrl.isNullOrEmpty()) {
                        preview_url = episodeMeta.thumbnailUrl
                    }
                    if (showDescs && !episodeMeta.description.isNullOrEmpty()) {
                        summary = episodeMeta.description
                    }
                    if (showTitles && !episodeMeta.title.isNullOrBlank()) {
                        val epNumStr = if (episode_number % 1 == 0f) episode_number.toInt().toString() else episode_number.toString()
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
        return baseUrl + path.replace("/watch/anime/", "/anime/").replace("/watch/", "/anime/")
    }

    override suspend fun fetchFreshEpisodeMeta(slug: String, epNum: String): EpisodeMeta? {
        try {
            val cleanSlug = getCleanSlug(slug)
            val detailResponse = client.newCall(GET("$baseUrl/anime/$cleanSlug")).execute()
            val detailDoc = detailResponse.asJsoup()

            val animeId = detailDoc.selectFirst(".favourite[data-id], [data-id]")?.attr("data-id")
                ?: mangaIdRegex.find(detailDoc.html())?.groupValues?.get(1)
                ?: return null

            val vrf = getVrf(animeId)
            val ajaxUrl = "$baseUrl/ajax/episode/list/$animeId?vrf=$vrf"
            val headers = headersBuilder()
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Referer", "$baseUrl/")
                .build()
            val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
            val ajaxJson = json.decodeFromString<EpisodeListResponse>(ajaxResponse.body.string())
            if (ajaxJson.result.isEmpty()) return null

            val epDoc = Jsoup.parse(ajaxJson.result)
            val elements = epDoc.select("a[data-ids]")
            for (element in elements) {
                val slugNum = element.attr("data-slug")
                val num = element.text().trim().ifEmpty { slugNum }.toIntOrNull() ?: slugNum.toIntOrNull() ?: 1
                if (num.toString() == epNum || slugNum == epNum) {
                    val malId = element.attr("data-mal")
                    val timestamp = element.attr("data-timestamp")
                    val dataIds = element.attr("data-ids")
                    val hasSub = element.attr("data-sub") == "1"
                    val hasDub = element.attr("data-dub") == "1"
                    var title = element.attr("title").trim()
                    if (title.isBlank()) {
                        title = element.attr("data-num").trim()
                    }
                    if (title.isBlank()) {
                        title = "Episode $num"
                    }
                    return EpisodeMeta(cleanSlug, epNum, malId, timestamp, dataIds, hasSub, hasDub, title)
                }
            }
        } catch (e: Exception) {
            loge("fetchFreshEpisodeMeta FAILED", e)
        }
        return null
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        try {
            // --- Playback Settings ---
            screen.addListPreference(
                key = "pref_quality",
                default = "720",
                title = "Playback: Preferred quality",
                summary = "Sorts videos so this quality is on top. Currently: %s",
                entries = listOf("1080p", "720p", "480p", "360p"),
                entryValues = listOf("1080", "720", "480", "360"),
            )
            screen.addListPreference(
                key = "pref_audio",
                default = "SUB",
                title = "Playback: Preferred audio",
                summary = "Sub, Dub, or Hardsub first. Currently: %s",
                entries = listOf("Sub", "Dub", "Hardsub"),
                entryValues = listOf("SUB", "A-DUB", "H-SUB"),
            )
            screen.addListPreference(
                key = "pref_server",
                default = "auto",
                title = "Playback: Preferred video server",
                summary = "Which video server to try first. Currently: %s",
                entries = listOf("Auto", "VidPlay-1", "HD-1", "Megaplay-1", "Vidwish-1"),
                entryValues = listOf("auto", "VidPlay-1", "HD-1", "Megaplay-1", "Vidwish-1"),
            )
            screen.addListPreference(
                key = "pref_buffer",
                default = "10",
                title = "Playback: Pre-fetch buffer",
                summary = "How much to download ahead of playback. Currently: %s",
                entries = listOf("10%", "20%", "30%", "50%", "100%"),
                entryValues = listOf("10", "20", "30", "50", "100"),
            )

            // --- Exclusion / Content Filters ---
            MultiSelectListPreference(screen.context).apply {
                key = "pref_exclude_servers"
                title = "Exclude: Exclude Servers"
                entries = arrayOf("VidPlay-1", "HD-1", "Megaplay-1", "Vidwish-1")
                entryValues = arrayOf("VidPlay-1", "HD-1", "Megaplay-1", "Vidwish-1")
                setDefaultValue(emptySet<String>())
                summary = "Select servers to exclude from the video list"
            }.also { screen.addPreference(it) }

            MultiSelectListPreference(screen.context).apply {
                key = "pref_exclude_audio"
                title = "Exclude: Exclude Audio"
                entries = arrayOf("Sub", "Dub", "Hsub")
                entryValues = arrayOf("SUB", "DUB", "HSUB")
                setDefaultValue(emptySet<String>())
                summary = "Select audio formats to exclude from the video list"
            }.also { screen.addPreference(it) }

            // --- Episode Metadata Settings ---
            screen.addListPreference(
                key = "pref_title_lang",
                default = "en",
                title = "Metadata: Preferred title language",
                summary = "Preferred language for episode titles. Currently: %s",
                entries = listOf("English", "Japanese (Romaji)"),
                entryValues = listOf("en", "jp"),
            )
            screen.addSwitchPreference(
                key = "pref_load_thumbnails",
                default = true,
                title = "Metadata: Load episode thumbnails",
                summary = "Fetching preview images from external sources",
            )
            screen.addSwitchPreference(
                key = "pref_load_titles",
                default = true,
                title = "Metadata: Load episode titles",
                summary = "Fetching episode titles from external sources",
            )
            screen.addSwitchPreference(
                key = "pref_load_descriptions",
                default = true,
                title = "Metadata: Load episode descriptions",
                summary = "Fetching episode descriptions from external sources",
            )

            // --- Smart Search Settings ---
            screen.addSwitchPreference(
                key = "pref_smart_search",
                default = false,
                title = "Smart Search: Enable smart search",
                summary = "AI resolves descriptive queries and corrects spelling",
            )
            screen.addEditTextPreference(
                key = "pref_smart_search_phrase",
                default = "?",
                title = "Smart Search: Activation phrase",
                summary = "Type this at the start of your search to trigger AI. Leave empty to use AI for all searches.",
                dialogMessage = "Type this at the start of your search to trigger AI.\nCase-insensitive. Must be followed by a space.\nLeave empty to use AI for all searches.",
            )
        } catch (e: Exception) {
            // ignore
        }
    }
}
