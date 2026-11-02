package eu.kanade.tachiyomi.animeextension.en.pornhub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Flashvars(
    val mediaDefinitions: List<MediaDefinition> = emptyList(),
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("video_title")
    val videoTitle: String? = null,
    @SerialName("video_duration")
    val videoDuration: String? = null,
)

@Serializable
data class MediaDefinition(
    val format: String? = null,
    val quality: JsonElement? = null,
    val videoUrl: String? = null,
    val height: Int? = null,
) {
    val qualityInt: Int?
        get() {
            if (quality is JsonPrimitive) {
                return quality.content.filter { it.isDigit() }.toIntOrNull() ?: height
            }
            if (quality is JsonArray) {
                for (elem in quality) {
                    val num = (elem as? JsonPrimitive)?.content?.filter { it.isDigit() }?.toIntOrNull()
                    if (num != null && num > 0) return num
                }
            }
            return height
        }
}
