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

try:
    import readline
except ImportError:
    pass

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
    with_extractors: bool = True,
    site_type: str = "html"
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

    if site_type in ["tmdb", "tmdb-media"]:
        lib_deps = """
dependencies {
    implementation(project(":lib:playlist-utils"))
    implementation(project(":lib:vidsrc-extractor"))
    implementation(project(":lib:universal-extractor"))
}
"""
    elif with_extractors:
        lib_deps = """
dependencies {
    implementation(project(":lib:dood-extractor"))
    implementation(project(":lib:filemoon-extractor"))
    implementation(project(":lib:streamtape-extractor"))
    implementation(project(":lib:universal-extractor"))
    implementation(project(":lib:playlist-utils"))
}
"""
    else:
        lib_deps = ""

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
            "import keiyoushi.utils.parallelCatchingFlatMap",
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
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()

        // Unpack "SUB|embedUrl;;DUB|embedUrl" from provider folder
        val audioEntries = hoster.hosterUrl.split(";;").mapNotNull { item ->
            val parts = item.split("|")
            if (parts.size >= 2) Pair(parts[0], parts[1]) else null
        }

        val videoList = audioEntries.parallelCatchingFlatMap { (audioType, embedUrl) ->
            if (audioType.uppercase() in excludedAudios) return@parallelCatchingFlatMap emptyList()

            val extractedVideos = when {
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
                        videoNameGen = { quality -> "$quality [$audioType]" }
                    )
                else ->
                    universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = "${hoster.hosterName} - ")
            }

            extractedVideos.map { video ->
                val baseTitle = video.videoTitle.replace(Regex("\\\\s*\\\\[(?:SUB|DUB|Soft-Sub)\\\\]", RegexOption.IGNORE_CASE), "").trim()
                val finalTitle = if (baseTitle.isNotBlank()) "$baseTitle [$audioType]" else "HD [$audioType]"
                Video(
                    videoUrl = video.videoUrl,
                    videoTitle = finalTitle,
                    headers = video.headers ?: embedHeaders,
                    resolution = video.resolution,
                    subtitleTracks = video.subtitleTracks,
                )
            }
        }

        return videoList.sortVideos()
    }""" if with_extractors else """    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val audioEntries = hoster.hosterUrl.split(";;").mapNotNull { item ->
            val parts = item.split("|")
            if (parts.size >= 2) Pair(parts[0], parts[1]) else null
        }

        return audioEntries.map { (audioType, streamUrl) ->
            Video(
                videoUrl = streamUrl,
                videoTitle = "${hoster.hosterName} [$audioType]",
                headers = headers
            )
        }
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
    // Note: SEpisode.url MUST be permanent and deterministic (e.g., "${{anime.url}}#season=$s&ep=$e" or "${{anime.url}}#movie").
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
        }}
    }}

    // ============================ Video Links =============================
    // 2-Tier Model:
    // 1. getHosterList(episode) returns List<Hoster> representing Provider Folders (e.g. "MegaCloud", "VidStreaming", "FileMoon").
    // 2. getVideoList(hoster) resolves and returns List<Video> with Audio tags next to Quality (e.g. "1080p [Sub]", "1080p [Dub]").
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {{
        val doc = client.newCall(GET("$baseUrl${{episode.url}}", headers)).execute().asJsoup()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()

        // Map: ProviderName -> List of "SUB|embedUrl", "DUB|embedUrl"
        val providerMap = mutableMapOf<String, MutableList<Pair<String, String>>>()

        doc.select("iframe.player-iframe, [data-embed]").forEachIndexed {{ idx, iframe ->
            val embedUrl = iframe.absUrl("src").ifBlank {{ iframe.attr("data-embed") }}
            val serverName = iframe.attr("data-server-name").ifBlank {{ "Server ${{idx + 1}}" }}
            val audioType = iframe.attr("data-audio-type").ifBlank {{ "SUB" }}.uppercase()

            if (serverName !in excludedServers && embedUrl.isNotBlank()) {{
                providerMap.getOrPut(serverName) {{ mutableListOf() }}.add(Pair(audioType, embedUrl))
            }}
        }}

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return providerMap.map {{ (providerName, audioList) ->
            Hoster(
                hosterName = providerName,
                hosterUrl = audioList.joinToString(";;") {{ "${{it.first}}|${{it.second}}" }},
            )
        }}.sortedByDescending {{ it.hosterName.contains(prefServer, ignoreCase = true) }}
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

def generate_movie_locker_kotlin_source(
    lang: str,
    pkg_name: str,
    class_name: str,
    base_url: str,
    with_prefs: bool = True,
    with_extractors: bool = True,
) -> str:
    package_path = f"eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}"
    return f"""package {package_path}

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlin.time.Duration.Companion.seconds
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

class {class_name} : Source() {{

    override val name = "{class_name}"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "{lang}"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {{
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }}

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {{
        val url = if (page > 1) "$baseUrl/trending/page/$page/" else "$baseUrl/trending/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }}

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {{
        val url = if (page > 1) "$baseUrl/movies/page/$page/" else "$baseUrl/movies/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }}

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {{
        if (query.isNotBlank()) {{
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/page/$page/?s=$encodedQuery"
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimeListPage(response, page)
        }}

        var categoryPath = ""
        for (filter in filters) {{
            when (filter) {{
                is Filters.CategoryFilter -> {{
                    if (!filter.isDefault()) {{
                        categoryPath = filter.toUriPart()
                    }}
                }}
                else -> {{}}
            }}
        }}

        val targetPath = categoryPath.ifBlank {{ "movies" }}
        val url = if (page > 1) "$baseUrl/$targetPath/page/$page/" else "$baseUrl/$targetPath/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }}

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.CategoryFilter(),
    )

    private fun parseAnimeListPage(response: Response, page: Int): AnimesPage {{
        val doc = response.asJsoup()
        val animeList = doc.select("article.item, div.items article, article[id^=post-]").mapNotNull {{ element ->
            val linkEl = element.selectFirst("div.poster a, .data h3 a, h3 a, a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#")) return@mapNotNull null

            val imgEl = element.selectFirst("div.poster img, img")
            val imgUrl = imgEl?.attr("data-lazy-src")?.ifEmpty {{ imgEl.attr("abs:src").ifEmpty {{ imgEl.attr("src") }} }}
            val rawTitle = imgEl?.attr("alt")?.ifEmpty {{ linkEl.text() }} ?: linkEl.text()

            SAnime.create().apply {{
                title = cleanAnimeTitle(rawTitle)
                setUrlWithoutDomain(href)
                thumbnail_url = imgUrl
                fetch_type = FetchType.Episodes
            }}
        }}

        val hasNext = doc.select(".horizontal-pagination a.next-page, .pagination a.next-page, a.next-page").isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }}

    private fun cleanAnimeTitle(title: String): String {{
        return title
            .replace(Regex(\"\"\"\\s*\\{{[^}}]*\\}}\"\"\"), \"\")
            .replace(Regex(\"\"\"\\s*(Dual Audio|Multi Audio|Hindi Dubbed|Hindi Movie|CR WEB-DL|WEB-DL|BluRay|HDTC|ESubs|MSubs|NF).*\"\"\", RegexOption.IGNORE_CASE), \"\")
            .trim()
            .ifEmpty {{ title.trim() }}
    }}

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {{
        val doc = client.newCall(GET("$baseUrl${{anime.url}}", headers)).execute().asJsoup()

        val rawTitle = doc.selectFirst("div.data h1, h1")?.text() ?: anime.title
        val posterEl = doc.selectFirst("div.poster img, .sheader .poster img, img[itemprop=image]")
        val posterUrl = posterEl?.attr("data-lazy-src")?.ifEmpty {{ posterEl.attr("abs:src").ifEmpty {{ posterEl.attr("src") }} }}
        val synopsis = doc.selectFirst("div.wp-content, div[itemprop=description], #info .wp-content")?.text() ?: ""
        val ratingText = doc.selectFirst(".starstruck-rating span.dt_rating_vgs, span[itemprop=ratingValue]")?.text()
        val score = ratingText?.toDoubleOrNull()
        val releaseDate = doc.selectFirst("span.date, span[itemprop=dateCreated]")?.text() ?: ""

        return SAnime.create().apply {{
            title = cleanAnimeTitle(rawTitle)
            thumbnail_url = posterUrl ?: anime.thumbnail_url
            genre = doc.select("div.sgeneros a, .genres a").joinToString {{ it.text() }}
            status = SAnime.COMPLETED
            initialized = true

            description = buildString {{
                if (score != null && score > 0.0) {{
                    val full = (score / 2).toInt().coerceIn(0, 5)
                    append("${{"★".repeat(full)}}${"☆".repeat(5 - full)} ${{"%.2f".format(score)}}\\n\\n")
                }}
                if (synopsis.isNotBlank()) append("$synopsis\\n\\n")
                if (releaseDate.isNotBlank()) append("Released: $releaseDate\\n")
            }}.trim()
        }}
    }}

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {{
        val doc = client.newCall(GET("$baseUrl${{anime.url}}", headers)).execute().asJsoup()

        // 1. TV Series batch linkstore buttons
        val linkStoreButtons = doc.select("a[href*=linkstore], a[href*=batch]")
        if (linkStoreButtons.isNotEmpty()) {{
            val seasonBatchMap = mutableMapOf<Int, String>()
            linkStoreButtons.forEach {{ btn ->
                val seasonNum = Regex(\"\"\"(?i)Season\\s*0*(\\d+)\"\"\").find(btn.text())?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val href = btn.attr("href")
                if (href.isNotBlank() && seasonNum !in seasonBatchMap) {{
                    seasonBatchMap[seasonNum] = href
                }}
            }}

            val totalSeasons = seasonBatchMap.size
            val episodes = mutableListOf<SEpisode>()

            seasonBatchMap.entries.forEach {{ (seasonNum, linkStoreUrl) ->
                runCatching {{
                    val lsDoc = client.newCall(GET(linkStoreUrl, headers)).execute().asJsoup()
                    val seasonEpNums = mutableSetOf<Int>()

                    lsDoc.select("a[href*=/file/], a[href*=/download/]").forEach {{ epLink ->
                        val epText = epLink.text().trim()
                        if (epText.contains("Zip", ignoreCase = true)) return@forEach

                        val epNum = Regex(\"\"\"(?i)EPISODE\\s*-\\s*0*(\\d+)\"\"\").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex(\"\"\"(?i)E0*(\\d+)\"\"\").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: return@forEach

                        seasonEpNums.add(epNum)
                    }}

                    seasonEpNums.sorted().forEach {{ epNum ->
                        val epName = if (totalSeasons > 1) "Season $seasonNum - Episode $epNum" else "Episode $epNum"
                        episodes.add(
                            SEpisode.create().apply {{
                                name = epName
                                setUrlWithoutDomain("${{anime.url}}#season=$seasonNum&ep=$epNum")
                                episode_number = if (totalSeasons > 1) {{
                                    ((seasonNum - 1) * 100 + epNum).toFloat()
                                }} else {{
                                    epNum.toFloat()
                                }}
                            }},
                        )
                    }}
                }}
            }}

            if (episodes.isNotEmpty()) {{
                return episodes.sortedByDescending {{ it.episode_number }}
            }}
        }}

        // 2. Single Movie fallback
        return listOf(
            SEpisode.create().apply {{
                name = "Full Movie"
                setUrlWithoutDomain("${{anime.url}}#movie")
                episode_number = 1f
            }},
        )
    }}

    // ============================ Video Links & Hosters ===================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {{
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        val rawUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${{episode.url}}"
        val baseAnimePath = rawUrl.substringBefore("#")
        val isMovie = episode.url.contains("#movie") || !episode.url.contains("ep=")
        val targetSeason = Regex(\"\"\"season=(\\d+)\"\"\").find(episode.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val targetEp = Regex(\"\"\"ep=(\\d+)\"\"\").find(episode.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val doc = client.newCall(GET(baseAnimePath, headers)).execute().asJsoup()
        val qualityFiles = mutableListOf<Pair<String, String>>()

        if (isMovie) {{
            doc.select("a[href*=/file/], div.movie-button-container a").forEach {{ btn ->
                val href = btn.attr("abs:href").ifEmpty {{ btn.attr("href") }}
                if (href.isNotBlank() && qualityFiles.none {{ it.second == href }}) {{
                    val quality = Regex(\"\"\"(?i)(480p|720p|1080p|4k|2160p)\"\"\").find(btn.text())?.groupValues?.get(1)?.uppercase() ?: "HD"
                    qualityFiles.add(Pair(quality, href))
                }}
            }}
        }} else {{
            val linkStoreButtons = doc.select("a[href*=linkstore], a[href*=batch]")
            val seasonBatches = linkStoreButtons.filter {{ btn ->
                val sNum = Regex(\"\"\"(?i)Season\\s*0*(\\d+)\"\"\").find(btn.text())?.groupValues?.get(1)?.toIntOrNull() ?: 1
                sNum == targetSeason
            }}.ifEmpty {{ linkStoreButtons }}

            seasonBatches.forEach {{ btn ->
                val href = btn.attr("href")
                val btnText = btn.text().trim()
                val quality = Regex(\"\"\"(?i)(480p|720p|1080p|4k|2160p)\"\"\").find(btnText)?.groupValues?.get(1)?.uppercase() ?: "HD"

                runCatching {{
                    val lsDoc = client.newCall(GET(href, headers)).execute().asJsoup()
                    lsDoc.select("a[href*=/file/], a[href*=/download/]").forEach {{ epLink ->
                        val epText = epLink.text().trim()
                        if (epText.contains("Zip", ignoreCase = true)) return@forEach

                        val epNum = Regex(\"\"\"(?i)EPISODE\\s*-\\s*0*(\\d+)\"\"\").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex(\"\"\"(?i)E0*(\\d+)\"\"\").find(epText)?.groupValues?.get(1)?.toIntOrNull()

                        if (epNum == targetEp) {{
                            val fileHref = epLink.attr("abs:href").ifEmpty {{ epLink.attr("href") }}
                            if (fileHref.isNotBlank() && qualityFiles.none {{ it.second == fileHref }}) {{
                                qualityFiles.add(Pair(quality, fileHref))
                            }}
                        }}
                    }}
                }}
            }}
        }}

        val serverMap = mutableMapOf<String, MutableList<Pair<String, String>>>()

        qualityFiles.parallelCatchingFlatMap {{ (quality, fileUrl) ->
            // In a live implementation, resolve fileUrl to direct server mirrors (Fast Cloud, HubCloud, GDFlix, etc.)
            synchronized(serverMap) {{
                val defaultServer = "Direct Fast Stream"
                if (defaultServer !in excludedServers) {{
                    serverMap.getOrPut(defaultServer) {{ mutableListOf() }}.add(Pair(quality, fileUrl))
                }}
            }}
            emptyList<Unit>()
        }}

        return serverMap.map {{ (serverName, entries) ->
            Hoster(
                hosterName = serverName,
                hosterUrl = entries.joinToString(";;") {{ "${{it.first}}|${{it.second}}" }},
            )
        }}.sortedByDescending {{ it.hosterName.contains(prefServer, ignoreCase = true) }}
    }}

    override suspend fun getVideoList(hoster: Hoster): List<Video> {{
        val serverName = hoster.hosterName
        val qualityEntries = hoster.hosterUrl.split(";;").mapNotNull {{ item ->
            val parts = item.split("|")
            if (parts.size >= 2) Pair(parts[0], parts[1]) else null
        }}

        val videoList = qualityEntries.parallelCatchingFlatMap {{ (quality, serverUrl) ->
            listOf(
                Video(
                    videoUrl = serverUrl,
                    videoTitle = "$quality - $serverName",
                    headers = headers,
                    resolution = parseResolution(quality),
                ),
            )
        }}

        return videoList.distinctBy {{ it.videoUrl }}.sortVideos()
    }}

    private fun parseResolution(quality: String): Int {{
        return when {{
            quality.contains("2160", ignoreCase = true) || quality.contains("4k", ignoreCase = true) -> 2160
            quality.contains("1080", ignoreCase = true) -> 1080
            quality.contains("720", ignoreCase = true) -> 720
            quality.contains("480", ignoreCase = true) -> 480
            quality.contains("360", ignoreCase = true) -> 360
            else -> 0
        }}
    }}

    // ============================ Preferences & Sorting ===================
    override fun List<Video>.sortVideos(): List<Video> {{
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> {{ it.videoTitle.contains(prefQuality, ignoreCase = true) }}
                .thenByDescending {{ it.resolution ?: 0 }},
        )
    }}

    override fun setupPreferenceScreen(screen: PreferenceScreen) {{
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Fast Cloud", "HubCloud", "GDFlix", "FilePress"),
            entryValues = listOf("Fast Cloud", "HubCloud", "GDFlix", "FilePress"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select server folders to hide",
            entries = listOf("Fast Cloud", "HubCloud", "GDFlix", "FilePress"),
            entryValues = listOf("Fast Cloud", "HubCloud", "GDFlix", "FilePress"),
            default = emptySet(),
        )
    }}

    companion object {{
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "{base_url}"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "Fast Cloud"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }}
}}
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
import extensions.utils.injectLazy
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        url = this@AnimeItemDto.id ?: ""
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
        url = this@EpisodeDto.id ?: ""
    }}
}}

@Serializable
data class StreamDto(
    val server: String? = null,
    val url: String? = null
)
"""

def generate_tmdb_kotlin_source(
    lang: str,
    pkg_name: str,
    class_name: str,
    base_url: str,
    with_prefs: bool = True,
    tmdb_api_key: str = "ef311eb0b9b07b9c73e9fb0a732cc150"
) -> str:
    package_path = f"eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}"
    return f"""package {package_path}

import android.net.Uri
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

class {class_name} :
    Source(),
    ConfigurableAnimeSource {{

    override val name = "{class_name}"

    override val baseUrl = "{base_url}"

    private val tmdbApiKey = "{tmdb_api_key}"
    private val apiBaseUrl = "https://api.themoviedb.org/3"

    override val lang = "{lang}"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {{
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }}

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/plain, */*")

    private val playlistUtils by lazy {{ PlaylistUtils(client, headers) }}
    private val vidsrcExtractor by lazy {{ VidsrcExtractor(client, headers) }}
    private val universalExtractor by lazy {{ UniversalExtractor(client) }}

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {{
        val request = GET("$apiBaseUrl/trending/all/day?api_key=$tmdbApiKey&page=$page", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull {{ it.toSAnime() }}
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }}

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {{
        val request = GET("$apiBaseUrl/discover/movie?api_key=$tmdbApiKey&page=$page&sort_by=primary_release_date.desc&vote_count.gte=10", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull {{ it.toSAnime() }}
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }}

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {{
        val request = if (query.isNotBlank()) {{
            GET("$apiBaseUrl/search/multi?api_key=$tmdbApiKey&query=${{Uri.encode(query)}}&page=$page", headers)
        }} else {{
            var mediaType = "trending"
            var sortBy = "popularity.desc"
            val genreIds = mutableListOf<String>()

            for (filter in filters) {{
                when (filter) {{
                    is Filters.MediaTypeFilter -> mediaType = filter.selected
                    is Filters.SortFilter -> sortBy = filter.selected
                    is Filters.GenreFilter -> {{
                        filter.state.forEach {{ check ->
                            if (check.state) genreIds.add(check.value)
                        }}
                    }}
                    else -> {{}}
                }}
            }}

            val endpoint = when (mediaType) {{
                "movie" -> {{
                    val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${{genreIds.joinToString(",")}}" else ""
                    "$apiBaseUrl/discover/movie?api_key=$tmdbApiKey&page=$page&sort_by=$sortBy$genreParam"
                }}
                "tv" -> {{
                    val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${{genreIds.joinToString(",")}}" else ""
                    "$apiBaseUrl/discover/tv?api_key=$tmdbApiKey&page=$page&sort_by=$sortBy$genreParam"
                }}
                else -> {{
                    "$apiBaseUrl/trending/all/day?api_key=$tmdbApiKey&page=$page"
                }}
            }}
            GET(endpoint, headers)
        }}

        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull {{ it.toSAnime() }}
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }}

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {{
        val isMovie = anime.url.contains("movie")
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        val endpoint = if (isMovie) {{
            "$apiBaseUrl/movie/$id?api_key=$tmdbApiKey"
        }} else {{
            "$apiBaseUrl/tv/$id?api_key=$tmdbApiKey"
        }}

        return try {{
            val response = client.newCall(GET(endpoint, headers)).execute()
            if (isMovie) {{
                val details = response.parseAs<MovieDetailsDto>(json)
                details.toSAnime(anime.url)
            }} else {{
                val details = response.parseAs<TvDetailsDto>(json)
                details.toSAnime(anime.url)
            }}
        }} catch (_: Exception) {{
            anime
        }}.apply {{
            initialized = true
        }}
    }}

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {{
        val isMovie = anime.url.contains("movie")
        val id = anime.url.substringAfterLast("/").substringBefore("?")

        if (isMovie) {{
            return listOf(
                SEpisode.create().apply {{
                    name = "Full Movie"
                    episode_number = 1.0f
                    url = if (anime.url.startsWith("/")) anime.url else "/movie/$id"
                }},
            )
        }}

        val episodeList = mutableListOf<SEpisode>()
        try {{
            val tvResponse = client.newCall(GET("$apiBaseUrl/tv/$id?api_key=$tmdbApiKey", headers)).execute()
            val tvDetails = tvResponse.parseAs<TvDetailsDto>(json)
            val seasons = tvDetails.seasons ?: emptyList()
            val validSeasons = seasons.filter {{
                val sNum = it.season_number ?: 0
                sNum > 0 && (it.episode_count ?: 0) > 0
            }}.ifEmpty {{
                seasons.filter {{ (it.episode_count ?: 0) > 0 }}
            }}

            for (season in validSeasons) {{
                val seasonNum = season.season_number ?: 1
                val count = season.episode_count ?: 1
                var loadedFromApi = false

                try {{
                    val seasonRes = client.newCall(GET("$apiBaseUrl/tv/$id/season/$seasonNum?api_key=$tmdbApiKey", headers)).execute()
                    val seasonDetails = seasonRes.parseAs<SeasonDetailsDto>(json)
                    val eps = seasonDetails.episodes ?: emptyList()
                    if (eps.isNotEmpty()) {{
                        eps.forEach {{ ep ->
                            episodeList.add(ep.toSEpisode(id.toLong(), seasonNum))
                        }}
                        loadedFromApi = true
                    }}
                }} catch (_: Exception) {{}}

                if (!loadedFromApi && count > 0) {{
                    for (epNum in 1..count) {{
                        episodeList.add(
                            SEpisode.create().apply {{
                                name = "S$seasonNum E$epNum - Episode $epNum"
                                episode_number = epNum.toFloat()
                                url = "/tv/$id?season=$seasonNum&episode=$epNum"
                                scanlator = "Season $seasonNum"
                            }},
                        )
                    }}
                }}
            }}
        }} catch (_: Exception) {{
            return listOf(
                SEpisode.create().apply {{
                    name = "Full Movie / Episode 1"
                    episode_number = 1.0f
                    url = "/movie/$id"
                }},
            )
        }}

        if (episodeList.isEmpty()) {{
            episodeList.add(
                SEpisode.create().apply {{
                    name = "Episode 1"
                    episode_number = 1.0f
                    url = "/tv/$id?season=1&episode=1"
                }},
            )
        }}

        return episodeList.distinctBy {{ it.url }}.reversed()
    }}

    // ============================ Dynamic Hoster Discovery =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {{
        val isMovie = episode.url.contains("movie")
        val id = if (isMovie) {{
            episode.url.substringAfterLast("/").substringBefore("?")
        }} else {{
            episode.url.substringAfter("/tv/").substringBefore("?")
        }}

        val parsedUri = Uri.parse("https://dummy.com${{episode.url}}")
        val season = parsedUri.getQueryParameter("season") ?: "1"
        val ep = parsedUri.getQueryParameter("episode") ?: "1"

        val path = if (isMovie) "movie/$id" else "tv/$id/$season/$ep"
        val hosters = mutableListOf<Hoster>()

        // 1. Premier All-in-One Multi-Quality Folder
        hosters.add(Hoster(hosterName = "⭐ All Servers (Auto / Multi-Quality)", hosterUrl = "vidrock:ALL:$path"))

        val vidrockHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://vidrock.ru/")
            .add("Origin", "https://vidrock.ru")
            .build()

        // 2. Query active dynamic servers
        try {{
            val apiRes = client.newCall(GET("https://vidrock.ru/api/$path", vidrockHeaders)).execute()
            val serverMap = apiRes.parseAs<Map<String, VidrockServerDto?>>(json)

            serverMap.forEach {{ (serverName, dto) ->
                if (dto != null && !dto.url.isNullOrBlank()) {{
                    val lang = dto.language ?: ""
                    val langSuffix = if (lang.isNotBlank() && !lang.equals("English", true)) " [$lang]" else ""
                    val label = when (serverName) {{
                        "Atlas" -> "Server 1 (Atlas - 1080p HLS)"
                        "Orion" -> "Server 2 (Orion - 1080p HLS)"
                        "Lyra" -> "Server 3 (Lyra - 1080p HLS)"
                        "Astra" -> "Server 4 (Astra - Direct MP4)"
                        "Vega" -> "Server 5 (Vega - Fast HLS)"
                        "Nova" -> "Server 6 (Nova)"
                        "Luna" -> "Server 7 (Luna)"
                        else -> "Server ($serverName$langSuffix)"
                    }}
                    hosters.add(Hoster(hosterName = label, hosterUrl = "vidrock:$serverName:$path"))
                }}
            }}
        }} catch (_: Exception) {{}}

        // Fallback default servers if API call failed
        if (hosters.size == 1) {{
            hosters.add(Hoster(hosterName = "Server 1 (Atlas - 1080p HLS)", hosterUrl = "vidrock:Atlas:$path"))
            hosters.add(Hoster(hosterName = "Server 2 (Orion - 1080p HLS)", hosterUrl = "vidrock:Orion:$path"))
            hosters.add(Hoster(hosterName = "Server 3 (Lyra - 1080p HLS)", hosterUrl = "vidrock:Lyra:$path"))
            hosters.add(Hoster(hosterName = "Server 4 (Astra - Direct MP4)", hosterUrl = "vidrock:Astra:$path"))
            hosters.add(Hoster(hosterName = "Server 5 (Vega - Fast HLS)", hosterUrl = "vidrock:Vega:$path"))
        }}

        // 3. Alternative External Video Hosters
        hosters.add(Hoster(hosterName = "Server (VidSrc)", hosterUrl = "vidsrc:$path"))
        hosters.add(Hoster(hosterName = "Server (Vidfast)", hosterUrl = "vidfast:$path"))
        hosters.add(Hoster(hosterName = "Server (MoviesAPI)", hosterUrl = "moviesapi:$path"))
        hosters.add(Hoster(hosterName = "Server (2Embed)", hosterUrl = "2embed:$path"))
        hosters.add(Hoster(hosterName = "Server (Flicky)", hosterUrl = "flicky:$path"))
        hosters.add(Hoster(hosterName = "Server (Nxsha)", hosterUrl = "nxsha:$path"))

        return orderHostersByPref(hosters)
    }}

    private fun orderHostersByPref(hosters: List<Hoster>): List<Hoster> {{
        val prefServer = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hosters.sortedByDescending {{ it.hosterName.contains(prefServer, ignoreCase = true) }}
    }}

    // ============================ Stream Extraction =============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {{
        val rawUrl = hoster.hosterUrl
        val subTracks = mutableListOf<Track>()

        val path = rawUrl.substringAfter(":")
        val isMovie = path.startsWith("movie") || rawUrl.endsWith(":movie")
        val id = when {{
            path.startsWith("movie/") -> path.substringAfter("movie/").substringBefore("?")
            path.startsWith("tv/") -> path.substringAfter("tv/").substringBefore("/")
            rawUrl.contains(":") -> rawUrl.split(":").getOrNull(1) ?: ""
            else -> ""
        }}

        val season = if (!isMovie && path.startsWith("tv/")) {{
            path.split("/").getOrNull(2) ?: "1"
        }} else if (!isMovie && rawUrl.contains(":")) {{
            rawUrl.split(":").getOrNull(2) ?: "1"
        }} else {{
            "1"
        }}

        val ep = if (!isMovie && path.startsWith("tv/")) {{
            path.split("/").getOrNull(3) ?: "1"
        }} else if (!isMovie && rawUrl.contains(":")) {{
            rawUrl.split(":").getOrNull(3) ?: "1"
        }} else {{
            "1"
        }}

        val subPath = if (isMovie) "movie/$id" else "tv/$id/$season/$ep"

        // 1. Multi-Language Subtitles Extraction
        if (id.isNotBlank()) {{
            val subHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .add("Referer", "https://vidrock.ru/")
                .build()

            try {{
                val subReq = GET("https://sub.vdrk.site/v2/$subPath", subHeaders)
                val subRes = client.newCall(subReq).execute()
                val subList = subRes.parseAs<List<SubtitleDto>>(json)
                subList.forEach {{ sub ->
                    val subUrl = sub.file ?: sub.url
                    val subLabel = sub.display ?: sub.label ?: sub.language ?: "Subtitle"
                    if (!subUrl.isNullOrBlank()) {{
                        subTracks.add(Track(subUrl, subLabel))
                    }}
                }}
            }} catch (_: Exception) {{}}

            try {{
                val subReq = GET("https://sub.wyzie.ru/v2/$subPath", subHeaders)
                val subRes = client.newCall(subReq).execute()
                val subList = subRes.parseAs<List<SubtitleDto>>(json)
                subList.forEach {{ sub ->
                    val subUrl = sub.url ?: sub.file
                    val subLabel = sub.display ?: sub.label ?: sub.language ?: "Subtitle"
                    if (!subUrl.isNullOrBlank()) {{
                        subTracks.add(Track(subUrl, subLabel))
                    }}
                }}
            }} catch (_: Exception) {{}}
        }}

        val videoList = mutableListOf<Video>()

        when {{
            // Vidrock ALL Servers Concurrent Extraction
            rawUrl.startsWith("vidrock:ALL:") -> {{
                val vPath = rawUrl.removePrefix("vidrock:ALL:")
                videoList.addAll(extractAllVidrock(vPath, subTracks))
            }}

            // Specific Vidrock Server Extraction
            rawUrl.startsWith("vidrock:") -> {{
                val parts = rawUrl.removePrefix("vidrock:").split(":", limit = 2)
                val targetServer = parts.getOrNull(0) ?: "Atlas"
                val vPath = parts.getOrNull(1) ?: ""
                val res = extractSingleVidrock(targetServer, vPath, subTracks)
                if (res.isNotEmpty()) {{
                    videoList.addAll(res)
                }} else {{
                    videoList.addAll(extractAllVidrock(vPath, subTracks))
                }}
            }}

            // VidSrc Extractor
            rawUrl.startsWith("vidsrc:") -> {{
                val vidsrcPath = rawUrl.removePrefix("vidsrc:")
                val embedUrl = "https://vidsrc.to/embed/$vidsrcPath"
                try {{
                    videoList.addAll(vidsrcExtractor.videosFromUrl(embedUrl, hosterName = "VidSrc", subtitleList = subTracks))
                }} catch (_: Exception) {{}}
            }}

            // Vidfast Provider
            rawUrl.startsWith("vidfast:") -> {{
                val vPath = rawUrl.removePrefix("vidfast:")
                val embedUrl = "https://vidfast.vc/$vPath"
                videoList.addAll(extractUniversalWithFallback(embedUrl, "Vidfast", subTracks))
            }}

            // MoviesAPI Provider
            rawUrl.startsWith("moviesapi:") -> {{
                val vPath = rawUrl.removePrefix("moviesapi:")
                val embedUrl = "https://moviesapi.to/$vPath"
                videoList.addAll(extractUniversalWithFallback(embedUrl, "MoviesAPI", subTracks))
            }}

            // 2Embed Provider
            rawUrl.startsWith("2embed:") -> {{
                val vPath = rawUrl.removePrefix("2embed:")
                val embedUrl = "https://www.2embed.stream/embed/$vPath"
                videoList.addAll(extractUniversalWithFallback(embedUrl, "2Embed", subTracks))
            }}

            // Flicky Provider
            rawUrl.startsWith("flicky:") -> {{
                val vPath = rawUrl.removePrefix("flicky:")
                val embedUrl = if (vPath.startsWith("movie")) {{
                    "https://flicky.host/embed/movie/?id=$id"
                }} else {{
                    "https://flicky.host/embed/tv/?id=$id&season=$season&episode=$ep"
                }}
                videoList.addAll(extractUniversalWithFallback(embedUrl, "Flicky", subTracks))
            }}

            // Nxsha Provider
            rawUrl.startsWith("nxsha:") -> {{
                val vPath = rawUrl.removePrefix("nxsha:")
                val embedUrl = "https://nxsha.space/embed/$vPath"
                videoList.addAll(extractUniversalWithFallback(embedUrl, "Nxsha", subTracks))
            }}
        }}

        // Clean & Attach Global Subtitle Tracks
        val distinctSubs = subTracks.distinctBy {{ it.url }}
        val cleanedList = videoList.map {{ v ->
            val cleanTitle = v.videoTitle
                .replace(Regex("^(vidfast|vidlink|vidsrc|2embed|smashy|multiembed|vidrock)\\\\s*-\\\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank {{ "Auto" }}

            Video(
                videoUrl = v.videoUrl,
                videoTitle = cleanTitle,
                headers = v.headers,
                audioTracks = v.audioTracks,
                subtitleTracks = (v.subtitleTracks.orEmpty() + distinctSubs).distinctBy {{ it.url }},
            )
        }}

        return cleanedList.sortVideos()
    }}

    private suspend fun extractAllVidrock(vPath: String, subTracks: List<Track>): List<Video> = coroutineScope {{
        val vidrockHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://vidrock.ru/")
            .add("Origin", "https://vidrock.ru")
            .build()

        val serverMap: Map<String, VidrockServerDto?> = try {{
            val apiRes = client.newCall(GET("https://vidrock.ru/api/$vPath", vidrockHeaders)).execute()
            apiRes.parseAs<Map<String, VidrockServerDto?>>(json)
        }} catch (_: Exception) {{
            emptyMap()
        }}

        serverMap.entries.mapNotNull {{ (serverName, serverDto) ->
            if (serverDto == null || serverDto.url.isNullOrBlank()) return@mapNotNull null
            async {{
                try {{
                    val streamUrl = decryptVidrock(serverDto.url)
                    if (streamUrl.isBlank()) return@async emptyList<Video>()

                    val lang = serverDto.language ?: ""
                    val langSuffix = if (lang.isNotBlank() && !lang.equals("English", true)) " [$lang]" else ""
                    val prefix = "$serverName$langSuffix - "

                    if (serverName.equals("Astra", ignoreCase = true)) {{
                        val astraRes = client.newCall(GET(streamUrl, vidrockHeaders)).execute()
                        val astraItems = astraRes.parseAs<List<AstraItemDto>>(json)
                        astraItems.mapNotNull {{ item ->
                            if (!item.url.isNullOrBlank()) {{
                                val res = item.resolution ?: 720
                                Video(
                                    videoUrl = item.url,
                                    videoTitle = "$prefix${{res}}p (MP4)",
                                    headers = vidrockHeaders,
                                    subtitleTracks = subTracks,
                                )
                            }} else {{
                                null
                            }}
                        }}
                    }} else if (streamUrl.contains(".m3u8", ignoreCase = true)) {{
                        playlistUtils.extractFromHls(
                            playlistUrl = streamUrl,
                            referer = "https://vidrock.ru/",
                            masterHeaders = vidrockHeaders,
                            videoHeaders = vidrockHeaders,
                            videoNameGen = {{ q -> "$prefix$q" }},
                            subtitleList = subTracks,
                        )
                    }} else {{
                        listOf(
                            Video(
                                videoUrl = streamUrl,
                                videoTitle = "$prefix Direct Stream",
                                headers = vidrockHeaders,
                                subtitleTracks = subTracks,
                            ),
                        )
                    }}
                }} catch (_: Exception) {{
                    emptyList<Video>()
                }}
            }}
        }}.awaitAll().flatten()
    }}

    private fun extractSingleVidrock(serverName: String, vPath: String, subTracks: List<Track>): List<Video> {{
        val vidrockHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://vidrock.ru/")
            .add("Origin", "https://vidrock.ru")
            .build()

        return try {{
            val apiRes = client.newCall(GET("https://vidrock.ru/api/$vPath", vidrockHeaders)).execute()
            val serverMap = apiRes.parseAs<Map<String, VidrockServerDto?>>(json)
            val serverDto = serverMap[serverName] ?: return emptyList()

            if (serverDto.url.isNullOrBlank()) return emptyList()
            val streamUrl = decryptVidrock(serverDto.url)
            if (streamUrl.isBlank()) return emptyList()

            val lang = serverDto.language ?: ""
            val langSuffix = if (lang.isNotBlank() && !lang.equals("English", true)) " [$lang]" else ""
            val prefix = "$serverName$langSuffix - "

            if (serverName.equals("Astra", ignoreCase = true)) {{
                val astraRes = client.newCall(GET(streamUrl, vidrockHeaders)).execute()
                val astraItems = astraRes.parseAs<List<AstraItemDto>>(json)
                astraItems.mapNotNull {{ item ->
                    if (!item.url.isNullOrBlank()) {{
                        val res = item.resolution ?: 720
                        Video(
                            videoUrl = item.url,
                            videoTitle = "$prefix${{res}}p (MP4)",
                            headers = vidrockHeaders,
                            subtitleTracks = subTracks,
                        )
                    }} else {{
                        null
                    }}
                }}
            }} else if (streamUrl.contains(".m3u8", ignoreCase = true)) {{
                playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = "https://vidrock.ru/",
                    masterHeaders = vidrockHeaders,
                    videoHeaders = vidrockHeaders,
                    videoNameGen = {{ q -> "$prefix$q" }},
                    subtitleList = subTracks,
                )
            }} else {{
                listOf(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "$prefix Direct Stream",
                        headers = vidrockHeaders,
                        subtitleTracks = subTracks,
                    ),
                )
            }}
        }} catch (_: Exception) {{
            emptyList()
        }}
    }}

    private fun extractUniversalWithFallback(embedUrl: String, hosterLabel: String, subTracks: List<Track>): List<Video> {{
        val embedHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", embedUrl)
            .build()

        return try {{
            val videos = universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = "$hosterLabel - ")
            videos.map {{ v ->
                Video(
                    videoUrl = v.videoUrl,
                    videoTitle = v.videoTitle,
                    headers = embedHeaders,
                    audioTracks = v.audioTracks,
                    subtitleTracks = (v.subtitleTracks.orEmpty() + subTracks).distinctBy {{ it.url }},
                )
            }}
        }} catch (_: Exception) {{
            emptyList()
        }}
    }}

    private fun decryptVidrock(b64url: String): String {{
        return runCatching {{
            val decoded = Base64.decode(b64url, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            if (decoded.size < 28) return@runCatching ""
            val iv = decoded.copyOfRange(0, 12)
            val ciphertextAndTag = decoded.copyOfRange(12, decoded.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(VIDROCK_AES_KEY, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val plaintext = cipher.doFinal(ciphertextAndTag)
            String(plaintext, Charsets.UTF_8)
        }}.getOrDefault("")
    }}

    // ============================== Settings / Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {{
        screen.addListPreference(
            key = PREF_HOSTER_KEY,
            title = "Preferred Server",
            summary = "%s",
            entries = listOf(
                "⭐ All Servers (Auto / Multi-Quality)",
                "Atlas (1080p HLS)",
                "Orion (1080p HLS)",
                "Lyra (1080p HLS)",
                "Astra (Direct MP4)",
                "Vega (Fast HLS)",
                "VidSrc",
                "Vidfast",
                "MoviesAPI",
                "2Embed",
                "Flicky",
                "Nxsha",
            ),
            entryValues = listOf(
                "All Servers",
                "Atlas",
                "Orion",
                "Lyra",
                "Astra",
                "Vega",
                "VidSrc",
                "Vidfast",
                "MoviesAPI",
                "2Embed",
                "Flicky",
                "Nxsha",
            ),
            default = PREF_HOSTER_DEFAULT,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p", "Auto"),
            entryValues = listOf("1080", "720", "480", "360", "Auto"),
            default = PREF_QUALITY_DEFAULT,
        )
    }}

    override fun List<Video>.sortVideos(): List<Video> {{
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> {{ it.videoTitle.contains(qualityPref, ignoreCase = true) }}
                .thenByDescending {{ getVideoQualityWeight(it.videoTitle) }},
        )
    }}

    private fun getVideoQualityWeight(title: String): Int {{
        val lower = title.lowercase()
        return when {{
            lower.contains("4k") || lower.contains("2160p") -> 4000
            lower.contains("1080p") -> 1080
            lower.contains("720p") -> 720
            lower.contains("480p") -> 480
            lower.contains("360p") -> 360
            lower.contains("hevc") || lower.contains("x265") -> 50
            lower.contains("av1") -> 60
            lower.contains("10-bit") || lower.contains("hdr") -> 10
            else -> 0
        }}
    }}

    companion object {{
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_DEFAULT = "All Servers"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val VIDROCK_AES_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            .chunked(2).map {{ it.toInt(16).toByte() }}.toByteArray()
    }}
}}
"""

def generate_tmdb_dto(lang: str, pkg_name: str) -> str:
    package_path = f"eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}"
    return f"""package {package_path}

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun parseDate(dateStr: String?): Long {{
    if (dateStr.isNullOrBlank()) return 0L
    return try {{
        dateFormat.parse(dateStr)?.time ?: 0L
    }} catch (_: Exception) {{
        0L
    }}
}}

@Serializable
data class SearchResponseDto(
    val page: Int? = null,
    val total_pages: Int? = null,
    val results: List<TmdbItemDto>? = null,
)

@Serializable
data class TmdbItemDto(
    val id: Long? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val media_type: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val genre_ids: List<Int>? = null,
) {{
    fun toSAnime(): SAnime? {{
        val itemId = id ?: return null
        val isMovie = media_type == "movie" || (title != null && media_type != "tv")
        val displayTitle = title ?: name ?: "Unknown"
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""

        return SAnime.create().apply {{
            this.title = displayTitle
            this.url = if (isMovie) "/movie/$itemId" else "/tv/$itemId"
            this.thumbnail_url = fullImageUrl
            this.description = overview
        }}
    }}
}}

@Serializable
data class MovieDetailsDto(
    val id: Long? = null,
    val title: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val status: String? = null,
    val vote_average: Double? = null,
    val runtime: Int? = null,
    val genres: List<GenreDto>? = null,
) {{
    fun toSAnime(fallbackUrl: String): SAnime {{
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""

        return SAnime.create().apply {{
            this.title = this@MovieDetailsDto.title ?: "Movie"
            this.url = fallbackUrl
            this.thumbnail_url = fullImageUrl
            this.description = overview
            this.genre = genres?.mapNotNull {{ it.name }}?.joinToString(", ")
            this.status = when (this@MovieDetailsDto.status) {{
                "Released" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }}
        }}
    }}
}}

@Serializable
data class TvDetailsDto(
    val id: Long? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val status: String? = null,
    val vote_average: Double? = null,
    val number_of_seasons: Int? = null,
    val number_of_episodes: Int? = null,
    val genres: List<GenreDto>? = null,
    val seasons: List<SeasonSummaryDto>? = null,
) {{
    fun toSAnime(fallbackUrl: String): SAnime {{
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""

        return SAnime.create().apply {{
            this.title = this@TvDetailsDto.name ?: "TV Show"
            this.url = fallbackUrl
            this.thumbnail_url = fullImageUrl
            this.description = overview
            this.genre = genres?.mapNotNull {{ it.name }}?.joinToString(", ")
            this.status = when (this@TvDetailsDto.status) {{
                "Ended", "Canceled" -> SAnime.COMPLETED
                "Returning Series", "In Production" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }}
        }}
    }}
}}

@Serializable
data class SeasonSummaryDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val episode_count: Int? = null,
    val name: String? = null,
    val air_date: String? = null,
    val poster_path: String? = null,
)

@Serializable
data class SeasonDetailsDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val episodes: List<EpisodeItemDto>? = null,
)

@Serializable
data class EpisodeItemDto(
    val id: Long? = null,
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val air_date: String? = null,
    val still_path: String? = null,
    val vote_average: Double? = null,
) {{
    fun toSEpisode(showId: Long, seasonNum: Int): SEpisode {{
        val epNum = episode_number ?: 1
        val epName = name ?: "Episode $epNum"
        return SEpisode.create().apply {{
            this.name = "S$seasonNum E$epNum - $epName"
            this.episode_number = epNum.toFloat()
            this.date_upload = parseDate(air_date)
            this.url = "/tv/$showId?season=$seasonNum&episode=$epNum"
            this.scanlator = "Season $seasonNum"
        }}
    }}
}}

@Serializable
data class GenreDto(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
data class VidrockServerDto(
    val url: String? = null,
    val language: String? = null,
    val flag: String? = null,
    val type: String? = null,
)

@Serializable
data class AstraItemDto(
    val resolution: Int? = null,
    val url: String? = null,
)

@Serializable
data class SubtitleDto(
    val file: String? = null,
    val url: String? = null,
    val label: String? = null,
    val display: String? = null,
    val language: String? = null,
)
"""

def generate_tmdb_filters_source(lang: str, pkg_name: str) -> str:
    package_path = f"eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}"
    return f"""package {package_path}

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {{
    open class SelectFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: String? = null,
    ) : AnimeFilter.Select<String>(
        name,
        vals.map {{ it.first }}.toTypedArray(),
        vals.indexOfFirst {{ it.second == defaultValue }}.takeIf {{ it != -1 }} ?: 0,
    ) {{
        val selected: String
            get() = vals[state].second
    }}

    open class CheckBoxFilter(
        name: String,
        val value: String,
    ) : AnimeFilter.CheckBox(name)

    open class GenreGroup(
        name: String,
        genres: List<GenreVal>,
    ) : AnimeFilter.Group<CheckBoxFilter>(
        name,
        genres.map {{ CheckBoxFilter(it.name, it.id) }},
    )

    data class GenreVal(val name: String, val id: String)

    class MediaTypeFilter :
        SelectFilter(
            "Media Type",
            arrayOf(
                Pair("All / Trending", "trending"),
                Pair("Movies", "movie"),
                Pair("TV Shows", "tv"),
            ),
            "trending",
        )

    class SortFilter :
        SelectFilter(
            "Sort By",
            arrayOf(
                Pair("Popularity", "popularity.desc"),
                Pair("Release Date", "primary_release_date.desc"),
                Pair("Rating", "vote_average.desc"),
                Pair("Title", "original_title.asc"),
            ),
            "popularity.desc",
        )

    val GENRES = listOf(
        GenreVal("Action", "28"),
        GenreVal("Adventure", "12"),
        GenreVal("Animation", "16"),
        GenreVal("Comedy", "35"),
        GenreVal("Crime", "80"),
        GenreVal("Documentary", "99"),
        GenreVal("Drama", "18"),
        GenreVal("Family", "10751"),
        GenreVal("Fantasy", "14"),
        GenreVal("History", "36"),
        GenreVal("Horror", "27"),
        GenreVal("Music", "10402"),
        GenreVal("Mystery", "9648"),
        GenreVal("Romance", "10749"),
        GenreVal("Science Fiction", "878"),
        GenreVal("TV Movie", "10770"),
        GenreVal("Thriller", "53"),
        GenreVal("War", "10752"),
        GenreVal("Western", "37"),
    )

    class GenreFilter : GenreGroup("Genres", GENRES)

    fun getFilterList() = AnimeFilterList(
        MediaTypeFilter(),
        SortFilter(),
        GenreFilter(),
    )
}}
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
        }}
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
    print("  1. Standard Anime HTML Scraper (Jsoup + CSS Selectors)")
    print("  2. JSON / REST API (kotlinx.serialization)")
    print("  3. Multisrc Theme Variant (inherits lib-multisrc theme)")
    print("  4. Movie & Series Locker (2-Tier Hoster Folders + Batch Linkstores)")
    print("  5. TMDB Media Hub (Next.js / Direct Multi-Server Extractors + Subtitles)")
    arch_choice = input("Select Architecture [1-5, default 1]: ").strip()

    theme = None
    if arch_choice == "2":
        site_type = "api"
    elif arch_choice == "3":
        site_type = "theme"
        theme = input("Theme package name (e.g. 'anikototheme', 'dooplay', 'jellyfin'): ").strip()
    elif arch_choice == "4":
        site_type = "movie-locker"
    elif arch_choice == "5":
        site_type = "tmdb"
    else:
        site_type = "html"

    with_prefs = input("Generate PreferenceScreen dropdowns (Quality/Server/Exclusions)? [Y/n]: ").strip().lower() != "n"
    with_extractors = input("Include common video extractors (Dood, StreamTape, FileMoon, Universal)? [Y/n]: ").strip().lower() != "n"
    with_metadata = input("Include episode metadata & Sub/Dub scanlator parser? [Y/n]: ").strip().lower() != "n"
    with_filters = input("Generate modular Filters.kt file? [Y/n]: ").strip().lower() != "n"
    nsfw = input("Is this extension NSFW / 18+? [y/N]: ").strip().lower() == "y"

    print("\n" + "═" * 55)
    print("📋 Extension Scaffolding Review Summary")
    print("═" * 55)
    print(f"  • Name:              {name}")
    print(f"  • Language:          {lang}")
    print(f"  • Target Directory:  src/{lang}/{to_package_name(name)}")
    print(f"  • Architecture:      {site_type.upper()}")
    print(f"  • Base URL:          {base_url}")
    print(f"  • Preferences:       {'Yes' if with_prefs else 'No'}")
    print(f"  • Extractors:        {'Yes (Dood, StreamTape, FileMoon, Universal)' if with_extractors else 'No'}")
    print(f"  • Episode Metadata:  {'Yes' if with_metadata else 'No'}")
    print(f"  • Modular Filters:   {'Yes' if with_filters else 'No'}")
    print(f"  • NSFW / 18+:        {'Yes' if nsfw else 'No'}")
    print("═" * 55)
    confirm = input("\nGenerate extension module with these settings? [Y/n]: ").strip().lower()
    if confirm == "n":
        print("🛑 Scaffolding cancelled by user.")
        sys.exit(0)

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

def generate_extension(
    ext_name: str,
    lang: str = "en",
    base_url: str = "https://example.com",
    site_type: str = "html",
    theme_name: Optional[str] = None,
    repo_root: Optional[Path] = None,
    has_preferences: bool = True,
    has_extractors: bool = True,
    has_metadata: bool = True,
    has_filters: bool = True,
    nsfw: bool = False,
    version_code: int = 1,
    custom_selectors: Optional[Dict[str, str]] = None
) -> bool:
    if repo_root is None:
        repo_root = Path(__file__).resolve().parent.parent

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
        theme_pkg=theme_name if site_type == "theme" else None,
        nsfw=nsfw,
        version_code=version_code,
        with_extractors=has_extractors,
        site_type=site_type
    )
    build_gradle_path.write_text(build_gradle_content, encoding="utf-8")
    print(f"  [+] Created {build_gradle_path.relative_to(repo_root)}")

    # 2. Write Filters.kt if enabled
    if has_filters and site_type != "theme":
        filters_path = src_dir / "Filters.kt"
        if site_type in ["tmdb", "tmdb-media"]:
            filters_content = generate_tmdb_filters_source(lang, pkg_name)
        else:
            filters_content = generate_filters_kotlin_source(lang, pkg_name)
        filters_path.write_text(filters_content, encoding="utf-8")
        print(f"  [+] Created {filters_path.relative_to(repo_root)}")

    # 3. Write Main Kotlin Source and DTOs
    kt_file_path = src_dir / f"{class_name}.kt"
    if site_type in ["tmdb", "tmdb-media"]:
        dto_path = src_dir / f"{class_name}Dto.kt"
        dto_content = generate_tmdb_dto(lang, pkg_name)
        dto_path.write_text(dto_content, encoding="utf-8")
        print(f"  [+] Created {dto_path.relative_to(repo_root)}")

        kt_content = generate_tmdb_kotlin_source(
            lang=lang,
            pkg_name=pkg_name,
            class_name=class_name,
            base_url=base_url,
            with_prefs=has_preferences
        )
    elif site_type == "api":
        kt_content = generate_api_kotlin_source(
            lang=lang,
            pkg_name=pkg_name,
            class_name=class_name,
            base_url=base_url,
            with_prefs=has_preferences,
            with_extractors=has_extractors
        )
    elif site_type == "movie-locker":
        kt_content = generate_movie_locker_kotlin_source(
            lang=lang,
            pkg_name=pkg_name,
            class_name=class_name,
            base_url=base_url,
            with_prefs=has_preferences,
            with_extractors=has_extractors
        )
    elif site_type == "theme" and theme_name:
        package_path = f"eu.kanade.tachiyomi.animeextension.{lang}.{pkg_name}"
        theme_class = to_pascal_case(theme_name) + ("Theme" if not theme_name.endswith("theme") else "")
        kt_content = f"""package {package_path}

import eu.kanade.tachiyomi.multisrc.{theme_name.lower()}.{theme_class}

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
            with_prefs=has_preferences,
            with_extractors=has_extractors,
            with_metadata=has_metadata,
            with_filters=has_filters
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
    return True

def main():
    parser = argparse.ArgumentParser(description="Aniyomi Extension Generator CLI (Production Grade)")
    parser.add_argument("--name", help="Extension display name (e.g., 'AnimeFlix')")
    parser.add_argument("--lang", default="en", help="Language code (e.g., 'en', 'all', 'es', 'id'). Default: 'en'")
    parser.add_argument("--baseUrl", default="https://example.com", help="Base URL of the target site")
    parser.add_argument("--siteType", choices=["html", "api", "theme", "movie-locker", "tmdb", "tmdb-media"], default="html", help="Source architecture (html, api, theme, movie-locker, tmdb)")
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

    success = generate_extension(
        ext_name=ext_name,
        lang=lang,
        base_url=base_url,
        site_type=site_type,
        theme_name=theme,
        repo_root=repo_root,
        has_preferences=with_prefs,
        has_extractors=with_extractors,
        has_metadata=with_metadata,
        has_filters=with_filters,
        nsfw=nsfw,
        version_code=version_code
    )
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()

