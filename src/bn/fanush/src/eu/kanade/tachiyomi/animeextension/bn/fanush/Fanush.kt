package eu.kanade.tachiyomi.animeextension.bn.fanush

import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin

class Fanush : Jellyfin("Fanush", "bn") {
    override val defaultBaseUrl = "http://103.132.95.221:8096"
    override val defaultUsername = "pcl"
    override val defaultPassword = "pcl"
}
