package eu.kanade.tachiyomi.animeextension.all.mangolivetv

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
import org.json.JSONObject

class MangoLiveTV :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Mango Live TV"
    override val baseUrl = "http://10.114.130.254"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 8519283712345678910L

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    private val apiUrl = "http://tv.mango.com.bd/tv-server"

    override suspend fun getPopularAnime(page: Int): AnimesPage = getSearchAnime(page, "", getFilterList())

    override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(GET(apiUrl)).execute()
        val jsonString = response.body?.string() ?: ""
        val json = JSONObject(jsonString)

        val categoryFilter = filters.find { it is CategoryFilter } as? CategoryFilter
        val selectedCategory = categoryFilter?.let { it.values[it.state] } ?: "ALL"

        val animeList = mutableListOf<SAnime>()

        json.keys().forEach { categoryName ->
            if (selectedCategory == "ALL" || selectedCategory.equals(categoryName, ignoreCase = true)) {
                val channels = json.getJSONArray(categoryName)
                for (i in 0 until channels.length()) {
                    val channel = channels.getJSONObject(i)
                    val name = channel.optString("channel_name")
                    val url = channel.optString("url")
                    val image = channel.optString("image")

                    if (name.contains(query, ignoreCase = true)) {
                        animeList.add(
                            SAnime.create().apply {
                                this.title = name
                                this.url = url
                                this.thumbnail_url = image
                                this.genre = categoryName
                                this.initialized = true
                            },
                        )
                    }
                }
            }
        }

        return AnimesPage(animeList.distinctBy { it.url }, false)
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

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(CategoryFilter())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Category",
            arrayOf("ALL", "Top Picks", "News", "Sports", "Prime Play", "Sportzfy", "Religion", "Movie", "Kids", "Ent", "Music", "Info", "International"),
        )
}
