package eu.kanade.tachiyomi.lib.dailymotionextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class DailymotionExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val DAILYMOTION_URL = "https://www.dailymotion.com"
        private const val GRAPHQL_URL = "https://graphql.api.dailymotion.com"
        private val TS_REGEX = Regex(""""ts"\s*:\s*(\d+)""")
        private val V1ST_REGEX = Regex(""""v1st"\s*:\s*"([^"]+)"""")
        private val DMVK_REGEX = Regex(""""dmvk"\s*:\s*"([^"]+)"""")
    }

    private fun headersBuilder(block: Headers.Builder.() -> Unit = {}) = headers.newBuilder()
        .set("Accept", "*/*")
        .set("Referer", "$DAILYMOTION_URL/")
        .set("Origin", DAILYMOTION_URL)
        .apply { block() }
        .build()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = "Dailymotion - ", baseUrl: String = "", password: String? = null): List<Video> {
        val htmlString = client.newCall(GET(url, headersBuilder())).execute().body.string()

        val ts = TS_REGEX.find(htmlString)?.groupValues?.get(1) ?: ""
        val v1st = V1ST_REGEX.find(htmlString)?.groupValues?.get(1) ?: ""

        val videoQuery = url.toHttpUrl().run {
            queryParameter("video") ?: pathSegments.last()
        }

        val jsonUrl = "$DAILYMOTION_URL/player/metadata/video/$videoQuery?locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val parsed = client.newCall(GET(jsonUrl, headersBuilder())).execute().parseAs<DailyQuality>()

        return when {
            parsed.qualities != null && parsed.error == null -> videosFromDailyResponse(parsed, prefix, ts, v1st)

            parsed.error?.type == "password_protected" && parsed.id != null -> {
                videosFromProtectedUrl(url, prefix, parsed.id, htmlString, ts, v1st, baseUrl, password)
            }

            else -> emptyList()
        }
    }

    private fun videosFromProtectedUrl(
        url: String,
        prefix: String,
        videoId: String,
        htmlString: String,
        ts: String,
        v1st: String,
        baseUrl: String,
        password: String?,
    ): List<Video> {
        val postUrl = "$GRAPHQL_URL/oauth/token"
        val clientId = htmlString.substringAfter("client_id\":\"").substringBefore('"')
        val clientSecret = htmlString.substringAfter("client_secret\":\"").substringBefore('"')
        val scope = htmlString.substringAfter("client_scope\":\"").substringBefore('"')

        val tokenBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("traffic_segment", ts)
            .add("visitor_id", v1st)
            .add("grant_type", "client_credentials")
            .add("scope", scope)
            .build()

        val tokenResponse = client.newCall(POST(postUrl, headersBuilder(), tokenBody)).execute()
        val tokenParsed = tokenResponse.parseAs<TokenResponse>()

        val idUrl = "$GRAPHQL_URL/"
        val idHeaders = headersBuilder {
            set("Accept", "application/json, text/plain, */*")
            set("Authorization", "${tokenParsed.token_type} ${tokenParsed.access_token}")
        }

        val idData = """
            {
               "query":"query playerPasswordQuery(${'$'}videoId:String!,${'$'}password:String!){video(xid:${'$'}videoId,password:${'$'}password){id xid}}",
               "variables":{
                  "videoId":"$videoId",
                  "password":"$password"
               }
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val idResponse = client.newCall(POST(idUrl, idHeaders, idData)).execute()
        val idParsed = idResponse.parseAs<ProtectedResponse>().data.video

        val dmvk = DMVK_REGEX.find(htmlString)?.groupValues?.get(1) ?: ""
        val getVideoIdUrl = "$DAILYMOTION_URL/player/metadata/video/${idParsed.xid}?embedder=${"$baseUrl/"}&locale=en-US&dmV1st=$v1st&dmTs=$ts&is_native_app=0"
        val getVideoIdHeaders = headersBuilder {
            set("Cookie", "dmvk=$dmvk; ts=$ts; v1st=$v1st; usprivacy=1---; client_token=${tokenParsed.access_token}")
            set("Referer", url)
        }

        val parsed = client.newCall(GET(getVideoIdUrl, getVideoIdHeaders)).execute()
            .parseAs<DailyQuality>()

        return videosFromDailyResponse(parsed, prefix, ts, v1st, getVideoIdHeaders)
    }

    private fun videosFromDailyResponse(
        parsed: DailyQuality,
        prefix: String,
        ts: String,
        v1st: String,
        fetchHeaders: Headers? = null,
    ): List<Video> {
        val masterUrl = parsed.qualities?.auto?.firstOrNull()?.url
            ?: return emptyList()

        val subtitleList = parsed.subtitles?.data?.map {
            Track(it.urls.first(), it.label)
        } ?: emptyList()

        val masterHeaders = (fetchHeaders?.newBuilder() ?: headers.newBuilder())
            .set("Accept", "*/*")
            .set("Referer", "$DAILYMOTION_URL/")
            .set("Cookie", "ts=$ts; v1st=$v1st")
            .build()

        val masterPlaylist = runCatching {
            client.newCall(GET(masterUrl, masterHeaders)).execute().body.string()
        }.getOrNull()

        if (!masterPlaylist.isNullOrBlank() && masterPlaylist.contains("#EXT-X-STREAM-INF")) {
            if (masterPlaylist.contains("#EXT-X-MEDIA:TYPE=AUDIO")) {
                return parseMasterPlaylistWithAudio(masterPlaylist, prefix, subtitleList)
            }
        }

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            masterHeadersGen = { _, _ -> masterHeaders },
            videoHeadersGen = { _, _, _ -> headers },
            subtitleList = subtitleList,
            videoNameGen = { "$prefix$it" },
        )
    }

    private fun parseMasterPlaylistWithAudio(
        masterPlaylist: String,
        prefix: String,
        subtitleList: List<Track>,
    ): List<Video> {
        val lines = masterPlaylist.lines()
        val mediaLines = lines.filter {
            it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") || it.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")
        }

        val videos = mutableListOf<Video>()
        val seenQualities = mutableSetOf<String>()

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val nextUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                if (nextUrl.startsWith("#") || nextUrl.isBlank()) continue

                val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                val height = resMatch?.groupValues?.get(1)?.toIntOrNull()
                val nameMatch = Regex("""NAME="([^"]+)"""").find(line)
                val qualityName = nameMatch?.groupValues?.get(1)

                val qualityLabel = when {
                    height != null && height >= 2160 -> "2160p (4K)"
                    height != null -> "${height}p"
                    !qualityName.isNullOrBlank() -> "${qualityName}p"
                    else -> "Video"
                }

                if (qualityLabel in seenQualities) continue
                seenQualities.add(qualityLabel)

                val miniMaster = buildString {
                    append("#EXTM3U\n")
                    append("#EXT-X-VERSION:7\n")
                    append("#EXT-X-INDEPENDENT-SEGMENTS\n")
                    mediaLines.forEach { append(it).append("\n") }
                    append(line).append("\n")
                    append(nextUrl).append("\n")
                }

                val dataUri = "data:application/vnd.apple.mpegurl;base64," +
                    Base64.encodeToString(miniMaster.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                videos.add(
                    Video(
                        videoUrl = dataUri,
                        videoTitle = "$prefix$qualityLabel",
                        headers = headers,
                        subtitleTracks = subtitleList,
                    ),
                )
            }
        }

        if (videos.isNotEmpty()) {
            val autoDataUri = "data:application/vnd.apple.mpegurl;base64," +
                Base64.encodeToString(masterPlaylist.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            videos.add(
                0,
                Video(
                    videoUrl = autoDataUri,
                    videoTitle = "${prefix}Auto (Adaptive)",
                    headers = headers,
                    subtitleTracks = subtitleList,
                ),
            )
            return videos
        }

        return playlistUtils.extractFromHls(
            playlistUrl = "",
            subtitleList = subtitleList,
            videoNameGen = { "$prefix$it" },
        )
    }
}
