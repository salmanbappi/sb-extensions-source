package eu.kanade.tachiyomi.animeextension.all.animex

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.animex.hosts.HostResolver
import eu.kanade.tachiyomi.animeextension.all.animex.hosts.KotoHostResolver
import eu.kanade.tachiyomi.animeextension.all.animex.hosts.LoliResolver
import eu.kanade.tachiyomi.animeextension.all.animex.hosts.NeroResolver
import eu.kanade.tachiyomi.animeextension.all.animex.hosts.SoraResolver
import eu.kanade.tachiyomi.animeextension.all.animex.hosts.VidcloudHostResolver
import eu.kanade.tachiyomi.animeextension.all.animex.hosts.YukiResolver
import eu.kanade.tachiyomi.animeextension.all.animex.hosts.ZunaResolver
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Animex : Source() {

    override val name = "Animex"
    override val baseUrl = "https://animex.one"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 7391826450193847201L

    override val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(AnimexInterceptor(network.client.cookieJar))
        .build()

    private val proxy: AnimexProxy by lazy { AnimexProxy(client, json) }

    private val resolvers: Map<String, HostResolver> = listOf(
        NeroResolver,
        YukiResolver,
        ZunaResolver,
        LoliResolver,
        SoraResolver,
        VidcloudHostResolver,
        KotoHostResolver,
    ).associateBy { it.id.lowercase() }

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", ANIMEX_API_UA)
        set("Referer", "https://animex.one/")
        set("Origin", "https://animex.one")
        set("Accept", "application/json, text/plain, */*")
    }

    private fun getPreferredServer(): String {
        val server = preferences.getString("pref_preferred_server", "nero") ?: "nero"
        return if (server == "auto") "nero" else server.lowercase()
    }

    private fun getPreferredQuality(): String = preferences.getString("pref_preferred_quality", "1080") ?: "1080"

    private fun getPreferredType(): String = preferences.getString("pref_preferred_type", "sub") ?: "sub"

    // ============================== POPULAR / LATEST ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = """
                query CatalogAnime(${'$'}filter: AnimeCatalogFilterInput, ${'$'}sort: [AnimeSortInput!], ${'$'}limit: Int, ${'$'}offset: Int) {
                  catalogAnime(filter: ${'$'}filter, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
                    items {
                      id
                      anilistId
                      titleRomaji
                      titleEnglish
                      coverImage
                      bannerImage
                      backdropUrl
                      description
                      status
                      format
                      averageScore
                      popularity
                      episodeCount
                      seasonYear
                      season
                      color
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(
                sort = listOf(AnimeSortInput("POPULARITY", "DESC")),
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.animex.one/graphql", headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = """
                query CatalogAnime(${'$'}filter: AnimeCatalogFilterInput, ${'$'}sort: [AnimeSortInput!], ${'$'}limit: Int, ${'$'}offset: Int) {
                  catalogAnime(filter: ${'$'}filter, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
                    items {
                      id
                      anilistId
                      titleRomaji
                      titleEnglish
                      coverImage
                      bannerImage
                      backdropUrl
                      description
                      status
                      format
                      averageScore
                      popularity
                      episodeCount
                      seasonYear
                      season
                      color
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(
                sort = listOf(AnimeSortInput("CREATED_AT", "DESC")),
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.animex.one/graphql", headers, body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== SEARCH / DISCOVERY ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var selectedSort = listOf(AnimeSortInput("POPULARITY", "DESC"))
        var selectedGenres: List<String>? = null
        var selectedFormats: List<String>? = null
        var selectedStatus: List<String>? = null
        var selectedSeason: List<String>? = null
        var selectedYear: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    val sortVal = filter.toValue()
                    selectedSort = listOf(
                        when (sortVal) {
                            "TRENDING_DESC" -> AnimeSortInput("TRENDING", "DESC")
                            "POPULARITY_DESC" -> AnimeSortInput("POPULARITY", "DESC")
                            "SCORE_DESC" -> AnimeSortInput("AVERAGE_SCORE", "DESC")
                            "FAVOURITES_DESC" -> AnimeSortInput("FAVOURITES", "DESC")
                            "START_DATE_DESC" -> AnimeSortInput("CREATED_AT", "DESC")
                            "START_DATE" -> AnimeSortInput("CREATED_AT", "ASC")
                            else -> AnimeSortInput("POPULARITY", "DESC")
                        },
                    )
                }

                is StatusFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) selectedStatus = listOf(value)
                }

                is SeasonFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) selectedSeason = listOf(value)
                }

                is FormatFilter -> {
                    val formats = filter.state.filter { it.state }.map { it.value }
                    if (formats.isNotEmpty()) selectedFormats = formats
                }

                is GenreFilter -> {
                    val genres = filter.state.filter { it.state }.map { it.value }
                    if (genres.isNotEmpty()) selectedGenres = genres
                }

                is YearFilter -> {
                    val value = filter.state
                    if (value.isNotBlank()) selectedYear = value.toIntOrNull()
                }

                else -> {}
            }
        }

        val filterInput = AnimeCatalogFilterInput(
            query = if (query.isNotBlank()) query else null,
            statusIn = selectedStatus,
            seasonIn = selectedSeason,
            seasonYearMin = selectedYear,
            seasonYearMax = selectedYear,
            formatIn = selectedFormats,
            genres = selectedGenres,
        )

        val queryBody = GraphQLRequest(
            query = """
                query CatalogAnime(${'$'}filter: AnimeCatalogFilterInput, ${'$'}sort: [AnimeSortInput!], ${'$'}limit: Int, ${'$'}offset: Int) {
                  catalogAnime(filter: ${'$'}filter, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
                    items {
                      id
                      anilistId
                      titleRomaji
                      titleEnglish
                      coverImage
                      bannerImage
                      backdropUrl
                      description
                      status
                      format
                      averageScore
                      popularity
                      episodeCount
                      seasonYear
                      season
                      color
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(
                filter = filterInput,
                sort = selectedSort,
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )

        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.animex.one/graphql", headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
        val result = json.decodeFromString<CatalogAnimeResponse>(responseBody)
        val animeList = result.data?.catalogAnime?.items?.map { item ->
            SAnime.create().apply {
                val titleSlug = item.id.substringBeforeLast("-")
                url = "/anime/$titleSlug-${item.anilistId}?id=${item.id}"
                title = item.titleEnglish ?: item.titleRomaji ?: "Unknown Title"
                thumbnail_url = item.coverImage?.extraLarge
                description = item.description
                genre = item.genres.joinToString()
            }
        } ?: emptyList()
        return AnimesPage(animeList, animeList.size >= 30)
    }

    // ============================== ANIME DETAILS ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val fullUrl = when {
            anime.url.startsWith("http://") || anime.url.startsWith("https://") -> anime.url
            anime.url.startsWith("/") -> "$baseUrl${anime.url}"
            else -> "$baseUrl/${anime.url}"
        }
        return GET(fullUrl, headers)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val anilistId = anime.url.substringAfter("/anime/").substringBefore("?").trimEnd('/').substringAfterLast("-").toIntOrNull()
            ?: anime.url.split("/").getOrNull(2)?.toIntOrNull()
            ?: throw Exception("Invalid anime URL: ${anime.url}")

        val queryBody = GraphQLRequest(
            query = """
                query GetAnime(${'$'}anilistId: Int) {
                  anime(anilistId: ${'$'}anilistId) {
                    id
                    anilistId
                    malId
                    titleRomaji
                    titleEnglish
                    description
                    coverImage
                    bannerImage
                    status
                    format
                    genres
                    averageScore
                    seasonYear
                    season
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(anilistId = anilistId),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = POST("https://graphql.animex.one/graphql", headers, body)
        val response = client.newCall(request).execute()
        animeDetailsParse(response)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        val result = json.decodeFromString<GetAnimeResponse>(responseBody)
        val anime = result.data?.anime ?: throw Exception("Anime not found")

        return SAnime.create().apply {
            title = anime.titleEnglish ?: anime.titleRomaji ?: "Unknown Title"
            thumbnail_url = anime.coverImage?.extraLarge
            description = buildString {
                anime.description?.let { append(it) }
                anime.averageScore?.let { append("\n\nScore: $it/100") }
                anime.seasonYear?.let { append("\nYear: $it") }
                anime.season?.let { append("\nSeason: $it") }
            }
            genre = anime.genres.joinToString()
            status = when (anime.status?.uppercase()) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== EPISODE LIST ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = extractSlug(anime.url)
            ?: throw Exception("Could not extract slug from URL: ${anime.url}")
        return GET("https://pp.animex.one/rest/api/episodes?id=$slug", headers)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val resolvedSlug = extractSlug(anime.url)
            ?: resolveSlugFromAnilistId(anime.url)
            ?: throw Exception("Could not resolve slug for URL: ${anime.url}")

        val episodesRequest = GET("https://pp.animex.one/rest/api/episodes?id=$resolvedSlug", headers)
        val response = client.newCall(episodesRequest).execute()
        return episodeListParseWithSlug(response, resolvedSlug, anime)
    }

    private fun extractSlug(url: String): String? = when {
        url.contains("?id=") -> url.substringAfter("?id=").substringBefore("&")
        url.contains("&id=") -> url.substringAfter("&id=").substringBefore("&")
        else -> null
    }

    private fun resolveSlugFromAnilistId(url: String): String? {
        val anilistId = url.substringAfter("/anime/").substringBefore("?").substringAfterLast("-").toIntOrNull()
            ?: url.split("/").getOrNull(2)?.toIntOrNull() ?: return null

        val queryBody = GraphQLRequest(
            query = """
                query GetAnime(${'$'}anilistId: Int) {
                  anime(anilistId: ${'$'}anilistId) {
                    id
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(anilistId = anilistId),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = POST("https://graphql.animex.one/graphql", headers, body)
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val result = json.decodeFromString<GetAnimeResponse>(response.body.string())
                    result.data?.anime?.id
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val slug = response.request.url.queryParameter("id")
            ?: throw Exception("Could not extract slug from request URL: ${response.request.url}")
        return episodeListParseWithSlug(response, slug)
    }

    private fun episodeListParseWithSlug(response: Response, slug: String, anime: SAnime? = null): List<SEpisode> {
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val responseBody = response.body.string()
        val restEpisodes = json.decodeFromString<List<RestEpisode>>(responseBody)

        val animeUrl = anime?.url
        val animePath = animeUrl?.substringAfter("/anime/")?.substringBefore("?")
        val titleSlug = animePath?.substringBeforeLast("-") ?: slug.substringBeforeLast("-")
        val anilistId = animePath?.substringAfterLast("-") ?: ""

        val episodes = restEpisodes.map { episode ->
            val num = episode.number ?: 0f
            SEpisode.create().apply {
                val numStr = if (num % 1f == 0f) num.toInt().toString() else num.toString()
                name = "Episode $numStr${episode.titles?.en?.let { ": $it" } ?: ""}"
                episode_number = num
                url = if (anilistId.isNotEmpty()) {
                    "/watch/$titleSlug-$anilistId-episode-$numStr?id=$slug"
                } else {
                    "/watch/$slug/$numStr"
                }
            }
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================== 2-TIER HOSTER LIST ==============================

    override fun videoListRequest(episode: SEpisode): Request = throw Exception("Not used")

    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val url = episode.url
        val slug = extractSlug(url)
            ?: url.split("/").getOrNull(2)
            ?: throw Exception("Invalid episode URL: $url")
        val epNum = if (url.contains("-episode-")) {
            url.substringAfter("-episode-").substringBefore("?")
        } else {
            url.substringBefore("?").split("/").getOrNull(3) ?: "1"
        }

        val disabledServers = preferences.getStringSet("pref_disabled_servers", emptySet()) ?: emptySet()
        val preferredServer = getPreferredServer()
        val preferredType = getPreferredType()

        val hosterList = mutableListOf<Hoster>()

        try {
            val serversRequest = GET("https://pp.animex.one/rest/api/servers?id=$slug&epNum=$epNum", headers)
            client.newCall(serversRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val serversData = json.decodeFromString<ServersResponse>(response.body.string())

                    for (provider in serversData.subProviders) {
                        val pid = provider.id?.lowercase() ?: continue
                        if (pid in disabledServers) continue
                        val subStyle = when {
                            provider.tip?.contains("soft sub", ignoreCase = true) == true -> " [Soft Subs]"
                            provider.tip?.contains("hard sub", ignoreCase = true) == true -> " [Hard Subs]"
                            else -> ""
                        }
                        val name = (provider.id ?: pid).uppercase()
                        hosterList.add(
                            Hoster(
                                hosterName = "$name [SUB]$subStyle",
                                hosterUrl = "$url&type=sub&providerId=$pid",
                            ),
                        )
                    }

                    for (provider in serversData.dubProviders) {
                        val pid = provider.id?.lowercase() ?: continue
                        if (pid in disabledServers) continue
                        val name = (provider.id ?: pid).uppercase()
                        hosterList.add(
                            Hoster(
                                hosterName = "$name [DUB]",
                                hosterUrl = "$url&type=dub&providerId=$pid",
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Add external web hosters if not disabled
        if ("vidcloud" !in disabledServers) {
            hosterList.add(
                Hoster(
                    hosterName = "ZORO (VIDCLOUD) [SUB]",
                    hosterUrl = "$url&type=sub&providerId=vidcloud",
                ),
            )
            hosterList.add(
                Hoster(
                    hosterName = "ZORO (VIDCLOUD) [DUB]",
                    hosterUrl = "$url&type=dub&providerId=vidcloud",
                ),
            )
        }
        if ("koto" !in disabledServers) {
            hosterList.add(
                Hoster(
                    hosterName = "KOTO [SUB]",
                    hosterUrl = "$url&type=sub&providerId=koto",
                ),
            )
        }

        // Fallback default hosters if upstream servers API failed completely
        if (hosterList.isEmpty()) {
            hosterList.add(Hoster(hosterName = "NERO [SUB]", hosterUrl = "$url&type=sub&providerId=nero"))
            hosterList.add(Hoster(hosterName = "YUKI [SUB]", hosterUrl = "$url&type=sub&providerId=yuki"))
            hosterList.add(Hoster(hosterName = "ZUNA [SUB]", hosterUrl = "$url&type=sub&providerId=zuna"))
            hosterList.add(Hoster(hosterName = "LOLI [SUB]", hosterUrl = "$url&type=sub&providerId=loli"))
            hosterList.add(Hoster(hosterName = "NERO [DUB]", hosterUrl = "$url&type=dub&providerId=nero"))
            hosterList.add(Hoster(hosterName = "YUKI [DUB]", hosterUrl = "$url&type=dub&providerId=yuki"))
        }

        // 2-Tier Hoster priority sorting: preferred server first, then preferred audio type
        return hosterList.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(preferredServer, ignoreCase = true) }
                .thenByDescending {
                    when (preferredType) {
                        "dub" -> it.hosterName.contains("[DUB]", ignoreCase = true)
                        else -> it.hosterName.contains("[SUB]", ignoreCase = true)
                    }
                },
        )
    }

    // ============================== VIDEO LIST (DISPATCH TO RESOLVERS) ==============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val uri = Uri.parse("https://animex.one$url")

        val slug = uri.getQueryParameter("id")
            ?: extractSlug(url)
            ?: url.split("/").getOrNull(2)
            ?: throw Exception("Invalid episode URL: $url")

        val epNum = if (url.contains("-episode-")) {
            url.substringAfter("-episode-").substringBefore("?")
        } else {
            url.substringBefore("?").split("/").getOrNull(3) ?: "1"
        }

        val type = uri.getQueryParameter("type") ?: "sub"
        val providerId = uri.getQueryParameter("providerId")?.lowercase() ?: "nero"

        val subStyle = when {
            hoster.hosterName.contains("[Soft Subs]", ignoreCase = true) -> " [Soft Subs]"
            hoster.hosterName.contains("[Hard Subs]", ignoreCase = true) -> " [Hard Subs]"
            else -> ""
        }

        val resolver = resolvers[providerId] ?: NeroResolver
        val videos = resolver.getVideos(
            slug = slug,
            epNum = epNum,
            type = type,
            subStyle = subStyle,
            client = client,
            baseHeaders = headers,
            proxy = proxy,
            json = json,
        )

        val preferredQuality = getPreferredQuality()
        return videos.sortedByDescending { it.videoTitle.contains(preferredQuality, ignoreCase = true) }
    }

    // ============================== FILTERS & PREFERENCES ==============================

    override fun getFilterList() = getAnimexFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "pref_preferred_server"
            title = "Preferred Server"
            entries = arrayOf("Nero", "Yuki", "Zuna", "Loli", "Zoro (Vidcloud)", "Koto", "Sora")
            entryValues = arrayOf("nero", "yuki", "zuna", "loli", "vidcloud", "koto", "sora")
            setDefaultValue("nero")
            summary = "%s"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = "pref_preferred_type"
            title = "Preferred Audio/Sub Type"
            entries = arrayOf("Subtitled (SUB)", "Dubbed (DUB)")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue("sub")
            summary = "%s"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = "pref_preferred_quality"
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"
        }.also { screen.addPreference(it) }

        MultiSelectListPreference(screen.context).apply {
            key = "pref_disabled_servers"
            title = "Disable Servers"
            entries = arrayOf("Nero", "Yuki", "Zuna", "Loli", "Zoro (Vidcloud)", "Koto", "Sora")
            entryValues = arrayOf("nero", "yuki", "zuna", "loli", "vidcloud", "koto", "sora")
            setDefaultValue(emptySet<String>())
        }.also { screen.addPreference(it) }
    }
}
