package eu.kanade.tachiyomi.animeextension.en.anilight

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves the MegaPlay embeds AniLight hands out as
 * `https://megaplay.buzz/stream/s-2/<id>/<sub|dub>` (the website's "Meg"
 * server, and its default player whenever an episode has an `embed_url`).
 *
 * Chain (reverse engineered from `lib/newclient.min.js`, 2026-09-19):
 *  1. stream page  -> `data-id="<streamId>"`
 *  2. `/stream/getSources?id=<streamId>&id=<streamId>` -> JSON with `tracks`
 *     and either a plain `sources.file` or an AES-256-CBC `enc` blob whose
 *     plaintext is `{"file":"<master.m3u8>"}`.
 *  3. The key is the literal `"i?LMTAx0Q6,:}50U"` right-padded with zeroes to
 *     32 bytes and the IV is the literal `"W0;27ToaUpl_P%'c"`; the ciphertext
 *     is base64url. MegaPlay rotates this pair occasionally — if playback
 *     starts failing with a decrypt error, re-read `newclient.min.js` for the
 *     two literals next to `crypto.subtle.decrypt`.
 *
 * The m3u8 CDN behind it 403s unless the request carries the megaplay referer,
 * so every request from here does; that referer is also what the returned
 * [Video]s advertise, because [aniyomi.lib.m3u8server.M3u8Integration] copies
 * `Referer` and `User-Agent` onto the local relay's upstream fetches.
 */
class MegaPlayExtractor(
    private val client: OkHttpClient,
    private val playlistUtils: PlaylistUtils,
) {

    fun videosFromEmbed(embedUrl: String, isDub: Boolean): List<Video> {
        val streamId = fetchStreamId(embedUrl) ?: return emptyList()
        val sources = fetchSources(streamId) ?: return emptyList()
        val fileUrl = resolveFileUrl(sources) ?: return emptyList()

        val subtitleTracks = sources.tracks.orEmpty().mapNotNull { track ->
            val url = track.file ?: return@mapNotNull null
            if (track.kind.equals("thumbnails", ignoreCase = true)) return@mapNotNull null
            Track(url = url, lang = track.label ?: "English")
        }

        val audioBadge = when {
            isDub -> "[Dub]"
            subtitleTracks.isNotEmpty() -> "[Soft Sub]"
            else -> "[Sub]"
        }

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = fileUrl,
                masterHeaders = embedHeaders,
                videoHeaders = embedHeaders,
                videoNameGen = { quality -> "$quality $audioBadge" },
                subtitleList = subtitleTracks,
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = fileUrl,
                    videoTitle = "Auto $audioBadge",
                    headers = embedHeaders,
                    subtitleTracks = subtitleTracks,
                ),
            )
        }
    }

    private fun fetchStreamId(embedUrl: String): String? = runCatching {
        client.newCall(GET(embedUrl, pageHeaders)).execute().use { response ->
            if (!response.isSuccessful) return@use null
            STREAM_ID_REGEX.find(response.peekBody(PEEK_BYTES).string())?.groupValues?.get(1)
        }
    }.getOrNull()

    private fun fetchSources(streamId: String): MegaPlaySourcesDto? = runCatching {
        val url = "$MEGAPLAY/getSources?id=$streamId&id=$streamId"
        client.newCall(GET(url, embedHeaders)).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.parseAs<MegaPlaySourcesDto>()
        }
    }.getOrNull()

    private fun resolveFileUrl(sources: MegaPlaySourcesDto): String? {
        sources.sources?.file?.takeIf { it.isNotBlank() }?.let { return it }
        val enc = sources.enc?.takeIf { it.isNotBlank() } ?: return null
        return decryptFileUrl(enc)
    }

    private fun decryptFileUrl(enc: String): String? = runCatching {
        val data = Base64.decode(
            enc.replace('-', '+').replace('_', '/'),
            Base64.DEFAULT or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY, "AES"),
            IvParameterSpec(IV),
        )
        val plain = String(cipher.doFinal(data), Charsets.UTF_8)
        plain.parseAs<MegaPlayFileDto>().file?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private val pageHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$SITE_ORIGIN/")
            .build()
    }

    private val embedHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$MEGAPLAY/")
            .build()
    }

    private companion object {
        const val MEGAPLAY = "https://megaplay.buzz"
        const val SITE_ORIGIN = "https://anilight.live"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val PEEK_BYTES = 64L * 1024L

        val STREAM_ID_REGEX = Regex("""data-id=["']([^"']+)["']""")

        /** `new TextEncoder().encode("i?LMTAx0Q6,:}50U")` zero-padded to 32 bytes. */
        val KEY: ByteArray = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8).copyOf(32)

        /** `new TextEncoder().encode("W0;27ToaUpl_P%'c")` */
        val IV: ByteArray = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8)
    }
}

@Serializable
data class MegaPlaySourcesDto(
    val sources: MegaPlayFileDto? = null,
    val tracks: List<MegaPlayTrackDto>? = null,
    val enc: String? = null,
)

@Serializable
data class MegaPlayFileDto(
    val file: String? = null,
)

@Serializable
data class MegaPlayTrackDto(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean? = null,
)
