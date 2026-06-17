package eu.kanade.tachiyomi.animeextension.all.fmftp

import eu.kanade.tachiyomi.animeextension.all.fmftp.dto.FmFtpContent
import eu.kanade.tachiyomi.animeextension.all.fmftp.dto.FmFtpResponse
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import okhttp3.OkHttpClient
import okhttp3.Response

class FmFtp : Source() {

    override val name = "FM FTP"
    override val baseUrl = "https://fmftp.net"
    private val apiBaseUrl = "https://fmftp.net/api"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 7214566391484419847L

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", baseUrl)
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$apiBaseUrl/movies?page=$page&limit=20")).execute()
        return parseAnimesPage(response)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = throw UnsupportedOperationException("Not Used")

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            // Sanitize query to prevent server-side SQL errors (apostrophes break their API)
            val sanitizedQuery = query.replace("'", "").replace("\"", "")
            val response = client.newCall(GET("$apiBaseUrl/search?search=$sanitizedQuery")).execute()
            val bodyString = response.body.string()

            // Handle error response gracefully
            if (bodyString.contains("\"success\":false")) {
                return AnimesPage(emptyList(), false)
            }

            return try {
                val data = json.decodeFromString<List<FmFtpContent>>(bodyString)
                val animes = data.sortedByDescending { diceCoefficient(it.title.lowercase(), sanitizedQuery.lowercase()) }
                    .map { it.toSAnime() }
                AnimesPage(animes, false)
            } catch (e: Exception) {
                AnimesPage(emptyList(), false)
            }
        }

        var libraryId = ""
        var contentType = "movies"
        filters.forEach { filter ->
            when (filter) {
                is MovieLibraryFilter -> if (filter.toValue().isNotEmpty()) {
                    libraryId = filter.toValue()
                    contentType = "movies"
                }

                is TvLibraryFilter -> if (filter.toValue().isNotEmpty()) {
                    libraryId = filter.toValue()
                    contentType = "tv-shows"
                }

                else -> {}
            }
        }

        val url = if (libraryId.isNotEmpty()) {
            "$apiBaseUrl/$contentType?library=$libraryId&page=$page&limit=20"
        } else {
            "$apiBaseUrl/movies?page=$page&limit=20"
        }

        val response = client.newCall(GET(url)).execute()
        return parseAnimesPage(response)
    }

    private fun parseAnimesPage(response: Response): AnimesPage {
        val res = json.decodeFromString<FmFtpResponse>(response.body.string())
        val animes = res.data?.map { it.toSAnime() } ?: emptyList()
        val hasNextPage = res.current_page?.let { it < (res.pages ?: 0) } ?: false
        return AnimesPage(animes, hasNextPage)
    }

    private fun FmFtpContent.toSAnime(): SAnime = SAnime.create().apply {
        val type = Library?.type ?: "MOVIE"
        url = "/watch?type=$type&id=$id"
        title = this@toSAnime.title
        thumbnail_url = if (poster_path != null) "https://image.tmdb.org/t/p/w500$poster_path" else null
        genre = this@toSAnime.genre
        description = overview
        status = if (type == "MOVIE") SAnime.COMPLETED else SAnime.ONGOING
        initialized = true
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val type = anime.url.substringAfter("type=").substringBefore("&")
        val id = anime.url.substringAfter("id=")

        if (type == "MOVIE") {
            return listOf(
                SEpisode.create().apply {
                    name = "Play Movie"
                    url = "/watch?type=movies&id=$id"
                    episode_number = 1F
                },
            )
        } else {
            val response = client.newCall(GET("$apiBaseUrl/tv-shows/$id?fields=episodes")).execute()
            val content = json.decodeFromString<FmFtpContent>(response.body.string())
            return content.episodes?.map { ep ->
                SEpisode.create().apply {
                    val s = ep.season_number ?: 1
                    val e = ep.episode_number ?: 0
                    name = "S$s E$e" + (if (ep.name.isNullOrBlank()) "" else " - ${ep.name}")
                    url = "/watch?type=tv_shows&id=${ep.id}"
                    episode_number = (s * 1000 + e).toFloat()
                }
            }?.sortedByDescending { it.episode_number } ?: emptyList()
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val type = episode.url.substringAfter("type=").substringBefore("&")
        val id = episode.url.substringAfter("id=")
        val videoUrl = "$apiBaseUrl/stream/video/stream?type=$type&id=$id"
        return listOf(Video(videoUrl = videoUrl, videoTitle = "Direct"))
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        val n1 = s1.length
        val n2 = s2.length
        if (n1 == 0 || n2 == 0) return 0.0
        val bigrams1 = HashSet<String>()
        for (i in 0 until n1 - 1) bigrams1.add(s1.substring(i, i + 2))
        var intersection = 0
        for (i in 0 until n2 - 1) {
            val bigram = s2.substring(i, i + 2)
            if (bigrams1.contains(bigram)) intersection++
        }
        return (2.0 * intersection) / (n1 + n2 - 2).coerceAtLeast(1)
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Use only one filter at a time"),
        MovieLibraryFilter(),
        TvLibraryFilter(),
    )

    private open class SelectFilter(name: String, val items: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, items.map { it.first }.toTypedArray()) {
        fun toValue() = items[state].second
    }

    private class MovieLibraryFilter :
        SelectFilter(
            "Movies",
            arrayOf(
                "None" to "",
                "Bollywood" to "1",
                "Hollywood" to "2",
                "Indian Bangla" to "7",
                "Tamil" to "8",
                "Horror" to "6",
                "Hindi dubbed" to "5",
                "Korean" to "4",
                "Animation" to "3",
            ),
        )

    private class TvLibraryFilter :
        SelectFilter(
            "TV Shows",
            arrayOf(
                "None" to "",
                "Turkish Tv Series" to "13",
                "Bangla Tv Series" to "12",
                "korean Tv Series" to "11",
                "Indian Tv Series" to "10",
                "English tv series" to "9",
            ),
        )

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {}
}
