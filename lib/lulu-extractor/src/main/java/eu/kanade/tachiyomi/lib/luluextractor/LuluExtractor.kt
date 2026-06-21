package eu.kanade.tachiyomi.lib.luluextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.regex.Pattern

class LuluExtractor(private val client: OkHttpClient, private val baseHeaders: Headers) {

    // Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/lulustream.py
    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val videos = mutableListOf<Video>()

        // Dynamically build headers based on the URL host
        val uri = url.toHttpUrl()
        val referer = "${uri.scheme}://${uri.host}/"
        val headers = baseHeaders.newBuilder()
            .set("Referer", url) // Using full URL as Referer is more stable
            .set("Origin", referer.removeSuffix("/"))
            .build()

        try {
            val html = client.newCall(GET(url, headers)).execute().use { it.body.string() }
            val m3u8Url = extractM3u8Url(html) ?: return emptyList()
            val fixedUrl = fixM3u8Link(m3u8Url)
            val quality = getResolution(fixedUrl, headers)

            val videoHeaders = headers.newBuilder()
                .set("Referer", url)
                .set("Origin", referer.removeSuffix("/"))
                .build()

            videos.add(Video(videoUrl = fixedUrl, videoTitle = "${prefix}Lulu - $quality", headers = videoHeaders))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return videos
    }

    private fun extractM3u8Url(html: String): String? {
        val hexReverseRegex = Regex("""const _0x1 = '([^']+)';""")
        val hexMatch = hexReverseRegex.find(html)
        if (hexMatch != null) {
            return try {
                val hex = hexMatch.groupValues[1].replace("|", "")
                hex.chunked(2)
                    .map { it.toInt(16).toChar() }
                    .joinToString("")
                    .reversed()
            } catch (e: Exception) {
                null
            }
        }

        return when {
            html.contains("eval(function(p,a,c,k,e") -> {
                val unpacked = JavaScriptUnpacker.unpack(html) ?: return null
                Pattern.compile("sources:\\[\\{file:\"([^\"]+)\"")
                    .matcher(unpacked)
                    .takeIf { it.find() }
                    ?.group(1)
            }

            else -> {
                Pattern.compile("sources: \\[\\{file:\"(https?://[^\"]+)\"")
                    .matcher(html)
                    .takeIf { it.find() }
                    ?.group(1)
            }
        }
    }

    private fun fixM3u8Link(link: String): String {
        val paramOrder = listOf("t", "s", "e", "f")
        val params = Pattern.compile("[?&]([^=]*)=([^&]*)").matcher(link).let { matcher ->
            generateSequence { if (matcher.find()) matcher.group(1) to matcher.group(2) else null }.toList()
        }

        val paramDict = mutableMapOf<String, String>()
        val extraParams = mutableMapOf<String, String>()

        params.forEachIndexed { index, (key, value) ->
            if (key.isNullOrEmpty()) {
                if (index < paramOrder.size) {
                    if (value != null) {
                        paramDict[paramOrder[index]] = value
                    }
                }
            } else {
                if (value != null) {
                    extraParams[key] = value
                }
            }
        }

        extraParams["i"] = "0.3"
        extraParams["sp"] = "0"

        val baseUrl = link.split("?")[0]

        val fixedLink = baseUrl.toHttpUrl().newBuilder()
        paramOrder.filter { paramDict.containsKey(it) }.forEach { key ->
            fixedLink.addQueryParameter(key, paramDict[key])
        }
        extraParams.forEach { (key, value) ->
            fixedLink.addQueryParameter(key, value)
        }

        return fixedLink.build().toString()
    }

    private fun getResolution(m3u8Url: String, headers: Headers): String = try {
        val content = client.newCall(GET(m3u8Url, headers)).execute()
            .use { it.body.string() }

        Pattern.compile("RESOLUTION=\\d+x(\\d+)")
            .matcher(content)
            .takeIf { it.find() }
            ?.group(1)
            ?.let { "${it}p" }
            ?: "Unknown"
    } catch (e: Exception) {
        "Unknown"
    }
}

object JavaScriptUnpacker {
    private val UNPACK_REGEX by lazy {
        Regex(
            """\}\('(.*)', *(\d+), *(\d+), *'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
    fun unpack(encodedJs: String): String? {
        val match = UNPACK_REGEX.find(encodedJs) ?: return null
        val (payload, radixStr, countStr, symtabStr) = match.destructured

        val radix = radixStr.toIntOrNull() ?: return null
        val count = countStr.toIntOrNull() ?: return null
        val symtab = symtabStr.split('|')

        if (symtab.size != count) throw IllegalArgumentException("Invalid symtab size")

        val baseDict = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            .take(radix)
            .withIndex()
            .associate { it.value to it.index }

        return Regex("""\b\w+\b""").replace(payload) { mr ->
            symtab.getOrNull(unbase(mr.value, radix, baseDict)) ?: mr.value
        }.replace("\\", "")
    }
    private fun unbase(value: String, radix: Int, dict: Map<Char, Int>): Int {
        var result = 0
        var multiplier = 1

        for (char in value.reversed()) {
            result += dict[char]?.times(multiplier) ?: 0
            multiplier *= radix
        }
        return result
    }
}
