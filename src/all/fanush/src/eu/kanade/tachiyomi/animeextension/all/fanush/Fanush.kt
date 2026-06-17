package eu.kanade.tachiyomi.animeextension.all.fanush

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import extensions.utils.delegate
import extensions.utils.parseAs
import extensions.utils.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.apache.commons.text.StringSubstitutor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

@Serializable(with = ItemTypeSerializer::class)
enum class ItemType {
    BoxSet,
    Movie,
    Season,
    Series,
    Episode,
    Other,
    ;

    companion object {
        fun fromString(value: String): ItemType = values().find { it.name.equals(value, ignoreCase = true) } ?: Other
    }
}

object ItemTypeSerializer : KSerializer<ItemType> {
    override val descriptor = PrimitiveSerialDescriptor("ItemType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ItemType) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder) = ItemType.fromString(decoder.decodeString())
}

@Serializable data class ItemListDto(val items: List<ItemDto>, val totalRecordCount: Int)

@Serializable data class ItemDto(
    val name: String,
    val type: ItemType,
    val id: String,
    val locationType: String,
    val imageTags: ImageDto,
    val collectionType: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonName: String? = null,
    val seriesPrimaryImageTag: String? = null,
    val status: String? = null,
    val overview: String? = null,
    val genres: List<String>? = null,
    val studios: List<StudioDto>? = null,
    val originalTitle: String? = null,
    val sortName: String? = null,
    val indexNumber: Int? = null,
    val premiereDate: String? = null,
    val runTimeTicks: Long? = null,
    val dateCreated: String? = null,
    val mediaSources: List<MediaDto>? = null,
) {
    @Serializable data class ImageDto(val primary: String? = null)

    @Serializable class StudioDto(val name: String)
    fun toSAnime(baseUrl: String, userId: String): SAnime = SAnime.create().apply {
        val typeMap = mapOf(ItemType.Season to "seriesId,$seriesId", ItemType.Movie to "movie", ItemType.BoxSet to "boxSet", ItemType.Series to "series")
        url = baseUrl.toHttpUrl().newBuilder().addPathSegment("Users").addPathSegment(userId).addPathSegment("Items").addPathSegment(id).fragment(typeMap[type]).build().toString()
        thumbnail_url = imageTags.primary?.getImageUrl(baseUrl, id)
        title = name
        description = overview?.replace("<br>", "\n")?.replace(Regex("<[^>]*>"), "")
        genre = genres?.joinToString(", ")
        author = studios?.joinToString(", ") { it.name }
        status = if (type == ItemType.Movie) SAnime.COMPLETED else this@ItemDto.status.parseStatus()
        if (type == ItemType.Season) {
            if (locationType == "Virtual") {
                title = seriesName ?: "Season"
                seriesId?.let { thumbnail_url = seriesPrimaryImageTag?.getImageUrl(baseUrl, it) }
            } else {
                title = "$seriesName $name"
            }
            if (imageTags.primary == null) seriesId?.let { thumbnail_url = seriesPrimaryImageTag?.getImageUrl(baseUrl, it) }
        }
    }
    private fun String?.parseStatus(): Int = when (this?.lowercase()) {
        "ended" -> SAnime.COMPLETED
        "continuing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }
    fun toSEpisode(baseUrl: String, userId: String, prefix: String, epDetails: Set<String>, episodeTemplate: String): SEpisode = SEpisode.create().apply {
        val runtimeInSec = runTimeTicks?.div(10_000_000)
        val size = mediaSources?.firstOrNull()?.size?.formatBytes()
        val runTime = runtimeInSec?.formatSeconds()
        val epTitle = buildString {
            append(prefix)
            if (type != ItemType.Movie) append(this@ItemDto.name)
        }
        val values = mapOf("title" to epTitle, "originalTitle" to (originalTitle ?: ""), "sortTitle" to (sortName ?: ""), "type" to type.name, "typeShort" to type.name.replace("Episode", "Ep."), "seriesTitle" to (seriesName ?: ""), "seasonTitle" to (seasonName ?: ""), "number" to (indexNumber?.toString() ?: ""), "createdDate" to (dateCreated?.substringBefore("T") ?: ""), "releaseDate" to (premiereDate?.substringBefore("T") ?: ""), "size" to (size ?: ""), "sizeBytes" to (mediaSources?.firstOrNull()?.size?.toString() ?: ""), "runtime" to (runTime ?: ""), "runtimeS" to (runtimeInSec?.toString() ?: ""))
        val sub = StringSubstitutor(values, "{", "}")
        val extraInfo = buildList {
            if (epDetails.contains("Overview") && overview != null && type == ItemType.Episode) add(overview)
            if (epDetails.contains("Size") && size != null) add(size)
            if (epDetails.contains("Runtime") && runTime != null) add(runTime)
        }
        name = sub.replace(episodeTemplate).trim().removeSuffix("-").removePrefix("-").trim()
        url = "$baseUrl/Users/$userId/Items/$id"
        scanlator = extraInfo.joinToString(" • ")
        premiereDate?.let { date_upload = parseDateTime(it) }
        indexNumber?.let { episode_number = it.toFloat() }
        if (type == ItemType.Movie) episode_number = 1F
    }
    private fun Long.formatSeconds(): String {
        val minutes = this / 60
        val hours = minutes / 60
        val rs = this % 60
        val rm = minutes % 60
        return "${if (hours > 0) "${hours}h " else ""}${if (rm > 0) "${rm}m " else ""}${rs}s".trim()
    }
    private fun parseDateTime(date: String) = try {
        FORMATTER_DATE_TIME.parse(date.removeSuffix("Z"))!!.time
    } catch (_: Exception) {
        0L
    }
    companion object {
        private val FORMATTER_DATE_TIME = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
    }
}

@Serializable data class LoginDto(val accessToken: String, val sessionInfo: LoginSessionDto) {
    @Serializable data class LoginSessionDto(val userId: String)
}

@Serializable data class MediaDto(val size: Long? = null, val id: String? = null)

fun Long.formatBytes(): String = when {
    this >= 1_000_000_000L -> "%.2f GB".format(this / 1_000_000_000.0)
    this >= 1_000_000L -> "%.2f MB".format(this / 1_000_000.0)
    this >= 1_000L -> "%.2f KB".format(this / 1_000.0)
    else -> "$this B"
}
fun String.getImageUrl(baseUrl: String, id: String): String = baseUrl.toHttpUrl().newBuilder().addPathSegment("Items").addPathSegment(id).addPathSegment("Images").addPathSegment("Primary").addQueryParameter("tag", this).build().toString()
object PascalCaseToCamelCase : JsonNamingStrategy {
    override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String = serialName.replaceFirstChar { it.uppercase() }
}
fun getAuthHeader(deviceInfo: Fanush.DeviceInfo, token: String? = null): String {
    val params = listOf("Client" to deviceInfo.clientName, "Version" to deviceInfo.version, "DeviceId" to deviceInfo.id, "Device" to deviceInfo.name, "Token" to token)
    return params.filterNot { it.second == null }.joinToString(separator = ", ", prefix = "MediaBrowser ", transform = { "${it.first}=\"" + URLEncoder.encode(it.second!!.trim().replace("\n", " "), "UTF-8") + "\"" })
}

class Fanush :
    Source(),
    UnmeteredSource,
    ConfigurableAnimeSource {
    override val name = "Fanush"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419852L

    private val prefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val baseUrl: String
        get() = prefs.getString(PREF_BASE_URL, "http://103.132.95.221:8096")!!

    override val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        namingStrategy = PascalCaseToCamelCase
    }
    private val deviceInfo by lazy { getDeviceInfo(Injekt.get<Application>()) }

    private var accessToken: String by prefs.delegate("access_token", "")
    private var userId: String by prefs.delegate("user_id", "")

    override val client = network.client.newBuilder()
        .dns(Dns.SYSTEM)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("AuthenticateByName")) return@addInterceptor chain.proceed(request)

            if (accessToken.isBlank()) {
                synchronized(this) {
                    if (accessToken.isBlank()) login()
                }
            }

            val authRequest = request.newBuilder()
                .addHeader("Authorization", getAuthHeader(deviceInfo, accessToken))
                .build()

            val response = chain.proceed(authRequest)
            if (response.code == 401) {
                synchronized(this) {
                    response.close()
                    login()
                    val newAuthRequest = request.newBuilder()
                        .addHeader("Authorization", getAuthHeader(deviceInfo, accessToken))
                        .build()
                    return@addInterceptor chain.proceed(newAuthRequest)
                }
            }
            response
        }.build()

    private fun login() {
        val authHeaders = Headers.headersOf("Authorization", getAuthHeader(deviceInfo))
        val body = buildJsonObject {
            put("Username", "pcl")
            put("Pw", "pcl")
        }.toRequestBody(json)
        val resp = network.client.newCall(POST("$baseUrl/Users/AuthenticateByName", authHeaders, body)).execute()
        if (resp.isSuccessful) {
            val loginDto = resp.parseAs<LoginDto>(json)
            accessToken = loginDto.accessToken
            userId = loginDto.sessionInfo.userId
        } else {
            resp.close()
            throw IOException("Login failed: ${resp.code}")
        }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = getSearchAnime(page, "", AnimeFilterList())

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val startIndex = (page - 1) * 20
        val url = getItemsUrl(startIndex).newBuilder().apply {
            addQueryParameter("SortBy", "DateCreated,SortName")
            addQueryParameter("SortOrder", "Descending")
        }.build()
        return parseItemsPage(url, page)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val startIndex = (page - 1) * 20
        val url = getItemsUrl(startIndex).newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("SearchTerm", query)
            filters.forEach { filter ->
                when (filter) {
                    is CategoryFilter -> if (filter.toValue().isNotBlank()) setQueryParameter("ParentId", filter.toValue())

                    is SortFilter -> {
                        setQueryParameter("SortBy", filter.toSortValue())
                        setQueryParameter("SortOrder", if (filter.isAscending()) "Ascending" else "Descending")
                    }

                    else -> {}
                }
            }
        }.build()
        return parseItemsPage(url, page)
    }

    private suspend fun parseItemsPage(url: HttpUrl, page: Int): AnimesPage {
        val items = client.newCall(GET(url)).await().parseAs<ItemListDto>(json)
        val animeList = items.items.map { it.toSAnime(baseUrl, userId) }
        return AnimesPage(animeList, 20 * page < items.totalRecordCount)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val item = client.newCall(GET(anime.url)).await().parseAs<ItemDto>(json)
        return item.toSAnime(baseUrl, userId)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url.toHttpUrl()
        val itemId = url.pathSegments.last()
        val frag = url.fragment ?: ""

        // Correct approach: Use /Users/{userId}/Items with ParentId for series
        val epUrl = if (frag.startsWith("series")) {
            "$baseUrl/Users/$userId/Items?ParentId=$itemId&IncludeItemTypes=Episode&Recursive=true&Fields=DateCreated,OriginalTitle,SortName"
        } else {
            anime.url
        }

        val resp = client.newCall(GET(epUrl)).await()
        val items = if (epUrl.contains("ParentId")) {
            resp.parseAs<ItemListDto>(json).items
        } else {
            listOf(resp.parseAs<ItemDto>(json))
        }
        return items.map { it.toSEpisode(baseUrl, userId, "", emptySet(), "{number} - {title}") }.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val item = client.newCall(GET(episode.url)).await().parseAs<ItemDto>(json)
        val mediaSource = item.mediaSources?.firstOrNull() ?: return emptyList()
        val videoHeaders = Headers.headersOf("Authorization", getAuthHeader(deviceInfo, accessToken))
        val staticUrl = "$baseUrl/Videos/${item.id}/stream?static=True"
        return listOf(Video(videoUrl = staticUrl, videoTitle = "Source", headers = videoHeaders))
    }

    private fun getItemsUrl(startIndex: Int): HttpUrl = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
        addQueryParameter("StartIndex", startIndex.toString())
        addQueryParameter("Limit", "20")
        addQueryParameter("Recursive", "true")
        addQueryParameter("IncludeItemTypes", "Movie,Series")
        addQueryParameter("ImageTypeLimit", "1")
        addQueryParameter("EnableImageTypes", "Primary")
    }.build()

    data class DeviceInfo(val clientName: String, val version: String, val id: String, val name: String)
    private fun getDeviceInfo(context: Application): DeviceInfo {
        val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also { prefs.edit().putString("device_id", it).apply() }
        return DeviceInfo("Aniyomi", "1.0.0", deviceId, Build.MODEL)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL
            title = "Base URL"
            summary = "Jellyfin Server URL (default: http://103.132.95.221:8096)"
            setDefaultValue("http://103.132.95.221:8096")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val newUrl = (newValue as String).trim()
                    newUrl.toHttpUrl() // Validate URL
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }.also(screen::addPreference)
    }

    // Dynamic Filters
    private var categoriesCache: List<Pair<String, String>>? = null

    private fun fetchCategories(): List<Pair<String, String>> {
        if (categoriesCache == null) {
            val cachedJson = prefs.getString("pref_cached_categories", null)
            if (cachedJson != null) {
                try {
                    val list = mutableListOf<Pair<String, String>>()
                    val array = json.parseToJsonElement(cachedJson).jsonArray
                    array.forEach {
                        val obj = it.jsonObject
                        val name = obj["name"]!!.jsonPrimitive.content
                        val id = obj["id"]!!.jsonPrimitive.content
                        list.add(Pair(name, id))
                    }
                    categoriesCache = list
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        categoriesCache?.let { if (it.isNotEmpty()) return it }

        val list = mutableListOf<Pair<String, String>>(Pair("All", ""))
        try {
            if (userId.isNotBlank()) {
                val url = "$baseUrl/Users/$userId/Views"
                val resp = client.newCall(GET(url)).execute()
                if (resp.isSuccessful) {
                    val views = resp.parseAs<ItemListDto>(json)
                    views.items.forEach { list.add(Pair(it.name, it.id)) }

                    val jsonArray = buildJsonArray {
                        list.forEach { pair ->
                            add(
                                buildJsonObject {
                                    put("name", pair.first)
                                    put("id", pair.second)
                                },
                            )
                        }
                    }
                    prefs.edit().putString("pref_cached_categories", jsonArray.toString()).apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        categoriesCache = list
        return list
    }

    override fun getFilterList(): AnimeFilterList {
        val categories = fetchCategories()
        return AnimeFilterList(
            CategoryFilter(categories),
            SortFilter(),
        )
    }

    private class CategoryFilter(val categories: List<Pair<String, String>>) : AnimeFilter.Select<String>("Category", categories.map { it.first }.toTypedArray()) {
        fun toValue() = categories[state].second
    }

    private class SortFilter : AnimeFilter.Sort("Sort by", arrayOf("Name", "Date Added", "Premiere Date"), Selection(0, false)) {
        private val sortables = arrayOf("SortName", "DateCreated", "ProductionYear")
        fun toSortValue() = sortables[state!!.index]
        fun isAscending() = state!!.ascending
    }

    private suspend fun okhttp3.Call.await(): Response = withContext(Dispatchers.IO) { execute() }

    companion object {
        private const val PREF_BASE_URL = "pref_base_url"
    }
}
