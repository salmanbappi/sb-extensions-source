"""
Kotlin Synthesizer Agent
Synthesizes strictly compliant Aniyomi API v16 extension modules,
null-safe Kotlin Serialization DTOs, Source inheritance, and Video model constructors.
"""

from dataclasses import dataclass, field
from typing import Dict, List, Optional
from .recon_swarm import SchemaField


@dataclass
class SynthesizerConfig:
    pkg_name: str
    class_name: str
    source_name: str
    base_url: str
    lang: str = "en"
    use_preferences: bool = True
    support_search: bool = True
    support_filters: bool = True
    enable_local_proxy: bool = False
    custom_headers: Dict[str, str] = field(default_factory=dict)
    helper_code: List[str] = field(default_factory=list)


@dataclass
class SynthesizedExtension:
    pkg_name: str
    class_name: str
    source_code: str
    dto_code: str
    full_combined_code: str
    config: SynthesizerConfig


class DtoGenerator:
    """Generates robust, null-safe Kotlinx Serialization DTO models with `= null` fallbacks."""

    @staticmethod
    def generate_dto_class(class_name: str, fields: List[SchemaField]) -> str:
        """Renders a single null-safe @Serializable data class."""
        lines = [f"@Serializable", f"data class {class_name}("]

        field_lines = []
        for f in fields:
            # Ensure type is nullable
            ktype = f.kotlin_type if f.kotlin_type.endswith("?") else f"{f.kotlin_type}?"
            serial_name_anno = f'    @SerialName("{f.json_key}") ' if f.json_key else "    "
            default_str = f" = {f.default_value}" if f.default_value is not None else " = null"
            field_lines.append(f"{serial_name_anno}val {f.name}: {ktype}{default_str},")

        lines.append("\n".join(field_lines))
        lines.append(")")
        return "\n".join(lines)

    @classmethod
    def generate_all_dtos(cls, schemas: Dict[str, List[SchemaField]]) -> str:
        """Generates all DTO classes from a dictionary of inferred schemas."""
        classes = []
        for class_name, fields in schemas.items():
            classes.append(cls.generate_dto_class(class_name, fields))
        return "\n\n".join(classes)


class SourceClassGenerator:
    """Generates the main Source class implementing Aniyomi API v16 specifications."""

    @staticmethod
    def generate_source(config: SynthesizerConfig, dtos_code: str = "") -> str:
        headers_entries = []
        for k, v in config.custom_headers.items():
            headers_entries.append(f'        .add("{k}", "{v}")')
        headers_block = (
            "\n" + "\n".join(headers_entries) if headers_entries else ""
        )

        helpers_block = "\n\n".join(config.helper_code) if config.helper_code else ""

        code = f"""package {config.pkg_name}

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import extensions.utils.UrlUtils
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class {config.class_name} : Source(), ConfigurableAnimeSource {{

    override val name = "{config.source_name}"
    override val baseUrl = "{config.base_url}"
    override val lang = "{config.lang}"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json = Json {{
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }}

    private val preferences: SharedPreferences by lazy {{
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }}

    override fun headersBuilder(): Headers.Builder = super.headersBuilder(){headers_block}

    // ============================== Popular / Latest ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {{
        val document = response.asJsoup()
        val animeList = document.select("div.item, div.anime-card").map {{ element ->
            SAnime.create().apply {{
                title = element.selectFirst("h2, .title")?.text().orEmpty()
                val relativeUrl = element.selectFirst("a")?.attr("href").orEmpty()
                url = UrlUtils.fixUrl(relativeUrl, baseUrl)
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }}
        }}
        return AnimesPage(animeList, animeList.isNotEmpty())
    }}

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {{
        val url = "$baseUrl/search?q=$query&page=$page"
        return GET(url, headers)
    }}

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details (API v16) ===========================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {{
        val request = GET(UrlUtils.fixUrl(anime.url, baseUrl), headers)
        val response = client.newCall(request).execute()
        val document = response.asJsoup()

        return anime.apply {{
            title = document.selectFirst("h1.title, .entry-title")?.text() ?: anime.title
            thumbnail_url = document.selectFirst("div.thumb img")?.attr("abs:src") ?: anime.thumbnail_url
            description = document.selectFirst("div.synopsis, div.description")?.text()
            genre = document.select("div.genres a").joinToString {{ it.text() }}
            status = SAnime.UNKNOWN
            initialized = true
        }}
    }}

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {{
        val document = response.asJsoup()
        return document.select("ul.episodes li, div.episode-item").mapIndexed {{ index, element ->
            SEpisode.create().apply {{
                val epUrl = element.selectFirst("a")?.attr("href").orEmpty()
                url = UrlUtils.fixUrl(epUrl, baseUrl)
                name = element.selectFirst(".ep-title, a")?.text() ?: "Episode ${{index + 1}}"
                episode_number = (index + 1).toFloat()
            }}
        }}.reversed()
    }}

    // ============================ Video Streams (v16) ============================

    override fun videoListParse(response: Response): List<Video> {{
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val iframeSrc = document.selectFirst("iframe")?.attr("abs:src")
        if (!iframeSrc.isNullOrBlank()) {{
            val videoUrl = UrlUtils.fixUrl(iframeSrc, baseUrl)
            videoList.add(
                Video(
                    videoUrl = videoUrl,
                    videoTitle = "Default Server",
                    headers = headers
                )
            )
        }}

        return videoList
    }}

    // ============================ Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {{
        val serverPref = ListPreference(screen.context).apply {{
            key = PREF_KEY_SERVER
            title = "Preferred Server"
            entries = arrayOf("Default", "Backup")
            entryValues = arrayOf("default", "backup")
            setDefaultValue(PREF_DEFAULT_SERVER)
            summary = "%s"
            setOnPreferenceChangeListener {{ _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }}
        }}
        screen.addPreference(serverPref)
    }}

    {helpers_block}

    companion object {{
        private const val PREF_KEY_SERVER = "preferred_server"
        private const val PREF_DEFAULT_SERVER = "default"
    }}
}}

{dtos_code}
""".strip()
        return code


class KotlinSynthesizerAgent:
    """Autonomous Kotlin Code Synthesizer adhering strictly to API v16 invariants."""

    def __init__(self):
        self.dto_gen = DtoGenerator()
        self.source_gen = SourceClassGenerator()

    def synthesize(
        self,
        config: SynthesizerConfig,
        schemas: Optional[Dict[str, List[SchemaField]]] = None,
    ) -> SynthesizedExtension:
        """Synthesizes complete Kotlin extension code and DTO definitions."""
        dto_code = ""
        if schemas:
            dto_code = self.dto_gen.generate_all_dtos(schemas)

        source_code = self.source_gen.generate_source(config, dto_code)

        return SynthesizedExtension(
            pkg_name=config.pkg_name,
            class_name=config.class_name,
            source_code=source_code,
            dto_code=dto_code,
            full_combined_code=source_code,
            config=config,
        )
