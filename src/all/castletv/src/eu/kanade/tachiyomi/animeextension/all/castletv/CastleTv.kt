package eu.kanade.tachiyomi.animeextension.all.castletv

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.addEditTextPreference
import keiyoushi.utils.parallelCatchingMapNotNull
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CastleTv : Source() {

    override val name = "Castle TV"
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN, "https://api.hlowb.com") ?: "https://api.hlowb.com"
    override val lang = "all"
    override val supportsLatest = true

    private val suffix: String
        get() = preferences.getString(PREF_SUFFIX, "T!BgJB") ?: "T!BgJB"

    private fun deriveKey(apiKeyB64: String): ByteArray {
        val apiKeyBytes = android.util.Base64.decode(apiKeyB64, android.util.Base64.DEFAULT)
        val suffixBytes = suffix.toByteArray(StandardCharsets.US_ASCII)
        val keyMaterial = apiKeyBytes + suffixBytes
        return when {
            keyMaterial.size < 16 -> keyMaterial + ByteArray(16 - keyMaterial.size)
            keyMaterial.size > 16 -> keyMaterial.copyOfRange(0, 16)
            else -> keyMaterial
        }
    }

    private fun decryptData(encryptedB64: String, apiKeyB64: String): String? = try {
        val aesKey = deriveKey(apiKeyB64)
        val iv = aesKey
        val encryptedData = android.util.Base64.decode(encryptedB64, android.util.Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(aesKey, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decrypted = cipher.doFinal(encryptedData)
        String(decrypted, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private suspend fun getSecurityKey(): String? = try {
        val url = "$baseUrl/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US"
        val response = client.newCall(GET(url)).execute()
        val text = response.body.string()
        val securityResponse = json.decodeFromString<SecurityKeyResponse>(text)
        if (securityResponse.code == 200) {
            securityResponse.data
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchPage(
        page: Int,
        categoryFilter: String = "",
        locationId: String = "1001",
        genre: String = "All",
        platform: String = "All",
        language: String = "All",
        region: String = "All",
        year: String = "All",
        sort: String = "Default",
    ): List<SAnime> {
        val securityKey = getSecurityKey() ?: return emptyList()
        val url = "$baseUrl/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=$locationId&mode=1&packageName=com.external.castle&page=$page&size=50"
        val response = client.newCall(GET(url)).execute()
        val text = response.body.string()
        val apiResponse = try {
            json.decodeFromString<CastleApiResponse>(text)
        } catch (e: Exception) {
            CastleApiResponse(200, "OK", text)
        }
        val encryptedData = apiResponse.data ?: return emptyList()
        val decryptedJson = decryptData(encryptedData, securityKey) ?: return emptyList()
        val decryptedResponse = json.decodeFromString<DecryptedResponse>(decryptedJson)
        val filteredItems = mutableListOf<ContentItem>()
        val sdf = java.text.SimpleDateFormat("yyyy", java.util.Locale.US)

        decryptedResponse.data.rows?.forEach { row ->
            // 1. Filter rows by category filter if specified
            if (categoryFilter.isNotBlank() && row.name?.contains(categoryFilter, ignoreCase = true) != true) {
                return@forEach
            }

            // 2. Filter rows by Genre if selected
            if (genre != "All") {
                val rowName = row.name ?: ""
                val matchesGenre = when (genre) {
                    "Action" -> rowName.contains("Action", true) || rowName.contains("Adventure", true) || rowName.contains("Superheroes", true) || rowName.contains("Marvel", true) || rowName.contains("X-Men", true)
                    "Drama" -> rowName.contains("Drama", true) || rowName.contains("Reality", true)
                    "Comedy" -> rowName.contains("Comedy", true) || rowName.contains("Hilarious", true)
                    "Romance" -> rowName.contains("Romance", true) || rowName.contains("Romantic", true) || rowName.contains("Sweet love", true)
                    "Thriller/Crime" -> rowName.contains("Thriller", true) || rowName.contains("Crime", true) || rowName.contains("Mystery", true) || rowName.contains("Supernatural", true)
                    "Sci-Fi/Adventure" -> rowName.contains("Sci-Fi", true) || rowName.contains("Adventure", true) || rowName.contains("Superheroes", true) || rowName.contains("Marvel", true) || rowName.contains("X-Men", true)
                    "Fantasy/Mystery" -> rowName.contains("Fantasy", true) || rowName.contains("Mystery", true) || rowName.contains("Supernatural", true)
                    "Cartoon/Animation" -> rowName.contains("Cartoon", true) || rowName.contains("Anime", true) || rowName.contains("Kids", true)
                    else -> true
                }
                if (!matchesGenre) return@forEach
            }

            // 3. Filter rows by Platform if selected
            if (platform != "All") {
                val rowName = row.name ?: ""
                val matchesPlatform = when (platform) {
                    "Netflix" -> rowName.contains("Netflix", true)
                    "Disney+ Hotstar" -> rowName.contains("Disney", true) || rowName.contains("Hotstar", true) || rowName.contains("Marvel", true)
                    "Zee5" -> rowName.contains("Zee", true) || rowName.contains("Z5", true)
                    "Prime Video" -> rowName.contains("Prime", true) || rowName.contains("Amazon", true)
                    "MX Player" -> rowName.contains("MX", true)
                    "Crunchyroll" -> rowName.contains("Anime", true)
                    else -> true
                }
                if (!matchesPlatform) return@forEach
            }

            row.contents?.forEach { content ->
                // 4. Filter items by Language
                if (language != "All") {
                    val hasLanguage = content.languages?.any { it.contains(language, true) } == true
                    if (!hasLanguage) return@forEach
                }

                // 5. Filter items by Region (approximate)
                if (region != "All") {
                    val title = content.title ?: ""
                    val langs = content.languages ?: emptyList()
                    val matchesRegion = when (region) {
                        "India" -> title.contains("Bollywood", true) || title.contains("Tollywood", true) || langs.any { it.equals("Hindi", true) || it.equals("Tamil", true) || it.equals("Telugu", true) || it.equals("Malayalam", true) || it.equals("Kannada", true) || it.equals("Bengali", true) }
                        "United States" -> langs.any { it.equals("English", true) } && !title.contains("Bollywood", true) && !title.contains("Tollywood", true)
                        "South Korea" -> title.contains("Korean", true) || langs.any { it.equals("Korean", true) }
                        "Japan" -> title.contains("Anime", true) || langs.any { it.equals("Japanese", true) }
                        else -> true
                    }
                    if (!matchesRegion) return@forEach
                }

                // 6. Filter items by Year
                if (year != "All") {
                    val pTime = content.publishTime ?: 0L
                    val itemYear = if (pTime > 0) sdf.format(java.util.Date(pTime)) else ""
                    if (year == "Older") {
                        val numericYear = itemYear.toIntOrNull()
                        if (numericYear != null && numericYear >= 2015) return@forEach
                    } else {
                        if (itemYear != year) return@forEach
                    }
                }

                filteredItems.add(content)
            }
        }

        // 7. Sort the items
        var finalItems: List<ContentItem> = filteredItems.distinctBy { it.redirectId }
        finalItems = when (sort) {
            "Latest" -> finalItems.sortedByDescending { it.publishTime ?: 0L }
            "Most Viewed (Heat)" -> finalItems.sortedByDescending { it.heat ?: 0 }
            "Rating" -> finalItems.sortedByDescending { it.score ?: 0.0 }
            else -> finalItems
        }

        return finalItems.map { content ->
            SAnime.create().apply {
                this.title = content.title ?: ""
                this.url = content.redirectId?.toString() ?: ""
                this.thumbnail_url = content.coverImage ?: ""
            }
        }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val list = fetchPage(page)
        return AnimesPage(list, list.isNotEmpty())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        var categoryFilter = ""
        var locationId = "1001"
        var genre = "All"
        var platform = "All"
        var language = "All"
        var region = "All"
        var year = "All"
        var sort = "Default"

        for (filter in filters) {
            if (filter is SectionFilter && filter.state > 0) {
                locationId = when (filter.values[filter.state]) {
                    "Home" -> "1001"
                    "TV Shows" -> "1002"
                    "Movies" -> "1003"
                    "Anime" -> "1069"
                    else -> "1001"
                }
            }
            if (filter is CategoryFilter && filter.state > 0) {
                val selectedCategory = filter.values[filter.state]
                if (selectedCategory != "All") {
                    categoryFilter = when (selectedCategory) {
                        "Anime (Home)" -> "Anime"
                        "Anime (TV Shows)" -> "Anime"
                        "Hottest International Film" -> "Hottest International Film 🔥"
                        "Korean Drama Hindi dubbed" -> "Korean  Drama Hindi dubbed"
                        "International Movies" -> "International Movies  "
                        "Bollywood" -> "Bollywood "
                        else -> selectedCategory
                    }
                    locationId = getCategoryLocation(selectedCategory)
                }
            }
            if (filter is GenreFilter && filter.state > 0) {
                genre = filter.values[filter.state]
            }
            if (filter is PlatformFilter && filter.state > 0) {
                platform = filter.values[filter.state]
            }
            if (filter is LanguageFilter && filter.state > 0) {
                language = filter.values[filter.state]
            }
            if (filter is RegionFilter && filter.state > 0) {
                region = filter.values[filter.state]
            }
            if (filter is YearFilter && filter.state > 0) {
                year = filter.values[filter.state]
            }
            if (filter is SortFilter && filter.state > 0) {
                sort = filter.values[filter.state]
            }
        }

        // Category-only browse (no query)
        if (query.isBlank()) {
            val list = fetchPage(page, categoryFilter, locationId, genre, platform, language, region, year, sort)
            return AnimesPage(list, list.isNotEmpty())
        }

        // Keyword search
        val securityKey = getSecurityKey() ?: return AnimesPage(emptyList(), false)

        val genreTagId = when (genre) {
            "Action" -> "1003"
            "Drama" -> "1004"
            "Comedy" -> "1006"
            "Romance" -> "1007"
            "Cartoon/Animation" -> "1008"
            "Thriller/Crime" -> "1005"
            "Sci-Fi/Adventure" -> "1001"
            "Fantasy/Mystery" -> "1002"
            else -> null
        }
        val tagParam = if (genreTagId != null) "&tagId=$genreTagId" else ""

        val searchUrl = "$baseUrl/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=1&clientType=1&keyword=${URLEncoder.encode(query, "UTF-8")}&lang=en-US&mode=1&packageName=com.external.castle&page=$page&size=30$tagParam"

        val response = client.newCall(GET(searchUrl)).execute()
        val encryptedData = response.body.string()
        if (encryptedData.isBlank()) return AnimesPage(emptyList(), false)

        val decryptedJson = decryptData(encryptedData, securityKey) ?: return AnimesPage(emptyList(), false)
        val searchResponse = json.decodeFromString<SearchApiResponse>(decryptedJson)
        val searchData = searchResponse.data

        val sdf = java.text.SimpleDateFormat("yyyy", java.util.Locale.US)
        var searchRows = searchData.rows ?: emptyList()

        if (language != "All") {
            searchRows = searchRows.filter { item ->
                item.languages?.any { it.contains(language, true) } == true
            }
        }
        if (region != "All") {
            searchRows = searchRows.filter { item ->
                val countries = item.countries ?: emptyList()
                val langs = item.languages ?: emptyList()
                when (region) {
                    "India" -> countries.any { it.contains("India", true) } || langs.any { it.equals("Hindi", true) || it.equals("Tamil", true) || it.equals("Telugu", true) }
                    "United States" -> countries.any { it.contains("United States", true) || it.contains("US", true) }
                    "South Korea" -> countries.any { it.contains("Korea", true) } || langs.any { it.equals("Korean", true) }
                    "Japan" -> countries.any { it.contains("Japan", true) } || langs.any { it.equals("Japanese", true) }
                    "United Kingdom" -> countries.any { it.contains("United Kingdom", true) || it.contains("UK", true) }
                    else -> true
                }
            }
        }
        if (year != "All") {
            searchRows = searchRows.filter { item ->
                val pTime = item.publishTime ?: 0L
                val itemYear = if (pTime > 0) sdf.format(java.util.Date(pTime)) else ""
                if (year == "Older") {
                    val numericYear = itemYear.toIntOrNull()
                    numericYear != null && numericYear < 2015
                } else {
                    itemYear == year
                }
            }
        }

        searchRows = when (sort) {
            "Latest" -> searchRows.sortedByDescending { it.publishTime ?: 0L }
            "Rating" -> searchRows.sortedByDescending { it.score ?: 0.0 }
            else -> searchRows
        }

        val list = searchRows.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val id = item.id?.toString() ?: return@mapNotNull null
            val posterUrl = item.coverVerticalImage ?: item.coverHorizontalImage ?: ""

            SAnime.create().apply {
                this.title = title
                this.url = id
                thumbnail_url = posterUrl
            }
        }

        return AnimesPage(list, list.isNotEmpty())
    }

    override fun getFilterList(): AnimeFilterList = getCastleTvFilters()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val movieId = anime.url
        val securityKey = getSecurityKey() ?: return anime
        val detailsUrl = "$baseUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&clientType=1&lang=en-US&movieId=$movieId&packageName=com.external.castle"

        val response = client.newCall(GET(detailsUrl)).execute()
        val encryptedData = response.body.string()
        if (encryptedData.isBlank()) return anime

        val decryptedJson = decryptData(encryptedData, securityKey) ?: return anime
        val detailsResponse = json.decodeFromString<MovieDetailsResponse>(decryptedJson)
        val details = detailsResponse.data

        val animeTitle = anime.title
        val animeThumbnail = anime.thumbnail_url
        return SAnime.create().apply {
            title = details.title ?: animeTitle
            thumbnail_url = details.coverVerticalImage ?: details.coverHorizontalImage ?: animeThumbnail
            description = details.briefIntroduction
            genre = details.tags?.joinToString(", ")
            status = if (details.seasonDescription?.contains("season", true) == true) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
            author = details.directors?.joinToString(", ") { p -> p.name ?: "" }
            artist = details.actors?.joinToString(", ") { p -> p.name ?: "" }
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val movieId = anime.url
        val securityKey = getSecurityKey() ?: return emptyList()
        val detailsUrl = "$baseUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&clientType=1&lang=en-US&movieId=$movieId&packageName=com.external.castle"

        val response = client.newCall(GET(detailsUrl)).execute()
        val encryptedData = response.body.string()
        if (encryptedData.isBlank()) return emptyList()

        val decryptedJson = decryptData(encryptedData, securityKey) ?: return emptyList()
        val detailsResponse = json.decodeFromString<MovieDetailsResponse>(decryptedJson)
        val details = detailsResponse.data

        val episodes = mutableListOf<SEpisode>()

        val isSeriesLike = details.movieType == 1 || details.movieType == 3 || details.movieType == 5 || (details.episodes?.size ?: 0) > 1

        if (isSeriesLike) {
            if (details.seasons != null && details.seasons.size > 1) {
                for (season in details.seasons) {
                    val seasonId = season.movieId?.toString() ?: continue
                    val seasonNumber = season.number ?: continue

                    try {
                        val seasonUrl = "$baseUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&clientType=1&lang=en-US&movieId=$seasonId&packageName=com.external.castle"
                        val seasonResponse = client.newCall(GET(seasonUrl)).execute()
                        val seasonEncrypted = seasonResponse.body.string()
                        if (seasonEncrypted.isNotBlank()) {
                            val seasonDecrypted = decryptData(seasonEncrypted, securityKey)
                            if (seasonDecrypted != null) {
                                val seasonDetails = json.decodeFromString<MovieDetailsResponse>(seasonDecrypted).data
                                seasonDetails.episodes?.forEach { ep ->
                                    val epName = "S$seasonNumber E${ep.number ?: 0} - ${ep.title ?: ""}"
                                    episodes.add(
                                        SEpisode.create().apply {
                                            name = epName
                                            url = "${seasonId}_${ep.id}"
                                            episode_number = ep.number?.toFloat() ?: 0f
                                            date_upload = ep.onlineTime ?: 0L
                                            preview_url = ep.coverImage
                                        },
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Skip failed seasons
                    }
                }
            } else {
                details.episodes?.forEachIndexed { index, ep ->
                    val epName = "Episode ${ep.number ?: (index + 1)} - ${ep.title ?: ""}"
                    episodes.add(
                        SEpisode.create().apply {
                            name = epName
                            url = "${details.id}_${ep.id}"
                            episode_number = ep.number?.toFloat() ?: (index + 1).toFloat()
                            date_upload = ep.onlineTime ?: 0L
                            preview_url = ep.coverImage
                        },
                    )
                }
            }
        } else {
            val ep = details.episodes?.firstOrNull()
            val movieName = details.title ?: "Movie"
            episodes.add(
                SEpisode.create().apply {
                    name = movieName
                    url = "${details.id}_${ep?.id}"
                    episode_number = 1f
                    date_upload = details.publishTime ?: 0L
                    preview_url = ep?.coverImage ?: details.coverVerticalImage ?: details.coverHorizontalImage
                },
            )
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("_")
        if (parts.size != 2) return emptyList()
        val movieId = parts[0]
        val episodeId = parts[1]

        val securityKey = getSecurityKey() ?: return emptyList()
        val detailsUrl = "$baseUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&clientType=1&lang=en-US&movieId=$movieId&packageName=com.external.castle"
        val response = client.newCall(GET(detailsUrl)).execute()
        val detailsDecrypted = decryptData(response.body.string(), securityKey) ?: return emptyList()
        val details = json.decodeFromString<MovieDetailsResponse>(detailsDecrypted).data

        val ep = details.episodes?.find { it.id?.toString() == episodeId } ?: return emptyList()
        val availableTracks = ep.tracks ?: emptyList()
        val resolutions = listOf(3, 2, 1)
        val videoList = mutableListOf<Video>()

        val hasIndividualVideo = availableTracks.any { it.existIndividualVideo == true }

        if (!hasIndividualVideo && availableTracks.isNotEmpty()) {
            val firstTrack = availableTracks.first()
            val languageId = firstTrack.languageId ?: return emptyList()
            val allLanguageNames = availableTracks.mapNotNull { it.languageName ?: it.abbreviate }.joinToString(", ")

            val videos = resolutions.parallelCatchingMapNotNull { resolution ->
                val videoUrl = "$baseUrl/film-api/v2.0.1/movie/getVideo2?clientType=1&packageName=com.external.castle&channel=IndiaA&lang=en-US"
                val postBody = """
                    {
                      "mode": "1",
                      "appMarket": "GuanWang",
                      "clientType": "1",
                      "woolUser": "false",
                      "apkSignKey": "ED0955EB04E67A1D9F3305B95454FED485261475",
                      "androidVersion": "13",
                      "movieId": "$movieId",
                      "episodeId": "$episodeId",
                      "isNewUser": "true",
                      "resolution": "$resolution",
                      "packageName": "com.external.castle"
                    }
                """.trimIndent()

                val req = Request.Builder()
                    .url(videoUrl)
                    .post(postBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val videoResponse = client.newCall(req).execute()
                val encryptedData = videoResponse.body.string()
                if (encryptedData.isBlank()) return@parallelCatchingMapNotNull null

                val decryptedJson = decryptData(encryptedData, securityKey) ?: return@parallelCatchingMapNotNull null
                val videoData = json.decodeFromString<VideoResponse>(decryptedJson).data

                if (videoData.videoUrl != null && videoData.permissionDenied != true) {
                    val qualityName = when (resolution) {
                        3 -> "1080p"
                        2 -> "720p"
                        1 -> "480p"
                        else -> "${resolution}p"
                    }

                    val subtitleTracks = videoData.subtitles?.mapNotNull { subtitle ->
                        val subUrl = subtitle.url
                        if (subUrl.isNullOrBlank()) {
                            null
                        } else {
                            Track(subUrl, subtitle.title ?: subtitle.abbreviate ?: "Unknown")
                        }
                    } ?: emptyList()

                    Video(
                        videoUrl = videoData.videoUrl,
                        videoTitle = "$allLanguageNames - $qualityName",
                        headers = Headers.Builder().add("Referer", baseUrl).build(),
                        subtitleTracks = subtitleTracks,
                    )
                } else {
                    null
                }
            }
            videoList.addAll(videos)
        } else {
            val videos = availableTracks.flatMap { track ->
                val languageId = track.languageId ?: return@flatMap emptyList()
                val languageName = track.languageName ?: track.abbreviate ?: "Unknown"

                resolutions.parallelCatchingMapNotNull { resolution ->
                    val videoUrl = "$baseUrl/film-api/v2.0.1/movie/getVideo2?clientType=1&packageName=com.external.castle&channel=IndiaA&lang=en-US"
                    val postBody = """
                        {
                          "mode": "1",
                          "appMarket": "GuanWang",
                          "clientType": "1",
                          "woolUser": "false",
                          "apkSignKey": "ED0955EB04E67A1D9F3305B95454FED485261475",
                          "androidVersion": "13",
                          "languageId": "$languageId",
                          "movieId": "$movieId",
                          "episodeId": "$episodeId",
                          "isNewUser": "true",
                          "resolution": "$resolution",
                          "packageName": "com.external.castle"
                        }
                    """.trimIndent()

                    val req = Request.Builder()
                        .url(videoUrl)
                        .post(postBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val videoResponse = client.newCall(req).execute()
                    val encryptedData = videoResponse.body.string()
                    if (encryptedData.isBlank()) return@parallelCatchingMapNotNull null

                    val decryptedJson = decryptData(encryptedData, securityKey) ?: return@parallelCatchingMapNotNull null
                    val videoData = json.decodeFromString<VideoResponse>(decryptedJson).data

                    if (videoData.videoUrl != null && videoData.permissionDenied != true) {
                        val qualityName = when (resolution) {
                            3 -> "1080p"
                            2 -> "720p"
                            1 -> "480p"
                            else -> "${resolution}p"
                        }

                        val subtitleTracks = videoData.subtitles?.mapNotNull { subtitle ->
                            val subUrl = subtitle.url
                            if (subUrl.isNullOrBlank()) {
                                null
                            } else {
                                Track(subUrl, subtitle.title ?: subtitle.abbreviate ?: "Unknown")
                            }
                        } ?: emptyList()

                        Video(
                            videoUrl = videoData.videoUrl,
                            videoTitle = "$languageName - $qualityName",
                            headers = Headers.Builder().add("Referer", baseUrl).build(),
                            subtitleTracks = subtitleTracks,
                        )
                    } else {
                        null
                    }
                }
            }
            videoList.addAll(videos)
        }

        return videoList
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_DOMAIN,
            default = "https://api.hlowb.com",
            title = "API Domain",
            summary = "The domain of Castle TV API (default: https://api.hlowb.com)",
        )
        screen.addEditTextPreference(
            key = PREF_SUFFIX,
            default = "T!BgJB",
            title = "Decryption Key Suffix",
            summary = "Suffix appended to decoded security key before AES decryption (default: T!BgJB)",
        )
    }

    companion object {
        private const val PREF_DOMAIN = "pref_domain"
        private const val PREF_SUFFIX = "pref_suffix"
    }
}
