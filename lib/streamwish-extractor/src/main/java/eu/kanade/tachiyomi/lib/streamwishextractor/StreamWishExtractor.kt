package eu.kanade.tachiyomi.lib.streamwishextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.lib.unpacker.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val dmcaServersRegex = """dmca\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val mainServersRegex = """main\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val rulesServersRegex = """rules\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)

    fun videosFromUrl(url: String, prefix: String) = videosFromUrl(url) { "$prefix - $it" }

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamWish - $quality" }): List<Video> {
        val embedUrl = getEmbedUrl(url).toHttpUrl()
        var doc = try {
            val res = client.newCall(GET(embedUrl, headers)).execute()
            if (!res.isSuccessful) return emptyList()
            res.asJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        val scriptElement = doc.selectFirst("body > script[src*=/main.js]")
        if (scriptElement != null) {
            val scriptUrl = scriptElement.absUrl("src")
            val scriptContent = try {
                val res = client.newCall(GET(scriptUrl, headers)).execute()
                if (res.isSuccessful) res.body.string() else null
            } catch (_: Exception) {
                null
            }

            if (!scriptContent.isNullOrBlank()) {
                val deobfuscatedScript = runCatching { Deobfuscator.deobfuscateScript(scriptContent) }.getOrNull()
                if (deobfuscatedScript != null) {
                    val dmcaServers = extractServerList(dmcaServersRegex, deobfuscatedScript)
                    val mainServers = extractServerList(mainServersRegex, deobfuscatedScript)
                    val rulesServers = extractServerList(rulesServersRegex, deobfuscatedScript)

                    val candidateServers = if (embedUrl.host in rulesServers) {
                        (mainServers + dmcaServers).distinct().shuffled()
                    } else {
                        (dmcaServers + mainServers).distinct().shuffled()
                    }

                    for (destination in candidateServers) {
                        try {
                            val redirectedUrl = embedUrl.newBuilder()
                                .host(destination)
                                .build()
                                .toString()

                            val redirectedRes = client.newCall(GET(getEmbedUrl(redirectedUrl), headers)).execute()
                            if (redirectedRes.isSuccessful) {
                                doc = redirectedRes.asJsoup()
                                break
                            }
                        } catch (_: Exception) {
                            // Continue trying other candidate servers on 503 / 522 / network errors
                        }
                    }
                }
            }
        }

        val scriptBody = doc.select("script").mapNotNull { scriptEl ->
            val data = scriptEl.data().ifBlank { scriptEl.html() }
            when {
                data.contains("eval(function(p,a,c") -> JsUnpacker.unpackAndCombine(data)
                data.contains("m3u8") || data.contains("master") -> data
                else -> null
            }
        }.firstOrNull { it.contains("m3u8") || it.contains(".txt") || it.contains("master") }

        val masterUrl = scriptBody?.let {
            m3u8Regex.find(it)?.value ?: fallbackM3u8Regex.find(it)?.value
        } ?: return emptyList()

        val subtitleList = extractSubtitles(scriptBody)

        return try {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = "https://${url.toHttpUrl().host}/",
                videoNameGen = videoNameGen,
                subtitleList = playlistUtils.fixSubtitles(subtitleList),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractServerList(regex: Regex, script: String): List<String> = regex.find(script)?.groupValues?.get(1)
        ?.split(",")
        ?.map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    private fun getEmbedUrl(url: String): String = if (url.contains("/f/")) {
        val videoId = url.substringAfter("/f/")
        "https://streamwish.com/$videoId"
    } else {
        url
    }

    private fun extractSubtitles(script: String): List<Track> = try {
        val subtitleStr = script
            .substringAfter("tracks")
            .substringAfter("[")
            .substringBefore("]")
        val fixedSubtitleStr = fixTracksRegex.replace(subtitleStr) { match ->
            "\"${match.value}\""
        }

        json.decodeFromString<List<TrackDto>>("[$fixedSubtitleStr]")
            .filter { it.kind.equals("captions", true) }
            .map { Track(it.file, it.label ?: "") }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(val file: String, val kind: String, val label: String? = null)

    private val m3u8Regex = Regex("""https?://[^"',\s\\]+\.(?:m3u8|txt)[^"',\s\\]*""")
    private val fallbackM3u8Regex = Regex("""https?://[^"',\s\\]*m3u8[^"',\s\\]*""")
    private val fixTracksRegex = Regex("""(?<!["])(file|kind|label)(?!["])""")
}

