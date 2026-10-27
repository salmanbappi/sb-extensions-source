package eu.kanade.tachiyomi.animeextension.all.animex.hosts

import okhttp3.Headers

object NeroResolver : BaseAnmxResolver() {
    override val id = "nero"
    override val displayName = "Nero"

    override fun modifyVideoHeaders(
        headersBuilder: Headers.Builder,
        providerId: String,
        sourcesHeaders: Map<String, String>?,
    ) {
        super.modifyVideoHeaders(headersBuilder, providerId, sourcesHeaders)
        if (headersBuilder.get("Referer") == null) {
            headersBuilder.set("Referer", "https://animex.one/")
        }
    }
}
