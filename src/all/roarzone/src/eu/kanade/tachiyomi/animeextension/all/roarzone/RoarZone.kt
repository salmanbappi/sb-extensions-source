package eu.kanade.tachiyomi.animeextension.all.roarzone

import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin

class RoarZone : Jellyfin("RoarZone") {
    override val defaultBaseUrl = "https://play.roarzone.net"
    override val defaultUsername = "Roarzone_guest"
    override val defaultPassword = ""
}
