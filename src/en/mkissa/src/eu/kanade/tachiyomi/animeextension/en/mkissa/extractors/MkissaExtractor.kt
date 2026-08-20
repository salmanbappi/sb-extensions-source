package eu.kanade.tachiyomi.animeextension.en.mkissa.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale

class MkissaExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        // The DASH CDN 403s any request with a Referer, and an unset header set makes the player
        // fall back to the source's, which has one.
        private val DASH_HEADERS = Headers.headersOf("Accept", "*/*")
    }

    private fun bytesIntoHumanReadable(bytes: Long): String {
        val kilobyte: Long = 1000
        val megabyte = kilobyte * 1000
        val gigabyte = megabyte * 1000
        val terabyte = gigabyte * 1000
        return when {
            bytes < 0 -> "$bytes bits/s"
            bytes < kilobyte -> "$bytes b/s"
            bytes < megabyte -> "${bytes / kilobyte} kb/s"
            bytes < gigabyte -> String.format(Locale.US, "%.2f mb/s", bytes.toDouble() / megabyte)
            bytes < terabyte -> String.format(Locale.US, "%.2f gb/s", bytes.toDouble() / gigabyte)
            else -> String.format(Locale.US, "%.2f tb/s", bytes.toDouble() / terabyte)
        }
    }

    suspend fun videoFromUrl(url: String, name: String, endPoint: String): List<Video> {
        val linkJson = client.newCall(
            GET(endPoint + url.replace("/clock?", "/clock.json?")),
        ).awaitSuccess()
            .parseAs<VideoLink>()

        return linkJson.links.parallelCatchingFlatMap { link ->
            val subtitles = link.subtitles?.map { sub ->
                val label = sub.label?.let { " - $it" } ?: ""
                Track(url = sub.src, lang = Locale(sub.lang).displayLanguage + label)
            }.orEmpty()

            when {
                link.mp4 == true -> {
                    Video(
                        videoUrl = link.link,
                        videoTitle = "Original ($name - ${link.resolutionStr})",
                        subtitleTracks = subtitles,
                    ).let(::listOf)
                }

                link.hls == true -> {
                    val masterHeaders = headers.newBuilder()
                        .add("Accept", "*/*")
                        .add("Host", link.link.toHttpUrl().host)
                        .add("Origin", endPoint)
                        .add("Referer", "$endPoint/")
                        .build()

                    playlistUtils.extractFromHls(
                        playlistUrl = link.link,
                        masterHeaders = masterHeaders,
                        videoHeaders = masterHeaders,
                        videoNameGen = { quality -> "$quality ($name - ${link.resolutionStr})" },
                        subtitleList = subtitles,
                    )
                }

                link.crIframe == true -> {
                    link.portData?.streams?.parallelCatchingFlatMap {
                        when (it.format) {
                            "adaptive_dash" ->
                                Video(
                                    videoUrl = it.url,
                                    videoTitle = "Original (AC - Dash${if (it.hardsub_lang.isEmpty()) "" else " - Hardsub: ${it.hardsub_lang}"})",
                                    subtitleTracks = subtitles,
                                ).let(::listOf)

                            "adaptive_hls" ->
                                playlistUtils.extractFromHls(
                                    playlistUrl = it.url,
                                    masterHeaders = headers,
                                    videoHeaders = headers,
                                    videoNameGen = { quality -> "$quality (AC - HLS${if (it.hardsub_lang.isEmpty()) "" else " - Hardsub: ${it.hardsub_lang}"})" },
                                    subtitleList = subtitles,
                                )

                            else -> emptyList()
                        }
                    }.orEmpty()
                }

                link.dash == true -> {
                    val audioList = link.rawUrls?.audios?.map {
                        Track(url = it.url, lang = bytesIntoHumanReadable(it.bandwidth))
                    }.orEmpty()

                    link.rawUrls?.vids?.map {
                        Video(
                            videoUrl = it.url,
                            videoTitle = "$name - ${it.height} ${bytesIntoHumanReadable(it.bandwidth)}",
                            headers = DASH_HEADERS,
                            audioTracks = audioList,
                            subtitleTracks = subtitles,
                        )
                    }.orEmpty()
                }

                else -> emptyList()
            }
        }
    }

    @Serializable
    data class VideoLink(
    val links: List<Link>? = null
) {
        @Serializable
        data class Link(
    val link: String? = null,
    val hls: Boolean? = null,
    val mp4: Boolean? = null,
    val dash: Boolean? = null,
    val crIframe: Boolean? = null,
    val resolutionStr: String? = null,
    val subtitles: List<Subtitles>? = null,
    val rawUrls: RawUrl? = null,
    val portData: Stream? = null
) {
            @Serializable
            data class Subtitles(
    val lang: String? = null,
    val src: String? = null,
    val label: String? = null
)

            @Serializable
            data class RawUrl(
    val audios: List<Audio>? = null,
    val vids: List<Vid>? = null
) {
                @Serializable
                data class Audio(
    val bandwidth: Long? = null,
    val url: String? = null
)

                @Serializable
                data class Vid(
    val bandwidth: Long? = null,
    val height: Int? = null,
    val url: String? = null
)
            }

            @Serializable
            data class Stream(
    val streams: List<StreamData>? = null
) {
                @Serializable
                data class StreamData(
    val format: String? = null,
    val url: String? = null,
    val hardsub_lang: String? = null
)
            }
        }
    }
}
