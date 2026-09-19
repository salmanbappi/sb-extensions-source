package eu.kanade.tachiyomi.animeextension.all.anikoto

import eu.kanade.tachiyomi.multisrc.anikototheme.AnikotoTheme

class Anikoto : AnikotoTheme() {

    override val name = "Anikoto"
    override val defaultBaseUrl = "https://anikototv.to"
    override val lang = "all"

    // Mirrors offered in Settings → Playback → Preferred domain, same list as the reference.
    override val domainMirrors = listOf(
        "anikototv.to (Primary)" to "https://anikototv.to",
        "anikoto.cz (Regional mirror)" to "https://anikoto.cz",
        "anikoto.me (Short TLD mirror)" to "https://anikoto.me",
        "anikoto.net (Network mirror)" to "https://anikoto.net",
        "anikototv.se (Nordic mirror)" to "https://anikototv.se",
        "anikototv.com (Legacy mirror)" to "https://anikototv.com",
    )

    override val useMapper = true
}
