package eu.kanade.tachiyomi.animeextension.all.livesports

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

class LiveSports :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Live Sports"
    override val baseUrl = "http://10.20.30.40"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 8246513271928475192L

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET(baseUrl)).execute()
        val doc = response.asJsoup()
        val channelElements = doc.select("div.channel-item")
        val animeList = channelElements.map { element ->
            val dataChannel = element.attr("data-channel")
            val img = element.selectFirst("img")
            val imgSrc = img?.attr("src")
            val altText = img?.attr("alt")

            val title = if (!altText.isNullOrBlank()) {
                altText
            } else {
                // Extract from URL if alt is empty: .../Sports-1/index.m3u8 -> Sports-1
                dataChannel.substringBeforeLast("/").substringAfterLast("/")
            }

            SAnime.create().apply {
                this.url = dataChannel
                this.title = title
                this.thumbnail_url = if (imgSrc != null) fixUrl(imgSrc) else null
            }
        }

        return AnimesPage(animeList, false)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val all = getPopularAnime(1)
        if (query.isBlank()) return all
        val filtered = all.animes.filter { it.title.contains(query, ignoreCase = true) }
        return AnimesPage(filtered, false)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime.apply {
        status = SAnime.UNKNOWN
        description = "Live Sports Channel: ${anime.title}"
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

    override fun getFilterList() = AnimeFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
