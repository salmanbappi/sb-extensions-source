package eu.kanade.tachiyomi.animeextension.all.jellyfinbijoy

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin

class JellyfinBijoy : Jellyfin("Bijoy") {
    override val defaultBaseUrl = "http://10.20.30.50"
    override val defaultUsername = "bijoy"
    override val defaultPassword = ""

    private val staticCategories = listOf(
        Pair("All", ""),
        Pair("Movies (Anime)", "9403711afa65061e9967086eac702a66"),
        Pair("Movies (Asian)", "27dcfec804e37e6fb3104d8f631ea57f"),
        Pair("Movies (English)", "6bb2c3ec9d67c18652f0dab47bd9ee2e"),
        Pair("Movies (Foreign)", "ac15933b5a8f0721ce5f929f1cc3668e"),
        Pair("Movies (Indian)", "76c327c29a53c7380a05858d7c871402"),
        Pair("TV Shows (Anime)", "2b150b1b7dc3b96f450a420ce95c2cd2"),
        Pair("TV Shows (Asian)", "2dfef46d25ad65cf8fc4b0d882567a25"),
        Pair("TV Shows (English)", "58b107e1ff3124b9a07ffb3501cc89f2"),
        Pair("TV Shows (Foreign)", "93f4cc54b483a044f40a25eae8b005aa"),
        Pair("TV Shows (Indian)", "83f92a8e94d2e1a200fa9d5399a801f6"),
    )

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            CategoryFilter(staticCategories),
            SortFilter(),
        )
    }
}
