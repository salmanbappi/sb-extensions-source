package eu.kanade.tachiyomi.lib.unpacker

import android.util.Base64

object PlayerJsDecoder {

    data class PlaylistItem(
        val label: String?,
        val url: String
    )

    /**
     * Decodes a PlayerJS playlist or file payload.
     * Supports quality brackets (e.g. `[1080p]#2aHR0c...,[720p]#2aHR0c...`) and single payloads.
     */
    fun decode(input: String, trashList: List<String> = DEFAULT_TRASH): String {
        val cleanInput = input.trim()
        if (cleanInput.isBlank()) return ""

        if (cleanInput.contains("[") && cleanInput.contains("]")) {
            return decodePlaylist(cleanInput, trashList).joinToString(",") { item ->
                if (item.label != null) "[${item.label}]${item.url}" else item.url
            }
        }
        return decodePayload(cleanInput, trashList)
    }

    fun decodePlaylist(input: String, trashList: List<String> = DEFAULT_TRASH): List<PlaylistItem> {
        val items = mutableListOf<PlaylistItem>()
        val parts = input.split(",")
        var currentLabel: String? = null
        val currentPayload = StringBuilder()

        for (part in parts) {
            var p = part.trim()
            if (p.startsWith("[")) {
                if (currentPayload.isNotEmpty()) {
                    val decodedUrl = decodePayload(currentPayload.toString().trim(), trashList)
                    if (decodedUrl.isNotBlank()) {
                        items.add(PlaylistItem(currentLabel, decodedUrl))
                    }
                    currentPayload.clear()
                }
                val labelEnd = p.indexOf("]")
                if (labelEnd != -1) {
                    currentLabel = p.substring(1, labelEnd)
                    p = p.substring(labelEnd + 1)
                }
            }
            if (currentPayload.isNotEmpty()) {
                currentPayload.append(",")
            }
            currentPayload.append(p)
        }

        if (currentPayload.isNotEmpty()) {
            val decodedUrl = decodePayload(currentPayload.toString().trim(), trashList)
            if (decodedUrl.isNotBlank()) {
                items.add(PlaylistItem(currentLabel, decodedUrl))
            }
        }

        return items
    }

    fun decodePayload(payload: String, trashList: List<String> = DEFAULT_TRASH): String {
        var str = payload.trim()
        if (str.isEmpty()) return ""

        if (str.startsWith("#2") || str.startsWith("#")) {
            str = if (str.startsWith("#2")) str.substring(2) else str.substring(1)

            for (trash in trashList) {
                if (trash.isNotEmpty()) {
                    str = str.replace(trash, "")
                }
            }

            val decoded = runCatching {
                val pad = str.length % 4
                val padded = if (pad > 0) str + "=".repeat(4 - pad) else str
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()

            if (decoded != null && (decoded.contains("http") || decoded.contains("//") || decoded.contains("."))) {
                return decoded
            }
        }

        return str
    }

    private val DEFAULT_TRASH = listOf(
        "//", "_", "bk0", "bk1", "bk2", "bk3", "bk4", "=0", "=1", "=2", "=3", "=4"
    )
}
