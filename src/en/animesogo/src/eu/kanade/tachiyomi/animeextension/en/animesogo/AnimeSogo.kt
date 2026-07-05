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
    override val synopsisSelector = "div.synopsis > div.content"
    override val detailPosterSelector = "section#w-info div.poster img"

    override fun getVrf(animeId: String): String = AnikotoRC4.encodeAnimeSogoVrf(animeId)
}
