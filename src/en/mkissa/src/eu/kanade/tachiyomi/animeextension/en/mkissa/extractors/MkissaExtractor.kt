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

    private fun bytesIntoHumanReadable(bytes: Long): String {
        val kilobyte: Long = 1000
        val megabyte = kilobyte * 1000
        val gigabyte = megabyte * 1000
        val terabyte = gigabyte * 1000
        return when {
            bytes in 0 until kilobyte -> "$bytes b/s"
            bytes in kilobyte until megabyte -> "${bytes / kilobyte} kb/s"
            bytes in megabyte until gigabyte -> "${bytes / megabyte} mb/s"
            bytes in gigabyte until terabyte -> "${bytes / gigabyte} gb/s"
            bytes >= terabyte -> "${bytes / terabyte} tb/s"
            else -> "$bytes bits/s"
        }
    }

    suspend fun videoFromUrl(url: String, name: String, endPoint: String): List<Video> {
        val linkJson = client.newCall(
            GET(endPoint + url.replace("/clock?", "/clock.json?")),
        ).awaitSuccess()
            .parseAs<VideoLink>()

        return (linkJson.links ?: emptyList()).parallelCatchingFlatMap { link ->
            val subtitles = link.subtitles?.mapNotNull { sub ->
                val subSrc = sub.src ?: return@mapNotNull null
                val label = sub.label?.let { " - $it" } ?: ""
                Track(url = subSrc, lang = Locale(sub.lang ?: "en").displayLanguage + label)
            }.orEmpty()

            when {
                link.mp4 == true -> {
                    val linkUrl = link.link ?: return@parallelCatchingFlatMap emptyList()
                    Video(
                        videoUrl = linkUrl,
                        videoTitle = "Original ($name - ${link.resolutionStr})",
                        subtitleTracks = subtitles,
                    ).let(::listOf)
                }

                link.hls == true -> {
                    val linkUrl = link.link ?: return@parallelCatchingFlatMap emptyList()
                    val masterHeaders = headers.newBuilder()
                        .add("Accept", "*/*")
                        .add("Host", linkUrl.toHttpUrl().host)
                        .add("Origin", endPoint)
                        .add("Referer", "$endPoint/")
                        .build()

                    playlistUtils.extractFromHls(
                        playlistUrl = linkUrl,
                        masterHeaders = masterHeaders,
                        videoHeaders = masterHeaders,
                        videoNameGen = { quality -> "$quality ($name - ${link.resolutionStr})" },
                        subtitleList = subtitles,
                    )
                }

                link.crIframe == true -> {
                    link.portData?.streams?.parallelCatchingFlatMap {
                        val streamUrl = it.url ?: return@parallelCatchingFlatMap emptyList()
                        when (it.format) {
                            "adaptive_dash" ->
                                Video(
                                    videoUrl = streamUrl,
                                    videoTitle = "Original (AC - Dash${if (it.hardsub_lang.isNullOrEmpty()) "" else " - Hardsub: ${it.hardsub_lang}"})",
                                    subtitleTracks = subtitles,
                                ).let(::listOf)

                            "adaptive_hls" ->
                                playlistUtils.extractFromHls(
                                    playlistUrl = streamUrl,
                                    masterHeaders = headers,
                                    videoHeaders = headers,
                                    videoNameGen = { quality -> "$quality (AC - HLS${if (it.hardsub_lang.isNullOrEmpty()) "" else " - Hardsub: ${it.hardsub_lang}"})" },
                                    subtitleList = subtitles,
                                )

                            else -> emptyList()
                        }
                    }.orEmpty()
                }

                link.dash == true -> {
                    val audioList = link.rawUrls?.audios?.mapNotNull {
                        val audioUrl = it.url ?: return@mapNotNull null
                        Track(url = audioUrl, lang = bytesIntoHumanReadable(it.bandwidth ?: 0L))
                    }.orEmpty()

                    link.rawUrls?.vids?.mapNotNull {
                        val vidUrl = it.url ?: return@mapNotNull null
                        Video(
                            videoUrl = vidUrl,
                            videoTitle = "$name - ${it.height} ${bytesIntoHumanReadable(it.bandwidth ?: 0L)}",
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
    data class VersionResponse(
        val episodeIframeHead: String? = null,
    )

    @Serializable
    data class VideoLink(
        val links: List<Link>? = null,
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
            val portData: Stream? = null,
        ) {
            @Serializable
            data class Subtitles(
                val src: String? = null,
                val label: String? = null,
                val lang: String? = null,
                val default: String? = null,
            )

            @Serializable
            data class RawUrl(
                val vids: List<MediaItem>? = null,
                val audios: List<MediaItem>? = null,
            ) {
                @Serializable
                data class MediaItem(
                    val url: String? = null,
                    val height: Int? = null,
                    val bandwidth: Long? = null,
                )
            }

            @Serializable
            data class Stream(
                val streams: List<StreamItem>? = null,
            ) {
                @Serializable
                data class StreamItem(
                    val format: String? = null,
                    val url: String? = null,
                    val hardsub_lang: String? = null,
                )
            }
        }
    }
}
