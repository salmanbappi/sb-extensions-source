package eu.kanade.tachiyomi.animeextension.all.animex.hosts

import okhttp3.Headers

object LoliResolver : BaseAnmxResolver() {
    override val id = "loli"
    override val displayName = "Loli"

    override fun modifyVideoHeaders(
        headersBuilder: Headers.Builder,
        providerId: String,
        sourcesHeaders: Map<String, String>?,
    ) {
        super.modifyVideoHeaders(headersBuilder, providerId, sourcesHeaders)
        if (headersBuilder.get("Referer") == null) {
            headersBuilder.set("Referer", "https://play.echovideo.ru/")
        }
    }
}
