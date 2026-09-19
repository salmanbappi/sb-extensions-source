package eu.kanade.tachiyomi.animeextension.en.anilight

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds

class Anilight : Source() {

    override val name = "AniLight"

    override val baseUrl = "https://anilight.live"

    override val lang = "en"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val m3u8Integration by lazy { M3u8Integration(client) }

    private val doodExtractor by lazy { DoodExtractor(client) }

    private val megaPlayExtractor by lazy { MegaPlayExtractor(client, playlistUtils) }

    private val streamProxy by lazy { AniLightStreamProxy(client) }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            // The upstream API allows 100 req/min and answers 429 beyond that.
            .rateLimit(permits = 6, period = 1.seconds)
            .build()
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$API_BASE/filter".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "POPULARITY_DESC")
            .build()
            .toString()

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<FilterResponseDto>(json)
        val animes = (dto.media ?: emptyList()).map { it.toSAnime(getTitleLangPref()) }
        return AnimesPage(animes, dto.pageInfo?.hasNextPage ?: false)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page == 1) {
            val response = client.newCall(GET("$API_BASE/homepage", headers)).execute()
            val dto = response.parseAs<HomepageResponseDto>(json)
            val recentList = dto.recentlyAddedEpisodes
            if (!recentList.isNullOrEmpty()) {
                return AnimesPage(recentList.map { it.toSAnime(getTitleLangPref()) }, true)
            }
        }

        val url = "$API_BASE/filter".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "TRENDING_DESC")
            .build()
            .toString()

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<FilterResponseDto>(json)
        val animes = (dto.media ?: emptyList()).map { it.toSAnime(getTitleLangPref()) }
        return AnimesPage(animes, dto.pageInfo?.hasNextPage ?: false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = "$API_BASE/filter".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("search", query.trim())
        }

        var sortApplied = false
        for (filter in filters) {
            when (filter) {
                is Filters.SortFilter -> {
                    val sort = filter.toUriPart()
                    if (sort.isNotBlank()) {
                        urlBuilder.addQueryParameter("sort", sort)
                        sortApplied = true
                    }
                }

                is Filters.FormatFilter -> {
                    val format = filter.toUriPart()
                    if (format.isNotBlank()) {
                        urlBuilder.addQueryParameter("format", format)
                    }
                }

                is Filters.StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotBlank()) {
                        urlBuilder.addQueryParameter("status", status)
                    }
                }

                is Filters.SeasonFilter -> {
                    val season = filter.toUriPart()
                    if (season.isNotBlank()) {
                        urlBuilder.addQueryParameter("season", season)
                    }
                }

                is Filters.YearFilter -> {
                    val year = filter.state.trim()
                    if (year.isNotBlank() && year.toIntOrNull() != null) {
                        urlBuilder.addQueryParameter("seasonYear", year)
                    }
                }

                is Filters.GenreFilter -> {
                    val included = filter.getIncluded()
                    if (included.isNotEmpty()) {
                        urlBuilder.addQueryParameter("genres", included.joinToString(","))
                    }
                }

                else -> {}
            }
        }

        if (!sortApplied && query.isBlank()) {
            urlBuilder.addQueryParameter("sort", "POPULARITY_DESC")
        }

        val response = client.newCall(GET(urlBuilder.build().toString(), headers)).execute()
        val dto = response.parseAs<FilterResponseDto>(json)
        val animes = (dto.media ?: emptyList()).map { it.toSAnime(getTitleLangPref()) }
        return AnimesPage(animes, dto.pageInfo?.hasNextPage ?: false)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = anime.url.substringBefore("#").trim('/')
        val response = client.newCall(GET("$API_BASE/anime/$slug", headers)).execute()
        val item = response.parseAs<MediaItemDto>(json)

        return anime.apply {
            val titlePref = getTitleLangPref()
            title = item.getTitle(titlePref)
            thumbnail_url = item.coverImage?.extraLarge ?: item.coverImage?.large
            description = buildString {
                item.description?.let {
                    append(it.replace(Regex("<[^>]*>"), "").trim())
                    append("\n\n")
                }
                item.season?.let { s ->
                    append("Season: ${s.lowercase().replaceFirstChar { it.uppercase() }}")
                    item.seasonYear?.let { y -> append(" $y") }
                    append("\n")
                }
                item.format?.let { append("Format: $it\n") }
                item.episodes?.let { append("Episodes: $it\n") }
                item.averageScore?.let { append("Score: $it%\n") }
                item.studios?.nodes?.mapNotNull { it.name }?.distinct()?.takeIf { it.isNotEmpty() }?.let {
                    append("Studios: ${it.joinToString()}\n")
                }
            }.trim()
            genre = item.genres?.joinToString()
            status = when (item.status?.uppercase()) {
                "RELEASING", "ONGOING" -> SAnime.ONGOING
                "FINISHED", "COMPLETED" -> SAnime.COMPLETED
                "CANCELLED" -> SAnime.CANCELLED
                "HIATUS", "ON_HIATUS" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url.substringBefore("#").trim('/')
        val response = client.newCall(GET("$API_BASE/watch/$slug", headers)).execute()
        val dto = response.parseAs<WatchResponseDto>(json)
        val animeId = dto.id ?: return emptyList()

        return (dto.episodes ?: emptyList()).map { ep ->
            val epNum = ep.number ?: 1f
            val epNumInt = if (epNum % 1f == 0f) epNum.toInt().toString() else epNum.toString()
            val epTitle = ep.title?.trim()

            SEpisode.create().apply {
                name = when {
                    epTitle.isNullOrBlank() || epTitle.equals("Episode $epNumInt", true) -> "Episode $epNumInt"
                    else -> "Episode $epNumInt: $epTitle"
                }
                episode_number = epNum
                // Keep the anchor free of float noise ("1", not "1.0"): it is
                // matched verbatim against the episode numbers the watch API
                // returns, and used as the `epNum` query parameter.
                url = "$slug#id=$animeId&ep=$epNumInt"
                scanlator = when {
                    ep.embed_url?.sub != null && ep.embed_url.dub != null -> "Sub & Dub"
                    ep.embed_url?.dub != null -> "Dub"
                    else -> "Sub"
                }
            }
        }.reversed()
    }

    // ============================ Hoster List =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val slug = episode.url.substringBefore("#")
        val fragment = episode.url.substringAfter("#", "")
        val params = fragment.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }

        val animeId = params["id"]?.takeIf { it.isNotBlank() }
        val epNum = params["ep"]?.takeIf { it.isNotBlank() }
            ?: if (episode.episode_number % 1f == 0f) episode.episode_number.toInt().toString() else "${episode.episode_number}"

        if (animeId == null) {
            return emptyList()
        }

        val response = client.newCall(GET("$API_BASE/watch/$slug", headers)).execute()
        val dto = response.parseAs<WatchResponseDto>(json)
        val servers = dto.servers

        val providerMap = linkedMapOf<String, MutableList<String>>()
        val tipMap = mutableMapOf<String, String>()

        servers?.subProviders?.forEach { p ->
            p.id?.let { pid ->
                val typeLabel = if (p.tip?.contains("Soft Sub", ignoreCase = true) == true) "soft-sub" else "sub"
                providerMap.getOrPut(pid) { mutableListOf() }.add(typeLabel)
                p.tip?.let { tip -> if (pid !in tipMap) tipMap[pid] = tip }
            }
        }

        servers?.dubProviders?.forEach { p ->
            p.id?.let { pid ->
                providerMap.getOrPut(pid) { mutableListOf() }.add("dub")
                p.tip?.let { tip -> if (pid !in tipMap) tipMap[pid] = tip }
            }
        }

        // The API roster carries internal/experimental entries the website
        // hides from its own server picker.
        providerMap.keys
            .filter { id -> HIDDEN_PROVIDER_IDS.any { id.contains(it, ignoreCase = true) } }
            .forEach { hidden ->
                providerMap.remove(hidden)
                tipMap.remove(hidden)
            }

        // The site's own default player: a MegaPlay embed that exists per
        // episode (not per provider), so it is bolted onto the roster here
        // exactly like the web player does.
        val episodeDto = (dto.episodes ?: emptyList()).firstOrNull { it.episodeKey() == epNum }
        val embedSub = episodeDto?.embed_url?.sub.orEmpty()
        val embedDub = episodeDto?.embed_url?.dub.orEmpty()
        val embedTypes = buildList {
            if (embedSub.isNotBlank()) add("sub")
            if (embedDub.isNotBlank()) add("dub")
        }

        // The API occasionally ships an empty `servers` object; fall back to
        // the full provider roster the site itself offers.
        if (providerMap.isEmpty()) {
            FALLBACK_PROVIDERS.forEach { (pid, tip) ->
                providerMap[pid] = mutableListOf("sub", "dub")
                tipMap[pid] = tip
            }
        }

        if (embedTypes.isNotEmpty()) {
            providerMap[MEG_PROVIDER] = embedTypes.toMutableList()
            tipMap[MEG_PROVIDER] = "Embed"
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return providerMap.map { (providerId, types) ->
            val displayName = providerId.replaceFirstChar { it.uppercase() }
            val tip = tipMap[providerId]
            Hoster(
                hosterName = if (tip.isNullOrBlank()) displayName else "$displayName · $tip",
                // megaplay embeds are per-episode rather than per-provider, so
                // they ride along in two extra fields.
                hosterUrl = "$animeId|$epNum|$providerId|${types.distinct().joinToString(",")}|$embedSub|$embedDub",
            )
        }.sortedByDescending { it.providerId() == prefServer }
    }

    private fun Hoster.providerId(): String = hosterUrl.split("|").getOrNull(2).orEmpty()

    private fun EpisodeDto.episodeKey(): String = (number ?: 1f).let {
        if (it % 1f == 0f) it.toInt().toString() else it.toString()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 4) return emptyList()

        val animeId = parts[0]
        val epNum = parts[1]
        val providerId = parts[2]
        val types = parts[3].split(",").filter { it.isNotBlank() }
        val embedSub = parts.getOrNull(4).orEmpty()
        val embedDub = parts.getOrNull(5).orEmpty()

        // MegaPlay is a per-episode embed, not an API provider: it never sees
        // `/sources`, and it brings its own subtitle tracks.
        if (providerId == MEG_PROVIDER) {
            val megaVideos = types.parallelCatchingFlatMap { rawType ->
                val isDub = rawType.equals("dub", ignoreCase = true)
                val embedUrl = if (isDub) embedDub else embedSub
                if (embedUrl.isBlank()) return@parallelCatchingFlatMap emptyList<Video>()
                megaPlayExtractor.videosFromEmbed(embedUrl, isDub)
            }
            return m3u8Integration.processVideoList(megaVideos.sortVideos())
        }

        val videos = types.parallelCatchingFlatMap { rawType ->
            val apiType = if (rawType.equals("dub", ignoreCase = true)) "dub" else "sub"

            val sourcesDto = fetchSources(animeId, epNum, apiType, providerId)
                ?: return@parallelCatchingFlatMap emptyList<Video>()

            val subtitleTracks = (sourcesDto.tracks ?: emptyList())
                .filterNot { it.kind.equals("thumbnails", ignoreCase = true) }
                .mapNotNull { track ->
                    val subUrl = track.url ?: return@mapNotNull null
                    Track(
                        url = proxyCaption(subUrl),
                        lang = track.label ?: track.lang ?: "English",
                    )
                }

            val audioBadge = when {
                apiType == "dub" -> "[Dub]"
                rawType.equals("soft-sub", ignoreCase = true) || subtitleTracks.isNotEmpty() -> "[Soft Sub]"
                else -> "[Sub]"
            }

            // Provider "vid" is an iframe-only DoodStream player that the site
            // never proxies; it has to be unwrapped with the dood extractor.
            if (providerId == VID_PROVIDER) {
                return@parallelCatchingFlatMap (sourcesDto.sources ?: emptyList()).mapNotNull { src ->
                    val embedUrl = src.url ?: return@mapNotNull null
                    doodExtractor.videoFromUrl(
                        url = embedUrl,
                        prefix = audioBadge,
                        externalSubs = subtitleTracks,
                    )
                }
            }

            (sourcesDto.sources ?: emptyList()).flatMap { src ->
                val rawUrl = src.url ?: return@flatMap emptyList()
                // Providers round-robin their CDN hosts, and the one baked
                // into the API response is not always the live one — walk the
                // candidate list the website itself builds and keep the first
                // that actually answers with media. A candidate blowing up
                // must not take its siblings down with it.
                try {
                    proxyCandidates(rawUrl, providerId).firstNotNullOfOrNull { candidate ->
                        videosFromCandidate(candidate, audioBadge, subtitleTracks, src.quality).ifEmpty { null }
                    } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        return m3u8Integration.processVideoList(videos.sortVideos())
    }

    private suspend fun fetchSources(animeId: String, epNum: String, apiType: String, providerId: String): SourcesResponseDto? {
        val url = "$API_BASE/sources?id=${enc(animeId)}&epNum=${enc(epNum)}&type=${enc(apiType)}&providerId=${enc(providerId)}"
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            if (!response.isSuccessful) return null
            response.parseAs<SourcesResponseDto>(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Turns a raw stream URL into the list of proxy URLs the AniLight web
     * player itself would try, in order.
     *
     * The site removed its public `/proxy` + `/lb/<server>/proxy` shims from
     * the page markup and now resolves them server-side through a rotating
     * pool of Cloudflare Workers (`pro-lrvp-*`, `pro-mvp-*`, `pro-mevp-*`, …).
     * Those worker hostnames change per request, so the only stable entry
     * point is the API proxy — hardcoding a worker is guaranteed to rot.
     */
    private fun proxyCandidates(rawUrl: String, providerId: String): List<String> = when {
        // krussdomi-hosted CDNs, including the per-provider krussdomi
        // sub-account that serves soft subs.
        providerId == "l" || providerId == "raye" -> listOf(apiProxy("lb/$providerId/proxy", rawUrl))

        // animegg progressive MP4s; the worker rewrites them onto vidcache.
        providerId == "ryu" -> listOf(apiProxy("proxy/ryu", rawUrl))

        // otakuhg embed pages; the endpoint resolves the embed into a stream.
        providerId == "light" || providerId == "rem" ->
            listOf("$API_BASE/proxy/light-rem?url=${enc(rawUrl)}&serverId=${enc(providerId)}")

        // The misa CDN rotates between a primary and five mirrors, and the
        // primary 404s more often than not, so every origin gets a turn.
        providerId == "misa" -> misaCandidates(rawUrl).map { apiProxy("lb/misa/proxy", it) }

        rawUrl.contains("hls.anidb.app") -> listOf(apiProxy("lb/near/proxy", rawUrl))

        rawUrl.contains("?t.m3u8") -> listOf(apiProxy("lb/mello/proxy", rawUrl))

        rawUrl.contains("bd.24stream.xyz") || rawUrl.contains("bd.aniwatchtv.site") ->
            listOf(apiProxy("proxy", rawUrl.replace("bd.24stream.xyz", "bd.aniwatchtv.site")))

        else -> listOf(rawUrl)
    }

    private fun misaCandidates(rawUrl: String): List<String> {
        val origin = MISA_MIRRORS.firstOrNull { rawUrl.startsWith(it, ignoreCase = true) }
        if (origin != null) return listOf(rawUrl)

        val base = rawUrl.toHttpUrlOrNull() ?: return listOf(rawUrl)
        return buildList {
            add(rawUrl)
            MISA_MIRRORS.forEach { mirror ->
                add(base.newBuilder().scheme("https").host(mirror.substringAfter("//")).port(443).build().toString())
            }
        }
    }

    private fun apiProxy(path: String, target: String): String = "$API_BASE/$path?url=${enc(target)}"

    /** Subtitles that 403 on direct fetches are served through the API. */
    private fun proxyCaption(subUrl: String): String = if (CAPTION_PROXY_HOSTS.any { subUrl.contains(it) }) apiProxy("proxy/captions", subUrl) else subUrl

    private suspend fun videosFromCandidate(
        candidateUrl: String,
        audioBadge: String,
        subtitleTracks: List<Track>,
        quality: String?,
    ): List<Video> {
        val streamHeaders = streamHeaders()

        val fetched = try {
            client.newCall(GET(candidateUrl, streamHeaders)).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                // The effective URL matters: several proxies answer with
                // relative child paths that only resolve against the redirect
                // target.
                response.peekBody(PEEK_BYTES).string() to response.request.url.toString()
            }
        } catch (_: Exception) {
            return emptyList()
        }
        val body = fetched.first

        val trimmed = body.trimStart().removePrefix("\uFEFF")
        return when {
            trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXT-X-") -> when {
                // A master carrying independent audio renditions (providers
                // "l" and "raye") must stay whole — splitting it into
                // per-quality Videos kills audio because ExoPlayer cannot sync
                // separate HLS audio playlists through MergingMediaSource. It
                // also has to go through the dedicated loopback relay rather
                // than the shared m3u8 server: that one rewrites bare URI
                // lines only, leaving the `#EXT-X-MEDIA` audio playlists to be
                // fetched straight from the worker, where every chunk is
                // disguised as a `.jpg` with an `image/jpeg` content type.
                body.contains("#EXT-X-MEDIA:TYPE=AUDIO") ->
                    listOf(
                        Video(
                            videoUrl = streamProxy.relay(candidateUrl, "$baseUrl/"),
                            videoTitle = "Auto $audioBadge",
                            headers = streamHeaders,
                            subtitleTracks = subtitleTracks,
                        ),
                    )

                !body.contains("#EXT-X-STREAM-INF") -> {
                    // Plain media playlist. Its chunks are what the player
                    // actually pulls, so a playlist whose first chunk host is
                    // gone (the retired misora `*.nukitashi.top` nodes) would
                    // otherwise surface as a bare "loading failed" mid-play.
                    if (!firstChunkIsAlive(body, fetched.second, streamHeaders)) {
                        emptyList()
                    } else {
                        listOf(
                            Video(
                                videoUrl = candidateUrl.withM3u8FragmentIfNeeded(),
                                videoTitle = "Auto $audioBadge",
                                headers = streamHeaders,
                                subtitleTracks = subtitleTracks,
                            ),
                        )
                    }
                }

                else -> try {
                    playlistUtils.extractFromHls(
                        playlistUrl = candidateUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality2 -> "$quality2 $audioBadge" },
                        subtitleList = subtitleTracks,
                    )
                } catch (_: Exception) {
                    emptyList()
                }
            }

            // Dead proxy / HTML error page / rotated-domain junk.
            trimmed.startsWith("<") || trimmed.startsWith("{") || trimmed.startsWith("http") -> emptyList()

            else -> listOf(
                Video(
                    videoUrl = candidateUrl,
                    videoTitle = "${quality ?: "Video"} $audioBadge",
                    headers = streamHeaders,
                    subtitleTracks = subtitleTracks,
                ),
            )
        }
    }

    /**
     * Probes the first chunk a media playlist points at.
     *
     * Only unambiguous deaths count as dead — a host that no longer resolves,
     * or an explicit 404/410. Anything else (timeout, 403, 5xx) is treated as
     * transient so a flaky CDN never loses a video that would have played.
     */
    private fun firstChunkIsAlive(playlist: String, baseUrl: String, requestHeaders: Headers): Boolean {
        val uri = playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return true

        // Master playlists descend into another playlist; verified separately.
        if (uri.contains(".m3u8", ignoreCase = true)) return true

        val chunkUrl = baseUrl.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri
        return try {
            client.newCall(GET(chunkUrl, requestHeaders)).execute().use { response ->
                response.isSuccessful || response.code !in DEAD_CHUNK_CODES
            }
        } catch (_: UnknownHostException) {
            false
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Appends a `#.m3u8` fragment to playlist URLs that lack an .m3u8
     * extension so [M3u8Integration.processVideoList] routes them through the
     * local m3u8 server. URL fragments are stripped client-side (OkHttp and
     * ExoPlayer never send them), so upstream requests stay byte-identical,
     * while the library's `\.m3u8($|\?|#)` matcher still recognizes them.
     */
    private fun String.withM3u8FragmentIfNeeded(): String {
        if (contains(".m3u8", ignoreCase = true)) return this
        return "$this#.m3u8"
    }

    private fun streamHeaders(): Headers = headers.newBuilder()
        // Every candidate is either an API proxy URL or a worker URL the proxy
        // redirected to, and the workers/CDNs behind them are gated on the
        // site origin — anonymous fetches get HTML error pages or 403s.
        // Sending it for the unmatched raw-CDN fallback too is harmless and
        // mirrors what the web player's own requests look like.
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .build()

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("[$prefAudio]", ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    private fun getTitleLangPref(): String = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred Title Language"
            entries = arrayOf("English", "Romaji", "Native")
            entryValues = arrayOf("english", "romaji", "native")
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred Audio"
            entries = arrayOf("Soft Sub", "Sub", "Dub")
            entryValues = arrayOf("Soft Sub", "Sub", "Dub")
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            // Meg is the site's own default embed and exists for nearly every
            // episode; l, misa, mello, rem and vid are the other consistently
            // playable providers (verified live 2026-09-19). near is currently
            // rate-limited at its origin and light/rem/raye drop episodes per
            // show.
            entries = arrayOf("Meg", "L", "Mello", "Misa", "Misora", "Near", "Raye", "Rem", "Ryu", "Vid", "Light")
            entryValues = arrayOf("meg", "l", "mello", "misa", "misora", "near", "raye", "rem", "ryu", "vid", "light")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Auto")
            entryValues = arrayOf("1080", "720", "480", "360", "Auto")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val API_BASE = "https://api.anilight.live/api"

        private const val VID_PROVIDER = "vid"

        private const val MEG_PROVIDER = "meg"

        /** Entries the website hides from its own server picker. */
        private val HIDDEN_PROVIDER_IDS = listOf("yuki", "vee")

        private const val PEEK_BYTES = 64L * 1024L

        /** Providers the site ships in `servers` as of 2026-09-19. */
        private val FALLBACK_PROVIDERS = listOf(
            "l" to "Soft Sub, Fast",
            "light" to "Hard Sub, Fast",
            "mello" to "Hard Sub, Fast",
            "misa" to "Soft Sub, Fast",
            "misora" to "Hard Sub, Fast",
            "near" to "Hard Sub, Fast",
            "raye" to "Soft Sub, Fast",
            "rem" to "Soft Sub, Fast",
            "ryu" to "Hard Sub, Fast",
            "vid" to "Hard Sub, Embed",
        )

        /** Alternate origins the misa CDN rotates through. */
        private val MISA_MIRRORS = listOf(
            "https://ncdn.imgnex.top",
            "https://f0ja7.zhaevor.top",
            "https://bb.akirax.buzz",
            "https://xdw5v.qeltrix.top",
            "https://fetch.nexabloom.top",
        )

        private val CAPTION_PROXY_HOSTS = listOf("1oe.lostproject.club", "subbl.krussdomi.com")

        /** Chunk responses that mean the CDN node is gone rather than flaky. */
        private val DEAD_CHUNK_CODES = setOf(404, 410)

        private const val PREF_TITLE_LANG_KEY = "pref_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "english"

        private const val PREF_AUDIO_KEY = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "Soft Sub"

        private const val PREF_SERVER_KEY = "pref_server"

        // MegaPlay is what the website itself selects by default, and it is
        // the only source that exists for essentially every episode.
        private const val PREF_SERVER_DEFAULT = "meg"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class MediaTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class ImageDto(
    val large: String? = null,
    val extraLarge: String? = null,
    val color: String? = null,
)

@Serializable
data class MediaItemDto(
    val id: Long? = null,
    val slug: String? = null,
    val anilistId: Long? = null,
    val idMal: Long? = null,
    val title: MediaTitleDto? = null,
    val coverImage: ImageDto? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val status: String? = null,
    val source: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val studios: StudiosDto? = null,
) {
    fun getTitle(pref: String = "english"): String = when (pref.lowercase()) {
        "romaji" -> title?.romaji?.takeIf { it.isNotBlank() }
        "native" -> title?.native?.takeIf { it.isNotBlank() }
        else -> title?.english?.takeIf { it.isNotBlank() }
    } ?: title?.english?.takeIf { it.isNotBlank() }
        ?: title?.romaji?.takeIf { it.isNotBlank() }
        ?: title?.native?.takeIf { it.isNotBlank() }
        ?: "Untitled"

    fun toSAnime(titlePref: String = "english"): SAnime = SAnime.create().apply {
        title = getTitle(titlePref)
        url = slug ?: id?.toString() ?: ""
        thumbnail_url = coverImage?.extraLarge ?: coverImage?.large
        description = this@MediaItemDto.description?.replace(Regex("<[^>]*>"), "")?.trim()
        genre = genres?.joinToString()
        status = when (this@MediaItemDto.status?.uppercase()) {
            "RELEASING", "ONGOING" -> SAnime.ONGOING
            "FINISHED", "COMPLETED" -> SAnime.COMPLETED
            "CANCELLED" -> SAnime.CANCELLED
            "HIATUS", "ON_HIATUS" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
        fetch_type = FetchType.Episodes
    }
}

@Serializable
data class PageInfoDto(
    val currentPage: Int? = null,
    val hasNextPage: Boolean? = null,
    val lastPage: Int? = null,
    val perPage: Int? = null,
    val total: Int? = null,
)

@Serializable
data class FilterResponseDto(
    val pageInfo: PageInfoDto? = null,
    val media: List<MediaItemDto>? = null,
)

@Serializable
data class HomepageResponseDto(
    val recentlyAddedEpisodes: List<MediaItemDto>? = null,
)

@Serializable
data class EmbedUrlDto(
    val sub: String? = null,
    val dub: String? = null,
)

@Serializable
data class EpisodeDto(
    val number: Float? = null,
    val title: String? = null,
    val jp_title: String? = null,
    val description: String? = null,
    val img: String? = null,
    val isFiller: Boolean? = null,
    val embed_url: EmbedUrlDto? = null,
)

@Serializable
data class ProviderDto(
    val id: String? = null,
    val tip: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class ServersDto(
    val subProviders: List<ProviderDto>? = null,
    val dubProviders: List<ProviderDto>? = null,
)

@Serializable
data class WatchResponseDto(
    val id: Long? = null,
    val episodes: List<EpisodeDto>? = null,
    val servers: ServersDto? = null,
)

@Serializable
data class SourceStreamDto(
    val url: String? = null,
    val quality: String? = null,
)

@Serializable
data class TrackDto(
    val id: String? = null,
    val url: String? = null,
    val kind: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class SourcesResponseDto(
    val sources: List<SourceStreamDto>? = null,
    val tracks: List<TrackDto>? = null,
)

@Serializable
data class StudioNodeDto(
    val id: Long? = null,
    val name: String? = null,
)

@Serializable
data class StudiosDto(
    val nodes: List<StudioNodeDto>? = null,
)
