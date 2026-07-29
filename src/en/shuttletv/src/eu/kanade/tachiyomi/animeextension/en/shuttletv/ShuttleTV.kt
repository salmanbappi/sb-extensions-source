package eu.kanade.tachiyomi.animeextension.en.shuttletv

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

class ShuttleTV : AnimeHttpSource() {

    override val name = "ShuttleTV"

    override val baseUrl = "https://shuttletv.su"

    private val cineSrcUrl = "https://cinesrc.st"

    override val lang = "en"

    override val supportsLatest = true

    private val tmdbApiKey = "ea021b3b0775c8531592713ab727f254"

    private val json: Json by lazy { Injekt.get() }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Application.MODE_PRIVATE)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return client.newCall(popularAnimeRequest(page)).asObservable().map { response ->
            popularAnimeParse(response)
        }
    }

    override fun popularAnimeRequest(page: Int): Request {
        val url = "https://api.themoviedb.org/3/trending/all/week".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseTmdbMediaList(response)
    }

    // ============================== Latest ================================
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> {
        return client.newCall(latestUpdatesRequest(page)).asObservable().map { response ->
            latestUpdatesParse(response)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "https://api.themoviedb.org/3/discover/movie".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .addQueryParameter("sort_by", "primary_release_date.desc")
            .addQueryParameter("vote_count.gte", "10")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseTmdbMediaList(response)
    }

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return client.newCall(searchAnimeRequest(page, query, filters)).asObservable().map { response ->
            searchAnimeParse(response)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "https://api.themoviedb.org/3/search/multi".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", tmdbApiKey)
                .addQueryParameter("query", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }

        var mediaType = "movie"
        var sort = "popularity.desc"
        var year = ""
        var country = ""
        val genres = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> {
                    val part = filter.toUriPart()
                    if (part != "all") mediaType = part
                }
                is Filters.SortFilter -> sort = filter.toUriPart()
                is Filters.YearFilter -> year = filter.toUriPart()
                is Filters.CountryFilter -> country = filter.toUriPart()
                is Filters.GenreFilter -> genres.addAll(filter.selectedIds())
                else -> {}
            }
        }

        val endpoint = if (mediaType == "tv") "discover/tv" else "discover/movie"
        val urlBuilder = "https://api.themoviedb.org/3/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .addQueryParameter("sort_by", sort)
            .addQueryParameter("page", page.toString())

        if (year.isNotBlank()) {
            if (mediaType == "tv") {
                urlBuilder.addQueryParameter("first_air_date_year", year)
            } else {
                urlBuilder.addQueryParameter("primary_release_year", year)
            }
        }

        if (country.isNotBlank()) {
            urlBuilder.addQueryParameter("with_origin_country", country)
        }

        if (genres.isNotEmpty()) {
            urlBuilder.addQueryParameter("with_genres", genres.joinToString(","))
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseTmdbMediaList(response)
    }

    override fun getFilterList() = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.CategoryFilter(),
        Filters.SortFilter(),
        Filters.YearFilter(),
        Filters.GenreFilter(),
        Filters.CountryFilter()
    )

    private fun parseTmdbMediaList(response: Response): AnimesPage {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val results = root["results"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        val page = root["page"]?.jsonPrimitive?.intOrNull ?: 1
        val totalPages = root["total_pages"]?.jsonPrimitive?.intOrNull ?: 1

        val animes = results.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val mediaType = obj["media_type"]?.jsonPrimitive?.content ?: if (obj.containsKey("first_air_date") || obj.containsKey("name")) "tv" else "movie"
            val titleStr = obj["title"]?.jsonPrimitive?.content
                ?: obj["name"]?.jsonPrimitive?.content
                ?: obj["original_title"]?.jsonPrimitive?.content
                ?: obj["original_name"]?.jsonPrimitive?.content
                ?: return@mapNotNull null

            val posterPath = obj["poster_path"]?.jsonPrimitive?.content
            val posterUrl = if (!posterPath.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else ""

            SAnime.create().apply {
                title = titleStr
                setUrlWithoutDomain("/watch/$id?type=$mediaType")
                thumbnail_url = posterUrl
            }
        }

        return AnimesPage(animes, page < totalPages)
    }

    // =========================== Anime Details ============================
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsRequest(anime)).asObservable().map { response ->
            animeDetailsParse(response)
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfter("/watch/").substringBefore("?")
        val mediaType = if (anime.url.contains("type=tv")) "tv" else "movie"

        val url = "https://api.themoviedb.org/3/$mediaType/$id".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .addQueryParameter("append_to_response", "videos,credits")
            .build()

        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        val isTv = obj.containsKey("first_air_date") || obj.containsKey("name")

        val titleStr = obj["title"]?.jsonPrimitive?.content
            ?: obj["name"]?.jsonPrimitive?.content ?: ""

        val posterPath = obj["poster_path"]?.jsonPrimitive?.content
        val posterUrl = if (!posterPath.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else ""

        val synopsis = obj["overview"]?.jsonPrimitive?.content ?: ""
        val score = obj["vote_average"]?.jsonPrimitive?.doubleOrNull
        val voteCount = obj["vote_count"]?.jsonPrimitive?.intOrNull

        val genres = obj["genres"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content
        }?.joinToString(", ") ?: ""

        val statusRaw = if (isTv) {
            obj["status"]?.jsonPrimitive?.content ?: "Ongoing"
        } else {
            "Completed"
        }

        val releaseYear = (obj["release_date"]?.jsonPrimitive?.content
            ?: obj["first_air_date"]?.jsonPrimitive?.content ?: "").take(4)

        val trailerKey = obj["videos"]?.jsonObject?.get("results")?.jsonArray?.mapNotNull {
            val vObj = it.jsonObject
            if (vObj["site"]?.jsonPrimitive?.content == "YouTube" && vObj["type"]?.jsonPrimitive?.content == "Trailer") {
                vObj["key"]?.jsonPrimitive?.content
            } else null
        }?.firstOrNull()

        return SAnime.create().apply {
            title = titleStr
            thumbnail_url = posterUrl
            genre = genres
            status = when {
                statusRaw.equals("Ended", ignoreCase = true) || statusRaw.equals("Canceled", ignoreCase = true) || !isTv -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }
            initialized = true

            description = buildString {
                if (score != null && score > 0.0) {
                    val stars = (score / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} ${"%.2f".format(score)}")
                    if (voteCount != null) append(" ($voteCount votes)")
                    append("\n\n")
                }
                if (synopsis.isNotBlank()) append(synopsis)
                if (releaseYear.isNotBlank()) append("\n\nYear: $releaseYear")
                append("\nStatus: $statusRaw")
                if (!trailerKey.isNullOrBlank()) {
                    append("\n\n[Trailer](https://www.youtube.com/watch?v=$trailerKey)")
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val id = anime.url.substringAfter("/watch/").substringBefore("?")
        val isTv = anime.url.contains("type=tv")

        if (!isTv) {
            val singleEp = SEpisode.create().apply {
                name = "Movie"
                setUrlWithoutDomain("/watch/$id?type=movie")
                episode_number = 1.0f
            }
            return Observable.just(listOf(singleEp))
        }

        val url = "https://api.themoviedb.org/3/tv/$id".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .build()

        return client.newCall(GET(url, headers)).asObservable().flatMap { response ->
            val tvObj = json.parseToJsonElement(response.body.string()).jsonObject
            val seasons = tvObj["seasons"]?.jsonArray ?: return@flatMap Observable.just(emptyList())

            val seasonRequests = seasons.mapNotNull { sElem ->
                val sObj = sElem.jsonObject
                val seasonNum = sObj["season_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (seasonNum <= 0) return@mapNotNull null

                val seasonUrl = "https://api.themoviedb.org/3/tv/$id/season/$seasonNum".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", tmdbApiKey)
                    .build()
                GET(seasonUrl, headers)
            }

            val epList = mutableListOf<SEpisode>()
            for (req in seasonRequests) {
                runCatching {
                    val sRes = client.newCall(req).execute()
                    val sObj = json.parseToJsonElement(sRes.body.string()).jsonObject
                    val seasonNum = sObj["season_number"]?.jsonPrimitive?.intOrNull ?: 1
                    val episodes = sObj["episodes"]?.jsonArray ?: return@runCatching

                    for (eElem in episodes) {
                        val eObj = eElem.jsonObject
                        val epNum = eObj["episode_number"]?.jsonPrimitive?.intOrNull ?: continue
                        val epName = eObj["name"]?.jsonPrimitive?.content ?: "Episode $epNum"
                        val stillPath = eObj["still_path"]?.jsonPrimitive?.content
                        val stillUrl = if (!stillPath.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$stillPath" else ""
                        val overviewStr = eObj["overview"]?.jsonPrimitive?.content ?: ""

                        epList.add(
                            SEpisode.create().apply {
                                name = "S${seasonNum.toString().padStart(2, '0')}E${epNum.toString().padStart(2, '0')} - $epName"
                                setUrlWithoutDomain("/watch/$id?type=tv&s=$seasonNum&e=$epNum")
                                episode_number = (seasonNum * 1000 + epNum).toFloat()
                                if (stillUrl.isNotBlank()) preview_url = stillUrl
                                if (overviewStr.isNotBlank()) summary = overviewStr
                            }
                        )
                    }
                }
            }
            Observable.just(epList.reversed())
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun fetchHosterList(episode: SEpisode): Observable<List<Hoster>> {
        val urlStr = episode.url
        val id = urlStr.substringAfter("/watch/").substringBefore("?")
        val isTv = urlStr.contains("type=tv")
        val season = if (isTv) urlStr.substringAfter("s=", "1").substringBefore("&") else null
        val ep = if (isTv) urlStr.substringAfter("e=", "1").substringBefore("&") else null

        val mediaType = if (isTv) "tv" else "movie"

        return Observable.fromCallable {
            val challengeToken = generateChallengeToken(mediaType, id, season, ep)
            val providers = getProviderList(mediaType, id)

            val excludedServers = preferences.getStringSet("pref_exclude_servers", emptySet()) ?: emptySet()

            val hosters = providers.filter { it.first !in excludedServers }.map { (providerId, providerName) ->
                Hoster(
                    hosterName = providerName,
                    hosterUrl = "$mediaType|$id|$season|$ep|$challengeToken|$providerId"
                )
            }

            sortHostersByPreference(hosters)
        }
    }

    private fun generateChallengeToken(mediaType: String, id: String, season: String?, episode: String?): String {
        val arrayJson = buildString {
            append("[\"").append(mediaType).append("\",\"").append(id).append("\",")
            if (season != null) append("\"").append(season).append("\",") else append("null,")
            if (episode != null) append("\"").append(episode).append("\"]") else append("null]")
        }

        val b64Query = Base64.getUrlEncoder().withoutPadding().encodeToString(arrayJson.toByteArray(StandardCharsets.UTF_8))

        val req = Request.Builder()
            .url("$cineSrcUrl/api/c/bootstrap")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .headers(headers.newBuilder().set("x-cs-q", b64Query).build())
            .build()

        val res = client.newCall(req).execute()
        val obj = json.parseToJsonElement(res.body.string()).jsonObject
        val r = obj["r"]?.jsonPrimitive?.content ?: ""

        val fakeE = "e_ok"
        val fakeC2 = "c2_ok"

        return "$fakeE::$fakeC2::$r"
    }

    private fun getProviderList(mediaType: String, id: String): List<Pair<String, String>> {
        return runCatching {
            val embedPath = if (mediaType == "tv") "embed/tv/$id" else "embed/movie/$id"
            val req = Request.Builder()
                .url("$cineSrcUrl/$embedPath")
                .post("[]".toRequestBody("text/plain".toMediaType()))
                .headers(headers.newBuilder().set("Next-Action", "009ae233b6f5dd27b41a46896cad785bff36e42f4d").build())
                .build()

            val res = client.newCall(req).execute()
            val text = res.body.string()

            val jsonStr = text.substringAfter("1:0:").trim()
            val list = json.parseToJsonElement(jsonStr).jsonArray

            list.mapNotNull { elem ->
                val o = elem.jsonObject
                val pId = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val pName = o["name"]?.jsonPrimitive?.content ?: pId
                Pair(pId, pName)
            }
        }.getOrDefault(
            listOf(
                Pair("febbox-hls", "Feb HLS"),
                Pair("febbox-mp4", "Feb MP4"),
                Pair("auto", "Auto (Best Server)")
            )
        )
    }

    override fun fetchVideoList(hoster: Hoster): Observable<List<Video>> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 6) return Observable.just(emptyList())

        val mediaType = parts[0]
        val id = parts[1]
        val season = parts[2].ifEmpty { null }
        val ep = parts[3].ifEmpty { null }
        val challengeToken = parts[4]
        val providerId = parts[5]

        val embedPath = if (mediaType == "tv") "embed/tv/$id" else "embed/movie/$id"

        val payload = buildString {
            append("[\"").append(id).append("\",\"").append(mediaType).append("\",")
            if (season != null && season != "null") append("\"").append(season).append("\",") else append("null,")
            if (ep != null && ep != "null") append("\"").append(ep).append("\",") else append("null,")
            append("\"").append(challengeToken).append("\",\"").append(providerId).append("\"]")
        }

        val req = Request.Builder()
            .url("$cineSrcUrl/$embedPath")
            .post(payload.toRequestBody("text/plain".toMediaType()))
            .headers(headers.newBuilder().set("Next-Action", "7ee2ce6e276d24a29d32ee843aa18f1560caba9034").build())
            .build()

        return client.newCall(req).asObservable().map { response ->
            val bodyText = response.body.string()
            val encToken = bodyText.substringAfter("1:0:").trim().removeSurrounding("\"")

            val decryptedJson = decryptToken(encToken)
            val rootObj = json.parseToJsonElement(decryptedJson).jsonObject
            val urlArray = rootObj["url"]?.jsonArray ?: return@map listEmptyVideo()

            val videos = urlArray.mapNotNull { elem ->
                val vObj = elem.jsonObject
                val videoUrl = vObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val sourceLabel = vObj["source"]?.jsonPrimitive?.content ?: hoster.hosterName

                Video(
                    url = videoUrl,
                    quality = "$sourceLabel - ${hoster.hosterName}",
                    videoUrl = videoUrl,
                    headers = headersBuilder().set("Referer", "$cineSrcUrl/").build()
                )
            }

            videos.sortVideos()
        }
    }

    private fun decryptToken(encToken: String): String {
        return runCatching {
            val clean = encToken.trim().removeSurrounding("\"")
            val b64 = clean.replace("-", "+").replace("_", "/")
            val rawBytes = Base64.getDecoder().decode(b64)
            val decBytes = ByteArray(rawBytes.size) { i -> (rawBytes[i].toInt() inv () and 0xFF).toByte() }
            String(decBytes, StandardCharsets.UTF_8)
        }.getOrDefault("{\"url\":[]}")
    }

    private fun listEmptyVideo(): List<Video> = emptyList()

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(prefQuality, ignoreCase = true) }
        )
    }

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        if (prefServer == "auto") return hosters

        return hosters.sortedByDescending {
            it.hosterName.contains(prefServer, ignoreCase = true)
        }
    }

    // ============================ Recommendations =============================
    fun relatedAnimeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfter("/watch/").substringBefore("?")
        val mediaType = if (anime.url.contains("type=tv")) "tv" else "movie"

        val url = "https://api.themoviedb.org/3/$mediaType/$id/recommendations".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .build()

        return GET(url, headers)
    }

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        return parseTmdbMediaList(response).animes
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            summary = "%s"
            entries = arrayOf("Auto", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("Auto", "1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                summary = entries[index]
                true
            }
        }
        screen.addPreference(qualityPref)

        val serverPref = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            summary = "%s"
            entries = arrayOf("Auto", "Feb HLS", "Feb MP4", "Default")
            entryValues = arrayOf("auto", "Feb HLS", "Feb MP4", "Default")
            setDefaultValue(PREF_SERVER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                summary = entries[index]
                true
            }
        }
        screen.addPreference(serverPref)

        val subLangPref = ListPreference(screen.context).apply {
            key = PREF_SUB_LANG_KEY
            title = "Preferred Subtitle Language"
            summary = "%s"
            entries = arrayOf("English", "Spanish", "French", "German", "Italian", "Portuguese", "Russian", "Japanese", "Korean", "Chinese", "Hindi", "Arabic", "Turkish")
            entryValues = arrayOf("English", "Spanish", "French", "German", "Italian", "Portuguese", "Russian", "Japanese", "Korean", "Chinese", "Hindi", "Arabic", "Turkish")
            setDefaultValue(PREF_SUB_LANG_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                summary = entries[index]
                true
            }
        }
        screen.addPreference(subLangPref)

        val autoSkipPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTO_SKIP_KEY
            title = "Auto Skip Intro"
            summary = "Automatically skip intro segments when supported"
            setDefaultValue(true)
        }
        screen.addPreference(autoSkipPref)

        val disableAdsPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DISABLE_ADS_KEY
            title = "Disable Ads Parameter"
            summary = "Request ad-free stream token from player API"
            setDefaultValue(true)
        }
        screen.addPreference(disableAdsPref)

        val excludeServersPref = MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_SERVERS_KEY
            title = "Exclude Servers"
            summary = "Select servers to exclude from video list"
            entries = arrayOf("Feb HLS", "Feb MP4", "Default")
            entryValues = arrayOf("Feb HLS", "Feb MP4", "Default")
            setDefaultValue(emptySet<String>())
        }
        screen.addPreference(excludeServersPref)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "Auto"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"

        private const val PREF_SUB_LANG_KEY = "pref_sub_lang"
        private const val PREF_SUB_LANG_DEFAULT = "English"

        private const val PREF_AUTO_SKIP_KEY = "pref_auto_skip"
        private const val PREF_DISABLE_ADS_KEY = "pref_disable_ads"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
    }
}
