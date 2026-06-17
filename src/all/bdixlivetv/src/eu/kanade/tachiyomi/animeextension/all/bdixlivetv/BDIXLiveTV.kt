package eu.kanade.tachiyomi.animeextension.all.bdixlivetv

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BDIXLiveTV :
    Source(),
    ConfigurableAnimeSource {

    override val name = "BDIX Live TV"
    override val baseUrl = "http://172.16.29.28"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 4519283712345678910L

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun getPopularAnime(page: Int): AnimesPage = getSearchAnime(page, "", getFilterList())

    override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val mode = preferences.getString("preferred_mode", "website")

        return when (mode) {
            "m3u" -> getM3UChannels(query)
            "xtream" -> getXtreamChannels(query)
            else -> getWebsiteChannels(query, filters)
        }
    }

    private suspend fun getWebsiteChannels(query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(GET(baseUrl)).execute()
        val html = response.body?.string() ?: ""

        val categoryFilter = filters.find { it is CategoryFilter } as? CategoryFilter
        val selectedCategory = categoryFilter?.let { it.values[it.state] } ?: "ALL"

        val animeList = mutableListOf<SAnime>()
        val channelRegex = Regex("""\{name:\s*"(.*?)",\s*url:\s*'(.*?)',\s*logo:\s*"(.*?)"\}""")
        val categoryBlocks = Regex("""(\w+):\s*\[([\s\S]*?)\]""").findAll(html)

        categoryBlocks.forEach { block ->
            val categoryName = block.groups[1]?.value ?: ""
            val blockContent = block.groups[2]?.value ?: ""

            if (selectedCategory == "ALL" || selectedCategory.equals(categoryName, ignoreCase = true)) {
                channelRegex.findAll(blockContent).forEach { match ->
                    val name = match.groups[1]?.value ?: ""
                    val url = match.groups[2]?.value ?: ""
                    val logo = match.groups[3]?.value ?: ""

                    if (name.isNotBlank() && name.contains(query, ignoreCase = true)) {
                        animeList.add(
                            SAnime.create().apply {
                                this.title = name
                                this.url = url
                                this.thumbnail_url = fixUrl(logo)
                                this.genre = categoryName
                                this.initialized = true
                            },
                        )
                    }
                }
            }
        }
        return AnimesPage(animeList, false)
    }

    private suspend fun getM3UChannels(query: String): AnimesPage {
        val m3uUrl = preferences.getString("m3u_url", "http://172.16.29.34/playlist.m3u") ?: ""
        if (m3uUrl.isBlank()) return AnimesPage(emptyList(), false)

        val response = client.newCall(GET(m3uUrl)).execute()
        val content = response.body?.string() ?: ""

        val animeList = mutableListOf<SAnime>()
        val lines = content.lines()

        var currentName = ""
        var currentLogo = ""

        for (line in lines) {
            if (line.startsWith("#EXTINF")) {
                currentName = line.substringAfterLast(",").trim()
                currentLogo = line.substringAfter("tvg-logo=\"", "").substringBefore("\"", "")
            } else if (line.startsWith("http")) {
                if (currentName.contains(query, ignoreCase = true)) {
                    animeList.add(
                        SAnime.create().apply {
                            title = currentName
                            url = line.trim()
                            thumbnail_url = currentLogo.ifBlank { null }
                            initialized = true
                        },
                    )
                }
            }
        }
        return AnimesPage(animeList, false)
    }

    private suspend fun getXtreamChannels(query: String): AnimesPage {
        val xtreamUrl = "http://172.16.29.34/player_api.php?username=ontest1&password=ontest1&action=get_live_streams"
        val response = client.newCall(GET(xtreamUrl)).execute()
        val json = response.body?.string() ?: ""

        val nameRegex = Regex(""""name":"(.*?)"""")
        val idRegex = Regex(""""stream_id":(\d+)""")
        val iconRegex = Regex(""""stream_icon":"(.*?)"""")

        val names = nameRegex.findAll(json).toList()
        val ids = idRegex.findAll(json).toList()
        val icons = iconRegex.findAll(json).toList()

        val animeList = mutableListOf<SAnime>()
        for (i in names.indices) {
            val name = names[i].groups[1]?.value ?: ""
            val id = ids.getOrNull(i)?.groups[1]?.value ?: ""
            val icon = icons.getOrNull(i)?.groups[1]?.value?.replace("""\/""", "/") ?: ""

            if (name.contains(query, ignoreCase = true)) {
                animeList.add(
                    SAnime.create().apply {
                        title = name
                        url = "http://172.16.29.34/live/ontest1/ontest1/$id.ts"
                        thumbnail_url = icon.ifBlank { null }
                        initialized = true
                    },
                )
            }
        }
        return AnimesPage(animeList, false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply {
        status = SAnime.UNKNOWN
        description = "Live Stream: ${anime.title}"
        initialized = true
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = anime.title
            url = anime.url
            episode_number = 1F
        },
    )

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = episode.url
        return listOf(Video(videoUrl = url, videoTitle = "Live Stream"))
    }

    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    override fun getFilterList() = AnimeFilterList(CategoryFilter())

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Category (Website Only)",
            arrayOf("ALL", "SPORTS", "BANGLA", "HINDI", "NEWS", "MUSIC", "KIDS", "ENGLISH"),
        )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val modePref = ListPreference(screen.context).apply {
            key = "preferred_mode"
            title = "Streaming Source"
            entries = arrayOf("Website (172.16.29.28)", "M3U Playlist", "Xtream Codes API (172.16.29.34)")
            entryValues = arrayOf("website", "m3u", "xtream")
            setDefaultValue("website")
            summary = "%s"
        }
        screen.addPreference(modePref)

        val m3uUrlPref = EditTextPreference(screen.context).apply {
            key = "m3u_url"
            title = "M3U Playlist URL"
            setDefaultValue("http://172.16.29.34/playlist.m3u")
            summary = "Enter the full URL to your .m3u file"
        }
        screen.addPreference(m3uUrlPref)
    }
}
