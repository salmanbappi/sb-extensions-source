package eu.kanade.tachiyomi.animeextension.en.twodhive

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
) {
    private val okruExtractor by lazy { OkruExtractor(client) }

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

        val sourcesResp = runCatching {
            client.newCall(GET(sourcesUrl, sourcesHeaders)).execute()
        }.getOrNull() ?: return emptyList()

        val sourcesDto = runCatching { sourcesResp.parseAs<MegaPlaySourcesDto>() }.getOrNull()
            ?: return emptyList()

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

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = "https://megaplay.buzz/",
                masterHeadersGen = { _, _ -> streamRefHeaders },
                videoHeadersGen = { _, _, _ -> streamRefHeaders },
                subtitleList = subtitleTracks,
                videoNameGen = { quality -> "$quality ($typeTag)" },
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = masterUrl,
                    videoTitle = "Auto ($typeTag)",
                    headers = streamRefHeaders,
                    subtitleTracks = subtitleTracks,
                ),
            )
        }
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

            val resp = runCatching {
                client.newCall(POST("https://babastream.top$path", reqHeaders, postBody)).execute()
            }.getOrNull() ?: return null

            val respDto = runCatching { resp.parseAs<BabaResolveResponseDto>() }.getOrNull() ?: return null
            val respD = respDto.d ?: return null
            return TwoDHiveCrypto.decryptAesGcm(pk, respD)
        }

        var resolvedJson = postApi("/api/resolve", """{"ts":${System.currentTimeMillis()}}""")
        var payload = runCatching { resolvedJson?.parseAs<BabaDecryptedPayloadDto>() }.getOrNull()

        // Solve Cap challenge if server requires verification
        if (payload?.t == "error" && payload?.m == "verify" && !cfg.cap.isNullOrBlank()) {
            val redeemToken = solveCap(cfg.cap)
            if (!redeemToken.isNullOrBlank()) {
                val verifyJson = postApi(
                    "/api/cap-verify",
                    """{"ts":${System.currentTimeMillis()},"token":"$redeemToken","mode":"invisible"}""",
                )
                val verifyDto = runCatching { verifyJson?.parseAs<BabaVerifyResponseDto>() }.getOrNull()
                if (verifyDto?.t == "ok") {
                    resolvedJson = postApi("/api/resolve", """{"ts":${System.currentTimeMillis()}}""")
                    payload = runCatching { resolvedJson?.parseAs<BabaDecryptedPayloadDto>() }.getOrNull()
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
                    videos.add(
                        Video(
                            videoUrl = directUrl,
                            videoTitle = "Direct MP4 ($typeTag)",
                            headers = streamHeaders,
                            subtitleTracks = subtitleTracks,
                        ),
                    )
                }
            }

            "hls" -> {
                payload.u?.takeIf { it.isNotBlank() }?.let { hlsUrl ->
                    videos.addAll(
                        runCatching {
                            playlistUtils.extractFromHls(
                                playlistUrl = hlsUrl,
                                referer = embedUrl,
                                masterHeadersGen = { _, _ -> streamHeaders },
                                videoHeadersGen = { _, _, _ -> streamHeaders },
                                subtitleList = subtitleTracks,
                                videoNameGen = { quality -> "$quality ($typeTag)" },
                            )
                        }.getOrElse {
                            listOf(
                                Video(
                                    videoUrl = hlsUrl,
                                    videoTitle = "Auto ($typeTag)",
                                    headers = streamHeaders,
                                    subtitleTracks = subtitleTracks,
                                ),
                            )
                        },
                    )
                }
            }

            "embed" -> {
                payload.u?.takeIf { it.isNotBlank() }?.let { embedTarget ->
                    if (embedTarget.contains("ok.ru")) {
                        videos.addAll(
                            okruExtractor.videosFromUrl(embedTarget, prefix = "OK.ru ($typeTag) - "),
                        )
                    }
                }
            }
        }

        return videos
    }

    private fun solveCap(capEndpoint: String): String? = runCatching {
        val endpoint = if (capEndpoint.endsWith("/")) capEndpoint else "$capEndpoint/"
        val chalHeaders = headers.newBuilder()
            .set("Content-Type", "application/json")
            .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .build()

        val chalReq = client.newCall(
            POST(
                "${endpoint}challenge",
                chalHeaders,
                "{}".toRequestBody("application/json".toMediaType()),
            ),
        ).execute()

        val chalDto = chalReq.parseAs<CapChallengeResponseDto>()
        val token = chalDto.token ?: return null
        val count = chalDto.challenge?.c ?: 80
        val sLen = chalDto.challenge?.s ?: 32
        val dLen = chalDto.challenge?.d ?: 4

        val solutions = TwoDHiveCrypto.solveCapChallenges(token, count, sLen, dLen)

        val redeemPayload = json.encodeToString(
            CapRedeemRequestDto.serializer(),
            CapRedeemRequestDto(token = token, solutions = solutions),
        )

        val redeemReq = client.newCall(
            POST(
                "${endpoint}redeem",
                chalHeaders,
                redeemPayload.toRequestBody("application/json".toMediaType()),
            ),
        ).execute()

        val redeemDto = redeemReq.parseAs<CapRedeemResponseDto>()
        redeemDto.token
    }.getOrNull()

    // ======================== Wavy Resolver ========================
    fun extractWavy(malId: String, epNum: String, type: String): List<Video> {
        val embedUrl = "https://wavy.babastream.top/$malId/$epNum/$type"
        val embedHeaders = headers.newBuilder()
            .set("Referer", "https://2dhive.com/")
            .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .build()

        val embedResp = runCatching {
            client.newCall(GET(embedUrl, embedHeaders)).execute()
        }.getOrNull() ?: return emptyList()

        if (!embedResp.isSuccessful) return emptyList()

        val typeTag = type.replaceFirstChar { it.uppercase() }
        val streamHeaders = streamHeaders(embedUrl, "https://wavy.babastream.top")
        val streamUrl = "https://wavy.babastream.top/stream.m3u8"

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                referer = embedUrl,
                masterHeadersGen = { _, _ -> streamHeaders },
                videoHeadersGen = { _, _, _ -> streamHeaders },
                videoNameGen = { quality -> "$quality ($typeTag)" },
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "Auto ($typeTag)",
                    headers = streamHeaders,
                ),
            )
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
