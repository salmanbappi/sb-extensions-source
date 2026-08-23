package eu.kanade.tachiyomi.animeextension.en.aniwave

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme
import eu.kanade.tachiyomi.network.GET
import extensions.utils.asJsoup

class AniWave : AnikotoTheme() {

    override val name = "AniWave"
    override val baseUrl = "https://aniwaves.ru"
    override val lang = "en"

    override fun getSourcesUrl(token: String): String = "$baseUrl/ajax/sources?id=$token&asi=1&autoPlay=1"

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/most-popular" else "$baseUrl/most-popular/page/$page"
        val response = client.newCall(GET(url)).execute()
        return parseAnimeList(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/updated" else "$baseUrl/updated/page/$page"
        val response = client.newCall(GET(url)).execute()
        return parseAnimeList(response.asJsoup())
    }
}
