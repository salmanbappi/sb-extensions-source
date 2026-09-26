package keiyoushi.utils

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.Response

abstract class AnimeHttpHosterSource : AnimeHttpSource() {
    open suspend fun getVideoList(episode: SEpisode): List<Video> = getHosterList(episode)
        .parallelCatchingFlatMapBlocking(::getVideoList)

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    // extensions-lib v17: abstract on AnimeSource and implemented nowhere in the
    // AnimeCatalogueSource/AnimeHttpSource chain, so every concrete source must
    // provide it. Default off; override to opt into related entries.
    override val supportsRelatedAnime: Boolean = false

    protected fun legacyHoster(
        hosterUrl: String = "",
        hosterName: String = "",
        videoList: List<Video>? = null,
        legacyData: String = "",
    ) = Hoster(
        hosterUrl = hosterUrl,
        hosterName = hosterName,
        videoList = videoList,
        lazy = false,
        memo = legacyData.toLegacyMemo(),
    )

    fun legacyVideo(
        videoUrl: String = "",
        videoTitle: String = "",
        resolution: Int? = null,
        bitrate: Int? = null,
        headers: Headers? = null,
        preferred: Boolean = false,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
        timestamps: List<TimeStamp> = emptyList(),
        mpvArgs: List<Pair<String, String>> = emptyList(),
        ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
        ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
        legacyData: String = "",
        initialized: Boolean = false,
    ) = Video(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        memo = legacyData.toLegacyMemo(),
        initialized = initialized,
    )

    fun Video.copyLegacy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        mpvArgs: List<Pair<String, String>> = this.mpvArgs,
        ffmpegStreamArgs: List<Pair<String, String>> = this.ffmpegStreamArgs,
        ffmpegVideoArgs: List<Pair<String, String>> = this.ffmpegVideoArgs,
        legacyData: String = this.memo.legacyInternalData(),
        initialized: Boolean = this.initialized,
    ): Video = legacyVideo(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        legacyData = legacyData,
        initialized = initialized,
    )
}

/**
 * Key under which a source's opaque Hoster -> Video payload is carried in [JsonObject] memo.
 *
 * extensions-lib v17 deprecates `Hoster.internalData` / `Video.internalData` (a String) at
 * ERROR level in favour of `memo: JsonObject`. These helpers keep the ergonomic String API
 * so existing sources only need to swap the property they read/write.
 */
const val LEGACY_DATA_KEY = "aniyomi.legacyData"

/** Wraps a legacy `internalData` String into a memo object (empty String -> empty memo). */
fun String.toLegacyMemo(): JsonObject =
    if (isEmpty()) {
        JsonObject(emptyMap())
    } else {
        buildJsonObject { put(LEGACY_DATA_KEY, JsonPrimitive(this@toLegacyMemo)) }
    }

/** Reads the legacy `internalData` String back out of a memo object. */
fun JsonObject.legacyInternalData(): String =
    (this[LEGACY_DATA_KEY] as? JsonPrimitive)?.contentOrNull ?: ""
