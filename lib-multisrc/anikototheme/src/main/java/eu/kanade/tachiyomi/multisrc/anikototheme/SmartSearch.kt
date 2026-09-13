package eu.kanade.tachiyomi.multisrc.anikototheme

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Smart Search module — AI-powered anime search.
 *
 * Two engines are supported (Settings → Smart Search → AI engine):
 * - [Engine.GOOGLE]: scrapes Google AI Mode through the app's WebView (no key needed, but Google
 *   may answer with a CAPTCHA/consent page depending on the network).
 * - [Engine.GEMINI]: calls the Gemini REST API with a user-supplied key (stable, but needs a key).
 *
 * [Engine.AUTO] prefers Gemini when a key is configured and falls back to Google on failure.
 */
class SmartSearch(
    private val webViewFetcher: WebViewFetcher,
) {
    private val tag = "SmartSearch"

    object Engine {
        const val AUTO = "auto"
        const val GEMINI = "gemini"
        const val GOOGLE = "google"
    }

    sealed class ResolveResult {
        data class Success(val title: String) : ResolveResult()
        data class Failure(val userMessage: String, val detail: String? = null) : ResolveResult()
    }

    /** Cache for pagination: last query (phrase stripped) → resolved title. */
    private var cachedQuery: String = ""
    private var cachedTitle: String = ""

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val geminiClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Check if smart search should trigger for this query.
     */
    fun shouldTrigger(query: String, enabled: Boolean, phrase: String): Boolean {
        if (!enabled) return false
        val queryTrimmed = query.trim()
        if (queryTrimmed.isEmpty()) return false

        val phraseTrimmed = phrase.trim()
        if (phraseTrimmed.isEmpty()) return true // empty phrase = all searches use AI

        if (!queryTrimmed.startsWith(phraseTrimmed, ignoreCase = true)) return false

        val afterPhrase = queryTrimmed.substring(phraseTrimmed.length)
        return afterPhrase.isEmpty() || afterPhrase.startsWith(" ")
    }

    /**
     * Strip the activation phrase from the start of the query.
     */
    fun stripPhrase(query: String, phrase: String): String {
        val phraseTrimmed = phrase.trim()
        if (phraseTrimmed.isEmpty()) return query.trim()

        val queryTrimmed = query.trim()
        if (queryTrimmed.startsWith(phraseTrimmed, ignoreCase = true)) {
            val afterPhrase = queryTrimmed.substring(phraseTrimmed.length)
            return afterPhrase.trim()
        }
        return queryTrimmed
    }

    /**
     * Resolve a query to an anime title using the selected engine.
     */
    fun resolve(query: String, engine: String, geminiApiKey: String, geminiModel: String): ResolveResult {
        if (query.isBlank()) {
            Log.w(tag, "SmartSearch: empty query")
            return ResolveResult.Failure("Smart search: empty query")
        }

        Log.i(tag, "SmartSearch: resolving (engine=$engine) query: \"$query\"")

        return when (engine) {
            Engine.GEMINI -> resolveWithGemini(query, geminiApiKey, geminiModel)
            Engine.GOOGLE -> resolveWithGoogle(query)
            else -> {
                if (geminiApiKey.isBlank()) {
                    resolveWithGoogle(query)
                } else {
                    val gemini = resolveWithGemini(query, geminiApiKey, geminiModel)
                    if (gemini is ResolveResult.Success) {
                        gemini
                    } else {
                        val google = resolveWithGoogle(query)
                        if (google is ResolveResult.Success) {
                            Log.i(tag, "SmartSearch: auto fallback Gemini→Google succeeded")
                            google
                        } else {
                            ResolveResult.Failure(
                                "Gemini failed (${(gemini as ResolveResult.Failure).userMessage}); " +
                                    "Google fallback failed (${(google as ResolveResult.Failure).userMessage})",
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Gemini ----

    private fun resolveWithGemini(query: String, apiKey: String, model: String): ResolveResult {
        if (apiKey.isBlank()) {
            return ResolveResult.Failure("Gemini API key is not set — add one in Settings → Smart Search", "blank key")
        }

        val url = "${Companion.GEMINI_ENDPOINT}${model.trim()}:generateContent"
        Log.i(tag, "SmartSearch: Gemini resolve via $model")

        return try {
            val prompt = buildGeminiPrompt(query)
            val withThinkingConfig = model.trim().startsWith("gemini-2.5")
            var (code, body) = postGemini(geminiClient, url, apiKey, buildGeminiRequestBody(prompt, withThinkingConfig))

            // Older/flash-lite models reject thinkingConfig with HTTP 400 — retry without it.
            if (code == 400 && withThinkingConfig) {
                Log.i(tag, "SmartSearch: model rejected thinkingConfig — retrying without it")
                val retry = postGemini(geminiClient, url, apiKey, buildGeminiRequestBody(prompt, false))
                code = retry.first
                body = retry.second
            }

            if (code !in 200..299) {
                return ResolveResult.Failure(describeGeminiHttpError(code, body, model), body.take(600))
            }

            val title = parseGeminiSuccess(body)
            if (title != null) {
                Log.i(tag, "SmartSearch: Gemini extracted title: \"$title\"")
                ResolveResult.Success(title)
            } else {
                ResolveResult.Failure(
                    "Gemini replied but no anime title could be read from its answer — try another model in Settings",
                    body.take(600),
                )
            }
        } catch (e: java.io.IOException) {
            Log.e(tag, "SmartSearch: Gemini network error", e)
            ResolveResult.Failure(
                "Could not reach the Gemini API (network error: ${e.javaClass.simpleName})",
                e.message,
            )
        } catch (e: Exception) {
            Log.e(tag, "SmartSearch: Gemini unexpected error", e)
            ResolveResult.Failure("Gemini request failed unexpectedly: ${e.message?.take(80)}")
        }
    }

    private fun parseGeminiSuccess(body: String): String? = try {
        val candidates = (json.parseToJsonElement(body).jsonObject["candidates"] as? JsonArray)
        val first = candidates?.firstOrNull()?.jsonObject
        val parts = first?.get("content")?.jsonObject?.get("parts") as? JsonArray

        val answer = parts?.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            // Thinking models emit their reasoning as parts flagged `thought: true`.
            if (obj["thought"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
            obj["text"]?.jsonPrimitive?.contentOrNull
        }?.joinToString(" ")?.trim()

        if (answer.isNullOrBlank()) {
            val finishReason = first?.get("finishReason")?.jsonPrimitive?.contentOrNull
            Log.d(tag, "SmartSearch: Gemini returned empty text (finishReason=$finishReason)")
            null
        } else {
            Log.d(tag, "SmartSearch: Gemini raw answer: ${answer.take(200)}")
            extractBracketTitle(answer) ?: cleanTitle(answer)
        }
    } catch (e: Exception) {
        Log.e(tag, "SmartSearch: Gemini response parse failed", e)
        null
    }

    // ---- Google AI Search ----

    private fun resolveWithGoogle(query: String): ResolveResult {
        val searchQuery = buildGooglePrompt(query)
        val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
        val googleUrl = "https://www.google.com/search?q=$encodedQuery&udm=50&hl=en"
        Log.d(tag, "SmartSearch: Google URL: ${googleUrl.take(120)}")

        val renderedText = try {
            webViewFetcher.fetchRenderedText(googleUrl, timeoutMs = 25_000)
        } catch (e: Exception) {
            Log.e(tag, "SmartSearch: scrape crashed", e)
            return ResolveResult.Failure("Google search could not be opened (${e.javaClass.simpleName})", e.message)
        }

        if (renderedText.isBlank()) {
            return ResolveResult.Failure("Google search returned nothing in time — likely a timeout or Google blocking the app's browser")
        }

        Log.d(tag, "SmartSearch: Google rendered text (${renderedText.length} chars)")

        classifyGoogleBlock(renderedText)?.let { return it }

        val title = extractBracketTitle(renderedText) ?: extractAnimeTitle(renderedText)
        if (title == null) {
            val head = renderedText.replace(Regex("\\s+"), " ").trim().take(110)
            return ResolveResult.Failure(
                "Google answered but no anime title could be read from its response (page head: \"$head\") — " +
                    "try rephrasing or use the Gemini engine",
                renderedText.take(20000),
            )
        }

        Log.i(tag, "SmartSearch: extracted title: \"$title\"")
        return ResolveResult.Success(title)
    }

    /** Google's AI Mode is asked to bracket the title so the page scrape is unambiguous. */
    private fun buildGooglePrompt(query: String): String {
        val base = if (query.contains("anime", ignoreCase = true)) query.trim() else "${query.trim()} anime"
        return "$base (in your answer, wrap the anime title in [{[ ]}] brackets)"
    }

    private fun classifyGoogleBlock(text: String): ResolveResult.Failure? {
        val lower = text.lowercase()
        if (listOf("unusual traffic", "not a robot", "captcha").any { it in lower }) {
            return ResolveResult.Failure(
                "Google triggered its bot-check (CAPTCHA) — automated search is blocked right now; the Gemini engine avoids this entirely",
            )
        }
        if (listOf("before you continue", "consent.google").any { it in lower }) {
            return ResolveResult.Failure("Google showed its cookie-consent page instead of results — the Gemini engine avoids this")
        }
        if (text.length < 400 && listOf("enable javascript", "enablejs").any { it in lower }) {
            return ResolveResult.Failure("Google demanded JavaScript in a way the app's browser could not satisfy")
        }
        if (listOf("sign in to confirm", "confirm you're not a bot").any { it in lower }) {
            return ResolveResult.Failure("Google is asking for sign-in verification — automated search is blocked; the Gemini engine avoids this")
        }
        return null
    }

    // ---- Title extraction ----

    /** Reads the `[{[ Title ]}]` marker the prompts ask the AI for. */
    private fun extractBracketTitle(text: String): String? {
        for (match in BRACKET_TITLE_REGEX.findAll(text)) {
            val candidate = match.groupValues[1]
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim(' ', ',', '.', ':', ';', '!', '?', '"', '\'', '“', '”')
            if (candidate.length !in 2..80) continue
            val words = candidate.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty() || words.size > 12) continue
            if (words.size == 1 && words[0].firstOrNull()?.isUpperCase() != true) continue
            return candidate
        }
        return null
    }

    /** Falls back to the first usable line of an AI answer that ignored the bracket instruction. */
    private fun cleanTitle(raw: String): String? {
        val firstLine = raw.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val cleaned = firstLine
            .replace(Regex("^\\[[^\\]]*]\\s*"), "")
            .trim()
            .replace(Regex("^(title|anime)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’', ' ')
            .trimEnd('.', ' ')
        if (cleaned.isBlank()) return null
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (words.size in 1..12) cleaned else null
    }

    private fun buildGeminiPrompt(query: String): String = "$query anime. [Identify the ONE anime this refers to and reply with ONLY its " +
        "English title wrapped inside these exact brackets: [{[Title]}] — nothing else inside the brackets. " +
        "If the query is already an anime title or close to one, return that title with spelling corrected. " +
        "If the query describes an anime, give the title of the anime being described. " +
        "If the query has spelling mistakes, correct them and give the proper title. " +
        "If the query mentions a genre or theme, give one popular anime from that genre. " +
        "If the query is vague, give the most likely anime match.]"

    private fun cleanGoogleResultTitle(raw: String): String {
        var t = raw.trim()
        t = t.removeSurrounding("\"").removeSurrounding("'").removeSurrounding("“").removeSurrounding("”")

        val suffixes = listOf(
            " - Wikipedia", " | Wikipedia",
            " - IMDb", " | IMDb",
            " | Netflix", " - Netflix",
            " | Crunchyroll", " - Crunchyroll",
            " - MyAnimeList.net", " | MyAnimeList.net",
            " (Anime) - MyAnimeList.net",
            " (Manga) - MyAnimeList.net",
            " | Anime-Planet", " - Anime-Planet",
            " - Anime News Network", " | Anime News Network",
            " - Fandom", " | Fandom",
            " - AniList", " | AniList",
        )

        for (suffix in suffixes) {
            if (t.endsWith(suffix, ignoreCase = true)) {
                t = t.substring(0, t.length - suffix.length).trim()
            }
        }

        t = t.replace(Regex("""\s*\(\s*(?:TV\s+)?(?:Series|Mini\s+Series|Anime|Manga|Movie|TV|OVA|ONA).*\)$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s*\(\s*\d{4}\s*\)$"""), "")
        t = t.replace(Regex("""\s*\([^)]+\)$"""), "")
        return t.trim()
    }

    private fun extractAnimeTitle(text: String): String? {
        val lines = text.lines()

        // Strategy 1: Search Result Suffix Matching (Highly Reliable)
        val siteSuffixes = listOf(
            " - Wikipedia",
            " - IMDb",
            " - MyAnimeList.net",
            " | Crunchyroll",
            " | Netflix",
            " | Anime-Planet",
        )
        for (line in lines) {
            val trimmed = line.trim()
            for (suffix in siteSuffixes) {
                if (trimmed.endsWith(suffix, ignoreCase = true)) {
                    val rawTitle = trimmed.substring(0, trimmed.length - suffix.length).trim()
                    val cleanedTitle = cleanGoogleResultTitle(rawTitle)
                    if (cleanedTitle.isNotEmpty()) {
                        val wordCount = cleanedTitle.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        Log.d(tag, "SmartSearch: Strategy 1 (Result Suffix) match: \"$trimmed\" -> \"$cleanedTitle\" ($wordCount words)")
                        if (wordCount in 1..12) return cleanedTitle
                    }
                }
            }
        }

        // Strategy 2: Breadcrumb/URL follower title extraction
        for (i in 0 until lines.size - 1) {
            val line = lines[i].trim()
            if (line.contains("›") || line.contains("http://") || line.contains("https://") || line.contains("www.")) {
                for (j in 1..2) {
                    if (i + j < lines.size) {
                        val nextLine = lines[i + j].trim()
                        if (nextLine.isNotEmpty() &&
                            nextLine.length in 5..80 &&
                            !nextLine.contains("›") &&
                            !nextLine.contains("http") &&
                            !nextLine.contains("Translate this page") &&
                            !nextLine.contains("Similar") &&
                            nextLine.firstOrNull()?.isUpperCase() == true
                        ) {
                            val cleaned = cleanGoogleResultTitle(nextLine)
                            val wordCount = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                            Log.d(tag, "SmartSearch: Strategy 2 (Breadcrumb follower) match: \"$nextLine\" -> \"$cleaned\" ($wordCount words)")
                            if (wordCount in 1..12) return cleaned
                        }
                    }
                }
            }
        }

        // Strategy 3: "is titled [X]" (From AI Overview or snippets)
        val titledPattern = Regex(
            """(?:is\s+titled|is\s+called|is\s+named|is\s+known\s+as)\s+([A-Z][^\n.!?]{2,80}?)(?:\s*[.\n!?]|$)""",
        )
        for (match in titledPattern.findAll(text)) {
            val raw = match.groupValues[1].trim()
            val title = stripParenthetical(raw)
            val wordCount = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            Log.d(tag, "SmartSearch: Strategy 3 (titled) match: \"$raw\" → \"$title\" ($wordCount words)")
            if (wordCount in 2..12) return title
        }

        // Strategy 4: Quoted text (From AI Overview or snippets)
        val quotedPattern = Regex("""["“'‘]([^"”'’]{2,80})["”'’]""")
        for (match in quotedPattern.findAll(text)) {
            val raw = match.groupValues[1].trim()
            if (raw.contains("(") && raw.endsWith(")")) continue
            val title = stripParenthetical(raw)
            val wordCount = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            Log.d(tag, "SmartSearch: Strategy 4 (quoted) match: \"$raw\" → \"$title\" ($wordCount words)")
            if (wordCount in 2..12) return title
        }

        // Strategy 5: First capitalized multi-word phrase after "Search Results" or "AI Overview"
        var inResults = false
        val uiWords = setOf(
            "Sign", "AI", "All", "Images", "Videos", "News", "Books", "Finance",
            "Search", "Results", "Mode", "sites", "Learn",
        )
        for (line in lines) {
            if ("Search Results" in line || "AI Overview" in line) {
                inResults = true
                continue
            }
            if (!inResults) continue
            val trimmed = line.trim()
            if (trimmed.length < 5) continue
            if ("Respond with only" in trimmed || "anime." in trimmed.lowercase()) continue

            val words = trimmed.split(Regex("\\s+"))
            if (words.size < 2) continue
            if (words[0] in uiWords) continue
            if (words[0].firstOrNull()?.isUpperCase() != true) continue

            val phrase = mutableListOf<String>()
            for (w in words) {
                val clean = w.trim()
                if (clean.isEmpty()) continue
                if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) {
                    val stripped = clean.trimEnd('.', '!', '?')
                    if (stripped.isNotEmpty()) phrase.add(stripped)
                    break
                }
                if (clean == "—" || clean == "is" || clean == "was") break
                phrase.add(clean)
                if (phrase.size >= 12) break
            }

            if (phrase.size in 2..12) {
                val title = stripParenthetical(phrase.joinToString(" "))
                Log.d(tag, "SmartSearch: Strategy 5 (first phrase) match: \"${phrase.joinToString(" ")}\" → \"$title\"")
                return title
            }
        }

        Log.d(tag, "SmartSearch: all 5 strategies failed")
        return null
    }

    private fun stripParenthetical(s: String): String {
        var result = s.trim()
        result = result.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        result = result.replace(Regex("^\\s*\\([^)]*\\)\\s*"), "").trim()
        return result
    }

    fun getCachedTitle(query: String, page: Int): String? {
        if (page > 1 && query == cachedQuery && cachedTitle.isNotEmpty()) {
            Log.i(tag, "SmartSearch: using cached title \"$cachedTitle\" for page $page")
            return cachedTitle
        }
        return null
    }

    fun cacheTitle(query: String, title: String) {
        cachedQuery = query
        cachedTitle = title
    }

    fun warmUp() {
        webViewFetcher.warmUpGoogleWebView()
    }

    companion object {
        const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"

        private val BRACKET_TITLE_REGEX = Regex("""\[\{\[([^\]}]{2,120}?)[\]}]""")

        /** Gemini request body, mirroring the app's own player calls. */
        fun buildGeminiRequestBody(prompt: String, withThinkingConfig: Boolean): String = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", JsonPrimitive(prompt)) })
                                },
                            )
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", JsonPrimitive(0.1))
                    put("maxOutputTokens", JsonPrimitive(2048))
                    if (withThinkingConfig) {
                        put("thinkingConfig", buildJsonObject { put("thinkingBudget", JsonPrimitive(0)) })
                    }
                },
            )
        }.toString()

        /** Maps a Gemini HTTP error to a message a user can act on. */
        fun describeGeminiHttpError(code: Int, body: String, model: String): String {
            val apiMessage = geminiApiErrorMessage(body)
            val trimmedModel = model.trim()
            val base = when {
                code == 400 && apiMessage?.contains("API key not valid", ignoreCase = true) == true ->
                    "Gemini API key is invalid (check Settings → Smart Search)"

                code == 400 && apiMessage?.contains("location is not supported", ignoreCase = true) == true ->
                    "Google blocks the Gemini API for this network/region (country or VPN restriction) — try another network"

                code == 400 ->
                    "Gemini rejected the request for model \"$trimmedModel\" (HTTP 400) — if \"$trimmedModel\" is a custom model ID it must exactly match Google's API model name"

                code == 401 || code == 403 ->
                    "Gemini API key was rejected (HTTP $code) — invalid, restricted, or Gemini API disabled for this key"

                code == 404 ->
                    "Gemini model \"$trimmedModel\" not found (HTTP 404) — pick one of the listed models, or enter the exact API model ID for custom models"

                code == 429 ->
                    "Gemini quota exceeded (HTTP 429) — free-tier limit hit, try again later or switch model"

                code in 500..599 -> "Gemini server error (HTTP $code) — Google-side problem, try again"

                else -> "Gemini API error (HTTP $code)"
            }
            return if (apiMessage == null) base else "$base — $apiMessage"
        }

        /** Pulls `error.message` out of a Gemini error body, if present. */
        fun geminiApiErrorMessage(body: String): String? = try {
            val message = Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(body)
                .jsonObject["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull
            message?.take(160)
        } catch (e: Exception) {
            null
        }

        /** POSTs a generateContent request; returns (statusCode, body). */
        fun postGemini(client: OkHttpClient, url: String, apiKey: String, body: String): Pair<Int, String> =
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", apiKey.trim())
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build(),
            ).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }

        /** Test request used by Settings → Smart Search → Test connection. Null means success. */
        fun testGemini(apiKey: String, model: String): String? {
            if (apiKey.isBlank()) return "Gemini API key is not set — paste your key first"

            val url = "$GEMINI_ENDPOINT${model.trim()}:generateContent"
            val testClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return try {
                val withThinkingConfig = model.trim().startsWith("gemini-2.5")
                var result = postGemini(testClient, url, apiKey, buildGeminiRequestBody("Reply with exactly: OK", withThinkingConfig))
                if (result.first == 400 && withThinkingConfig) {
                    result = postGemini(testClient, url, apiKey, buildGeminiRequestBody("Reply with exactly: OK", false))
                }
                if (result.first !in 200..299) {
                    describeGeminiHttpError(result.first, result.second, model)
                } else {
                    null
                }
            } catch (e: java.io.IOException) {
                "Could not reach the Gemini API (network error: ${e.javaClass.simpleName})"
            } catch (e: Exception) {
                "Test request failed: ${e.message?.take(80)}"
            }
        }
    }
}
