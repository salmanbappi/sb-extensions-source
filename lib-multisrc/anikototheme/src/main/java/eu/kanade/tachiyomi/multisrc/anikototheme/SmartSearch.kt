package eu.kanade.tachiyomi.multisrc.anikototheme

import android.util.Log
import java.net.URLEncoder

/**
 * Smart Search module — AI-powered anime search via Google AI Search.
 */
class SmartSearch(
    private val webViewFetcher: WebViewFetcher,
) {
    private val tag = "SmartSearch"

    /** Cache for pagination: last query (phrase stripped) → resolved title. */
    private var cachedQuery: String = ""
    private var cachedTitle: String = ""

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
     * Resolve a query to an anime title via Google AI Search.
     */
    fun resolve(query: String): String? {
        if (query.isBlank()) {
            Log.w(tag, "SmartSearch: empty query")
            return null
        }

        Log.i(tag, "SmartSearch: resolving query: \"$query\"")

        val searchQuery = buildPrompt(query)
        val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
        val googleUrl = "https://www.google.com/search?q=$encodedQuery&udm=50&hl=en"
        Log.d(tag, "SmartSearch: Google URL: $googleUrl")

        val renderedText = try {
            webViewFetcher.fetchRenderedText(googleUrl, timeoutMs = 20_000)
        } catch (e: Exception) {
            Log.e(tag, "SmartSearch: scrape failed", e)
            return null
        }

        if (renderedText.isBlank()) {
            Log.w(tag, "SmartSearch: Google scrape returned empty text")
            return null
        }

        Log.d(tag, "SmartSearch: Google rendered text (${renderedText.length} chars)")

        val title = extractAnimeTitle(renderedText)
        if (title == null) {
            Log.w(tag, "SmartSearch: could not extract title from Google AI text")
            return null
        }

        Log.i(tag, "SmartSearch: extracted title: \"$title\"")
        return title
    }

    private fun buildPrompt(query: String): String = if (query.contains("anime", ignoreCase = true)) {
        query.trim()
    } else {
        "${query.trim()} anime"
    }

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
}
