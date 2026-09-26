package eu.kanade.tachiyomi.multisrc.anikototheme

import java.net.URI

/**
 * Shared parser for HLS playlists.
 *
 * Both the extractors (initial resolve) and [LocalProxyServer] (periodic re-mint of expiring
 * segment URLs, and master-level re-mint when the variant URL itself dies) need to turn playlist
 * bodies into structured data, so the logic lives here instead of being duplicated.
 */
internal object HlsPlaylistParser {

    private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
    private val NAME_REGEX = Regex("""NAME="([^"]+)"""")
    private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")

    fun parseVariantSegments(text: String, variantUrl: String): List<LocalProxyServer.SegmentInfo> {
        val result = mutableListOf<LocalProxyServer.SegmentInfo>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("#EXTINF:")) {
                val duration = lines[i].substringAfter("#EXTINF:").substringBefore(",").toDoubleOrNull() ?: 0.0
                val next = lines.getOrNull(i + 1)?.trim() ?: ""
                if (next.isNotEmpty() && !next.startsWith("#")) {
                    val fullUrl = URI(variantUrl).resolve(next).toString()
                    result.add(LocalProxyServer.SegmentInfo(fullUrl, duration))
                    i += 2
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return result
    }

    fun parseMasterPlaylist(text: String, masterUrl: String): List<VariantInfo> {
        val result = mutableListOf<VariantInfo>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val next = lines.getOrNull(i + 1)?.trim() ?: ""
                if (next.isNotEmpty() && !next.startsWith("#")) {
                    val bandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val resH = RESOLUTION_REGEX.find(line)?.groupValues?.get(1) ?: ""
                    val resolution = resH.toIntOrNull() ?: 0
                    var quality = NAME_REGEX.find(line)?.groupValues?.get(1) ?: ""
                    if (quality.isBlank() || quality == "Unknown") {
                        quality = if (resolution > 0) "${resolution}p" else "Unknown"
                    }
                    val fullUrl = URI(masterUrl).resolve(next).toString()
                    result.add(VariantInfo(fullUrl, bandwidth, quality, resolution))
                    i += 2
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return result
    }
}
