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

    // ======================== BabaStream Resolver ========================
    fun extractBabaStream(malId: String, epNum: String, type: String): List<Video> {
        val embedUrl = "https://babastream.top/embed/$malId/$epNum/$type"
        val embedHeaders = headers.newBuilder()
            .set("Referer", "https://2dhive.com/")
            .build()

        val embedHtml = runCatching {
            client.newCall(GET(embedUrl, embedHeaders)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        val cfgJsonStr = Regex("var\\s+CFG\\s*=\\s*(\\{.*?\\});").find(embedHtml)?.groupValues?.get(1)
            ?: return emptyList()

        val cfg = runCatching { cfgJsonStr.parseAs<BabaConfigDto>() }.getOrNull()
            ?: return emptyList()

        val pk = cfg.pk ?: return emptyList()
        val sid = cfg.sid ?: return emptyList()

        val encryptedPayload = TwoDHiveCrypto.encryptAesGcm(pk, "{\"ts\":${System.currentTimeMillis()}}")
        val postBody = "{\"s\":\"$sid\",\"d\":\"$encryptedPayload\"}"
            .toRequestBody("application/json".toMediaType())

        val resolveHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .set("Origin", "https://babastream.top")
            .build()

        val resolveResp = runCatching {
            client.newCall(POST("https://babastream.top/api/resolve", resolveHeaders, postBody)).execute()
        }.getOrNull() ?: return emptyList()

        val resolveDto = runCatching { resolveResp.parseAs<BabaResolveResponseDto>() }.getOrNull()
            ?: return emptyList()

        val decryptedJson = TwoDHiveCrypto.decryptAesGcm(pk, resolveDto.d ?: return emptyList())
        val payload = runCatching { decryptedJson.parseAs<BabaDecryptedPayloadDto>() }.getOrNull()
            ?: return emptyList()

        val typeTag = type.replaceFirstChar { it.uppercase() }
        val videos = mutableListOf<Video>()

        when (payload.t) {
            "direct" -> {
                payload.u?.let { directUrl ->
                    videos.add(
                        Video(
                            videoUrl = directUrl,
                            videoTitle = "BabaStream - Direct MP4 ($typeTag)",
                            headers = headers.newBuilder().set("Referer", embedUrl).build(),
                        ),
                    )
                }
            }

            "embed" -> {
                payload.u?.let { embedTarget ->
                    if (embedTarget.contains("ok.ru")) {
                        videos.addAll(
                            okruExtractor.videosFromUrl(embedTarget, prefix = "BabaStream (OK.ru) - ", suffix = " ($typeTag)"),
                        )
                    }
                }
            }
        }

        // Secondary /api/vidara check
        runCatching {
            val vidaraResp = client.newCall(POST("https://babastream.top/api/vidara", resolveHeaders, postBody)).execute()
            val vidaraDto = vidaraResp.parseAs<BabaResolveResponseDto>()
            val decryptedVidara = TwoDHiveCrypto.decryptAesGcm(pk, vidaraDto.d ?: return@runCatching)
            val vidaraPayload = decryptedVidara.parseAs<BabaDecryptedPayloadDto>()
            if (vidaraPayload.t == "vidara" && !vidaraPayload.u.isNullOrBlank()) {
                val vUrl = vidaraPayload.u!!
                if (vUrl.contains("ok.ru")) {
                    videos.addAll(okruExtractor.videosFromUrl(vUrl, prefix = "Vidara (OK.ru) - ", suffix = " ($typeTag)"))
                }
            }
        }

        return videos
    }

    // ======================== MegaPlay Resolver ========================
    fun extractMegaPlay(malId: String, epNum: String, type: String): List<Video> {
        val streamPageUrl = "https://megaplay.buzz/stream/mal/$malId/$epNum/$type"
        val pageHeaders = headers.newBuilder()
            .set("Referer", "https://2dhive.com/")
            .build()

        val pageHtml = runCatching {
            client.newCall(GET(streamPageUrl, pageHeaders)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        val streamId = Regex("<title>File (\\d+)").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("data-id=\"(\\d+)\"").find(pageHtml)?.groupValues?.get(1)
            ?: return emptyList()

        val sourcesUrl = "https://megaplay.buzz/stream/getSources?id=$streamId&id=$streamId"
        val sourcesHeaders = headers.newBuilder()
            .set("Referer", streamPageUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val sourcesResp = runCatching {
            client.newCall(GET(sourcesUrl, sourcesHeaders)).execute()
        }.getOrNull() ?: return emptyList()

        val sourcesDto = runCatching { sourcesResp.parseAs<MegaPlaySourcesDto>() }.getOrNull()
            ?: return emptyList()

        val masterUrl = sourcesDto.sources?.file ?: return emptyList()
        val typeTag = type.replaceFirstChar { it.uppercase() }

        val subtitleTracks = sourcesDto.tracks
            ?.filter { it.kind == "captions" && !it.file.isNullOrBlank() }
            ?.map { Track(it.file!!, it.label ?: "English") }
            ?: emptyList()

        val streamRefHeaders = headers.newBuilder()
            .set("Referer", "https://megaplay.buzz/")
            .build()

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            masterHeaders = streamRefHeaders,
            videoHeaders = streamRefHeaders,
            subtitleList = subtitleTracks,
            videoNameGen = { quality -> "MegaPlay - $quality ($typeTag)" },
        )
    }
}
