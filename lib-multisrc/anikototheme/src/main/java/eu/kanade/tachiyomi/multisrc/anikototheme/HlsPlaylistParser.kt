package eu.kanade.tachiyomi.multisrc.anikototheme

import java.net.URI

/**
 * Shared parser for media (variant) playlists.
 *
 * Both the extractors (initial resolve) and [LocalProxyServer] (periodic re-mint of expiring
 * segment URLs) need to turn a variant playlist body into [LocalProxyServer.SegmentInfo]s, so the
 * logic lives here instead of being duplicated.
 */
internal object HlsPlaylistParser {

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
}
