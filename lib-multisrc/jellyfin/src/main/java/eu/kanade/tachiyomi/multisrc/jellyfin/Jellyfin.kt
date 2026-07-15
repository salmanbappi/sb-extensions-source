package eu.kanade.tachiyomi.multisrc.jellyfin

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.Sort.Selection
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import extensions.utils.addEditTextPreference
import extensions.utils.addSwitchPreference
import extensions.utils.delegate
import extensions.utils.parseAs
import extensions.utils.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    Folder,
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

@Serializable data class ItemListDto(val items: List<ItemDto> = emptyList(), val totalRecordCount: Int = 0)

@Serializable data class ItemDto(
    val name: String,
    val type: ItemType? = ItemType.Other,
    val id: String,
    val locationType: String? = null,
    val imageTags: ImageDto? = ImageDto(),
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
    val parentIndexNumber: Int? = null,
    val premiereDate: String? = null,
    val productionYear: Int? = null,
    val communityRating: Float? = null,
    val criticRating: Float? = null,
    val officialRating: String? = null,
    val productionLocations: List<String>? = null,
    val tags: List<String>? = null,
    val taglines: List<String>? = null,
    val people: List<PersonDto>? = null,
    val runTimeTicks: Long? = null,
    val dateCreated: String? = null,
    val mediaSources: List<MediaDto>? = null,
    val backdropImageTags: List<String>? = null,
) {
    @Serializable data class ImageDto(val primary: String? = null)

    @Serializable class StudioDto(val name: String)

    @Serializable data class PersonDto(val name: String, val type: String)

    fun toSAnime(baseUrl: String, userId: String): SAnime = SAnime.create().apply {
        val typeMap = mapOf(ItemType.Season to "seriesId,$seriesId", ItemType.Movie to "movie", ItemType.BoxSet to "boxSet", ItemType.Series to "series")
        url = baseUrl.toHttpUrl().newBuilder().addPathSegment("Users").addPathSegment(userId).addPathSegment("Items").addPathSegment(id).fragment(typeMap[type ?: ItemType.Other]).build().toString()
        thumbnail_url = imageTags?.primary?.getImageUrl(baseUrl, id)
        title = name
        description = buildString {
            overview?.replace("<br>", "\n")?.replace(Regex("<[^>]*>"), "")?.let {
                append(it)
                append("\n\n")
            }
            productionYear?.let { append("📅 Year: $it\n") }
            communityRating?.let { append("⭐ Rating: ${"%.1f".format(it)}\n") }
            criticRating?.let { append("🍅 Critic Rating: ${"%.1f".format(it)}\n") }
            officialRating?.let { append("🔞 Rating: $it\n") }
            val studioNames = studios?.map { it.name }?.filter { it.isNotBlank() }
            if (!studioNames.isNullOrEmpty()) {
                append("🏢 Studio: ${studioNames.joinToString(", ")}\n")
            }
            val location = productionLocations?.firstOrNull()
            if (!location.isNullOrBlank()) {
                append("🌐 Country: $location\n")
            }
            val mediaSource = mediaSources?.firstOrNull()
            if (mediaSource != null) {
                mediaSource.container?.let { append("📦 Container: ${it.uppercase(Locale.ENGLISH)}\n") }
                val streams = mediaSource.mediaStreams
                if (streams != null) {
                    val videoStreams = streams.filter { it.type.equals("Video", ignoreCase = true) }
                    if (videoStreams.isNotEmpty()) {
                        val videoInfo = videoStreams.joinToString(", ") { vs ->
                            buildString {
                                if (vs.width != null && vs.height != null) {
                                    append("${vs.width}x${vs.height}")
                                }
                                vs.codec?.let {
                                    if (isNotEmpty()) append(" ")
                                    append(it.uppercase(Locale.ENGLISH))
                                }
                            }.trim()
                        }
                        if (videoInfo.isNotBlank()) {
                            append("🎥 Video: $videoInfo\n")
                        }
                    }
                    val audioStreams = streams.filter { it.type.equals("Audio", ignoreCase = true) }
                    if (audioStreams.isNotEmpty()) {
                        val audioInfo = audioStreams.joinToString(", ") { asStream ->
                            buildString {
                                val lang = asStream.language?.let { code ->
                                    val locale = Locale(code)
                                    locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it != code } ?: code
                                } ?: asStream.title
                                if (!lang.isNullOrBlank()) {
                                    append(lang.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() })
                                }
                                asStream.codec?.let {
                                    if (isNotEmpty()) append(" (")
                                    append(it.uppercase(Locale.ENGLISH))
                                    if (isNotEmpty()) append(")")
                                }
                            }.trim()
                        }
                        if (audioInfo.isNotBlank()) {
                            append("🔊 Audio: $audioInfo\n")
                        }
                    }
                    val subtitleStreams = streams.filter { it.type.equals("Subtitle", ignoreCase = true) }
                    if (subtitleStreams.isNotEmpty()) {
                        val subInfo = subtitleStreams.mapNotNull {
                            it.language?.let { code ->
                                val locale = Locale(code)
                                locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it != code } ?: code
                            } ?: it.title
                        }.map { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() } }
                            .distinct()
                            .joinToString(", ")
                        if (subInfo.isNotBlank()) {
                            append("💬 Subtitles: $subInfo\n")
                        }
                    }
                }
            }
            if (!tags.isNullOrEmpty()) {
                append("\n🏷️ Tags: ${tags.joinToString(", ")}\n")
            }
            if (!taglines.isNullOrEmpty()) {
                append("\n💬 ${taglines.joinToString("\n💬 ")}\n")
            }
        }.trim()
        genre = genres?.joinToString(", ")
        val directors = people?.filter { it.type.equals("Director", ignoreCase = true) }?.map { it.name }
        val writers = people?.filter { it.type.equals("Writer", ignoreCase = true) }?.map { it.name }
        author = directors?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: studios?.joinToString(", ") { it.name }
        artist = writers?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        status = if (type == ItemType.Movie) SAnime.COMPLETED else this@ItemDto.status.parseStatus()
        if (type == ItemType.Season) {
            if (locationType == "Virtual") {
                title = seriesName ?: "Season"
                seriesId?.let { thumbnail_url = seriesPrimaryImageTag?.getImageUrl(baseUrl, it) }
            } else {
                title = "$seriesName $name"
            }
            if (imageTags?.primary == null) seriesId?.let { thumbnail_url = seriesPrimaryImageTag?.getImageUrl(baseUrl, it) }
        }
    }

    private fun String?.parseStatus(): Int = when (this?.lowercase()) {
        "ended" -> SAnime.COMPLETED
        "continuing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    fun toSEpisode(
        baseUrl: String,
        userId: String,
        showThumbnails: Boolean,
        showSummary: Boolean,
        episodeTemplate: String,
    ): SEpisode = SEpisode.create().apply {
        val runtimeInSec = runTimeTicks?.div(10_000_000)
        val size = mediaSources?.firstOrNull()?.size?.formatBytes()
        val runTime = runtimeInSec?.formatSeconds()
        val epTitle = buildString {
            if (type != ItemType.Movie) {
                append(this@ItemDto.name)
            } else {
                append("Movie")
            }
        }
        val values = mapOf(
            "title" to epTitle,
            "originalTitle" to (originalTitle ?: ""),
            "sortTitle" to (sortName ?: ""),
            "type" to (type?.name ?: ""),
            "typeShort" to (type?.name?.replace("Episode", "Ep.") ?: ""),
            "seriesTitle" to (seriesName ?: ""),
            "seasonTitle" to (seasonName ?: ""),
            "season" to (if (type == ItemType.Movie) "" else (parentIndexNumber?.toString() ?: "")),
            "seasonShort" to (if (type == ItemType.Movie) "" else (parentIndexNumber?.let { "S$it " } ?: "")),
            "seasonLong" to (if (type == ItemType.Movie) "" else (parentIndexNumber?.let { "Season $it " } ?: "")),
            "number" to (if (type == ItemType.Movie) "" else (indexNumber?.toString() ?: "")),
            "numberShort" to (if (type == ItemType.Movie) "" else (indexNumber?.let { "Ep. $it" } ?: "")),
            "createdDate" to (dateCreated?.substringBefore("T") ?: ""),
            "releaseDate" to (premiereDate?.substringBefore("T") ?: ""),
            "size" to (size ?: ""),
            "sizeBytes" to (mediaSources?.firstOrNull()?.size?.toString() ?: ""),
            "runtime" to (runTime ?: ""),
            "runtimeS" to (runtimeInSec?.toString() ?: ""),
        )
        val sub = StringSubstitutor(values, "{", "}")
        name = sub.replace(episodeTemplate).trim().removeSuffix("-").removePrefix("-").trim()
        url = "$baseUrl/Users/$userId/Items/$id"

        val extraInfo = buildList {
            if (size != null) add(size)
            if (runTime != null) add(runTime)
        }
        scanlator = extraInfo.joinToString(" • ")

        premiereDate?.let { date_upload = parseDateTime(it) }
        indexNumber?.let { episode_number = it.toFloat() }
        if (type == ItemType.Movie) episode_number = 1F

        if (showThumbnails) {
            preview_url = if (type == ItemType.Movie) {
                backdropImageTags?.firstOrNull()?.getBackdropImageUrl(baseUrl, id)
            } else {
                imageTags?.primary?.getImageUrl(baseUrl, id)
            }
        }
        if (showSummary && type != ItemType.Movie) {
            summary = overview?.replace("<br>", "\n")?.replace(Regex("<[^>]*>"), "")
        }
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

@Serializable data class MediaDto(
    val size: Long? = null,
    val id: String? = null,
    val container: String? = null,
    val mediaStreams: List<MediaStreamDto>? = null,
)

@Serializable data class MediaStreamDto(
    val type: String? = null,
    val codec: String? = null,
    val displayTitle: String? = null,
    val language: String? = null,
    val title: String? = null,
    val bitRate: Int? = null,
    val channels: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
)

fun Long.formatBytes(): String = when {
    this >= 1_000_000_000L -> "%.2f GB".format(this / 1_000_000_000.0)
    this >= 1_000_000L -> "%.2f MB".format(this / 1_000_000.0)
    this >= 1_000L -> "%.2f KB".format(this / 1_000.0)
    else -> "$this B"
}

fun String.getImageUrl(baseUrl: String, id: String): String = baseUrl.toHttpUrl().newBuilder()
    .addPathSegment("Items").addPathSegment(id)
    .addPathSegment("Images").addPathSegment("Primary")
    .addQueryParameter("tag", this)
    .build().toString()

fun String.getBackdropImageUrl(baseUrl: String, id: String): String = baseUrl.toHttpUrl().newBuilder()
    .addPathSegment("Items").addPathSegment(id)
    .addPathSegment("Images").addPathSegment("Backdrop")
    .addPathSegment("0")
    .addQueryParameter("tag", this)
    .build().toString()

object PascalCaseToCamelCase : JsonNamingStrategy {
    override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String = serialName.replaceFirstChar { it.uppercase() }
}

fun buildAuthHeader(deviceInfo: Jellyfin.DeviceInfo, token: String? = null): String {
    val params = listOf(
        "Client" to deviceInfo.clientName,
        "Version" to deviceInfo.version,
        "DeviceId" to deviceInfo.id,
        "Device" to deviceInfo.name,
        "Token" to token,
    )
    return params.filterNot { it.second == null }.joinToString(
        separator = ", ",
        prefix = "MediaBrowser ",
        transform = { "${it.first}=\"" + URLEncoder.encode(it.second!!.trim().replace("\n", " "), "UTF-8").replace("+", "%20") + "\"" },
    )
}

abstract class Jellyfin(
    override val name: String,
    override val lang: String = "all",
    override val supportsLatest: Boolean = true,
) : Source(),
    UnmeteredSource,
    ConfigurableAnimeSource {

    abstract val defaultBaseUrl: String
    open val defaultUsername: String = ""
    open val defaultPassword: String = ""
    open val hasCustomAuthSettings: Boolean = false

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL, defaultBaseUrl)!!.removeSuffix("/")

    open val username: String
        get() = if (hasCustomAuthSettings) {
            preferences.getString(PREF_USERNAME, defaultUsername)!!
        } else {
            defaultUsername
        }

    open val password: String
        get() = if (hasCustomAuthSettings) {
            preferences.getString(PREF_PASSWORD, defaultPassword)!!
        } else {
            defaultPassword
        }

    override val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        namingStrategy = PascalCaseToCamelCase
    }

    protected val deviceInfo by lazy { getDeviceInfo(Injekt.get<Application>()) }

    protected var accessToken: String by preferences.delegate("access_token", "")
    protected var userId: String by preferences.delegate("user_id", "")

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
                .addHeader("Authorization", buildAuthHeader(deviceInfo, accessToken))
                .build()

            val response = chain.proceed(authRequest)
            if (response.code == 401) {
                synchronized(this) {
                    response.close()
                    login()
                    val newAuthRequest = request.newBuilder()
                        .addHeader("Authorization", buildAuthHeader(deviceInfo, accessToken))
                        .build()
                    return@addInterceptor chain.proceed(newAuthRequest)
                }
            }
            response
        }.build()

    protected fun login() {
        val authHeaders = Headers.headersOf("Authorization", buildAuthHeader(deviceInfo))
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
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

    protected suspend fun parseItemsPage(url: HttpUrl, page: Int): AnimesPage {
        val items = client.newCall(GET(url)).await().parseAs<ItemListDto>(json)
        val animeList = items.items.map { it.toSAnime(baseUrl, userId) }
        return AnimesPage(animeList, 20 * page < items.totalRecordCount)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val fields = "Genres,Studios,Overview,ProductionYear,CommunityRating,OfficialRating,MediaSources,Tags,Taglines,CriticRating,ProductionLocations,People"
        val url = "${anime.url}?Fields=$fields"
        val item = client.newCall(GET(url)).await().parseAs<ItemDto>(json)
        return item.toSAnime(baseUrl, userId)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url.toHttpUrl()
        val itemId = url.pathSegments.last()
        val frag = url.fragment ?: ""
        val epUrl = when {
            frag.startsWith("series") -> "$baseUrl/Shows/$itemId/Episodes?Fields=DateCreated,OriginalTitle,SortName,Overview,ParentIndexNumber"
            else -> "$baseUrl/Users/$userId/Items/$itemId?Fields=DateCreated,OriginalTitle,SortName,Overview,MediaSources,BackdropImageTags"
        }
        val resp = client.newCall(GET(epUrl)).await()
        val items = if (epUrl.contains("Episodes")) resp.parseAs<ItemListDto>(json).items else listOf(resp.parseAs<ItemDto>(json))
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)
        val showSummary = preferences.getBoolean(PREF_SHOW_SUMMARY_KEY, true)
        val episodeTemplate = preferences.getString(PREF_EPISODE_NAME_TEMPLATE_KEY, PREF_EPISODE_NAME_TEMPLATE_DEFAULT)!!
        return items.map { it.toSEpisode(baseUrl, userId, showThumbnails, showSummary, episodeTemplate) }.reversed()
    }

    open fun relatedAnimeListRequest(anime: SAnime): okhttp3.Request {
        val url = anime.url.toHttpUrl()
        val itemId = url.pathSegments.last()
        return GET("$baseUrl/Items/$itemId/Similar?UserId=$userId&Limit=12", headers)
    }

    open fun relatedAnimeListParse(response: Response): List<SAnime> {
        val dto = response.parseAs<ItemListDto>(json)
        return dto.items.map { it.toSAnime(baseUrl, userId) }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val item = client.newCall(GET(episode.url)).await().parseAs<ItemDto>(json)
        val videoHeaders = Headers.headersOf("Authorization", buildAuthHeader(deviceInfo, accessToken))
        val staticUrl = "$baseUrl/Videos/${item.id}/stream?static=True"
        return listOf(Video(videoUrl = staticUrl, videoTitle = "Source", headers = videoHeaders))
    }

    protected open fun getItemsUrl(startIndex: Int): HttpUrl = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
        addQueryParameter("StartIndex", startIndex.toString())
        addQueryParameter("Limit", "20")
        addQueryParameter("Recursive", "true")
        addQueryParameter("IncludeItemTypes", "Movie,Series")
        addQueryParameter("ImageTypeLimit", "1")
        addQueryParameter("EnableImageTypes", "Primary")
    }.build()

    data class DeviceInfo(val clientName: String, val version: String, val id: String, val name: String)
    protected fun getDeviceInfo(context: Application): DeviceInfo {
        val deviceId = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also { preferences.edit().putString("device_id", it).apply() }
        return DeviceInfo("Aniyomi", "1.0.0", deviceId, Build.MODEL)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_BASE_URL,
            default = defaultBaseUrl,
            title = "Base URL",
            summary = "Server URL (default: $defaultBaseUrl)",
            validate = {
                try {
                    it.toHttpUrl()
                    true
                } catch (_: Exception) {
                    false
                }
            },
        )

        if (hasCustomAuthSettings) {
            screen.addEditTextPreference(
                key = PREF_USERNAME,
                default = defaultUsername,
                title = "Username",
                summary = "Login username (default: $defaultUsername)",
            )
            screen.addEditTextPreference(
                key = PREF_PASSWORD,
                default = defaultPassword,
                title = "Password",
                summary = "Login password (default: $defaultPassword)",
            )
        }

        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            default = true,
            title = "Show episode thumbnails",
            summary = "Fetch and display thumbnail images in the episode list.",
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_SUMMARY_KEY,
            default = true,
            title = "Show episode summaries",
            summary = "Fetch and display summaries in the episode list.",
        )

        screen.addEditTextPreference(
            key = PREF_EPISODE_NAME_TEMPLATE_KEY,
            default = PREF_EPISODE_NAME_TEMPLATE_DEFAULT,
            title = "Episode title format",
            summary = "Format template for episode titles (default: {seasonShort}{numberShort} - {title})",
        )
    }

    // Dynamic Filters
    private var categoriesCache: List<Pair<String, String>>? = null

    protected fun fetchCategories(): List<Pair<String, String>> {
        if (categoriesCache == null) {
            val cachedJson = preferences.getString("pref_cached_categories", null)
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

        categoriesCache?.let { if (it.size > 1) return it }

        val list = mutableListOf<Pair<String, String>>(Pair("All", ""))
        try {
            if (userId.isNotBlank()) {
                val url = "$baseUrl/Users/$userId/Views"
                val resp = runBlocking(Dispatchers.IO) {
                    client.newCall(GET(url)).execute()
                }
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
                    preferences.edit().putString("pref_cached_categories", jsonArray.toString()).apply()
                    categoriesCache = list
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return categoriesCache ?: list
    }

    override fun getFilterList(): AnimeFilterList {
        val categories = fetchCategories()
        return AnimeFilterList(
            CategoryFilter(categories),
            SortFilter(),
        )
    }

    protected class CategoryFilter(val categories: List<Pair<String, String>>) : AnimeFilter.Select<String>("Category", categories.map { it.first }.toTypedArray()) {
        fun toValue() = categories[state].second
    }

    protected class SortFilter : AnimeFilter.Sort("Sort by", arrayOf("Name", "Date Added", "Premiere Date"), Selection(0, false)) {
        private val sortables = arrayOf("SortName", "DateCreated", "ProductionYear")
        fun toSortValue() = sortables[state!!.index]
        fun isAscending() = state!!.ascending
    }

    private suspend fun okhttp3.Call.await(): Response = withContext(Dispatchers.IO) { execute() }

    companion object {
        protected const val PREF_BASE_URL = "pref_base_url"
        protected const val PREF_USERNAME = "pref_username"
        protected const val PREF_PASSWORD = "pref_password"
        protected const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"
        protected const val PREF_SHOW_SUMMARY_KEY = "pref_show_summary"
        protected const val PREF_EPISODE_NAME_TEMPLATE_KEY = "pref_episode_name_template"
        protected const val PREF_EPISODE_NAME_TEMPLATE_DEFAULT = "{seasonShort}{numberShort} - {title}"
    }
}
