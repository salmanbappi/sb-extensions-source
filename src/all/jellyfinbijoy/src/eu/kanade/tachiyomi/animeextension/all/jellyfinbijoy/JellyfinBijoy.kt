package eu.kanade.tachiyomi.animeextension.all.jellyfinbijoy

import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin

class JellyfinBijoy : Jellyfin("Bijoy") {
    override val defaultBaseUrl = "http://10.20.30.50"
    override val defaultUsername = "bijoy"
    override val defaultPassword = ""
}
