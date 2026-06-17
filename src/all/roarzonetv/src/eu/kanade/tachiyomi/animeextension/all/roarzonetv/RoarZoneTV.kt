package eu.kanade.tachiyomi.animeextension.all.roarzonetv

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
import org.jsoup.Jsoup

class RoarZoneTV :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Roar zone tv"
    override val baseUrl = "https://tv.roarzone.net"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 84769302158234569L

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "$baseUrl/")
                .build()
            chain.proceed(request)
        }
        .build()

    private suspend fun fetchChannels(): List<SAnime> {
        val response = client.newCall(GET(baseUrl)).execute()
        val document = Jsoup.parse(response.body?.string() ?: "")

        return document.select(".channel-card").map { element ->
            SAnime.create().apply {
                title = element.attr("data-title")
                url = element.attr("data-stream")
                thumbnail_url = element.select("img").attr("abs:src")
                genre = element.attr("data-tags")
                initialized = true
            }
        }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(fetchChannels(), false)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        var filtered = fetchChannels()

        if (query.isNotBlank()) {
            filtered = filtered.filter { it.title.contains(query, ignoreCase = true) }
        }

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    val selectedCategory = filter.toValue()
                    if (selectedCategory.isNotBlank()) {
                        filtered = filtered.filter { it.genre?.contains(selectedCategory, ignoreCase = true) == true }
                    }
                }

                else -> {}
            }
        }

        return AnimesPage(filtered, false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply {
        status = SAnime.UNKNOWN
        description = "Live Stream: $title"
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
        val playerUrl = "$baseUrl/player.php?stream=${episode.url}"
        val response = client.newCall(GET(playerUrl)).execute()
        val html = response.body?.string() ?: ""

        // Extract m3u8 from the Plyr/Hls script
        val streamUrl = Regex("""hls\.loadSource\(['"](.*?)['"]\)""").find(html)?.groupValues?.get(1)
            ?: Regex("""source src=['"](.*?)['"]""").find(html)?.groupValues?.get(1)
            ?: throw Exception("Could not find stream URL")

        return listOf(Video(videoUrl = streamUrl, videoTitle = "Live Stream"))
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
    )

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Category",
            arrayOf("All", "Bangla", "Documentary", "English", "Hindi", "Indian Bangla", "Kids", "Music", "Sports"),
        ) {
        fun toValue() = when (state) {
            1 -> "bangla"
            2 -> "documentary"
            3 -> "english"
            4 -> "hindi"
            5 -> "inbangla"
            6 -> "kids"
            7 -> "music"
            8 -> "sports"
            else -> ""
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
