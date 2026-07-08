package eu.kanade.tachiyomi.animeextension.all.agnisys

import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin

class AgniSYS : Jellyfin("AgniSYS") {
    override val defaultBaseUrl = "http://182.252.81.180:8096"
    override val defaultUsername = "vibe"
    override val defaultPassword = "121121"
}
