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
        get() = preferences.getString(PREF_SUFFIX, "w4rQ=n") ?: "w4rQ=n"

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

    private suspend fun fetchPage(page: Int): List<SAnime> {
        val securityKey = getSecurityKey() ?: return emptyList()
        val url = "$baseUrl/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=1001&mode=1&packageName=com.external.castle&page=$page&size=17"
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
        val list = mutableListOf<SAnime>()
        decryptedResponse.data.rows?.forEach { row ->
            row.contents?.forEach { content ->
                val title = content.title ?: return@forEach
                val id = content.redirectId?.toString() ?: return@forEach
                val coverImg = content.coverImage ?: return@forEach
                list.add(
                    SAnime.create().apply {
                        this.name = title
                        this.url = id
                        thumbnail_url = coverImg
                    },
                )
            }
        }
        return list.distinctBy { it.url }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val list = fetchPage(page)
        return AnimesPage(list, list.isNotEmpty())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return AnimesPage(emptyList(), false)
        val securityKey = getSecurityKey() ?: return AnimesPage(emptyList(), false)
        val searchUrl = "$baseUrl/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=1&clientType=1&keyword=${URLEncoder.encode(query, "UTF-8")}&lang=en-US&mode=1&packageName=com.external.castle&page=$page&size=30"

        val response = client.newCall(GET(searchUrl)).execute()
        val encryptedData = response.body.string()
        if (encryptedData.isBlank()) return AnimesPage(emptyList(), false)

        val decryptedJson = decryptData(encryptedData, securityKey) ?: return AnimesPage(emptyList(), false)
        val searchResponse = json.decodeFromString<SearchApiResponse>(decryptedJson)
        val searchData = searchResponse.data

        var typeFilterValue = 0
        for (filter in filters) {
            if (filter is TypeFilter) {
                typeFilterValue = filter.state
            }
        }

        val list = searchData.rows?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val id = item.id?.toString() ?: return@mapNotNull null
            val posterUrl = item.coverVerticalImage ?: item.coverHorizontalImage ?: ""
            val movieType = item.movieType ?: 2

            if (typeFilterValue == 1 && movieType != 2) {
                return@mapNotNull null
            }
            if (typeFilterValue == 2 && movieType == 2) {
                return@mapNotNull null
            }

            SAnime.create().apply {
                this.name = title
                this.url = id
                thumbnail_url = posterUrl
            }
        } ?: emptyList()

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

        val animeName = anime.name
        val animeThumbnail = anime.thumbnail_url
        return SAnime.create().apply {
            this.name = details.title ?: animeName
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
                                    episodes.add(
                                        SEpisode.create().apply {
                                            this.name = "S$seasonNumber E${ep.number ?: 0} - ${ep.title ?: ""}"
                                            url = "${seasonId}_${ep.id}"
                                            episode_number = ep.number?.toFloat() ?: 0f
                                            date_upload = ep.onlineTime ?: 0L
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
                    episodes.add(
                        SEpisode.create().apply {
                            this.name = "Episode ${ep.number ?: (index + 1)} - ${ep.title ?: ""}"
                            url = "${details.id}_${ep.id}"
                            episode_number = ep.number?.toFloat() ?: (index + 1).toFloat()
                            date_upload = ep.onlineTime ?: 0L
                        },
                    )
                }
            }
        } else {
            val ep = details.episodes?.firstOrNull()
            episodes.add(
                SEpisode.create().apply {
                    this.name = details.title ?: "Movie"
                    url = "${details.id}_${ep?.id}"
                    episode_number = 1f
                    date_upload = details.publishTime ?: 0L
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

            for (resolution in resolutions) {
                try {
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
                    if (encryptedData.isBlank()) continue

                    val decryptedJson = decryptData(encryptedData, securityKey) ?: continue
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

                        videoList.add(
                            Video(
                                videoUrl = videoData.videoUrl,
                                videoTitle = "$allLanguageNames - $qualityName",
                                headers = Headers.Builder().add("Referer", baseUrl).build(),
                                subtitleTracks = subtitleTracks,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    // Skip failed resolutions
                }
            }
        } else {
            for (track in availableTracks) {
                val languageId = track.languageId ?: continue
                val languageName = track.languageName ?: track.abbreviate ?: "Unknown"

                for (resolution in resolutions) {
                    try {
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
                        if (encryptedData.isBlank()) continue

                        val decryptedJson = decryptData(encryptedData, securityKey) ?: continue
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

                            videoList.add(
                                Video(
                                    videoUrl = videoData.videoUrl,
                                    videoTitle = "$languageName - $qualityName",
                                    headers = Headers.Builder().add("Referer", baseUrl).build(),
                                    subtitleTracks = subtitleTracks,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        // Skip failed resolutions
                    }
                }
            }
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
            default = "w4rQ=n",
            title = "Decryption Key Suffix",
            summary = "Suffix appended to decoded security key before AES decryption (default: w4rQ=n)",
        )
    }

    companion object {
        private const val PREF_DOMAIN = "pref_domain"
        private const val PREF_SUFFIX = "pref_suffix"
    }
}
