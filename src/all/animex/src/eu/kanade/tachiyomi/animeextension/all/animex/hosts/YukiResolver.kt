package eu.kanade.tachiyomi.animeextension.all.animex.hosts

import okhttp3.Headers

object YukiResolver : BaseAnmxResolver() {
    override val id = "yuki"
    override val displayName = "Yuki"

    override fun modifyVideoHeaders(
        headersBuilder: Headers.Builder,
        providerId: String,
        sourcesHeaders: Map<String, String>?,
    ) {
        super.modifyVideoHeaders(headersBuilder, providerId, sourcesHeaders)
        if (headersBuilder.get("Origin") == null) {
            headersBuilder.set("Origin", "https://megaplay.buzz")
        }
        if (headersBuilder.get("Referer") == null) {
            headersBuilder.set("Referer", "https://megaplay.buzz/")
        }
    }
}
