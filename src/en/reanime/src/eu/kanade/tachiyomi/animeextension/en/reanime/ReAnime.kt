package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.addListPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.getValue

@Serializable
data class SearchResponseDto(
    val results: List<AnimeDto> = emptyList(),
)

@Serializable
data class AnimeDto(
    val anime_id: String,
    val title: TitleDto,
    val cover_image: CoverImageDto,
)

@Serializable
data class TitleDto(
    val user_preferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
data class CoverImageDto(
    val extra_large: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class FlixResponseDto(
    val success: Boolean,
    val servers: List<FlixServerDto> = emptyList(),
)

@Serializable
data class FlixServerDto(
    val serverName: String,
    val dataLink: String,
    val dataType: String,
)

@Serializable
data class LatestResponseDto(
    val data: List<AnimeDto> = emptyList(),
)

@Serializable
data class DetailAnimeDto(
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val cover_image: CoverImageDto? = null,
    val anilist_id: Int? = null,
    val subbed: Int? = null,
    val dubbed: Int? = null,
)

@Serializable
data class EpisodeDto(
    val episode_number: Float,
    val title: String? = null,
    val aired: String? = null,
)

class ReAnime : Source() {

    override val name = "ReAnime"

    override val baseUrl = "https://reanime.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(CloudflareInterceptor(network.client))
        .build()

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    private val m3u8Integration by lazy {
        M3u8Integration(client)
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/top/anime?period=week&limit=20", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<LatestResponseDto>()
        val animes = data.data.map {
            SAnime.create().apply {
                url = it.anime_id
                title = it.title.english ?: it.title.user_preferred ?: it.title.romaji ?: ""
                thumbnail_url = it.cover_image.large
            }
        }
        return AnimesPage(animes, hasNextPage = false)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/v1/home/latest-aired?limit=20", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<LatestResponseDto>()
        val animes = data.data.map {
            SAnime.create().apply {
                url = it.anime_id
                title = it.title.english ?: it.title.user_preferred ?: it.title.romaji ?: ""
                thumbnail_url = it.cover_image.large
            }
        }
        return AnimesPage(animes, hasNextPage = false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val offset = (page - 1) * 20
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/api/v1/search?q=$encodedQuery&limit=20&offset=$offset", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<SearchResponseDto>()
        val animes = data.results.map {
            SAnime.create().apply {
                url = it.anime_id
                title = it.title.english ?: it.title.user_preferred ?: it.title.romaji ?: ""
                thumbnail_url = it.cover_image.large
            }
        }
        return AnimesPage(animes, hasNextPage = animes.size == 20)
    }

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/api/v1/anime/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.parseAs<DetailAnimeDto>()
        return SAnime.create().apply {
            description = anime.description?.replace("<br>", "\n")?.replace("<BR>", "\n")?.replace(Regex("<[^>]*>"), "")
            status = when (anime.status?.lowercase()) {
                "finished" -> SAnime.COMPLETED
                "releasing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            genre = anime.genres.joinToString(", ")
            thumbnail_url = anime.cover_image?.large
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val episodesRequest = GET("$baseUrl/api/v1/anime/${anime.url}/episodes?limit=2000", headers)
        val episodesResponse = client.newCall(episodesRequest).execute()

        val jsonElement = episodesResponse.parseAs<JsonElement>()
        val epList = if (jsonElement is JsonArray) {
            json.decodeFromJsonElement<List<EpisodeDto>>(jsonElement)
        } else {
            jsonElement.jsonObject["data"]?.let {
                json.decodeFromJsonElement<List<EpisodeDto>>(it)
            } ?: emptyList()
        }

        epList.map {
            SEpisode.create().apply {
                url = "${anime.url}?ep=${it.episode_number}"
                val numStr = if (it.episode_number % 1 == 0f) it.episode_number.toInt().toString() else it.episode_number.toString()
                name = it.title?.takeIf { it.isNotBlank() } ?: "Episode $numStr"
                episode_number = it.episode_number
                date_upload = parseDate(it.aired)
            }
        }.reversed() // Ascending order
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            if (dateStr.contains(".")) {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(dateStr)?.time ?: 0L
            } else {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(dateStr)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val requestUrl = episode.url
        val slug = requestUrl.substringBefore("?ep=")
        val epNumStr = requestUrl.substringAfter("?ep=")

        // Fetch watch page to get the details & servers
        val watchRequest = GET("$baseUrl/api/v1/anime/$slug", headers)
        val watchResponse = client.newCall(watchRequest).execute()
        val anime = watchResponse.parseAs<DetailAnimeDto>()

        // Find anilist ID
        var anilistId = anime.anilist_id ?: 0
        if (anilistId == 0) {
            for (url in listOf(anime.cover_image?.extra_large, anime.cover_image?.large, anime.cover_image?.medium)) {
                if (url != null) {
                    val m = Regex("""/bx(\d+)-""").find(url)
                    if (m != null) {
                        anilistId = m.groupValues[1].toInt()
                        break
                    }
                }
            }
        }

        if (anilistId == 0) {
            throw Exception("Could not find anilist_id")
        }

        // Fetch Flix API
        val flixRequest = GET("$baseUrl/api/flix/$anilistId/$epNumStr", headersBuilder().set("Referer", "$baseUrl/watch/$slug?ep=$epNumStr").build())
        val flixResponse = client.newCall(flixRequest).execute()
        val flixData = flixResponse.parseAs<FlixResponseDto>()

        val servers = mutableListOf<FlixServerDto>()
        flixData.servers.forEach { server ->
            if (servers.none { it.dataLink == server.dataLink }) {
                servers.add(server)
            }
        }
        servers.sortByDescending { it.dataLink.contains("v=2") }

        val exceptions = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val videos = withContext(Dispatchers.IO) {
            servers.map { server ->
                async {
                    try {
                        val embedReferer = "$baseUrl/watch/$slug?ep=$epNumStr"
                        val embedRequest = GET(server.dataLink, headersBuilder().set("Referer", embedReferer).build())
                        val embedResponse = client.newCall(embedRequest).execute()
                        val embedHtml = embedResponse.body.string()

                        val seed = Regex("""obfuscation_seed\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("obfuscation_seed not found in embed (${server.serverName}) — possible CF block or changed format")
                        val wPayload = Regex("""w_payload\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("w_payload not found in embed (${server.serverName})")

                        val mappings = resolveMappings(seed)

                        val w = Regex(""""?${mappings.tokenField}"?\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("tokenField (${mappings.tokenField}) not found — seed=$seed")

                        val frag2B64 = Regex(""""?${mappings.keyFrag2Field}"?\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("keyFrag2Field (${mappings.keyFrag2Field}) not found")

                        // Fetch session token
                        val m3u8ApiUrl = "https://flixcloud.cc/api/m3u8/$w"
                        val tokenHeaders = headersBuilder()
                            .set("Referer", server.dataLink)
                            .set("Origin", "https://flixcloud.cc")
                            .build()
                        val tokenResponse = client.newCall(GET(m3u8ApiUrl, tokenHeaders)).execute()
                        val tokenBody = tokenResponse.body.string()

                        val kField = getSha256(w + "vid").substring(0, 10)
                        val pField = getSha256(w + "key").substring(0, 10)

                        val jsonObject = json.parseToJsonElement(tokenBody).jsonObject
                        val v = jsonObject[kField]?.jsonPrimitive?.content
                            ?: error("kField ($kField) not in m3u8 response: ${tokenBody.take(200)}")
                        val t = jsonObject[pField]?.jsonPrimitive?.content
                            ?: error("pField ($pField) not in m3u8 response")

                        // Dynamic keys resolution via container parsing
                        val frag1B64 = Regex(""""?${mappings.keyField}"?\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("keyField (${mappings.keyField}) not found")
                        val ivB64 = Regex(""""?${mappings.ivField}"?\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                            ?: error("ivField (${mappings.ivField}) not found")

                        // Decryption
                        val frag1 = safeDecodeB64(frag1B64)
                        val frag2 = safeDecodeB64(frag2B64)
                        val keyPart = safeDecodeB64(t)
                        val seedInt = seed.substring(0, 8).toLong(16).toInt()

                        val wasmBytes = safeDecodeB64(wPayload)
                        val interpreter = MiniWasmInterpreter(wasmBytes)
                        val funcs = interpreter.parseWasm()
                        val derivedBaseKey = interpreter.executeWasm(funcs, frag1, frag2, keyPart, seedInt)

                        val salt = seed.toByteArray(Charsets.UTF_8)
                        val pbkdf2Key = pbkdf2(derivedBaseKey, salt, 1000)

                        val finalKey = ByteArray(32)
                        for (idx in 0 until 32) {
                            finalKey[idx] = (pbkdf2Key[idx].toInt() xor seed[idx % seed.length].code).toByte()
                        }

                        val aesKey = getSha256Bytes(finalKey)
                        val iv = safeDecodeB64(ivB64)
                        val ciphertext = safeDecodeB64(v)

                        val decryptedUrl = decryptAes(ciphertext, aesKey, iv)
                        val playHeaders = buildPlaybackHeaders(decryptedUrl, server.dataLink)

                        // PK key from the WASM interpreter — the local proxy uses it to
                        // decrypt manifests (base64 + XOR) and unwrap image-wrapped segments.
                        val pk = interpreter.getPkBytes(funcs)

                        // HD-1 segments are served from slopnet.site which has Cloudflare
                        // protection. Warm up CF clearance on the main client (which has a
                        // WebView-backed solver) before the proxy starts fetching segments.
                        // We use a subtitle URL as the warmup target — it never carries a
                        // video token, so it cannot invalidate the session.
                        // HD-2 (dataLink contains "v=2") uses a different CDN — skip.
                        if (!server.dataLink.contains("v=2")) {
                            val slopnetOrigin = Regex("""(https://[^/]*slopnet\.site)""").find(embedHtml)?.groupValues?.get(1)
                            if (slopnetOrigin != null) {
                                try {
                                    client.newCall(
                                        GET("$slopnetOrigin/", headersBuilder().set("Referer", server.dataLink).build()),
                                    ).execute().close()
                                } catch (_: Throwable) {}
                            }
                        }

                        ReAnimeProxyServer.ensureStarted(client)

                        val subtitleTracks = Regex("""\burl:"([^"]+)",\s*language:"([^"]+)",\s*format:"([^"]+)"""")
                            .findAll(embedHtml)
                            .map {
                                Track(
                                    url = ReAnimeProxyServer.subtitleProxyUrl(it.groupValues[1], server.dataLink),
                                    lang = it.groupValues[2],
                                )
                            }
                            .toList()

                        val masterProxyUrl = ReAnimeProxyServer.proxyUrl(decryptedUrl, pk, server.dataLink)

                        playlistUtils.extractFromHls(
                            playlistUrl = masterProxyUrl,
                            referer = server.dataLink,
                            masterHeaders = playHeaders,
                            videoHeaders = playHeaders,
                            subtitleList = subtitleTracks,
                            videoNameGen = { quality -> "${server.serverName} (${server.dataType}) - $quality" },
                        ).map { video ->
                            Video(
                                videoUrl = masterProxyUrl,
                                videoTitle = video.videoTitle,
                                subtitleTracks = video.subtitleTracks,
                                audioTracks = emptyList(),
                                headers = video.headers,
                            )
                        }
                    } catch (e: Throwable) {
                        exceptions.add(e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        if (videos.isEmpty() && exceptions.isNotEmpty()) {
            throw exceptions.first()
        }
        return videos
    }

    // ============================ Utilities ===============================

    private fun getSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        val hexChars = CharArray(hash.size * 2)
        for (i in hash.indices) {
            val v = hash[i].toInt() and 0xFF
            hexChars[i * 2] = "0123456789abcdef"[v ushr 4]
            hexChars[i * 2 + 1] = "0123456789abcdef"[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun getSha256Bytes(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    private data class Mappings(
        val videoField: String,
        val keyField: String,
        val ivField: String,
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val tokenField: String,
        val keyFrag2Field: String,
    )

    private fun resolveMappings(seed: String): Mappings {
        var e = seed
        for (o in 0 until 3) e = getSha256(e + o.toString())
        var s = e
        for (o in 0 until 3) s = getSha256(s + o.toString())

        return Mappings(
            videoField = "vf_${e.substring(0, 8)}",
            keyField = "kf_${e.substring(8, 16)}",
            ivField = "ivf_${e.substring(16, 24)}",
            containerName = "cd_${e.substring(24, 32)}",
            arrayName = "ad_${e.substring(32, 40)}",
            objectName = "od_${e.substring(40, 48)}",
            tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}",
            keyFrag2Field = "${s.substring(0, 16)}_${s.substring(16, 24)}",
        )
    }

    private fun pbkdf2(key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKey)

        val input = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, input, 0, salt.size)
        input[input.size - 1] = 1

        var u = mac.doFinal(input)
        val result = u.clone()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return result
    }

    private fun decryptAes(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun safeDecodeB64(str: String): ByteArray {
        val clean = str.trim()
        return try {
            Base64.decode(clean, Base64.DEFAULT)
        } catch (_: Exception) {
            try {
                Base64.decode(clean, Base64.URL_SAFE)
            } catch (_: Exception) {
                val normalized = clean.replace('-', '+').replace('_', '/')
                Base64.decode(normalized, Base64.DEFAULT)
            }
        }
    }

    private fun buildPlaybackHeaders(videoUrl: String, embedUrl: String): Headers {
        val videoHttpUrl = runCatching { videoUrl.toHttpUrl() }.getOrNull()
        val origin = videoHttpUrl?.let { "${it.scheme}://${it.host}" } ?: "https://fetch.flixcloud.cc"

        return headersBuilder()
            .set("Accept", "*/*")
            .set("Origin", origin)
            .set("Referer", embedUrl)
            .build()
    }

    private fun Headers.Builder.applyPlaybackHeaders(playHeaders: Headers): Headers.Builder {
        for (index in 0 until playHeaders.size) {
            val name = playHeaders.name(index)
            if (name.lowercase(Locale.US) != "referer") {
                set(name, playHeaders.value(index))
            }
        }
        set("Referer", playHeaders["Referer"]!!)
        return this
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val order = listOf("1080p", "720p", "480p", "360p")
        return this.sortedWith(
            compareBy(
                { !it.videoTitle.contains(quality) },
                { video ->
                    val index = order.indexOfFirst { video.videoTitle.contains(it) }
                    if (index == -1) order.size else index
                },
            ),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Calidad preferida"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
    }
}
