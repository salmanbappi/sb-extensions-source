package eu.kanade.tachiyomi.animeextension.en.twodhive

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.UrlUtils
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class TwoDHiveExtractors(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val json: Json,
    private val playlistUtils: PlaylistUtils,
    private val proxy: TwoDHiveProxy,
) {
    private val okruExtractor by lazy { OkruExtractor(client) }

    @Volatile
    private var cachedCapToken: String? = null

    @Volatile
    private var cachedCapTokenExpiry: Long = 0L

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())
    private inline fun <reified T> String.parseAs(): T = json.decodeFromString(this)

    private fun streamHeaders(referer: String, origin: String? = null): Headers {
        return headers.newBuilder().apply {
            set("Referer", referer)
            if (origin != null) set("Origin", origin)
            set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
        }.build()
    }

    // ======================== MegaPlay Resolver ========================
    fun extractMegaPlay(malId: String, epNum: String, type: String): List<Video> {
        val streamPageUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$type"
        val pageHeaders = headers.newBuilder()
            .set("Referer", "https://2dhive.com/")
            .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .build()

        val pageHtml = runCatching {
            client.newCall(GET(streamPageUrl, pageHeaders)).execute().use { it.body.string() }
        }.getOrNull() ?: return emptyList()

        val streamId = Regex("""data-id=["'](\d+)["']""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""<title>File (\d+)""").find(pageHtml)?.groupValues?.get(1)
            ?: return emptyList()

        val sourcesUrl = "https://megaplay.buzz/stream/getSources?id=$streamId&id=$streamId"
        val sourcesHeaders = headers.newBuilder()
            .set("Referer", streamPageUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .build()

        val sourcesDto = runCatching {
            client.newCall(GET(sourcesUrl, sourcesHeaders)).execute().use { resp ->
                resp.parseAs<MegaPlaySourcesDto>()
            }
        }.getOrNull() ?: return emptyList()

        val masterUrl = sourcesDto.sources?.file?.takeIf { it.isNotBlank() }
            ?: sourcesDto.enc?.takeIf { it.isNotBlank() }?.let { enc ->
                val decrypted = TwoDHiveCrypto.decryptMegaPlay(enc) ?: return@let null
                runCatching { json.decodeFromString<MegaPlayFileDto>(decrypted).file }.getOrNull()
            } ?: return emptyList()

        val typeTag = type.replaceFirstChar { it.uppercase() }

        val subtitleTracks = sourcesDto.tracks
            ?.filter { it.kind == "captions" && !it.file.isNullOrBlank() }
            ?.map { Track(it.file!!, it.label ?: "English") }
            ?: emptyList()

        val streamRefHeaders = streamHeaders("https://megaplay.buzz/", "https://megaplay.buzz")

        val rawVideos = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = "https://megaplay.buzz/",
                masterHeadersGen = { _, _ -> streamRefHeaders },
                videoHeadersGen = { _, _, _ -> streamRefHeaders },
                subtitleList = subtitleTracks,
                videoNameGen = { quality -> "MegaPlay - $quality ($typeTag)" },
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = masterUrl,
                    videoTitle = "MegaPlay - Auto ($typeTag)",
                    headers = streamRefHeaders,
                    subtitleTracks = subtitleTracks,
                ),
            )
        }

        return rawVideos.map { proxy.processVideo(it, "https://megaplay.buzz/") }
    }

    // ======================== BabaStream Resolver ========================
    fun extractBabaStream(malId: String, epNum: String, type: String): List<Video> {
        val embedUrl = "https://babastream.top/embed/$malId/$epNum/$type"
        val embedHeaders = headers.newBuilder()
            .set("Referer", "https://2dhive.com/")
            .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .build()

        val embedHtml = runCatching {
            client.newCall(GET(embedUrl, embedHeaders)).execute().use { it.body.string() }
        }.getOrNull() ?: return emptyList()

        val cfgJsonStr = Regex("""var\s+CFG\s*=\s*(\{.*?\});""").find(embedHtml)?.groupValues?.get(1)
            ?: return emptyList()

        val cfg = runCatching { cfgJsonStr.parseAs<BabaConfigDto>() }.getOrNull()
            ?: return emptyList()

        val pk = cfg.pk ?: return emptyList()
        val sid = cfg.sid ?: return emptyList()

        fun postApi(path: String, payloadJson: String): String? {
            val encData = TwoDHiveCrypto.encryptAesGcm(pk, payloadJson)
            val postBody = """{"s":"$sid","d":"$encData"}"""
                .toRequestBody("application/json".toMediaType())
            val reqHeaders = headers.newBuilder()
                .set("Referer", embedUrl)
                .set("Origin", "https://babastream.top")
                .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                .build()

            val (respD, isSuccess) = runCatching {
                client.newCall(POST("https://babastream.top$path", reqHeaders, postBody)).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null to false
                    val respDto = resp.parseAs<BabaResolveResponseDto>()
                    respDto.d to true
                }
            }.getOrNull() ?: (null to false)

            if (!isSuccess || respD == null) return null
            return TwoDHiveCrypto.decryptAesGcm(pk, respD)
        }

        var resolvedJson = postApi("/api/resolve", """{"ts":${System.currentTimeMillis()}}""")
        var payload = runCatching { resolvedJson?.parseAs<BabaDecryptedPayloadDto>() }.getOrNull()

        // Solve Cap challenge if server requires verification
        if (payload?.t == "error" && payload?.m == "verify" && !cfg.cap.isNullOrBlank()) {
            val redeemToken = getOrSolveCapToken(cfg.cap)
            if (!redeemToken.isNullOrBlank()) {
                val verifyJson = postApi(
                    "/api/cap-verify",
                    """{"ts":${System.currentTimeMillis()},"token":"$redeemToken","mode":"invisible"}""",
                )
                val verifyDto = runCatching { verifyJson?.parseAs<BabaVerifyResponseDto>() }.getOrNull()
                if (verifyDto?.t == "ok") {
                    resolvedJson = postApi("/api/resolve", """{"ts":${System.currentTimeMillis()}}""")
                    payload = runCatching { resolvedJson?.parseAs<BabaDecryptedPayloadDto>() }.getOrNull()
                } else {
                    val freshToken = solveCap(cfg.cap)
                    if (!freshToken.isNullOrBlank()) {
                        val vJson2 = postApi(
                            "/api/cap-verify",
                            """{"ts":${System.currentTimeMillis()},"token":"$freshToken","mode":"invisible"}""",
                        )
                        val vDto2 = runCatching { vJson2?.parseAs<BabaVerifyResponseDto>() }.getOrNull()
                        if (vDto2?.t == "ok") {
                            resolvedJson = postApi("/api/resolve", """{"ts":${System.currentTimeMillis()}}""")
                            payload = runCatching { resolvedJson?.parseAs<BabaDecryptedPayloadDto>() }.getOrNull()
                        }
                    }
                }
            }
        }

        if (payload == null) return emptyList()

        val typeTag = type.replaceFirstChar { it.uppercase() }
        val streamHeaders = streamHeaders(embedUrl, "https://babastream.top")
        val videos = mutableListOf<Video>()

        val subtitleTracks = payload.tracks?.mapNotNull { track ->
            val trackUrl = track.u?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(trackUrl, track.label ?: "English")
        } ?: emptyList()

        when (payload.t) {
            "direct" -> {
                payload.u?.takeIf { it.isNotBlank() }?.let { directUrl ->
                    val fullDirectUrl = UrlUtils.fixUrl(directUrl, "https://babastream.top")
                    val video = Video(
                        videoUrl = fullDirectUrl,
                        videoTitle = "BabaStream - Direct MP4 ($typeTag)",
                        headers = streamHeaders,
                        subtitleTracks = subtitleTracks,
                    )
                    val processed = if (fullDirectUrl.contains("fbcdn.net") || fullDirectUrl.contains("facebook.com")) {
                        proxy.processDirectVideo(video, embedUrl)
                    } else {
                        video
                    }
                    videos.add(processed)
                }
            }

            "hls" -> {
                payload.u?.takeIf { it.isNotBlank() }?.let { hlsUrl ->
                    val fullHlsUrl = UrlUtils.fixUrl(hlsUrl, "https://babastream.top")
                    val rawVideos = runCatching {
                        playlistUtils.extractFromHls(
                            playlistUrl = fullHlsUrl,
                            referer = embedUrl,
                            masterHeadersGen = { _, _ -> streamHeaders },
                            videoHeadersGen = { _, _, _ -> streamHeaders },
                            subtitleList = subtitleTracks,
                            videoNameGen = { quality -> "BabaStream - $quality ($typeTag)" },
                        )
                    }.getOrElse {
                        listOf(
                            Video(
                                videoUrl = fullHlsUrl,
                                videoTitle = "BabaStream - Auto ($typeTag)",
                                headers = streamHeaders,
                                subtitleTracks = subtitleTracks,
                            ),
                        )
                    }
                    videos.addAll(rawVideos.map { proxy.processVideo(it, embedUrl) })
                }
            }

            "embed" -> {
                payload.u?.takeIf { it.isNotBlank() }?.let { embedTarget ->
                    if (embedTarget.contains("ok.ru")) {
                        videos.addAll(
                            okruExtractor.videosFromUrl(embedTarget, prefix = "BabaStream - OK.ru ($typeTag) - "),
                        )
                    }
                }
            }
        }

        return videos
    }

    private fun getOrSolveCapToken(capEndpoint: String): String? {
        val now = System.currentTimeMillis()
        if (now < cachedCapTokenExpiry && !cachedCapToken.isNullOrBlank()) {
            return cachedCapToken
        }
        return solveCap(capEndpoint)
    }

    private fun solveCap(capEndpoint: String): String? = runCatching {
        val endpoint = if (capEndpoint.endsWith("/")) capEndpoint else "$capEndpoint/"
        val chalHeaders = headers.newBuilder()
            .set("Content-Type", "application/json")
            .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .build()

        val chalDto = runCatching {
            client.newCall(
                POST(
                    "${endpoint}challenge",
                    chalHeaders,
                    "{}".toRequestBody("application/json".toMediaType()),
                ),
            ).execute().use { it.parseAs<CapChallengeResponseDto>() }
        }.getOrNull() ?: return null

        val token = chalDto.token ?: return null
        val count = chalDto.challenge?.c ?: 80
        val sLen = chalDto.challenge?.s ?: 32
        val dLen = chalDto.challenge?.d ?: 4

        val solutions = TwoDHiveCrypto.solveCapChallenges(token, count, sLen, dLen)

        val redeemPayload = json.encodeToString(
            CapRedeemRequestDto.serializer(),
            CapRedeemRequestDto(token = token, solutions = solutions),
        )

        val redeemDto = runCatching {
            client.newCall(
                POST(
                    "${endpoint}redeem",
                    chalHeaders,
                    redeemPayload.toRequestBody("application/json".toMediaType()),
                ),
            ).execute().use { it.parseAs<CapRedeemResponseDto>() }
        }.getOrNull() ?: return null

        redeemDto.token?.also {
            cachedCapToken = it
            cachedCapTokenExpiry = redeemDto.expires ?: (System.currentTimeMillis() + 3600_000L)
        }
    }.getOrNull()

    // ======================== Wavy Resolver ========================
    fun extractWavy(malId: String, epNum: String, type: String): List<Video> {
        val embedUrl = "https://wavy.babastream.top/$malId/$epNum/$type"
        val embedHeaders = headers.newBuilder()
            .set("Referer", "https://2dhive.com/")
            .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .build()

        val embedOk = runCatching {
            client.newCall(GET(embedUrl, embedHeaders)).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        if (!embedOk) return emptyList()

        val typeTag = type.replaceFirstChar { it.uppercase() }
        val streamHeaders = streamHeaders(embedUrl, "https://wavy.babastream.top")
        val streamUrl = "https://wavy.babastream.top/stream.m3u8"

        val rawVideos = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                referer = embedUrl,
                masterHeadersGen = { _, _ -> streamHeaders },
                videoHeadersGen = { _, _, _ -> streamHeaders },
                videoNameGen = { quality -> "Wavy - $quality ($typeTag)" },
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "Wavy - Auto ($typeTag)",
                    headers = streamHeaders,
                ),
            )
        }

        return rawVideos.map { proxy.processVideo(it, embedUrl) }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
