package eu.kanade.tachiyomi.multisrc.dooplay

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Request
import okhttp3.Response

abstract class Dooplay(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : AnimeHttpSource() {

    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/page/$page/", headers)
    override fun popularAnimeParse(response: Response): AnimesPage = TODO("Not yet implemented")
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/movies/page/$page/", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = TODO("Not yet implemented")
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = TODO("Not yet implemented")
    override fun searchAnimeParse(response: Response): AnimesPage = TODO("Not yet implemented")
    override fun animeDetailsParse(response: Response): SAnime = TODO("Not yet implemented")
    override fun episodeListParse(response: Response): List<SEpisode> = TODO("Not yet implemented")
    override fun videoListParse(response: Response): List<Video> = TODO("Not yet implemented")
}
