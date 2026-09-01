// ============================================================================
// Vendored from lib/m3u8server/AutoDetector.kt via `cli.py vendor`
// Original Package: aniyomi.lib.m3u8server
// Managed Vendor Copy — Safe to customize locally without breaking other sources
// ============================================================================

package eu.kanade.tachiyomi.animeextension.all.moviebox.extractors

/**
 * Automatic file format detector and offset calculator
 */
object AutoDetector {

    // Magic headers for different formats
    private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
    private val GIF_HEADER = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte())
    private const val MPEG_TS_SYNC = 0x47.toByte()
    private val MP4_FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte()) // "ftyp"
    private val AVI_RIFF = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()) // "RIFF"
    private val AVI_AVI = byteArrayOf(0x41.toByte(), 0x56.toByte(), 0x49.toByte(), 0x20.toByte()) // "AVI "
    private const val MPEG_TS_PACKET_SIZE = 188

    private const val DEFAULT_JUNK_BLOCK_SIZE = 252

    private const val JUNK_END_SEARCH_LIMIT = 8 * 1024

    fun detectSkipBytes(data: ByteArray): Int {
        if (data.isEmpty()) return 0

        return when {
            isMpegTsValid(data) -> 0
            isJpegHeader(data) || isPngHeader(data) || isGifHeader(data) -> detectDisguise(data)
            isVideoFormat(data) -> 0
            else -> 0
        }
    }

    fun detectInterleavedSkips(data: ByteArray): List<IntRange> {
        if (data.isEmpty()) return emptyList()

        val regions = mutableListOf<IntRange>()

        var i = 0
        while (i < data.size - 2) {
            val junkEnd = detectJunkBlockEnd(data, i)
            if (junkEnd >= 0) {
                if (junkEnd > i) {
                    regions.add(i until junkEnd)
                }
                i = junkEnd
            } else {
                i++
            }
        }

        return mergeRegions(regions)
    }

    private fun detectJunkBlockEnd(data: ByteArray, offset: Int): Int {
        val isJpeg = offset + 2 < data.size &&
            data[offset] == JPEG_HEADER[0] &&
            data[offset + 1] == JPEG_HEADER[1] &&
            data[offset + 2] == JPEG_HEADER[2]
        val isPng = offset + 3 < data.size &&
            data[offset] == PNG_HEADER[0] &&
            data[offset + 1] == PNG_HEADER[1] &&
            data[offset + 2] == PNG_HEADER[2] &&
            data[offset + 3] == PNG_HEADER[3]
        val isGif = offset + 2 < data.size &&
            data[offset] == GIF_HEADER[0] &&
            data[offset + 1] == GIF_HEADER[1] &&
            data[offset + 2] == GIF_HEADER[2]

        if (!isJpeg && !isPng && !isGif) return -1

        val searchEnd = minOf(data.size, offset + JUNK_END_SEARCH_LIMIT)

        var i = offset + 1
        while (i < searchEnd) {
            if (i + 8 <= searchEnd && isFtypAt(data, i)) {
                return i
            }
            if (i + 12 <= searchEnd && isRiffAviAt(data, i)) {
                return i
            }
            if (data[i] == MPEG_TS_SYNC && isMpegTsValidAt(data, i)) {
                return i
            }
            i++
        }

        return minOf(data.size, offset + DEFAULT_JUNK_BLOCK_SIZE)
    }

    private fun isFtypAt(data: ByteArray, offset: Int): Boolean = data[offset + 4] == MP4_FTYP[0] &&
        data[offset + 5] == MP4_FTYP[1] &&
        data[offset + 6] == MP4_FTYP[2] &&
        data[offset + 7] == MP4_FTYP[3]

    private fun isRiffAviAt(data: ByteArray, offset: Int): Boolean = data[offset] == AVI_RIFF[0] &&
        data[offset + 1] == AVI_RIFF[1] &&
        data[offset + 2] == AVI_RIFF[2] &&
        data[offset + 3] == AVI_RIFF[3] &&
        data[offset + 8] == AVI_AVI[0] &&
        data[offset + 9] == AVI_AVI[1] &&
        data[offset + 10] == AVI_AVI[2] &&
        data[offset + 11] == AVI_AVI[3]

    private fun isMpegTsValidAt(data: ByteArray, start: Int): Boolean {
        if (start + MPEG_TS_PACKET_SIZE > data.size) return false
        if (data[start] != MPEG_TS_SYNC) return false
        var valid = 0
        var j = start
        val ceiling = minOf(data.size, start + 1024)
        while (j < ceiling) {
            if (j + MPEG_TS_PACKET_SIZE <= data.size && data[j] == MPEG_TS_SYNC) {
                valid++
            }
            j += MPEG_TS_PACKET_SIZE
        }
        return valid >= 2
    }

    private fun mergeRegions(regions: List<IntRange>): List<IntRange> {
        if (regions.isEmpty()) return emptyList()
        val sorted = regions.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun isMpegTsValid(data: ByteArray): Boolean {
        if (data.size < MPEG_TS_PACKET_SIZE) return false
        if (data[0] != MPEG_TS_SYNC) return false

        var validPackets = 0
        for (i in 0 until minOf(data.size, 1024) step MPEG_TS_PACKET_SIZE) {
            if (i + MPEG_TS_PACKET_SIZE <= data.size && data[i] == MPEG_TS_SYNC) {
                validPackets++
            }
        }

        return validPackets >= 3
    }

    private fun isJpegHeader(data: ByteArray): Boolean {
        if (data.size < 3) return false
        return data[0] == JPEG_HEADER[0] &&
            data[1] == JPEG_HEADER[1] &&
            data[2] == JPEG_HEADER[2]
    }

    private fun isPngHeader(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == PNG_HEADER[0] &&
            data[1] == PNG_HEADER[1] &&
            data[2] == PNG_HEADER[2] &&
            data[3] == PNG_HEADER[3]
    }

    private fun isGifHeader(data: ByteArray): Boolean {
        if (data.size < 3) return false
        return data[0] == GIF_HEADER[0] &&
            data[1] == GIF_HEADER[1] &&
            data[2] == GIF_HEADER[2]
    }

    private fun detectDisguise(data: ByteArray): Int {
        val ftypOffset = findPattern(data, MP4_FTYP)
        if (ftypOffset >= 4) {
            return ftypOffset - 4
        }

        val riffOffset = findPattern(data, AVI_RIFF)
        if (riffOffset > 0) {
            return riffOffset
        }

        val mpegTsOffset = findMpegTsSync(data)
        if (mpegTsOffset > 0) {
            return mpegTsOffset
        }

        return 0
    }

    private fun isVideoFormat(data: ByteArray): Boolean = isMpegTsValid(data) ||
        findPattern(data, MP4_FTYP) >= 0 ||
        findPattern(data, AVI_RIFF) >= 0

    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..data.size - pattern.size) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) {
                return i
            }
        }
        return -1
    }

    private fun findMpegTsSync(data: ByteArray): Int {
        for (i in data.indices) {
            if (data[i] == MPEG_TS_SYNC) {
                var validCount = 0
                for (j in i until minOf(data.size, i + 1024) step MPEG_TS_PACKET_SIZE) {
                    if (j + MPEG_TS_PACKET_SIZE <= data.size && data[j] == MPEG_TS_SYNC) {
                        validCount++
                    }
                }
                if (validCount >= 2) {
                    return i
                }
            }
        }
        return -1
    }
}
