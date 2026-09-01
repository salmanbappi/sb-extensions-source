package eu.kanade.tachiyomi.animeextension.en.fboxtv

import kotlinx.serialization.Serializable

/**
 * `/ajax/episode/sources/<serverId>` — resolves a server row into the third-party
 * embed URL fboxtv.bz delegates playback to.
 */
@Serializable
data class SourceLinkDto(
    val link: String? = null,
)

// ============================ moviesapi.to (vidora) ============================

@Serializable
data class VidoraResponseDto(
    val result: Boolean? = null,
    val sources: List<VidoraSourceDto>? = null,
)

@Serializable
data class VidoraSourceDto(
    val url: String? = null,
    val tracks: List<VidoraTrackDto>? = null,
)

@Serializable
data class VidoraTrackDto(
    val file: String? = null,
    val label: String? = null,
)

// ========================= player.vidlove.cc (api.shows.st) =========================

@Serializable
data class ShowsStResponseDto(
    val source: ShowsStSourceDto? = null,
    val subtitles: List<ShowsStSubtitleDto>? = null,
)

@Serializable
data class ShowsStSourceDto(
    val url: String? = null,
    val label: String? = null,
    val source: String? = null,
)

@Serializable
data class ShowsStSubtitleDto(
    val file: String? = null,
    val label: String? = null,
)
