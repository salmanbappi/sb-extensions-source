package eu.kanade.tachiyomi.animeextension.en.aniwave

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.multisrc.anikototheme.EpisodeListResponse
import eu.kanade.tachiyomi.network.GET
import extensions.utils.EpisodeMetadataFetcher
import extensions.utils.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

class AniWave : AnikotoTheme() {

    override val name = "AniWave"
    override val baseUrl = "https://aniwaves.ru"
    override val lang = "en"

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val customJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val aniMetadataFetcher by lazy {
        val tmdbKey = try {
            val buildConfigClass = Class.forName("eu.kanade.tachiyomi.animeextension.en.aniwave.BuildConfig")
            buildConfigClass.getField("TMDB_API").get(null) as String
        } catch (_: Exception) {
            ""
        }
        EpisodeMetadataFetcher(client, customJson, null, null, tmdbKey)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/most-popular" else "$baseUrl/most-popular/page/$page"
        val response = client.newCall(GET(url)).execute()
        return parseAniwaveList(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/updated" else "$baseUrl/updated/page/$page"
        val response = client.newCall(GET(url)).execute()
        return parseAniwaveList(response.asJsoup())
    }

    private fun parseAniwaveList(doc: Document): AnimesPage {
        val elements = doc.select("div.ani.items > div.item, .item")
        val animes = elements.map { el ->
            val linkEl = el.selectFirst("a.name.d-title, a.name, a.title") ?: el.selectFirst("a")
            val name = linkEl?.text()?.trim() ?: el.selectFirst(".title, h2, h3")?.text()?.trim().orEmpty()
            val href = linkEl?.attr("href")?.trim().orEmpty()
            val rawSlug = href.substringAfter("/watch/").substringAfter("/anime/").trim('/')
            val cleanSlug = getCleanSlug(rawSlug)

            val posterEl = el.selectFirst("div.poster img, .poster img, img")
            val posterUrl = posterEl?.absUrl("data-src")?.ifEmpty { posterEl.absUrl("src") } ?: ""

            SAnime.create().apply {
                this.title = name
                this.thumbnail_url = posterUrl
                this.url = cleanSlug
            }
        }
        val hasNext = doc.select("ul.pagination li.active ~ li, a.page-link[rel=next], a[rel=next], li.page-item.next:not(.disabled)").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val cleanSlug = getCleanSlug(anime.url)
        val detailResponse = client.newCall(GET("$baseUrl/watch/$cleanSlug")).execute()
        val detailDoc = detailResponse.asJsoup()
        val animeTitle = detailDoc.selectFirst("h1.title")?.text()?.trim() ?: anime.title
        val animeCoverUrl = detailDoc.selectFirst("#w-info .poster img, .poster img")?.absUrl("src") ?: anime.thumbnail_url

        val watchMain = detailDoc.selectFirst("#watch-main, #watch-page, .watch-wrap, [data-id]")
        val animeId = watchMain?.attr("data-id") ?: run {
            return emptyList()
        }

        val vrf = getVrf(animeId)
        val ajaxUrl = "$baseUrl/ajax/episode/list/$animeId?vrf=$vrf&style=default"
        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/watch/$cleanSlug")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val ajaxResponse = client.newCall(GET(ajaxUrl, ajaxHeaders)).execute()
        val ajaxJson = customJson.decodeFromString<EpisodeListResponse>(ajaxResponse.body.string())
        if (ajaxJson.status != 200 || ajaxJson.result.isEmpty()) {
            return emptyList()
        }

        val epDoc = Jsoup.parse(ajaxJson.result)
        val elements = epDoc.select("ul.ep-range a, .ep-range a, .range a, a[data-ids]")
        val episodes = elements.mapNotNull { element ->
            val num = element.attr("data-num").ifBlank { element.attr("data-slug") }
            if (num.isEmpty()) return@mapNotNull null
            val dataIds = element.attr("data-ids")
            val timestamp = element.attr("data-timestamp")
            val hasSub = element.attr("data-sub") == "1"
            val hasDub = element.attr("data-dub") == "1"
            var title = element.attr("title").trim()
            if (title.isBlank()) {
                title = "Episode $num"
            }

            SEpisode.create().apply {
                url = buildString {
                    append("/watch/$cleanSlug/ep-$num#$dataIds")
                }
                name = title
                episode_number = num.toFloatOrNull() ?: 0.0f
                date_upload = (timestamp.toLongOrNull() ?: 0L) * 1000L
                val scanlatorList = mutableListOf<String>()
                if (hasSub) scanlatorList.add("Sub")
                if (hasDub) scanlatorList.add("Dub")
                scanlator = if (scanlatorList.isEmpty()) "Raw" else scanlatorList.joinToString(" / ")
            }
        }.reversed()

        return enrichAniwaveEpisodes(episodes, animeTitle, animeCoverUrl)
    }

    private suspend fun enrichAniwaveEpisodes(
        episodes: List<SEpisode>,
        animeTitle: String,
        animeCoverUrl: String?,
    ): List<SEpisode> {
        if (animeTitle.isBlank()) return episodes

        return try {
            val metadataMap = aniMetadataFetcher.fetch("", animeTitle, animeCoverUrl)
            if (metadataMap.isEmpty()) return episodes

            episodes.map { episode ->
                val epNum = episode.episode_number.toInt()
                val episodeMeta = metadataMap[epNum] ?: return@map episode
                episode.apply {
                    if (!episodeMeta.thumbnailUrl.isNullOrEmpty()) {
                        preview_url = episodeMeta.thumbnailUrl
                    }
                    if (!episodeMeta.description.isNullOrEmpty()) {
                        summary = episodeMeta.description
                    }
                    if (!episodeMeta.title.isNullOrBlank()) {
                        val epNumFloat = episode.episode_number
                        val epNumInt = epNumFloat.toInt()
                        val epNumStr = if (epNumFloat == epNumInt.toFloat()) "$epNumInt" else "$epNumFloat"
                        name = "Episode $epNumStr: ${episodeMeta.title}"
                    }
                }
            }
        } catch (_: Exception) {
            episodes
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val slug = getCleanSlug(episode.url.substringBefore("#"))
        var dataIds = episode.url.substringAfter("#", "")
        if (dataIds.isEmpty() || dataIds == episode.url) {
            val epNum = episode.episode_number.toInt().toString()
            val fresh = fetchFreshEpisodeMeta(slug, epNum)
            dataIds = fresh?.dataIds ?: ""
        }
        if (dataIds.isEmpty()) return emptyList()

        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/watch/$slug")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val serverListUrl = "$baseUrl/ajax/server/list?servers=$dataIds"
        val serverListResp = client.newCall(GET(serverListUrl, ajaxHeaders)).execute()
        val serverListJson = customJson.decodeFromString<EpisodeListResponse>(serverListResp.body.string())
        if (serverListJson.status != 200 || serverListJson.result.isEmpty()) return emptyList()

        val serverDoc = Jsoup.parse(serverListJson.result)
        val serverTasks = mutableListOf<AniServerTask>()

        val excludedServers = preferences.getStringSet("pref_exclude_servers_key", emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet("pref_exclude_audio_key", emptySet()) ?: emptySet()

        for (typeElem in serverDoc.select("div.type, .server-type")) {
            val dataType = typeElem.attr("data-type").ifBlank {
                typeElem.selectFirst("label")?.text()?.trim() ?: "sub"
            }.lowercase(Locale.ROOT)
            val audioLabel = when (dataType) {
                "dub" -> "DUB"
                "ssub", "s-sub" -> "SSUB"
                "hsub", "h-sub" -> "HSUB"
                else -> "SUB"
            }
            if (excludedAudios.any { it.equals(audioLabel, true) }) continue

            for (li in typeElem.select("li[data-link-id], li[data-id]")) {
                val linkId = li.attr("data-link-id").ifBlank { li.attr("data-id") }
                val serverName = li.text().trim()
                if (excludedServers.any { it.equals(serverName, true) }) continue

                if (linkId.isNotEmpty()) {
                    serverTasks.add(AniServerTask(serverName, linkId, audioLabel, slug))
                }
            }
        }

        if (serverTasks.isEmpty()) return emptyList()

        val resolvedVideos = coroutineScope {
            serverTasks.map { task ->
                async(Dispatchers.IO) {
                    resolveAniServerVideos(task)
                }
            }.awaitAll().flatten()
        }

        // Group cleanly by server name (Provider folder) with Sub & Dub combined inside
        val hostersMap = mutableMapOf<String, MutableList<Video>>()
        for (video in resolvedVideos) {
            val serverName = video.extraData?.takeIf { it.isNotEmpty() }
                ?: serverTasks.firstOrNull { task -> video.videoTitle.contains(task.serverName) }?.serverName
                ?: "Server"

            hostersMap.getOrPut(serverName) { mutableListOf() }.add(video)
        }

        val preferredServerVal = preferredServer
        val hostersList = hostersMap.map { (serverName, videos) ->
            val sortedVideos = try {
                videos.sortVideos()
            } catch (_: Throwable) {
                videos
            }
            Hoster(
                hosterName = serverName,
                hosterUrl = "",
                videoList = sortedVideos,
            )
        }

        return if (preferredServerVal != "auto") {
            hostersList.sortedByDescending { it.hosterName.contains(preferredServerVal, ignoreCase = true) }
        } else {
            hostersList
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = getHosterList(episode).flatMap { it.videoList ?: emptyList() }

    private data class AniServerTask(
        val serverName: String,
        val linkId: String,
        val audioLabel: String,
        val slug: String,
    )

    private fun resolveAniServerVideos(task: AniServerTask): List<Video> {
        return try {
            val ajaxHeaders = headers.newBuilder()
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Referer", "$baseUrl/watch/${task.slug}")
                .set("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()

            val sourcesUrl = "$baseUrl/ajax/sources?id=${task.linkId}&asi=1&autoPlay=1"
            val sourcesResp = client.newCall(GET(sourcesUrl, ajaxHeaders)).execute()
            val sourcesJson = customJson.decodeFromString<JsonObject>(sourcesResp.body.string())
            val resultObj = sourcesJson["result"]?.jsonObject ?: return emptyList()
            val embedUrl = resultObj["url"]?.jsonPrimitive?.content ?: return emptyList()

            val videos = mutableListOf<Video>()

            if (embedUrl.contains("play.echovideo.ru")) {
                val parts = embedUrl.split("/")
                val embedType = parts.getOrNull(3) ?: "embed-0"
                val dataId = parts.getOrNull(4)?.substringBefore("?") ?: ""
                if (dataId.isNotEmpty()) {
                    val echoHeaders = headers.newBuilder()
                        .set("Referer", embedUrl)
                        .set("X-Requested-With", "XMLHttpRequest")
                        .set("Accept", "application/json, text/javascript, */*; q=0.01")
                        .build()

                    val echoResp = client.newCall(GET("https://play.echovideo.ru/$embedType/getSources?id=$dataId", echoHeaders)).execute()
                    val echoJson = customJson.decodeFromString<JsonObject>(echoResp.body.string())

                    val subtitles = mutableListOf<Track>()
                    echoJson["tracks"]?.jsonArray?.forEach { trackElem ->
                        val tObj = trackElem.jsonObject
                        val file = tObj["file"]?.jsonPrimitive?.content ?: ""
                        val label = tObj["label"]?.jsonPrimitive?.content ?: "Subtitles"
                        if (file.isNotEmpty()) {
                            subtitles.add(Track(file, label))
                        }
                    }

                    val sourcesElem = echoJson["sources"]
                    if (sourcesElem != null) {
                        if (sourcesElem is JsonObject) {
                            sourcesElem.forEach { (quality, urlArray) ->
                                val vUrl = urlArray.jsonArray.firstOrNull()?.jsonPrimitive?.content ?: ""
                                if (vUrl.isNotEmpty()) {
                                    val vidHeaders = headers.newBuilder()
                                        .set("Referer", "https://play.echovideo.ru/")
                                        .build()
                                    videos.add(
                                        Video(
                                            videoUrl = vUrl,
                                            videoTitle = "${task.audioLabel} - $quality",
                                            extraData = task.serverName,
                                            headers = vidHeaders,
                                            subtitleTracks = subtitles,
                                        ),
                                    )
                                }
                            }
                        } else {
                            val hlsUrl = sourcesElem.jsonPrimitive.content
                            if (hlsUrl.isNotEmpty()) {
                                val hlsVideos = playlistUtils.extractFromHls(
                                    hlsUrl,
                                    referer = "https://play.echovideo.ru/",
                                    videoNameGen = { "${task.audioLabel} - $it" },
                                    subtitleList = subtitles,
                                )
                                hlsVideos.forEach { it.extraData = task.serverName }
                                videos.addAll(hlsVideos)
                            }
                        }
                    }
                }
            } else if (embedUrl.contains(".m3u8")) {
                val hlsVideos = playlistUtils.extractFromHls(
                    embedUrl,
                    referer = "$baseUrl/",
                    videoNameGen = { "${task.audioLabel} - $it" },
                )
                hlsVideos.forEach { it.extraData = task.serverName }
                videos.addAll(hlsVideos)
            }

            videos
        } catch (_: Exception) {
            emptyList()
        }
    }
}
