package eu.kanade.tachiyomi.animeextension.all.anikoto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class EpisodeListResponse(
    val status: Int = 0,
    val result: String = "",
)

@Serializable
data class ServerListResponse(
    val status: Int = 0,
    val result: String = "",
)

@Serializable
data class ServerResponse(
    val status: Int = 0,
    val result: ServerResult? = null,
)

@Serializable
data class ServerResult(
    val url: String = "",
    @SerialName("skip_data") val skipData: SkipData? = null,
)

@Serializable
data class SkipData(
    val intro: List<Float> = emptyList(),
    val outro: List<Float> = emptyList(),
)

@Serializable
data class VidTubeSourcesResponse(
    val sources: VidTubeSources? = null,
    val tracks: List<VidTubeTrack> = emptyList(),
)

@Serializable
data class VidTubeSources(
    val file: String = "",
)

@Serializable
data class VidTubeTrack(
    val file: String = "",
    val label: String = "",
    val kind: String = "",
)

data class EpisodeMeta(
    val slug: String,
    val epNum: String,
    val malId: String,
    val timestamp: String,
    val dataIds: String,
    val hasSub: Boolean,
    val hasDub: Boolean,
    val epTitle: String,
) {
    fun encode(): String = buildString {
        append(slug)
        append("/ep-")
        append(epNum)
        append("|")
        append(malId)
        append("|")
        append(timestamp)
        append("|")
        append(dataIds)
        append("|")
        append(if (hasSub) "1" else "0")
        append("|")
        append(if (hasDub) "1" else "0")
        append("|")
        append(epTitle.replace("|", "│"))
    }

    companion object {
        fun decode(encoded: String): EpisodeMeta {
            val parts = encoded.split("|")
            val urlPart = parts.getOrElse(0) { "" }
            val slug = urlPart.substringBefore("/ep-")
            val epNum = urlPart.substringAfter("/ep-")
            val malId = parts.getOrElse(1) { "" }
            val timestamp = parts.getOrElse(2) { "" }
            val dataIds = parts.getOrElse(3) { "" }
            val hasSub = parts.getOrElse(4) { "0" } == "1"
            val hasDub = parts.getOrElse(5) { "0" } == "1"
            val epTitle = parts.drop(6).joinToString("|").replace("│", "|")
            return EpisodeMeta(slug, epNum, malId, timestamp, dataIds, hasSub, hasDub, epTitle)
        }
    }
}

@Serializable
data class MapperStreamToken(
    val serverName: String,
    val audio: String,
    val token: String,
)

fun parseMapperResponse(obj: JsonObject): List<MapperStreamToken> {
    val out = mutableListOf<MapperStreamToken>()
    for ((key, value) in obj) {
        if (!key.endsWith("-")) continue
        val serverName = key.removeSuffix("-")
        try {
            val serverObj = value.jsonObject
            for (audio in listOf("sub", "dub")) {
                serverObj[audio]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull?.let { url ->
                    out.add(MapperStreamToken(serverName, audio, url))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
    return out
}
