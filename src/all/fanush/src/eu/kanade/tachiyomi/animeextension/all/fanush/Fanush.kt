package eu.kanade.tachiyomi.animeextension.all.fanush

import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin

class Fanush : Jellyfin("Fanush") {
    override val defaultBaseUrl = "http://103.132.95.221:8096"
    override val defaultUsername = "pcl"
    override val defaultPassword = "pcl"
}
