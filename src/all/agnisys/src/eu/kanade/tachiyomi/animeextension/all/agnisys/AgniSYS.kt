package eu.kanade.tachiyomi.animeextension.all.agnisys

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

// ─── DTOs ────────────────────────────────────────────────────────────────────

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

@Serializable data class ItemListDto(val items: List<ItemDto>, val totalRecordCount: Int)

@Serializable data class ItemDto(
    val name: String,
    val type: ItemType,
    val id: String,
    val locationType: String = "",
    val imageTags: ImageDto = ImageDto(),
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
    val productionYear: Int? = null,
    val communityRating: Float? = null,
    val runTimeTicks: Long? = null,
    val dateCreated: String? = null,
    val mediaSources: List<MediaDto>? = null,
    val officialRating: String? = null,
) {
    @Serializable data class ImageDto(val primary: String? = null)

    @Serializable data class StudioDto(val name: String)

    fun toSAnime(baseUrl: String, userId: String): SAnime = SAnime.create().apply {
        url = "$baseUrl/Users/$userId/Items/$id"
        thumbnail_url = imageTags.primary?.getImageUrl(baseUrl, id)
        title = name
        description = buildString {
            overview?.replace("<br>", "\n")?.replace(Regex("<[^>]*>"), "")?.let {
                append(it)
                append("\n\n")
            }
            productionYear?.let { append("📅 Year: $it\n") }
            communityRating?.let { append("⭐ Rating: ${"%.1f".format(it)}\n") }
            officialRating?.let { append("🔞 Rating: $it\n") }
        }.trim()
        genre = genres?.joinToString(", ")
        author = studios?.joinToString(", ") { it.name }
        status = SAnime.COMPLETED
    }

    fun toSEpisode(baseUrl: String, userId: String): SEpisode = SEpisode.create().apply {
        val runtimeInSec = runTimeTicks?.div(10_000_000)
        val size = mediaSources?.firstOrNull()?.size?.formatBytes()
        val extraInfo = buildList {
            size?.let { add(it) }
            runtimeInSec?.formatSeconds()?.let { add(it) }
        }
        name = this@ItemDto.name
        url = "$baseUrl/Users/$userId/Items/$id"
        scanlator = extraInfo.joinToString(" • ")
        premiereDate?.let { date_upload = parseDateTime(it) }
        indexNumber?.let { episode_number = it.toFloat() }
    }

    private fun Long.formatSeconds(): String {
        val m = this / 60
        val h = m / 60
        val s = this % 60
        val rm = m % 60
        return "${if (h > 0) "${h}h " else ""}${if (rm > 0) "${rm}m " else ""}${s}s".trim()
    }
    private fun parseDateTime(date: String) = try {
        FORMATTER.parse(date.substringBefore(".").removeSuffix("Z"))!!.time
    } catch (_: Exception) {
        0L
    }
    companion object {
        private val FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
    }
}

@Serializable data class LoginDto(val accessToken: String, val sessionInfo: LoginSessionDto) {
    @Serializable data class LoginSessionDto(val userId: String)
}

@Serializable data class MediaDto(val size: Long? = null, val id: String? = null)

// ─── Helpers ─────────────────────────────────────────────────────────────────

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

object PascalCaseToCamelCase : JsonNamingStrategy {
    override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String = serialName.replaceFirstChar { it.uppercase() }
}

fun buildAuthHeader(deviceInfo: AgniSYS.DeviceInfo, token: String? = null): String {
    val params = listOf(
        "Client" to deviceInfo.clientName,
        "Version" to deviceInfo.version,
        "DeviceId" to deviceInfo.id,
        "Device" to deviceInfo.name,
        "Token" to token,
    )
    return params.filterNot { it.second == null }
        .joinToString(separator = ", ", prefix = "MediaBrowser ") {
            "${it.first}=\"${URLEncoder.encode(it.second!!.trim().replace("\n", " "), "UTF-8")}\""
        }
}

// ─── Filters ─────────────────────────────────────────────────────────────────

/**
 * All 17 libraries discovered on the server + an "All" catch-all.
 * IDs are the actual Jellyfin collection folder IDs.
 */
private val CATEGORIES = listOf(
    Pair("All Libraries", ""),
    Pair("Bangla Movies", "d6d7796e127b01138f8c2c4dc4b60f02"),
    Pair("Bollywood Movies", "21f5d92db0a8d1a5d0f526c8d8bca689"),
    Pair("Hollywood Movies", "cb65dd976efcaf208a83ba21856d1f67"),
    Pair("Animation Movies", "36b7cb06a8877931044683388b8dcc1f"),
    Pair("Horror Movies", "2ee7ab9a0e71901f8c71f54989f7ccdc"),
    Pair("Turkish Movies", "7e8896a2a0224459ee27eca3755892a5"),
    Pair("Iranian Movies", "e9b085bb5dc880331ea45e3b69fdbd02"),
    Pair("Korean & Hindi Movies", "a11aa43a6f987ab76773430ae0dee4db"),
    Pair("Chinese Movies", "b7e59048a8aaa09c96afc730dc18124a"),
    Pair("Web Series", "2704a4f904f147fd945a4f5b25ffa320"),
    Pair("IMDB Top Movies", "cf72f09a5e3ed3b3b412def312048962"),
    Pair("Tutorials", "0c3958d909ab63aeb7021619ffa5cac1"),
    Pair("Collections", "9d7ad6afe9afa2dab1a2f6e00ad28fa6"),
    Pair("Music Videos", "92f75a1a41e354235f4aded775720801"),
)

/** 27 genres found on the server */
private val GENRES = listOf(
    "Any", "Action", "Adult", "Adventure", "Animation", "Biography",
    "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
    "History", "Horror", "Music", "Musical", "Mystery", "Reality-TV",
    "Romance", "Science Fiction", "Sci-Fi", "Short", "Sport",
    "Talk-Show", "Thriller", "TV Movie", "War", "Western",
)

private val SORT_OPTIONS = arrayOf("Name", "Date Added", "Rating", "Release Year", "Play Count")
private val SORT_VALUES = arrayOf("SortName", "DateCreated,SortName", "CommunityRating,SortName", "ProductionYear,SortName", "PlayCount,SortName")

private class CategoryFilter(cats: List<Pair<String, String>>) : AnimeFilter.Select<String>("Library", cats.map { it.first }.toTypedArray()) {
    val cats = cats
    fun selectedId() = cats[state].second
}

private class GenreFilter : AnimeFilter.Select<String>("Genre", GENRES.toTypedArray()) {
    fun selectedGenre() = if (state == 0) null else GENRES[state]
}

private class SortFilter : AnimeFilter.Sort("Sort by", SORT_OPTIONS, Selection(0, true)) {
    fun sortValue() = SORT_VALUES[state!!.index]
    fun isAscending() = state!!.ascending
}

private class YearFilter : AnimeFilter.Text("Year (e.g. 2024)")

// ─── Source ───────────────────────────────────────────────────────────────────

class AgniSYS :
    Source(),
    UnmeteredSource,
    ConfigurableAnimeSource {

    override val name = "AgniSYS"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 84759302158234567L

    private val prefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val baseUrl: String
        get() = prefs.getString(PREF_BASE_URL, DEFAULT_URL)!!

    override val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        namingStrategy = PascalCaseToCamelCase
    }

    private val deviceInfo by lazy { buildDeviceInfo(Injekt.get<Application>()) }
    private var accessToken: String by prefs.delegate("access_token", "")
    private var userId: String by prefs.delegate("user_id", "")

    // ── OkHttp client with auto-login interceptor ──────────────────────────

    override val client = network.client.newBuilder()
        .dns(Dns.SYSTEM)
        .addInterceptor { chain ->
            val request = chain.request()
            // Skip auth on the login endpoint itself
            if (request.url.encodedPath.contains("AuthenticateByName")) {
                return@addInterceptor chain.proceed(request)
            }

            if (accessToken.isBlank()) synchronized(this) { if (accessToken.isBlank()) login() }

            val authed = request.newBuilder()
                .addHeader("Authorization", buildAuthHeader(deviceInfo, accessToken))
                .build()

            val response = chain.proceed(authed)
            if (response.code == 401) {
                synchronized(this) {
                    response.close()
                    login()
                    chain.proceed(
                        request.newBuilder()
                            .addHeader("Authorization", buildAuthHeader(deviceInfo, accessToken))
                            .build(),
                    )
                }
            } else {
                response
            }
        }.build()

    // ── Login ──────────────────────────────────────────────────────────────

    private fun login() {
        val headers = Headers.headersOf("Authorization", buildAuthHeader(deviceInfo))
        val body = buildJsonObject {
            put("Username", DEFAULT_USERNAME)
            put("Pw", DEFAULT_PASSWORD)
        }.toRequestBody(json)
        val resp = network.client.newCall(POST("$baseUrl/Users/AuthenticateByName", headers, body)).execute()
        if (resp.isSuccessful) {
            val dto = resp.parseAs<LoginDto>(json)
            accessToken = dto.accessToken
            userId = dto.sessionInfo.userId
        } else {
            resp.close()
            throw IOException("AgniSYS login failed: ${resp.code}")
        }
    }

    // ── Browse ─────────────────────────────────────────────────────────────

    /**
     * Popular = sorted by CommunityRating DESC.
     * This gives the highest-rated content first.
     */
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val startIndex = (page - 1) * PAGE_SIZE
        // Popular always browses all libraries recursively (Movie only)
        val url = baseItemsUrl(startIndex, parentId = null).newBuilder()
            .setQueryParameter("SortBy", "CommunityRating,SortName")
            .setQueryParameter("SortOrder", "Descending")
            .build()
        return parseItemsPage(url, page)
    }

    /**
     * Latest = sorted by DateCreated DESC — newest additions to the library.
     */
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val startIndex = (page - 1) * PAGE_SIZE
        // Latest always browses all libraries recursively (Movie only)
        val url = baseItemsUrl(startIndex, parentId = null).newBuilder()
            .setQueryParameter("SortBy", "DateCreated,SortName")
            .setQueryParameter("SortOrder", "Descending")
            .build()
        return parseItemsPage(url, page)
    }

    // ── Search + Filters ───────────────────────────────────────────────────

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val startIndex = (page - 1) * PAGE_SIZE

        // Extract parentId first — it controls whether we browse recursively or not
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val parentId = categoryFilter?.selectedId()?.takeIf { it.isNotBlank() }

        val url = baseItemsUrl(startIndex, parentId).newBuilder().apply {
            // Text search
            if (query.isNotBlank()) setQueryParameter("SearchTerm", query)

            // Apply remaining filters (skip CategoryFilter — already baked into baseItemsUrl)
            for (filter in filters) {
                when (filter) {
                    is CategoryFilter -> { /* handled above via parentId */ }

                    is GenreFilter -> {
                        val genre = filter.selectedGenre()
                        if (genre != null) setQueryParameter("Genres", genre)
                    }

                    is SortFilter -> {
                        setQueryParameter("SortBy", filter.sortValue())
                        setQueryParameter("SortOrder", if (filter.isAscending()) "Ascending" else "Descending")
                    }

                    is YearFilter -> {
                        val year = filter.state.trim()
                        if (year.isNotBlank()) setQueryParameter("Years", year)
                    }

                    else -> {}
                }
            }
        }.build()
        return parseItemsPage(url, page)
    }

    private suspend fun parseItemsPage(url: HttpUrl, page: Int): AnimesPage {
        val dto = client.newCall(GET(url)).await().parseAs<ItemListDto>(json)
        val animes = dto.items.map { it.toSAnime(baseUrl, userId) }
        return AnimesPage(animes, PAGE_SIZE * page < dto.totalRecordCount)
    }

    // ── Details ────────────────────────────────────────────────────────────

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val fields = "Genres,Studios,Overview,ProductionYear,CommunityRating,OfficialRating,MediaSources"
        val url = "${anime.url}?Fields=$fields"
        val item = client.newCall(GET(url)).await().parseAs<ItemDto>(json)
        return item.toSAnime(baseUrl, userId)
    }

    // ── Episodes ───────────────────────────────────────────────────────────

    /**
     * For this server every piece of content is a Movie.
     * Single-file items  → 1 episode
     * Folders (Web Series) → list child Movies recursively
     */
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val item = client.newCall(GET(anime.url)).await().parseAs<ItemDto>(json)
        return if (item.type == ItemType.Folder || item.type == ItemType.BoxSet) {
            // Folder / collection → fetch children
            val childUrl = "$baseUrl/Users/$userId/Items?ParentId=${item.id}&Recursive=true&IncludeItemTypes=Movie&SortBy=SortName&SortOrder=Ascending&Fields=MediaSources,OriginalTitle,SortName,Overview&Limit=500"
            val children = client.newCall(GET(childUrl)).await().parseAs<ItemListDto>(json)
            children.items.mapIndexed { idx, child ->
                child.toSEpisode(baseUrl, userId).also { it.episode_number = (idx + 1).toFloat() }
            }
        } else {
            // Single movie → wrap as 1 episode
            listOf(
                SEpisode.create().apply {
                    name = item.name
                    url = "$baseUrl/Users/$userId/Items/${item.id}"
                    episode_number = 1f
                    date_upload = item.premiereDate?.let {
                        try {
                            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).parse(it.substringBefore(".").removeSuffix("Z"))?.time ?: 0L
                        } catch (_: Exception) {
                            0L
                        }
                    } ?: 0L
                },
            )
        }
    }

    // ── Video ──────────────────────────────────────────────────────────────

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val item = client.newCall(GET(episode.url)).await().parseAs<ItemDto>(json)
        val videoHeaders = Headers.headersOf("Authorization", buildAuthHeader(deviceInfo, accessToken))
        val streams = mutableListOf<Video>()

        // Direct stream (no transcoding)
        val directUrl = "$baseUrl/Videos/${item.id}/stream?static=True&api_key=$accessToken"
        streams.add(Video(videoUrl = directUrl, videoTitle = "Direct Stream", url = directUrl, headers = videoHeaders))

        // Also offer a transcoded HLS stream for slow connections
        val hlsUrl = "$baseUrl/Videos/${item.id}/master.m3u8?api_key=$accessToken&VideoCodec=h264&AudioCodec=aac&MaxStreamingBitrate=8000000"
        streams.add(Video(videoUrl = hlsUrl, videoTitle = "HLS (8Mbps)", url = hlsUrl, headers = videoHeaders))

        return streams
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Base URL builder for /Users/{userId}/Items.
     *
     * KEY INSIGHT from live server inspection:
     *   - With NO parentId (All Libraries): Recursive=true, Movie only — 24,461 items
     *   - With parentId (specific library): NO Recursive — shows direct children.
     *     e.g. Web Series library direct children = 25 Folder items (one per show)
     *          Bangla Movies direct children = 295 Movie/Folder items
     *
     * This prevents Web Series from flooding the list with 837 individual episodes.
     * Instead, each series shows as one Folder card; tapping it fetches child episodes.
     */
    private fun baseItemsUrl(startIndex: Int, parentId: String? = null): HttpUrl = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
        addQueryParameter("StartIndex", startIndex.toString())
        addQueryParameter("Limit", PAGE_SIZE.toString())
        addQueryParameter("ImageTypeLimit", "1")
        addQueryParameter("EnableImageTypes", "Primary")
        addQueryParameter("Fields", "Genres,Studios,Overview,ProductionYear,CommunityRating,OfficialRating")
        addQueryParameter("SortBy", "SortName")
        addQueryParameter("SortOrder", "Ascending")

        if (parentId != null) {
            // Specific library selected: show direct children (Folder or Movie)
            // NO Recursive — prevents flooding with all sub-episodes
            addQueryParameter("ParentId", parentId)
            addQueryParameter("IncludeItemTypes", "Movie,Folder")
        } else {
            // All Libraries: recurse through everything, Movies only
            addQueryParameter("Recursive", "true")
            addQueryParameter("IncludeItemTypes", "Movie")
        }
    }.build()

    private suspend fun okhttp3.Call.await(): Response = withContext(Dispatchers.IO) { execute() }

    // ── Filter list ────────────────────────────────────────────────────────

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(CATEGORIES),
        GenreFilter(),
        SortFilter(),
        YearFilter(),
    )

    // ── Preferences ────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL
            title = "AgniSYS Server URL"
            summary = "Default: $DEFAULT_URL"
            setDefaultValue(DEFAULT_URL)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    (newValue as String).trim().toHttpUrl()
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }.also(screen::addPreference)
    }

    // ── Device info ────────────────────────────────────────────────────────

    data class DeviceInfo(val clientName: String, val version: String, val id: String, val name: String)

    private fun buildDeviceInfo(context: Application): DeviceInfo {
        val deviceId = prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
                .also { prefs.edit().putString("device_id", it).apply() }
        return DeviceInfo("Aniyomi", "1.0.0", deviceId, Build.MODEL)
    }

    // ── Constants ──────────────────────────────────────────────────────────

    companion object {
        private const val PREF_BASE_URL = "pref_base_url"
        private const val DEFAULT_URL = "http://182.252.81.180:8096"
        private const val DEFAULT_USERNAME = "vibe"
        private const val DEFAULT_PASSWORD = "121121"
        private const val PAGE_SIZE = 20
    }
}
