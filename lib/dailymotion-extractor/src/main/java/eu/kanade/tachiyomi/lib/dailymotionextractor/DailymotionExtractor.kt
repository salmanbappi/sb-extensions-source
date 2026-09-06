package eu.kanade.tachiyomi.lib.dailymotionextractor

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

        // The master M3U8 URL has a short-lived sec= signed token.
        // Fetch and parse the master NOW (server-side) so we can extract the
        // long-lived variant and audio playlist URLs from vod3.cf.dmcdn.net,
        // which are accessible without any special headers or cookies.
        //
        // NOTE: do NOT expose the master URL itself as an "Auto/Adaptive"
        // Video entry — the in-app player would run ABR and oscillate between
        // the 1080p rendition (6+ Mbps) and lower ones on throttled mobile
        // links, which users experience as rhythmic freezing. Fixed-quality
        // variant entries let the player sustain a single bitrate instead.
        val masterHeaders = (fetchHeaders?.newBuilder() ?: headers.newBuilder())
            .set("Accept", "*/*")
            .set("Referer", "$DAILYMOTION_URL/")
            .set("Cookie", "ts=$ts; v1st=$v1st")
            .build()

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            masterHeadersGen = { _, _ -> masterHeaders },
            // Variant/audio URLs at vod3.cf.dmcdn.net need no special auth
            videoHeadersGen = { _, _, _ -> headers },
            subtitleList = subtitleList,
            videoNameGen = { "$prefix$it" },
        )
    }
}
