package eu.kanade.tachiyomi.animeextension.all.animex.hosts

import okhttp3.Headers

object ZunaResolver : BaseAnmxResolver() {
    override val id = "zuna"
    override val displayName = "Zuna"

    override fun modifyVideoHeaders(
        headersBuilder: Headers.Builder,
        providerId: String,
        sourcesHeaders: Map<String, String>?,
    ) {
        super.modifyVideoHeaders(headersBuilder, providerId, sourcesHeaders)
        if (headersBuilder.get("Referer") == null) {
            headersBuilder.set("Referer", "https://zokoanime.video/")
        }
    }
}
