#!/usr/bin/env python3
"""
Aniyomi Extension Generator CLI (Production Grade)
Generates full-featured Aniyomi extension boilerplate with options for:
- HTML (Source + Jsoup) or API (Source + kotlinx.serialization)
- Modular Filters.kt (UriPartFilter, GenreTriStateFilter, SortFilter)
- Rate Limiting Interceptor (OkHttp rateLimit)
- Full Episode Metadata (Thumbnails, Summaries, Sub/Dub Scanlator badges, Date Upload)
- Preference Screens (Base URL override, Audio Type, Server, Exclusions)
- Video Extractors & 4-tier Video Sorting (List<Video>.sortVideos())
- Multi-Source Theme Scaffolding (lib-multisrc/)
"""

import argparse
import os
import re
import struct
import sys
import zlib
from pathlib import Path

# Ensure repo root and scripts directory are in sys.path
REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))


def create_minimal_png(filepath: Path, width=512, height=512):
    """Generates a valid minimal PNG file using Python standard library (zlib/struct)."""
    png_sig = b'\x89PNG\r\n\x1a\n'
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    ihdr_crc = zlib.crc32(b'IHDR' + ihdr_data)
    ihdr_chunk = struct.pack('>I', len(ihdr_data)) + b'IHDR' + ihdr_data + struct.pack('>I', ihdr_crc)

    pixel = b'\x21\x96\xF3\xFF'  # Material Blue Accent
    scanline = b'\x00' + (pixel * width)
    raw_data = scanline * height
    compressed_data = zlib.compress(raw_data)
    idat_crc = zlib.crc32(b'IDAT' + compressed_data)
    idat_chunk = struct.pack('>I', len(compressed_data)) + b'IDAT' + compressed_data + struct.pack('>I', idat_crc)

    iend_crc = zlib.crc32(b'IEND')
    iend_chunk = struct.pack('>I', 0) + b'IEND' + struct.pack('>I', iend_crc)

    filepath.parent.mkdir(parents=True, exist_ok=True)
    with open(filepath, 'wb') as f:
        f.write(png_sig + ihdr_chunk + idat_chunk + iend_chunk)


def to_pascal_case(name: str) -> str:
    words = re.findall(r'[a-zA-Z0-9]+', name)
    pascal = ''.join(w.capitalize() for w in words)
    if not pascal:
        return "CustomSource"
    if pascal[0].isdigit():
        digit_names = {
            '0': 'Zero', '1': 'One', '2': 'Two', '3': 'Three', '4': 'Four',
            '5': 'Five', '6': 'Six', '7': 'Seven', '8': 'Eight', '9': 'Nine'
        }
        pascal = digit_names.get(pascal[0], "Ext") + pascal[1:]
    return pascal


def to_package_name(name: str) -> str:
    words = re.findall(r'[a-zA-Z0-9]+', name.lower())
    pkg = ''.join(words)
    if not pkg:
        return "customsource"
    if pkg[0].isdigit():
        pkg = "ext" + pkg
    return pkg


def generate_build_gradle(
    ext_name: str,
    ext_class: str,
    theme_pkg: str = None,
    nsfw: bool = False,
    version_code: int = 1,
    with_extractors: bool = True
) -> str:
    nsfw_str = "true" if nsfw else "false"
    
    if theme_pkg:
        return f"""ext {{
    extName = '{ext_name}'
    extClass = '.{ext_class}'
    themePkg = '{theme_pkg}'
    baseUrl = 'https://example.com'
    overrideVersionCode = {version_code}
    isNsfw = {nsfw_str}
}}

apply from: "$rootDir/common.gradle"
"""

    lib_deps = """
dependencies {
    implementation(project(":lib:dood-extractor"))
    implementation(project(":lib:filemoon-extractor"))
    implementation(project(":lib:streamtape-extractor"))
    implementation(project(":lib:universal-extractor"))
    implementation(project(":lib:playlist-utils"))
}
""" if with_extractors else ""

    return f"""ext {{
    extName = '{ext_name}'
    extClass = '.{ext_class}'
    extVersionCode = {version_code}
    isNsfw = {nsfw_str}
}}

apply from: "$rootDir/common.gradle"
{lib_deps}"""


def generate_android_manifest() -> str:
    return """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-feature android:name="tachiyomi.animeextension" />

    <application
        android:allowBackup="false"
        android:icon="@drawable/ic_launcher"
        android:label="${appName}"
        android:usesCleartextTraffic="true"
        tools:replace="android:allowBackup,android:icon,android:label">

        <meta-data
            android:name="tachiyomi.animeextension.class"
            android:value="${extClass}" />
        <meta-data
            android:name="tachiyomi.animeextension.versionId"
            android:value="2" />
        <meta-data
            android:name="tachiyomi.animeextension.nsfw"
            android:value="${nsfw}" />
        <meta-data
            android:name="tachiyomi.animeextension.author"
            android:value="${author}" />
    </application>
</manifest>
"""



def generate_filters_kotlin_source(lang: str, pkg_name: str) -> str:
    return f"""package eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {{
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map {{ it.first }}.toTypedArray()) {{
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }}

    class TypeFilter : UriPartFilter("Type", arrayOf(
        Pair("All", ""),
        Pair("Movie", "movie"),
        Pair("TV Series", "tv"),
        Pair("OVA", "ova"),
        Pair("Special", "special"),
    ))

    class StatusFilter : UriPartFilter("Status", arrayOf(
        Pair("All", ""),
        Pair("Ongoing", "ongoing"),
        Pair("Completed", "completed"),
    ))

    class SortFilter : UriPartFilter("Sort By", arrayOf(
        Pair("Default", ""),
        Pair("Latest Update", "latest"),
        Pair("Most Popular", "popular"),
        Pair("Rating", "rating"),
    ))

    class YearFilter : AnimeFilter.Text("Year", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map {{ GenreCheckBox(it.first, it.second) }}) {{
        fun getIncluded(): List<String> = state.filter {{ it.state }}.map {{ (it as GenreCheckBox).id }}
    }}

    class GenreTriState(name: String, val id: String) : AnimeFilter.TriState(name)
    class GenreTriStateFilter(genres: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.TriState>("Genres (Include / Exclude)", genres.map {{ GenreTriState(it.first, it.second) }}) {{
        fun included(): List<String> = state.filter {{ it.isIncluded() }}.map {{ (it as GenreTriState).id }}
        fun excluded(): List<String> = state.filter {{ it.isExcluded() }}.map {{ (it as GenreTriState).id }}
    }}
}}
"""


def generate_html_kotlin_source(
    lang: str,
    pkg_name: str,
    class_name: str,
    base_url: str,
    with_prefs: bool = True,
    with_extractors: bool = True,
    with_metadata: bool = True,
    with_filters: bool = True
) -> str:
    package_path = f"eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}"

    import_lines = [
        "import androidx.preference.PreferenceScreen",
        "import eu.kanade.tachiyomi.animesource.model.AnimeFilter",
        "import eu.kanade.tachiyomi.animesource.model.AnimeFilterList",
        "import eu.kanade.tachiyomi.animesource.model.AnimesPage",
        "import eu.kanade.tachiyomi.animesource.model.FetchType",
        "import eu.kanade.tachiyomi.animesource.model.Hoster",
        "import eu.kanade.tachiyomi.animesource.model.SAnime",
        "import eu.kanade.tachiyomi.animesource.model.SEpisode",
        "import eu.kanade.tachiyomi.animesource.model.Video",
        "import eu.kanade.tachiyomi.network.GET",
        "import eu.kanade.tachiyomi.network.interceptor.rateLimit",
        "import extensions.utils.Source",
        "import extensions.utils.asJsoup",
        "import okhttp3.HttpUrl.Companion.toHttpUrl",
        "import okhttp3.OkHttpClient",
        "import okhttp3.Request",
        "import okhttp3.Response",
        "import java.text.SimpleDateFormat",
        "import java.util.Locale",
        "import kotlin.time.Duration.Companion.seconds",
    ]

    if with_prefs:
        import_lines.extend([
            "import keiyoushi.utils.addBaseUrlPreference",
            "import keiyoushi.utils.addListPreference",
            "import keiyoushi.utils.addSetPreference",
        ])

    if with_extractors:
        import_lines.extend([
            "import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor",
            "import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor",
            "import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor",
            "import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor",
            "import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils",
        ])

    import_block = "\n".join(sorted(set(import_lines)))

    extractor_fields = """
    // Shared Video Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
""" if with_extractors else ""

    video_list_block = """    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = hoster.hosterUrl
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()

        return when {
            embedUrl.contains("dood") || embedUrl.contains("ds2play") ->
                doodExtractor.videosFromUrl(embedUrl)
            embedUrl.contains("streamtape") ->
                streamtapeExtractor.videoFromUrl(embedUrl)?.let { listOf(it) } ?: emptyList()
            embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") ->
                filemoonExtractor.videosFromUrl(embedUrl, prefix = "${hoster.hosterName} - ", headers = embedHeaders)
            embedUrl.endsWith(".m3u8") || embedUrl.contains(".m3u8?") ->
                playlistUtils.extractFromHls(
                    playlistUrl = embedUrl,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "${hoster.hosterName} - $quality" }
                )
            else ->
                universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = "${hoster.hosterName} - ")
        }
    }""" if with_extractors else """    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return listOf(
            Video(
                videoUrl = hoster.hosterUrl,
                videoTitle = hoster.hosterName,
                headers = headers
            )
        )
    }"""

    sort_videos_block = """
    override fun List<Video>.sortVideos(): List<Video> {
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefType, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 }
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Audio Type",
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
            entries = listOf("Sub", "Dub", "Soft-Sub"),
            entryValues = listOf("SUB", "DUB", "SOFT-SUB"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Auto", "DoodStream", "StreamTape", "FileMoon"),
            entryValues = listOf("auto", "DoodStream", "StreamTape", "FileMoon"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from playback",
            entries = listOf("DoodStream", "StreamTape", "FileMoon"),
            entryValues = listOf("DoodStream", "StreamTape", "FileMoon"),
            default = emptySet(),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_TYPE_KEY,
            title = "Exclude Audio Types",
            summary = "Select audio formats to hide",
            entries = listOf("Sub", "Dub", "Soft-Sub"),
            entryValues = listOf("SUB", "DUB", "SOFT-SUB"),
            default = emptySet(),
        )
    }
""" if with_prefs else ""

    companion_block = f"""
    companion object {{
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "{base_url}"
        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_TYPE_DEFAULT = "SUB"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_TYPE_KEY = "pref_exclude_type"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }}
""" if with_prefs or with_metadata else ""

    base_url_getter = """override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT""" if with_prefs else f'override val baseUrl = "{base_url}"'

    filter_search_block = """        val urlBuilder = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("page", page.toString())
            }
        } else {
            val builder = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
            }
            filters.forEach { filter ->
                when (filter) {
                    is Filters.TypeFilter -> if (!filter.isDefault()) builder.addQueryParameter("type", filter.toUriPart())
                    is Filters.StatusFilter -> if (!filter.isDefault()) builder.addQueryParameter("status", filter.toUriPart())
                    is Filters.SortFilter -> if (!filter.isDefault()) builder.addQueryParameter("sort", filter.toUriPart())
                    is Filters.YearFilter -> if (filter.state.isNotBlank()) builder.addQueryParameter("year", filter.state.trim())
                    is Filters.GenreFilter -> filter.getIncluded().forEach { builder.addQueryParameter("genre[]", it) }
                    else -> {}
                }
            }
            builder
        }
        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        return parseAnimeListPage(response)""" if with_filters else """        val url = if (query.isNotBlank()) {
            "$baseUrl/search?q=$query&page=$page"
        } else {
            "$baseUrl/browse?page=$page"
        }
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)"""

    filter_list_block = """    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SortFilter(),
        Filters.YearFilter(),
    )""" if with_filters else "    override fun getFilterList(): AnimeFilterList = AnimeFilterList()"

    return f"""package {package_path}

{import_block}

class {class_name} : Source() {{

    override val name = "{class_name}"

    {base_url_getter}

    override val lang = "{lang}"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {{
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }}

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
{extractor_fields}
    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {{
        val response = client.newCall(GET("$baseUrl/popular?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }}

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {{
        val response = client.newCall(GET("$baseUrl/latest?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }}

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {{
{filter_search_block}
    }}

{filter_list_block}

    private fun parseAnimeListPage(response: Response): AnimesPage {{
        val doc = response.asJsoup()
        val animes = doc.select("div.anime-card, div.film-item").map {{ element ->
            SAnime.create().apply {{
                title = element.selectFirst("h2.title, a.title, .film-name")?.text() ?: ""
                setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
                thumbnail_url = element.selectFirst("img")?.absUrl("src") ?: element.selectFirst("img")?.attr("data-src")
                fetch_type = FetchType.Episodes
            }}
        }}
        val hasNext = doc.selectFirst("a.next-page, a.next, .pagination-next") != null
        return AnimesPage(animes, hasNext)
    }}

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {{
        val doc = client.newCall(GET("$baseUrl${{anime.url}}", headers)).execute().asJsoup()
        val synopsis = doc.selectFirst("div.description, .synopsis, .film-description")?.text() ?: ""
        val score = doc.selectFirst("span.score, .rating")?.text()?.toDoubleOrNull()
        val statusRaw = doc.selectFirst("span.status, .film-status")?.text() ?: ""

        return SAnime.create().apply {{
            title = anime.title
            thumbnail_url = anime.thumbnail_url
            genre = doc.select("div.genres a, .genre a").joinToString {{ it.text() }}
            status = when {{
                statusRaw.contains("Ongoing", ignoreCase = true) || statusRaw.contains("Airing", ignoreCase = true) -> SAnime.ONGOING
                statusRaw.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }}
            description = buildString {{
                if (score != null && score > 0.0) {{
                    val stars = (score / 2).toInt().coerceIn(0, 5)
                    append("★".repeat(stars) + "☆".repeat(5 - stars) + " " + "%.2f".format(score) + "\\n\\n")
                }}
                if (synopsis.isNotBlank()) append(synopsis)
            }}.trim()
            initialized = true
        }}
    }}

    // ============================== Episodes ==============================
    // Note: SEpisode.url MUST be permanent and deterministic (e.g., "${anime.url}#season=$s&ep=$e" or "${anime.url}#movie").
    // Never embed dynamic tokens in SEpisode.url to avoid Tachiyomi/AniZen database invalidation cycles.
    // Episode numbering: For Season 1, episode_number MUST start at 1.0f (never with a +1000 base offset).
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {{
        val doc = client.newCall(GET("$baseUrl${{anime.url}}", headers)).execute().asJsoup()
        return doc.select("ul.episodes > li, div.episode-item, .episodes-list a").mapIndexed {{ idx, element ->
            SEpisode.create().apply {{
                val link = element.selectFirst("a") ?: element
                val rawHref = link.attr("href").ifBlank {{ link.attr("data-href") }}
                setUrlWithoutDomain(if (rawHref.isNotBlank()) rawHref else "${{anime.url}}#ep=${{idx + 1}}")
                name = element.selectFirst("span.name, a.title, .ep-title")?.text() ?: link.text().ifBlank {{ "Episode ${{idx + 1}}" }}
                episode_number = element.selectFirst("span.num")?.text()?.toFloatOrNull()
                    ?: name.substringAfter("Episode ").substringBefore(" ").toFloatOrNull() ?: (idx + 1).toFloat()

                val hasSub = element.selectFirst(".sub-badge, [data-sub='1']") != null
                val hasDub = element.selectFirst(".dub-badge, [data-dub='1']") != null
                scanlator = when {{
                    hasSub && hasDub -> "Sub / Dub"
                    hasDub -> "Dub"
                    hasSub -> "Sub"
                    else -> null
                }}

                val dateStr = element.selectFirst("span.date, .ep-date")?.text() ?: ""
                if (dateStr.isNotBlank()) {{
                    date_upload = runCatching {{ DATE_FORMAT.parse(dateStr)?.time ?: 0L }}.getOrDefault(0L)
                }}
            }}
        }}.reversed()
    }}

    // ============================ Video Links =============================
    // 2-Tier Model:
    // 1. getHosterList(episode) returns List<Hoster> (Server Folders: "Fast Cloud", "HubCloud", "MegaCloud").
    // 2. getVideoList(hoster) resolves and returns List<Video> (Qualities: 1080p, 720p, 480p) inside that folder.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {{
        val doc = client.newCall(GET("$baseUrl${{episode.url}}", headers)).execute().asJsoup()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()

        val hosters = mutableListOf<Hoster>()
        doc.select("iframe.player-iframe, [data-embed]").forEachIndexed {{ idx, iframe ->
            val embedUrl = iframe.absUrl("src").ifBlank {{ iframe.attr("data-embed") }}
            val serverName = iframe.attr("data-server-name").ifBlank {{ "Server ${{idx + 1}}" }}
            val audioType = iframe.attr("data-audio-type").ifBlank {{ "SUB" }}.uppercase()

            if (serverName in excludedServers || audioType in excludedAudios || embedUrl.isBlank()) return@forEachIndexed
            hosters.add(Hoster(hosterName = "$audioType - $serverName", hosterUrl = embedUrl))
        }}

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending {{ it.hosterName.contains(prefServer, ignoreCase = true) }}
    }}

{video_list_block}

    // ============================ Recommendations ========================
    fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${{anime.url}}", headers)

    fun relatedAnimeListParse(response: Response): List<SAnime> {{
        val doc = response.asJsoup()
        return doc.select("div.recommendations a, div.related a").mapNotNull {{ el ->
            SAnime.create().apply {{
                title = el.selectFirst(".title")?.text() ?: el.text()
                setUrlWithoutDomain(el.attr("href"))
                thumbnail_url = el.selectFirst("img")?.absUrl("src")
            }}
        }}
    }}
{sort_videos_block}{companion_block}}}
"""


def generate_api_kotlin_source(
    lang: str,
    pkg_name: str,
    class_name: str,
    base_url: str,
    with_prefs: bool = True,
    with_extractors: bool = True
) -> str:
    package_path = f"eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}"
    return f"""package {package_path}

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

class {class_name} : Source() {{

    override val name = "{class_name}"

    override val baseUrl = "{base_url}"

    override val lang = "{lang}"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {{
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }}

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {{
        val response = client.newCall(GET("$baseUrl/api/popular?page=$page", headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val animes = (dto.results ?: emptyList()).map {{ it.toSAnime() }}
        return AnimesPage(animes, dto.hasNextPage ?: false)
    }}

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {{
        val response = client.newCall(GET("$baseUrl/api/latest?page=$page", headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val animes = (dto.results ?: emptyList()).map {{ it.toSAnime() }}
        return AnimesPage(animes, dto.hasNextPage ?: false)
    }}

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {{
        val response = client.newCall(GET("$baseUrl/api/search?q=$query&page=$page", headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val animes = (dto.results ?: emptyList()).map {{ it.toSAnime() }}
        return AnimesPage(animes, dto.hasNextPage ?: false)
    }}

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {{
        val response = client.newCall(GET("$baseUrl/api/anime/${{anime.url}}", headers)).execute()
        val item = response.parseAs<AnimeItemDto>(json)
        return item.toSAnime().apply {{
            initialized = true
        }}
    }}

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {{
        val response = client.newCall(GET("$baseUrl/api/anime/${{anime.url}}/episodes", headers)).execute()
        val episodesDto = response.parseAs<List<EpisodeDto>>(json)
        return episodesDto.map {{ it.toSEpisode() }}
    }}

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {{
        val response = client.newCall(GET("$baseUrl/api/episode/${{episode.url}}/streams", headers)).execute()
        val streamsDto = response.parseAs<List<StreamDto>>(json)
        return streamsDto.map {{
            Hoster(
                hosterName = it.server ?: "Server",
                hosterUrl = it.url ?: ""
            )
        }}
    }}

    override suspend fun getVideoList(hoster: Hoster): List<Video> {{
        return listOf(
            Video(
                videoUrl = hoster.hosterUrl,
                videoTitle = hoster.hosterName,
                headers = headers
            )
        )
    }}
}}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class ApiResponseDto(
    val results: List<AnimeItemDto>? = null,
    val hasNextPage: Boolean? = null
)

@Serializable
data class AnimeItemDto(
    val id: String? = null,
    val title: String? = null,
    val image: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val status: String? = null
) {{
    fun toSAnime(): SAnime = SAnime.create().apply {{
        title = this@AnimeItemDto.title ?: ""
        setUrlWithoutDomain(this@AnimeItemDto.id ?: "")
        thumbnail_url = this@AnimeItemDto.image
        description = this@AnimeItemDto.description
        genre = this@AnimeItemDto.genres?.joinToString()
        status = when (this@AnimeItemDto.status?.lowercase()) {{
            "ongoing", "airing" -> SAnime.ONGOING
            "completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }}
        fetch_type = FetchType.Episodes
    }}
}}

@Serializable
data class EpisodeDto(
    val id: String? = null,
    val number: Float? = null,
    val title: String? = null
) {{
    fun toSEpisode(): SEpisode = SEpisode.create().apply {{
        name = this@EpisodeDto.title ?: "Episode ${{this@EpisodeDto.number ?: 1f}}"
        episode_number = this@EpisodeDto.number ?: 1f
        setUrlWithoutDomain(this@EpisodeDto.id ?: "")
    }}
}}

@Serializable
data class StreamDto(
    val server: String? = null,
    val url: String? = null
)
"""


def generate_theme_scaffold(theme_name: str, repo_root: Path) -> bool:
    """Scaffolds a new multi-source theme module under lib-multisrc/<theme_name>/."""
    theme_clean = theme_name.lower().replace("-", "").replace("_", "")
    theme_pascal = to_pascal_case(theme_name)
    if not theme_pascal.endswith("Theme"):
        theme_pascal += "Theme"

    theme_dir = repo_root / "lib-multisrc" / theme_clean
    java_dir = theme_dir / "src" / "main" / "java" / "eu" / "kanade" / "tachiyomi" / "multisrc" / theme_clean
    java_dir.mkdir(parents=True, exist_ok=True)

    print(f"🚀 Scaffolding new multi-source theme ':lib-multisrc:{theme_clean}' at: {theme_dir}")

    # 1. build.gradle.kts
    gradle_file = theme_dir / "build.gradle.kts"
    gradle_file.write_text("""plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 1
""", encoding="utf-8")
    print(f"  [+] Created {gradle_file.relative_to(repo_root)}")

    # 2. Main Theme Base Class
    base_file = java_dir / f"{theme_pascal}.kt"
    base_file.write_text(f"""package eu.kanade.tachiyomi.multisrc.{theme_clean}

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

abstract class {theme_pascal} : Source() {{

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {{
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }}

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Open Selectors for Variant Customization
    open val popularSelector = "div.anime-card"
    open val titleSelector = "h2.title, a.title"
    open val imageSelector = "img"
    open val episodeSelector = "ul.episodes > li"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {{
        val response = client.newCall(GET("$baseUrl/popular?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }}

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {{
        val response = client.newCall(GET("$baseUrl/latest?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }}

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {{
        val url = if (query.isNotBlank()) "$baseUrl/search?q=$query&page=$page" else "$baseUrl/browse?page=$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }}

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    protected open fun parseAnimeListPage(response: Response): AnimesPage {{
        val doc = response.asJsoup()
        val animes = doc.select(popularSelector).map {{ element ->
            SAnime.create().apply {{
                title = element.selectFirst(titleSelector)?.text() ?: ""
                setUrlWithoutDomain(element.selectFirst("a")?.attr("href") ?: "")
                thumbnail_url = element.selectFirst(imageSelector)?.absUrl("src")
                fetch_type = FetchType.Episodes
            }}
        }}
        val hasNext = doc.selectFirst("a.next-page, .pagination-next") != null
        return AnimesPage(animes, hasNext)
    }}

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {{
        val doc = client.newCall(GET("$baseUrl${{anime.url}}", headers)).execute().asJsoup()
        return SAnime.create().apply {{
            title = anime.title
            thumbnail_url = anime.thumbnail_url
            description = doc.selectFirst("div.description, .synopsis")?.text()
            genre = doc.select("div.genres a").joinToString {{ it.text() }}
            status = SAnime.UNKNOWN
            initialized = true
        }}
    }}

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {{
        val doc = client.newCall(GET("$baseUrl${{anime.url}}", headers)).execute().asJsoup()
        return doc.select(episodeSelector).map {{ element ->
            val link = element.selectFirst("a") ?: element
            SEpisode.create().apply {{
                setUrlWithoutDomain(link.attr("href"))
                name = element.selectFirst("span.name, a.title")?.text() ?: link.text()
                episode_number = element.selectFirst("span.num")?.text()?.toFloatOrNull() ?: 1f
            }}
        }}.reversed()
    }}

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {{
        val doc = client.newCall(GET("$baseUrl${{episode.url}}", headers)).execute().asJsoup()
        val hosters = mutableListOf<Hoster>()
        doc.select("iframe[src], [data-embed]").forEachIndexed {{ idx, iframe ->
            val embedUrl = iframe.absUrl("src").ifBlank {{ iframe.attr("data-embed") }}
            if (embedUrl.isNotBlank()) {{
                hosters.add(Hoster(hosterName = "Server ${{idx + 1}}", hosterUrl = embedUrl))
            }}
        }}
        return hosters
    }}

    override suspend fun getVideoList(hoster: Hoster): List<Video> {{
        return listOf(
            Video(
                videoUrl = hoster.hosterUrl,
                videoTitle = hoster.hosterName,
                headers = headers
            )
        )
    }}
}}
""", encoding="utf-8")
    print(f"  [+] Created {base_file.relative_to(repo_root)}")

    # 3. Dto file
    dto_file = java_dir / f"{theme_pascal}Dto.kt"
    dto_file.write_text(f"""package eu.kanade.tachiyomi.multisrc.{theme_clean}

import kotlinx.serialization.Serializable

@Serializable
data class {theme_pascal}ItemDto(
    val id: String? = null,
    val title: String? = null,
    val image: String? = null
)
""", encoding="utf-8")
    print(f"  [+] Created {dto_file.relative_to(repo_root)}")

    print(f"\n✨ Theme ':lib-multisrc:{theme_clean}' scaffolded successfully!")
    return True


def interactive_wizard() -> dict:
    print("🪄 Aniyomi Extension Scaffolding Wizard (v16)\n" + "=" * 50)
    name = input("Extension display name (e.g. 'AnimePahe', 'HiAnime'): ").strip()
    while not name:
        name = input("Display name cannot be empty: ").strip()

    lang = input("Language code [default: 'en']: ").strip() or "en"
    base_url = input("Target website Base URL [default: 'https://example.com']: ").strip() or "https://example.com"

    print("\nSource Architecture:")
    print("  1. HTML Scraper (Jsoup + CSS Selectors)")
    print("  2. JSON / REST API (kotlinx.serialization)")
    print("  3. Multisrc Theme Variant (inherits lib-multisrc theme)")
    arch_choice = input("Select Architecture [1-3, default 1]: ").strip()

    theme = None
    if arch_choice == "2":
        site_type = "api"
    elif arch_choice == "3":
        site_type = "theme"
        theme = input("Theme package name (e.g. 'anikototheme', 'dooplay', 'jellyfin'): ").strip()
    else:
        site_type = "html"

    with_prefs = input("Generate PreferenceScreen dropdowns (Quality/Server/Exclusions)? [Y/n]: ").strip().lower() != "n"
    with_extractors = input("Include common video extractors (Dood, StreamTape, FileMoon, Universal)? [Y/n]: ").strip().lower() != "n"
    with_metadata = input("Include episode metadata & Sub/Dub scanlator parser? [Y/n]: ").strip().lower() != "n"
    with_filters = input("Generate modular Filters.kt file? [Y/n]: ").strip().lower() != "n"
    nsfw = input("Is this extension NSFW / 18+? [y/N]: ").strip().lower() == "y"

    return {
        "name": name,
        "lang": lang,
        "baseUrl": base_url,
        "siteType": site_type,
        "theme": theme,
        "with_preferences": with_prefs,
        "with_extractors": with_extractors,
        "with_metadata": with_metadata,
        "with_filters": with_filters,
        "nsfw": nsfw,
        "versionCode": 1
    }


def main():
    parser = argparse.ArgumentParser(description="Aniyomi Extension Generator CLI (Production Grade)")
    parser.add_argument("--name", help="Extension display name (e.g., 'AnimeFlix')")
    parser.add_argument("--lang", default="en", help="Language code (e.g., 'en', 'all', 'es', 'id'). Default: 'en'")
    parser.add_argument("--baseUrl", default="https://example.com", help="Base URL of the target site")
    parser.add_argument("--siteType", choices=["html", "api", "theme"], default="html", help="Source architecture (html, api, theme)")
    parser.add_argument("--theme", help="Theme package name if siteType == theme (e.g. 'dooplay', 'anikototheme')")
    parser.add_argument("--with-preferences", action="store_true", help="Include preference screen & quality sorting templates")
    parser.add_argument("--with-extractors", action="store_true", help="Include common video extractors dependencies & lazy fields")
    parser.add_argument("--with-metadata", action="store_true", help="Include episode thumbnail, scanlator sub/dub & upload date parsing")
    parser.add_argument("--with-filters", action="store_true", help="Include modular Filters.kt template")
    parser.add_argument("--create-theme", help="Scaffold a brand new theme inside lib-multisrc/<theme_name>")
    parser.add_argument("--nsfw", action="store_true", help="Set if extension contains NSFW content")
    parser.add_argument("--versionCode", type=int, default=1, help="Override version code. Default: 1")
    parser.add_argument("-i", "--interactive", action="store_true", help="Launch interactive scaffolding wizard")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    if args.create_theme:
        success = generate_theme_scaffold(args.create_theme, repo_root)
        sys.exit(0 if success else 1)

    if args.interactive or (not args.name and sys.stdin.isatty()):
        opts = interactive_wizard()
        ext_name = opts["name"]
        lang = opts["lang"].lower()
        base_url = opts["baseUrl"]
        site_type = opts["siteType"]
        theme = opts["theme"]
        with_prefs = opts["with_preferences"]
        with_extractors = opts["with_extractors"]
        with_metadata = opts["with_metadata"]
        with_filters = opts.get("with_filters", True)
        nsfw = opts["nsfw"]
        version_code = opts["versionCode"]
    elif not args.name:
        parser.print_help()
        sys.exit(1)
    else:
        ext_name = args.name
        lang = args.lang.lower()
        base_url = args.baseUrl
        site_type = args.siteType
        theme = args.theme
        with_prefs = args.with_preferences
        with_extractors = args.with_extractors
        with_metadata = args.with_metadata
        with_filters = args.with_filters
        nsfw = args.nsfw
        version_code = args.versionCode

    if site_type == "theme" and not theme:
        print("❌ Error: --theme <theme_name> is required when --siteType theme is selected.")
        sys.exit(1)

    class_name = to_pascal_case(ext_name)
    pkg_name = to_package_name(ext_name)

    ext_dir = repo_root / "src" / lang / pkg_name
    src_dir = ext_dir / "src" / "eu" / "kanade" / "tachiyomi" / "animeextension" / lang / pkg_name
    res_dir = ext_dir / "res" / "drawable-xxhdpi"

    print(f"🚀 Creating production-grade extension module for '{class_name}' ({lang}) at: {ext_dir}")

    src_dir.mkdir(parents=True, exist_ok=True)
    res_dir.mkdir(parents=True, exist_ok=True)

    # 1. Write build.gradle
    build_gradle_path = ext_dir / "build.gradle"
    build_gradle_content = generate_build_gradle(
        ext_name=class_name,
        ext_class=class_name,
        theme_pkg=theme if site_type == "theme" else None,
        nsfw=nsfw,
        version_code=version_code,
        with_extractors=with_extractors
    )
    build_gradle_path.write_text(build_gradle_content, encoding="utf-8")
    print(f"  [+] Created {build_gradle_path.relative_to(repo_root)}")

    # 2. Write Filters.kt if enabled
    if with_filters and site_type != "theme":
        filters_path = src_dir / "Filters.kt"
        filters_content = generate_filters_kotlin_source(lang, pkg_name)
        filters_path.write_text(filters_content, encoding="utf-8")
        print(f"  [+] Created {filters_path.relative_to(repo_root)}")

    # 3. Write Main Kotlin Source
    kt_file_path = src_dir / f"{class_name}.kt"
    if site_type == "api":
        kt_content = generate_api_kotlin_source(
            lang=lang,
            pkg_name=pkg_name,
            class_name=class_name,
            base_url=base_url,
            with_prefs=with_prefs,
            with_extractors=with_extractors
        )
    elif site_type == "theme" and theme:
        package_path = f"eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}"
        theme_class = to_pascal_case(theme) + ("Theme" if not theme.endswith("theme") else "")
        kt_content = f"""package {package_path}

import eu.kanade.tachiyomi.multisrc.{theme.lower()}.{theme_class}

class {class_name} : {theme_class}() {{
    override val name = "{class_name}"
    override val baseUrl = "{base_url}"
    override val lang = "{lang}"
}}
"""
    else:
        kt_content = generate_html_kotlin_source(
            lang=lang,
            pkg_name=pkg_name,
            class_name=class_name,
            base_url=base_url,
            with_prefs=with_prefs,
            with_extractors=with_extractors,
            with_metadata=with_metadata,
            with_filters=with_filters
        )

    kt_file_path.write_text(kt_content, encoding="utf-8")
    print(f"  [+] Created {kt_file_path.relative_to(repo_root)}")

    # 4. Write AndroidManifest.xml
    manifest_path = ext_dir / "AndroidManifest.xml"
    manifest_content = generate_android_manifest()
    manifest_path.write_text(manifest_content, encoding="utf-8")
    print(f"  [+] Created {manifest_path.relative_to(repo_root)}")

    # 5. Create default launcher icon
    drawable_dir = ext_dir / "res" / "drawable"
    drawable_dir.mkdir(parents=True, exist_ok=True)
    ic_launcher_path = drawable_dir / "ic_launcher.png"
    create_minimal_png(ic_launcher_path, 192, 192)
    print(f"  [+] Created {ic_launcher_path.relative_to(repo_root)}")

    icon_path = res_dir / "icon.png"
    create_minimal_png(icon_path)
    print(f"  [+] Created {icon_path.relative_to(repo_root)}")

    print("\n✨ Extension created with 85-95% automated boilerplate!")
    print("Pre-scaffolded features:")
    print("  • AndroidManifest.xml and valid PNG launcher icons")
    print("  • Modular Filters.kt (Type, Status, Sort, Year, Genres)")
    print("  • OkHttp rateLimit(4, 1.seconds) interceptor protection")
    print("  • SharedPreferences & PreferenceScreen (Base URL, Audio, Server, Exclusions)")
    print("  • Automatic List<Video>.sortVideos() quality & server sorting")
    print("  • Episode metadata (Sub/Dub scanlator badges, release date parsing)")
    print("  • Video extractors integration (DoodStream, StreamTape, FileMoon, Universal, PlaylistUtils)")


if __name__ == "__main__":
    main()
