package eu.kanade.tachiyomi.animeextension.en.zorotv

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.lang.Exception

class ZoroTv : Source() {

    override val name = "ZoroTv"

    override val baseUrl = "https://www.zorotv.se"

    override val lang = "en"

    override val supportsLatest = true

    private val unsafeClient: okhttp3.OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                },
            )
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            network.client.newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            network.client
        }
    }

    override val client: okhttp3.OkHttpClient by lazy { unsafeClient }

    private fun okhttp3.Call.executeSafe(): Response = try {
        this.execute()
    } catch (e: Exception) {
        unsafeClient.newCall(this.request()).execute()
    }

    private val playlistUtils by lazy { PlaylistUtils(unsafeClient, headers) }

    private val bloggerExtractor by lazy { BloggerExtractor(unsafeClient) }

    private val universalExtractor by lazy { UniversalExtractor(unsafeClient) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/anime/?page=$page&order=popular", headers)).executeSafe()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/anime/?page=$page&order=update", headers)).executeSafe()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.isNotBlank()) {
            val response = client.newCall(GET("$baseUrl/page/$page/?s=$query", headers)).executeSafe()
            parseAnimeListPage(response)
        } else {
            val url = "$baseUrl/anime/page/$page/".toHttpUrl().newBuilder().apply {
                filters.forEach { filter ->
                    when (filter) {
                        is TypeFilter -> {
                            val value = filter.getSelectedValue()
                            if (value.isNotBlank()) addQueryParameter("type", value)
                        }

                        is StatusFilter -> {
                            val value = filter.getSelectedValue()
                            if (value.isNotBlank()) addQueryParameter("status", value)
                        }

                        is SubFilter -> {
                            val value = filter.getSelectedValue()
                            if (value.isNotBlank()) addQueryParameter("sub", value)
                        }

                        is OrderFilter -> {
                            val value = filter.getSelectedValue()
                            if (value.isNotBlank()) addQueryParameter("order", value)
                        }

                        is GenreFilter -> {
                            filter.getSelected().forEach { genre ->
                                addQueryParameter("genre[]", genre)
                            }
                        }

                        is SeasonFilter -> {
                            filter.getSelected().forEach { season ->
                                addQueryParameter("season[]", season)
                            }
                        }

                        is StudioFilter -> {
                            filter.getSelected().forEach { studio ->
                                addQueryParameter("studio[]", studio)
                            }
                        }

                        else -> {}
                    }
                }
            }.build()
            val response = client.newCall(GET(url, headers)).executeSafe()
            return parseAnimeListPage(response)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        StatusFilter(),
        SubFilter(),
        OrderFilter(),
        GenreFilter(),
        SeasonFilter(),
        StudioFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val elements = doc.select("div.listupd article.bs")
        val animes = elements.map { element ->
            SAnime.create().apply {
                title = element.selectFirst("a")?.attr("title") ?: element.selectFirst("h2")?.text() ?: ""
                setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }
        val hasNextPage = animes.size >= 20 || doc.select("div.pagination a.next, div.pagination a.r, a.next.page-numbers").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).executeSafe()
        val doc = response.asJsoup()

        val infoDiv = doc.selectFirst("div.info-content")
        val speSpan = infoDiv?.select("div.spe span")

        val statusRaw = speSpan?.find { it.text().contains("Status", ignoreCase = true) }?.text()?.substringAfter(":")?.trim() ?: ""
        val studioRaw = speSpan?.find { it.text().contains("Studio", ignoreCase = true) }?.selectFirst("a")?.text() ?: ""
        val ratingValue = doc.selectFirst("div.rating-prc meta[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()
        val synopsis = doc.select("div.entry-content[itemprop=description] p").joinToString("\n") { it.text() }
        val genres = doc.select("div.genxed a").joinToString { it.text() }

        return SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.thumbnail_url ?: doc.selectFirst("div.thumb img")?.attr("src")
            genre = genres.ifBlank { null }
            author = studioRaw.ifBlank { null }
            status = when {
                statusRaw.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
                statusRaw.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                statusRaw.contains("Upcoming", ignoreCase = true) -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                if (ratingValue != null && ratingValue > 0.0) {
                    val score = ratingValue
                    val full = (score / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.2f".format(score)}")
                    append("\n\n")
                }
                if (synopsis.isNotBlank()) {
                    append(synopsis)
                }
                if (statusRaw.isNotBlank()) append("\n\nStatus: $statusRaw")
                if (studioRaw.isNotBlank()) append("\nStudio: $studioRaw")
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).executeSafe()
        val doc = response.asJsoup()
        val epElements = doc.select("div.eplister ul li")

        return epElements.map { li ->
            SEpisode.create().apply {
                val aTag = li.selectFirst("a")!!
                setUrlWithoutDomain(aTag.attr("href"))
                val epNumStr = li.selectFirst("div.epl-num")?.text() ?: ""
                episode_number = epNumStr.toFloatOrNull() ?: 1.0f

                val rawTitle = li.selectFirst("div.epl-title")?.text() ?: ""
                name = if (rawTitle.isNotBlank()) {
                    rawTitle
                } else {
                    "Episode $epNumStr"
                }

                val isSub = aTag.attr("href").contains("subbed", ignoreCase = true) || name.contains("subbed", ignoreCase = true)
                val isDub = aTag.attr("href").contains("dubbed", ignoreCase = true) || name.contains("dubbed", ignoreCase = true)
                scanlator = when {
                    isSub && isDub -> "Sub / Dub"
                    isDub -> "Dub"
                    isSub -> "Sub"
                    else -> null
                }

                val dateStr = li.selectFirst("div.epl-date")?.text() ?: ""
                if (dateStr.isNotBlank()) {
                    date_upload = parseEpisodeDate(dateStr)
                }
            }
        }
    }

    private fun parseEpisodeDate(dateStr: String): Long = try {
        java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US).parse(dateStr)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).executeSafe()
        val doc = response.asJsoup()
        val hosters = mutableListOf<Hoster>()

        val buttons = doc.select(".server-options .server-btn, .server-btn, [data-server]")
        if (buttons.isNotEmpty()) {
            buttons.forEach { btn ->
                var hostName = btn.attr("data-hostname").ifBlank {
                    btn.text().trim().ifBlank { "Server ${btn.attr("data-index")}" }
                }
                val b64Server = btn.attr("data-server")
                if (b64Server.isNotBlank()) {
                    try {
                        val decoded = String(android.util.Base64.decode(b64Server, android.util.Base64.NO_WRAP or android.util.Base64.DEFAULT))
                        val embedDoc = org.jsoup.Jsoup.parseBodyFragment(decoded)
                        var embedUrl = embedDoc.selectFirst("iframe")?.attr("src")
                        if (!embedUrl.isNullOrBlank()) {
                            if (embedUrl.startsWith("//")) {
                                embedUrl = "https:$embedUrl"
                            }
                            if (hostName.startsWith("Server")) {
                                if (embedUrl.contains("animesama.se")) {
                                    hostName = "Fast Server"
                                } else if (embedUrl.contains("tamilembed.lol")) {
                                    hostName = "Standard Server"
                                }
                            }
                            if (hosters.none { it.hosterUrl == embedUrl }) {
                                hosters.add(Hoster(hosterName = hostName, hosterUrl = embedUrl))
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore decoding errors
                    }
                }
            }
        }

        if (hosters.isEmpty()) {
            val defaultIframe = doc.selectFirst("div.player-embed iframe, #pembed iframe")
            var defaultUrl = defaultIframe?.attr("src")
            if (!defaultUrl.isNullOrBlank()) {
                if (defaultUrl.startsWith("//")) {
                    defaultUrl = "https:$defaultUrl"
                }
                val name = if (defaultUrl.contains("animesama.se")) "Fast Server" else "Standard Server"
                hosters.add(Hoster(hosterName = name, hosterUrl = defaultUrl))
            }
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        var embedUrl = hoster.hosterUrl
        if (embedUrl.startsWith("//")) {
            embedUrl = "https:$embedUrl"
        }
        return try {
            if (embedUrl.contains("animesama.se/e/")) {
                val embedId = embedUrl.substringAfterLast("/").substringBefore("?")
                val actionUrl = "https://animesama.se/e/$embedId?action=get_source&id=$embedId"
                val actionHeaders = headers.newBuilder()
                    .set("Referer", "https://www.zorotv.se/")
                    .build()
                val body = try {
                    client.newCall(GET(actionUrl, actionHeaders)).executeSafe().body.string()
                } catch (e: Exception) {
                    ""
                }
                var targetUrl = body
                    .substringAfter("\"rumble_url\":", "")
                    .trimStart()
                    .removePrefix("\"")
                    .substringBefore("\"")
                    .replace("\\/", "/")
                    .takeIf { it.startsWith("http") }

                if (targetUrl.isNullOrBlank()) {
                    val iframeResponse = try {
                        client.newCall(GET(embedUrl, actionHeaders)).executeSafe()
                    } catch (e: Exception) {
                        null
                    }
                    val iframeHtml = iframeResponse?.body?.string() ?: ""
                    val streamMatch = """STREAM\s*=\s*['"]([^'"]+)['"]""".toRegex().find(iframeHtml)
                    targetUrl = streamMatch?.groupValues?.get(1)?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
                }

                var videoList = mutableListOf<Video>()
                if (!targetUrl.isNullOrBlank()) {
                    val isRumble = targetUrl.contains("rumble.com")
                    val isM3u8 = targetUrl.contains(".m3u8") || targetUrl.contains("#playlist.m3u8")
                    val refererUrl = when {
                        isRumble -> "https://rumble.com/"
                        targetUrl.contains("kickassanime") -> "https://animesama.se/"
                        else -> "https://animesama.se/"
                    }
                    val streamHeaders = headers.newBuilder()
                        .set("Referer", refererUrl)
                        .build()

                    if (isM3u8) {
                        val fixedMasterUrl = if (targetUrl.contains("#")) targetUrl else "$targetUrl#playlist.m3u8"
                        videoList.add(
                            Video(
                                videoUrl = fixedMasterUrl,
                                videoTitle = "${hoster.hosterName} - Auto",
                                headers = streamHeaders,
                                preferred = true,
                            ),
                        )
                        try {
                            val extracted = playlistUtils.extractFromHls(
                                playlistUrl = targetUrl,
                                referer = refererUrl,
                                masterHeaders = streamHeaders,
                                videoHeaders = streamHeaders,
                                videoNameGen = { "${hoster.hosterName} - $it" },
                            )
                            videoList.addAll(
                                extracted.map { v ->
                                    val fixedUrl = if (v.videoUrl.contains("#")) v.videoUrl else "${v.videoUrl}#playlist.m3u8"
                                    Video(
                                        videoUrl = fixedUrl,
                                        videoTitle = v.videoTitle,
                                        headers = v.headers,
                                        subtitleTracks = v.subtitleTracks,
                                        audioTracks = v.audioTracks,
                                    )
                                },
                            )
                        } catch (e: Exception) {
                            // Ignore extraction errors
                        }
                    } else {
                        // Direct video file (e.g. MP4)
                        videoList.add(
                            Video(
                                videoUrl = targetUrl,
                                videoTitle = "${hoster.hosterName} - Auto",
                                headers = streamHeaders,
                                preferred = true,
                            ),
                        )
                    }
                }

                if (videoList.isEmpty()) {
                    videoList = universalExtractor.videosFromUrl(embedUrl, actionHeaders, prefix = hoster.hosterName).toMutableList()
                }

                videoList
            } else if (embedUrl.contains("tamilembed.lol/embed/stream/")) {
                val embedHeaders = headers.newBuilder()
                    .set("Referer", "https://www.zorotv.se/")
                    .build()
                var videos = emptyList<Video>()
                try {
                    val response = client.newCall(GET(embedUrl, embedHeaders)).executeSafe()
                    val html = response.body.string()
                    val bloggerUrl = """src="([^"]*blogger\.com/video\.g[^"]*)"""".toRegex()
                        .find(html)?.groupValues?.get(1)

                    if (!bloggerUrl.isNullOrBlank()) {
                        val unescapedUrl = bloggerUrl.replace("&amp;", "&")
                        val origin = unescapedUrl.substringAfter("origin=", "").substringBefore("&")
                        val ref = if (origin.isNotBlank()) "https://$origin/" else "https://www.blogger.com/"
                        val bloggerHeaders = headers.newBuilder()
                            .set("Referer", ref)
                            .build()
                        videos = universalExtractor.videosFromUrl(unescapedUrl, bloggerHeaders, prefix = hoster.hosterName)
                    }
                } catch (e: Exception) {
                    // Fallback to embedUrl
                }

                videos.ifEmpty {
                    universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = hoster.hosterName)
                }
            } else if (embedUrl.contains("kwik.cx/e/")) {
                try {
                    val kwikExtractor = eu.kanade.tachiyomi.animeextension.en.zorotv.extractor.KwikExtractor(client, headers)
                    listOf(kwikExtractor.getHlsVideo(embedUrl, "https://www.zorotv.se/", hoster.hosterName))
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                val embedHeaders = headers.newBuilder()
                    .set("Referer", "https://www.zorotv.se/")
                    .build()
                universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = hoster.hosterName)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================ Relation / Recommendations =============================

    fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        return document.select("div.bixbox:contains(Recommended) article.bs, div.bixbox:contains(recommend) article.bs").map { element ->
            SAnime.create().apply {
                title = element.selectFirst("a")?.attr("title") ?: element.selectFirst("h2")?.text() ?: ""
                setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    companion object {
        private val TYPES = arrayOf(
            Pair("All", ""),
            Pair("TV Series", "tv"),
            Pair("OVA", "ova"),
            Pair("Movie", "movie"),
            Pair("Live Action", "live action"),
            Pair("Special", "special"),
            Pair("BD", "bd"),
            Pair("ONA", "ona"),
            Pair("Music", "music"),
        )
        private val STATUS = arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
            Pair("Upcoming", "upcoming"),
            Pair("Hiatus", "hiatus"),
        )
        private val SUB = arrayOf(
            Pair("All", ""),
            Pair("Sub", "sub"),
            Pair("Dub", "dub"),
            Pair("RAW", "raw"),
        )
        private val ORDERS = arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Latest Update", "update"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular"),
            Pair("Rating", "rating"),
        )
        private val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Adult Cast", "adult-cast"),
            Pair("Adventure", "adventure"),
            Pair("Anthropomorphic", "anthropomorphic"),
            Pair("Avant Garde", "avant-garde"),
            Pair("Boys Love", "boys-love"),
            Pair("CGDCT", "cgdct"),
            Pair("Childcare", "childcare"),
            Pair("Comedy", "comedy"),
            Pair("Crossdressing", "crossdressing"),
            Pair("Detective", "detective"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Erotica", "erotica"),
            Pair("Fantasy", "fantasy"),
            Pair("Gag Humor", "gag-humor"),
            Pair("Girls Love", "girls-love"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("Hentai", "hentai"),
            Pair("High Stakes Game", "high-stakes-game"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Idols (Male)", "idols-male"),
            Pair("Isekai", "isekai"),
            Pair("Iyashikei", "iyashikei"),
            Pair("Josei", "josei"),
            Pair("Love Polygon", "love-polygon"),
            Pair("Love Status Quo", "love-status-quo"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Mythology", "mythology"),
            Pair("Organized Crime", "organized-crime"),
            Pair("Otaku Culture", "otaku-culture"),
            Pair("Parody", "parody"),
            Pair("Performing Arts", "performing-arts"),
            Pair("Psychological", "psychological"),
            Pair("Racing", "racing"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Reverse Harem", "reverse-harem"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Showbiz", "showbiz"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Strategy Game", "strategy-game"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Survival", "survival"),
            Pair("Suspense", "suspense"),
            Pair("Team Sports", "team-sports"),
            Pair("Time Travel", "time-travel"),
            Pair("Urban Fantasy", "urban-fantasy"),
            Pair("Vampire", "vampire"),
            Pair("Video Game", "video-game"),
            Pair("Villainess", "villainess"),
            Pair("Visual Arts", "visual-arts"),
            Pair("Workplace", "workplace"),
        )
        private val SEASONS = arrayOf(
            Pair("Fall 1999", "fall-1999"),
            Pair("Fall 2025", "fall-2025"),
            Pair("Spring 2018", "spring-2018"),
            Pair("Spring 2023", "spring-2023"),
            Pair("Spring 2026", "spring-2026"),
            Pair("Summer 2026", "summer-2026"),
            Pair("Winter 1996", "winter-1996"),
            Pair("Winter 2026", "winter-2026"),
        )
        private val STUDIOS = arrayOf(
            Pair("100studio", "100studio"),
            Pair("8bit", "8bit"),
            Pair("A-1 Pictures", "a-1-pictures"),
            Pair("A.C.G.T.", "a-c-g-t"),
            Pair("Actas", "actas"),
            Pair("animation studio42", "animation-studio42"),
            Pair("Asahi Production", "asahi-production"),
            Pair("Ashi Productions", "ashi-productions"),
            Pair("asread.", "asread"),
            Pair("Atelier Peuplier", "atelier-peuplier"),
            Pair("Aura Studio", "aura-studio"),
            Pair("AXsiZ", "axsiz"),
            Pair("B.CMAY PICTURES", "b-cmay-pictures"),
            Pair("Bandai Namco Pictures", "bandai-namco-pictures"),
            Pair("Bellnox Films", "bellnox-films"),
            Pair("BENTEN Film", "benten-film"),
            Pair("Bibury Animation Studios", "bibury-animation-studios"),
            Pair("Bones", "bones"),
            Pair("Bones Film", "bones-film"),
            Pair("Brain's Base", "brains-base"),
            Pair("BUG FILMS", "bug-films"),
            Pair("C-Station", "c-station"),
            Pair("CloverWorks", "cloverworks"),
            Pair("Colored Pencil Animation Japan", "colored-pencil-animation-japan"),
            Pair("CompTown", "comptown"),
            Pair("Connect", "connect"),
            Pair("Cue", "cue"),
            Pair("Cypic", "cypic"),
            Pair("David Production", "david-production"),
            Pair("Diomedéa", "diomedea"),
            Pair("Doga Kobo", "doga-kobo"),
            Pair("domerica", "domerica"),
            Pair("Doraku", "doraku"),
            Pair("Drive", "drive"),
            Pair("East Fish Studio", "east-fish-studio"),
            Pair("Elias", "elias"),
            Pair("EMT Squared", "emt-squared"),
            Pair("ENGI", "engi"),
            Pair("feel.", "feel"),
            Pair("Felix Film", "felix-film"),
            Pair("Ga-Crew", "ga-crew"),
            Pair("Gekkou", "gekkou"),
            Pair("Gift-o’-Animation", "gift-o-animation"),
            Pair("GoHands", "gohands"),
            Pair("Grass", "grass"),
            Pair("Hayabusa Film", "hayabusa-film"),
            Pair("HORNETS", "hornets"),
            Pair("ILCA", "ilca"),
            Pair("Imageworks Studio", "imageworks-studio"),
            Pair("Imagica Infos", "imagica-infos"),
            Pair("J.C.Staff", "j-c-staff"),
            Pair("Jumondou", "jumondou"),
            Pair("Juvenage", "juvenage"),
            Pair("Kinema Citrus", "kinema-citrus"),
            Pair("Kyoto Animation", "kyoto-animation"),
            Pair("LAN Studio", "lan-studio"),
            Pair("Lapin Track", "lapin-track"),
            Pair("Lay-duce", "lay-duce"),
            Pair("Lerche", "lerche"),
            Pair("LIDENFILMS", "lidenfilms"),
            Pair("Liyu Culture", "liyu-culture"),
            Pair("Madhouse", "madhouse"),
            Pair("Magic Bus", "magic-bus"),
            Pair("Maho Film", "maho-film"),
            Pair("Makaria", "makaria"),
            Pair("MAPPA", "mappa"),
            Pair("Millepensee", "millepensee"),
            Pair("NAZ", "naz"),
            Pair("Newon", "newon"),
            Pair("Nichicaline", "nichicaline"),
            Pair("Nippon Animation", "nippon-animation"),
            Pair("Nomad", "nomad"),
            Pair("Nut", "nut"),
            Pair("Nyan Pollution", "nyan-pollution"),
            Pair("Okuruto Noboru", "okuruto-noboru"),
            Pair("OLM", "olm"),
            Pair("Orange", "orange"),
            Pair("Passione", "passione"),
            Pair("Picante Circus", "picante-circus"),
            Pair("PINE JAM", "pine-jam"),
            Pair("Platinum Vision", "platinum-vision"),
            Pair("Polygon Pictures", "polygon-pictures"),
            Pair("PRA", "pra"),
            Pair("Production I.G", "production-i-g"),
            Pair("Project No.9", "project-no-9"),
            Pair("Psyde Kick Studio", "psyde-kick-studio"),
            Pair("Qiyuan Yinghua", "qiyuan-yinghua"),
            Pair("Qzil.la", "qzil-la"),
            Pair("ROLL2", "roll2"),
            Pair("S.o.K", "s-o-k"),
            Pair("Saber Works", "saber-works"),
            Pair("SANZIGEN", "sanzigen"),
            Pair("Satelight", "satelight"),
            Pair("Science SARU", "science-saru"),
            Pair("Seven", "seven"),
            Pair("Seven Arcs", "seven-arcs"),
            Pair("Shin-Ei Animation", "shin-ei-animation"),
            Pair("Shuka", "shuka"),
            Pair("Signal.MD", "signal-md"),
            Pair("SILVER LINK.", "silver-link"),
            Pair("Soigne", "soigne"),
            Pair("Studio A-CAT", "studio-a-cat"),
            Pair("Studio Bind", "studio-bind"),
            Pair("Studio CANDY BOX", "studio-candy-box"),
            Pair("Studio Comet", "studio-comet"),
            Pair("Studio Deen", "studio-deen"),
            Pair("Studio Eek", "studio-eek"),
            Pair("Studio Flad", "studio-flad"),
            Pair("Studio Gokumi", "studio-gokumi"),
            Pair("Studio Hibari", "studio-hibari"),
            Pair("Studio Hokiboshi", "studio-hokiboshi"),
            Pair("Studio Jemi", "studio-jemi"),
            Pair("Studio Kafka", "studio-kafka"),
            Pair("Studio KAI", "studio-kai"),
            Pair("Studio LEO", "studio-leo"),
            Pair("studio maf", "studio-maf"),
            Pair("Studio Massket", "studio-massket"),
            Pair("Studio Pierrot", "studio-pierrot"),
            Pair("Studio VOLN", "studio-voln"),
            Pair("Sunrise", "sunrise"),
            Pair("SynergySP", "synergysp"),
            Pair("Tatsunoko Production", "tatsunoko-production"),
            Pair("Tezuka Productions", "tezuka-productions"),
            Pair("Thundray", "thundray"),
            Pair("TMS Entertainment", "tms-entertainment"),
            Pair("Toei Animation", "toei-animation"),
            Pair("TROYCA", "troyca"),
            Pair("Typhoon Graphics", "typhoon-graphics"),
            Pair("UWAN Pictures", "uwan-pictures"),
            Pair("Voil", "voil"),
            Pair("White Fox", "white-fox"),
            Pair("Wit Studio", "wit-studio"),
            Pair("Yokohama Animation Lab", "yokohama-animation-lab"),
            Pair("Yostar Pictures", "yostar-pictures"),
            Pair("Zero-G", "zero-g"),
            Pair("Zexcs", "zexcs"),
            Pair("ZG-R", "zg-r"),
        )
    }

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Type",
            TYPES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = TYPES[state].second
    }

    private class StatusFilter :
        AnimeFilter.Select<String>(
            "Status",
            STATUS.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = STATUS[state].second
    }

    private class SubFilter :
        AnimeFilter.Select<String>(
            "Sub/Dub/Raw",
            SUB.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = SUB[state].second
    }

    private class OrderFilter :
        AnimeFilter.Select<String>(
            "Order",
            ORDERS.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = ORDERS[state].second
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxFilterList(name: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { CheckBoxVal(it.first, false) }) {
        fun getSelected(): List<String> = state.mapIndexedNotNull { index, filter ->
            if (filter.state) vals[index].second else null
        }
    }

    private class GenreFilter : CheckBoxFilterList("Genre", GENRES)
    private class SeasonFilter : CheckBoxFilterList("Season", SEASONS)
    private class StudioFilter : CheckBoxFilterList("Studio", STUDIOS)
}
