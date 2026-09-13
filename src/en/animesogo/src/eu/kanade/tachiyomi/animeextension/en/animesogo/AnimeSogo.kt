package eu.kanade.tachiyomi.animeextension.en.animesogo

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoRC4
import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class AnimeSogo : AnikotoTheme() {

    override val name = "AnimeSogo"
    override val baseUrl = "https://animesogo.to"
    override val lang = "en"

    override val bmetaSelector = "div.bl-meta"
    override val scoreLabel = "Scores"
    override val scorePrefix = "Scores"
    override val aliasSelector = "div.alias"
    override val synopsisSelector = "div.synopsis > div.content, div.synopsis div.content"
    override val detailPosterSelector = "section#w-info div.poster img, #w-info div.poster img, div.poster img"

    // Sogo's search/filter/browse cards: div.item > .inner > a.name.d-title (+ a.ani.poster img).
    override val popularAnimeSelector = "div.ani.items > div.item, div.items > div.item, div.item"

    override fun getVrf(animeId: String): String = AnikotoRC4.encodeAnimeSogoVrf(animeId)
}
