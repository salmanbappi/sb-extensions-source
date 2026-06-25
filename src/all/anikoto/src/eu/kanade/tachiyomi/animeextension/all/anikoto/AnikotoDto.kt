package eu.kanade.tachiyomi.animeextension.all.anikoto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder

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

data class MapperStreamToken(
    val serverName: String,
    val audio: String,
    val token: String,
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

fun parseMapperResponse(obj: JsonObject): List<MapperStreamToken> {
    val out = mutableListOf<MapperStreamToken>()
    for ((key, value) in obj) {
        if (key == "status" || !key.endsWith("-")) continue
        val serverName = key.removeSuffix("-")
        val serverObj = value.jsonObject
        serverObj["sub"]?.let { subEl ->
            extractUrl(subEl)?.let { url ->
                out.add(MapperStreamToken(serverName, "sub", url))
            }
        }
        serverObj["dub"]?.let { dubEl ->
            extractUrl(dubEl)?.let { url ->
                out.add(MapperStreamToken(serverName, "dub", url))
            }
        }
    }
    return out
}

private fun extractUrl(el: JsonElement): String? = runCatching {
    el.jsonObject["url"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

data class HosterTask(
    val label: String,
    val token: String,
    val audioType: String,
    val source: String,
) {
    fun serverName(): String = label.substringAfter(" - ", label)

    companion object {
        private const val HOSTER_SELECTION_SEPARATOR = "|||"
        private const val HOSTER_TASK_SEPARATOR = "###"

        fun encodeSelection(metaUrl: String, tasks: List<HosterTask>): String {
            val taskBlob = tasks.joinToString(HOSTER_TASK_SEPARATOR) { task ->
                listOf(task.label, task.token, task.audioType, task.source)
                    .joinToString(HOSTER_SELECTION_SEPARATOR) { URLEncoder.encode(it, "UTF-8") }
            }
            return listOf(metaUrl, taskBlob)
                .joinToString(HOSTER_SELECTION_SEPARATOR) { URLEncoder.encode(it, "UTF-8") }
        }

        fun decodeSelection(encoded: String): Pair<String, List<HosterTask>>? = runCatching {
            val parts = encoded.split(HOSTER_SELECTION_SEPARATOR, limit = 2)
            if (parts.size != 2) return null
            val metaUrl = URLDecoder.decode(parts[0], "UTF-8")
            val taskBlob = URLDecoder.decode(parts[1], "UTF-8")
            val tasks = taskBlob.split(HOSTER_TASK_SEPARATOR).mapNotNull { taskPart ->
                val taskFields = taskPart.split(HOSTER_SELECTION_SEPARATOR)
                if (taskFields.size != 4) return@mapNotNull null
                val decoded = taskFields.map { URLDecoder.decode(it, "UTF-8") }
                HosterTask(
                    label = decoded[0],
                    token = decoded[1],
                    audioType = decoded[2],
                    source = decoded[3],
                )
            }
            metaUrl to tasks
        }.getOrNull()
    }
}
