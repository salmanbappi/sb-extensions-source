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
import extensions.utils.addListPreference
import extensions.utils.parseAs
import extensions.utils.toJsonBody
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingMapNotNull
import okhttp3.Headers
import okhttp3.Request
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CastleTv : Source() {

    override val name = "Castle TV"
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT
    override val lang = "all"
    override val supportsLatest = true

    private val suffix: String
        get() = preferences.getString(PREF_SUFFIX, DEFAULT_SUFFIX) ?: DEFAULT_SUFFIX

    private val clientPackage: String
        get() = preferences.getString(PREF_PACKAGE_NAME, PACKAGE_TV) ?: PACKAGE_TV

    private val proxyServer by lazy {
        CastleProxyServer().apply { start() }
    }

    private val expansionCache = ConcurrentHashMap<String, String>()

    private fun getOrGenerateDeviceGuid(): String {
        var guid = preferences.getString(PREF_DEVICE_GUID, null)
        if (guid.isNullOrBlank()) {
            guid = generateRandomGuid()
            preferences.edit().putString(PREF_DEVICE_GUID, guid).apply()
        }
        return guid
    }

    private fun rotateDeviceGuid(): String {
        val newGuid = generateRandomGuid()
        preferences.edit().putString(PREF_DEVICE_GUID, newGuid).apply()
        return newGuid
    }

    private fun generateRandomGuid(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

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
        val text = client.newCall(GET(url)).execute().use { it.body.string() }
        val securityResponse = text.parseAs<SecurityKeyResponse>()
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
        val url = "$baseUrl/film-api/v0.1/category/home?channel=IndiaA&clientType=1&lang=en-US&locationId=$locationId&mode=1&packageName=$clientPackage&page=$page&size=50"
        val text = client.newCall(GET(url)).execute().use { it.body.string() }
        val apiResponse = try {
            text.parseAs<CastleApiResponse>()
        } catch (e: Exception) {
            CastleApiResponse(200, "OK", text)
        }
        val encryptedData = apiResponse.data ?: return emptyList()
        val decryptedJson = decryptData(encryptedData, securityKey) ?: return emptyList()
        val decryptedResponse = decryptedJson.parseAs<DecryptedResponse>()
        val filteredItems = mutableListOf<ContentItem>()
        val sdf = java.text.SimpleDateFormat("yyyy", Locale.US)

        decryptedResponse.data?.rows?.forEach { row ->
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

        val searchUrl = "$baseUrl/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=1&keyword=${URLEncoder.encode(query, "UTF-8")}&lang=en-US&mode=1&packageName=$clientPackage&page=$page&size=30$tagParam"

        val encryptedData = client.newCall(GET(searchUrl)).execute().use { it.body.string() }
        if (encryptedData.isBlank()) return AnimesPage(emptyList(), false)

        val decryptedJson = decryptData(encryptedData, securityKey) ?: return AnimesPage(emptyList(), false)
        val searchResponse = decryptedJson.parseAs<SearchApiResponse>()
        val searchData = searchResponse.data

        val sdf = java.text.SimpleDateFormat("yyyy", Locale.US)
        var searchRows = searchData?.rows ?: emptyList()

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
        anime.initialized = true
        val movieId = anime.url
        val securityKey = getSecurityKey() ?: return anime
        val detailsUrl = "$baseUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&lang=en-US&movieId=$movieId&packageName=$clientPackage"

        val encryptedData = client.newCall(GET(detailsUrl)).execute().use { it.body.string() }
        if (encryptedData.isBlank()) return anime

        val decryptedJson = decryptData(encryptedData, securityKey) ?: return anime
        val detailsResponse = decryptedJson.parseAs<MovieDetailsResponse>()
        val details = detailsResponse.data ?: return anime

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
        val detailsUrl = "$baseUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&lang=en-US&movieId=$movieId&packageName=$clientPackage"

        val encryptedData = client.newCall(GET(detailsUrl)).execute().use { it.body.string() }
        if (encryptedData.isBlank()) return emptyList()

        val decryptedJson = decryptData(encryptedData, securityKey) ?: return emptyList()
        val detailsResponse = decryptedJson.parseAs<MovieDetailsResponse>()
        val details = detailsResponse.data ?: return emptyList()

        val episodes = mutableListOf<SEpisode>()

        val isSeriesLike = details.movieType == 1 || details.movieType == 3 || details.movieType == 5 || (details.episodes?.size ?: 0) > 1

        if (isSeriesLike) {
            if (details.seasons != null && details.seasons.size > 1) {
                val seasonEpisodesResult = details.seasons.parallelCatchingFlatMap { season ->
                    val seasonId = season.movieId?.toString() ?: return@parallelCatchingFlatMap emptyList<SEpisode>()
                    val seasonNumber = season.number ?: return@parallelCatchingFlatMap emptyList<SEpisode>()

                    val seasonUrl = "$baseUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&lang=en-US&movieId=$seasonId&packageName=$clientPackage"
                    val seasonEncrypted = client.newCall(GET(seasonUrl)).execute().use { it.body.string() }
                    if (seasonEncrypted.isNotBlank()) {
                        val seasonDecrypted = decryptData(seasonEncrypted, securityKey)
                        if (seasonDecrypted != null) {
                            val seasonDetails = seasonDecrypted.parseAs<MovieDetailsResponse>().data
                            seasonDetails?.episodes?.map { ep ->
                                val epName = "S$seasonNumber E${ep.number ?: 0} - ${ep.title ?: ""}"
                                SEpisode.create().apply {
                                    name = epName
                                    url = "${seasonId}_${ep.id}"
                                    episode_number = ep.number?.toFloat() ?: 0f
                                    date_upload = ep.onlineTime ?: 0L
                                    preview_url = ep.coverImage
                                }
                            } ?: emptyList<SEpisode>()
                        } else {
                            emptyList<SEpisode>()
                        }
                    } else {
                        emptyList<SEpisode>()
                    }
                }
                episodes.addAll(seasonEpisodesResult)
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

    private suspend fun fetchVideoData(
        movieId: String,
        episodeId: String,
        resolution: Int,
        languageId: Int?,
        securityKey: String,
        retryOnBlacklist: Boolean = true,
    ): VideoData? {
        val deviceGuid = getOrGenerateDeviceGuid()
        val packageName = clientPackage
        val signKey = if (packageName == PACKAGE_TV) SIGN_KEY_TV else SIGN_KEY_MOBILE
        val videoUrl = "$baseUrl/film-api/v2.0.1/movie/getVideo2?clientType=1&packageName=$packageName&channel=IndiaA&lang=en-US"
        val languageField = if (languageId != null) "\n  \"languageId\": \"$languageId\"," else ""
        val postBody = """
            {
              "mode": "1",
              "appMarket": "GuanWang",
              "clientType": "1",
              "woolUser": "false",
              "apkSignKey": "$signKey",
              "androidVersion": "13",$languageField
              "movieId": "$movieId",
              "episodeId": "$episodeId",
              "isNewUser": "true",
              "resolution": "$resolution",
              "packageName": "$packageName",
              "deviceId": "$deviceGuid"
            }
        """.trimIndent()

        val req = Request.Builder()
            .url(videoUrl)
            .post(postBody.toJsonBody())
            .build()

        val encryptedData = client.newCall(req).execute().use { it.body.string() }
        if (encryptedData.isBlank()) return null

        val decryptedJson = decryptData(encryptedData, securityKey) ?: return null
        val videoData = try {
            decryptedJson.parseAs<VideoResponse>().data
        } catch (e: Exception) {
            null
        } ?: return null

        // Anti-blacklist recovery: if server flags device, roll a fresh 18-byte GUID and retry once
        if (videoData.inBlacklist == true && retryOnBlacklist) {
            rotateDeviceGuid()
            return fetchVideoData(movieId, episodeId, resolution, languageId, securityKey, retryOnBlacklist = false)
        }

        return videoData
    }

    /**
     * Reconstructs the complete HLS playlist by discovering and appending unlisted CDN segments
     * past the preview limit (inspired by Morphe's PreviewPlaylist bypass).
     */
    private fun expandPreviewPlaylist(previewUrl: String): String? = try {
        expansionCache[previewUrl]?.let { return it }

        val req = Request.Builder().url(previewUrl).build()
        val m3u8Text = client.newCall(req).execute().use { it.body.string() }
        val lines = m3u8Text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val segmentPattern = Pattern.compile("^(.*/)(\\d+)(\\.[^/?]+(?:\\?.*)?)$")

        var lastNum = -1
        var prefix = ""
        var suffix = ""
        val durations = mutableListOf<Double>()

        lines.forEachIndexed { i, line ->
            if (!line.startsWith("#")) {
                val matcher = segmentPattern.matcher(line)
                if (matcher.matches()) {
                    prefix = matcher.group(1) ?: ""
                    lastNum = matcher.group(2)?.toIntOrNull() ?: -1
                    suffix = matcher.group(3) ?: ""
                    if (i > 0 && lines[i - 1].startsWith("#EXTINF:")) {
                        val durStr = lines[i - 1].substringAfter("#EXTINF:").substringBefore(",")
                        durStr.toDoubleOrNull()?.let { durations.add(it) }
                    }
                }
            }
        }

        if (lastNum < 0 || prefix.isBlank()) {
            null
        } else {
            val avgDuration = if (durations.isNotEmpty()) durations.average() else 3.0

            fun checkExists(n: Int): Boolean = try {
                val checkUrl = "$prefix$n$suffix"
                val headReq = Request.Builder()
                    .url(checkUrl)
                    .header("Range", "bytes=0-0")
                    .head()
                    .build()
                client.newCall(headReq).execute().use { it.code in 200..299 }
            } catch (_: Exception) {
                false
            }

            var low = lastNum
            var high = lastNum + 500
            while (checkExists(high)) {
                low = high
                high *= 2
            }

            while (low < high - 1) {
                val mid = (low + high) ushr 1
                if (checkExists(mid)) {
                    low = mid
                } else {
                    high = mid
                }
            }

            val lastAvailable = low
            if (lastAvailable <= lastNum) {
                null
            } else {
                val sb = java.lang.StringBuilder()
                for (line in lines) {
                    if (line != "#EXT-X-ENDLIST") {
                        sb.append(line).append("\n")
                    }
                }
                val durationFormatted = String.format(Locale.US, "%.6f", avgDuration)
                for (n in (lastNum + 1)..lastAvailable) {
                    sb.append("#EXTINF:").append(durationFormatted).append(",\n")
                    sb.append(prefix).append(n).append(suffix).append("\n")
                }
                sb.append("#EXT-X-ENDLIST\n")
                val result = sb.toString()
                expansionCache[previewUrl] = result
                result
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun resolveStreamUrl(videoData: VideoData): Pair<String, String> {
        val rawUrl = videoData.videoUrl ?: return "" to ""
        val isPreview = videoData.isPreview == true || rawUrl.contains("preview", ignoreCase = true)

        if (isPreview) {
            val expandedM3u8 = expandPreviewPlaylist(rawUrl)
            if (expandedM3u8 != null && proxyServer.port > 0) {
                val streamId = UUID.randomUUID().toString()
                val localUrl = proxyServer.registerPlaylist(streamId, expandedM3u8)
                return localUrl to " [Unlocked Full]"
            }
            return rawUrl to " [Preview/VIP]"
        }

        return rawUrl to ""
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("_")
        if (parts.size != 2) return emptyList()
        val movieId = parts[0]
        val episodeId = parts[1]

        val securityKey = getSecurityKey() ?: return emptyList()
        val detailsUrl = "$baseUrl/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&lang=en-US&movieId=$movieId&packageName=$clientPackage"
        val responseBody = client.newCall(GET(detailsUrl)).execute().use { it.body.string() }
        val detailsDecrypted = decryptData(responseBody, securityKey) ?: return emptyList()
        val details = detailsDecrypted.parseAs<MovieDetailsResponse>().data ?: return emptyList()

        val ep = details.episodes?.find { it.id?.toString() == episodeId } ?: return emptyList()
        val availableTracks = ep.tracks ?: emptyList()
        val resolutions = listOf(3, 2, 1)
        val videoList = mutableListOf<Video>()

        val hasIndividualVideo = availableTracks.any { it.existIndividualVideo == true }

        if (!hasIndividualVideo && availableTracks.isNotEmpty()) {
            val allLanguageNames = availableTracks.mapNotNull { it.languageName ?: it.abbreviate }.joinToString(", ")

            val videos = resolutions.parallelCatchingMapNotNull { resolution ->
                val videoData = fetchVideoData(movieId, episodeId, resolution, null, securityKey) ?: return@parallelCatchingMapNotNull null

                if (videoData.videoUrl != null && videoData.permissionDenied != true) {
                    val qualityName = when (resolution) {
                        3 -> "1080p"
                        2 -> "720p"
                        1 -> "480p"
                        else -> "${resolution}p"
                    }
                    val (streamUrl, tag) = resolveStreamUrl(videoData)
                    if (streamUrl.isBlank()) return@parallelCatchingMapNotNull null

                    val subtitleTracks = videoData.subtitles?.mapNotNull { subtitle ->
                        val subUrl = subtitle.url
                        if (subUrl.isNullOrBlank()) {
                            null
                        } else {
                            Track(subUrl, subtitle.title ?: subtitle.abbreviate ?: "Unknown")
                        }
                    } ?: emptyList()

                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "$allLanguageNames - $qualityName$tag",
                        headers = Headers.Builder().add("Referer", baseUrl).build(),
                        subtitleTracks = subtitleTracks,
                    )
                } else {
                    null
                }
            }
            videoList.addAll(videos)
        } else {
            val videos = availableTracks.parallelCatchingFlatMap { track ->
                val languageId = track.languageId ?: return@parallelCatchingFlatMap emptyList()
                val languageName = track.languageName ?: track.abbreviate ?: "Unknown"

                resolutions.parallelCatchingMapNotNull { resolution ->
                    val videoData = fetchVideoData(movieId, episodeId, resolution, languageId, securityKey) ?: return@parallelCatchingMapNotNull null

                    if (videoData.videoUrl != null && videoData.permissionDenied != true) {
                        val qualityName = when (resolution) {
                            3 -> "1080p"
                            2 -> "720p"
                            1 -> "480p"
                            else -> "${resolution}p"
                        }
                        val (streamUrl, tag) = resolveStreamUrl(videoData)
                        if (streamUrl.isBlank()) return@parallelCatchingMapNotNull null

                        val subtitleTracks = videoData.subtitles?.mapNotNull { subtitle ->
                            val subUrl = subtitle.url
                            if (subUrl.isNullOrBlank()) {
                                null
                            } else {
                                Track(subUrl, subtitle.title ?: subtitle.abbreviate ?: "Unknown")
                            }
                        } ?: emptyList()

                        Video(
                            videoUrl = streamUrl,
                            videoTitle = "$languageName - $qualityName$tag",
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
        screen.addListPreference(
            key = PREF_PACKAGE_NAME,
            default = PACKAGE_TV,
            title = "Client App Profile",
            summary = "Select API client profile (Castle for TV vs Castle Mobile)",
            entries = listOf("Castle for TV ($PACKAGE_TV)", "Castle Mobile ($PACKAGE_MOBILE)"),
            entryValues = listOf(PACKAGE_TV, PACKAGE_MOBILE),
        )
        screen.addEditTextPreference(
            key = PREF_DOMAIN,
            default = PREF_BASE_URL_DEFAULT,
            title = "API Domain",
            summary = "The domain of Castle TV API (default: $PREF_BASE_URL_DEFAULT)",
        )
        screen.addEditTextPreference(
            key = PREF_SUFFIX,
            default = DEFAULT_SUFFIX,
            title = "Decryption Key Suffix",
            summary = "Suffix appended to decoded security key before AES decryption (default: $DEFAULT_SUFFIX)",
        )
    }

    companion object {
        private const val PREF_BASE_URL_DEFAULT = "https://api.hlowb.com"
        private const val DEFAULT_SUFFIX = "T!BgJB"
        private const val PREF_DOMAIN = "pref_domain"
        private const val PREF_SUFFIX = "pref_suffix"
        private const val PREF_PACKAGE_NAME = "pref_package_name"
        private const val PREF_DEVICE_GUID = "pref_device_guid"

        private const val PACKAGE_TV = "com.gxnet.castle.indiatv"
        private const val PACKAGE_MOBILE = "com.external.castle"

        private const val SIGN_KEY_TV = "17c6e44afc6b4c627fecdd30c27db7ad97cda41f728b74160ad55377dc4c7078"
        private const val SIGN_KEY_MOBILE = "ED0955EB04E67A1D9F3305B95454FED485261475"
    }
}

private class CastleProxyServer {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val playlists = ConcurrentHashMap<String, String>()

    val port: Int
        get() = serverSocket?.let { if (it.isClosed) 0 else it.localPort } ?: 0

    fun start() {
        if (serverSocket?.isClosed == false) return
        try {
            serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            executor.execute {
                while (serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun registerPlaylist(id: String, content: String): String {
        playlists[id] = content
        return "http://127.0.0.1:$port/playlist/$id.m3u8"
    }

    private fun handleSocket(socket: Socket) {
        socket.use { s ->
            val input: InputStream = s.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") return
            val path = parts[1]
            if (path.startsWith("/playlist/")) {
                val id = path.substringAfter("/playlist/").substringBefore(".m3u8")
                val content = playlists[id]
                if (content != null) {
                    val bytes = content.toByteArray(StandardCharsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    val out = s.getOutputStream()
                    out.write(response.toByteArray(StandardCharsets.UTF_8))
                    out.write(bytes)
                    out.flush()
                } else {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().write(notFound.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
    }
}
