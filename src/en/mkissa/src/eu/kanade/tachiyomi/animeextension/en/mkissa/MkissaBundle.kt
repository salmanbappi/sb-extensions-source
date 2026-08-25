package eu.kanade.tachiyomi.animeextension.en.mkissa

/**
 * Recovers `buildId` and the four mask seeds from the obfuscated JS chunk. The seeds are lookups
 * into a string table rotated at load time by an amount only the bundle's checksum loop knows, so
 * [parse] tries every rotation and keeps the one whose results all have the seed shape.
 */
object MkissaBundle {

    class BuildInfo(val buildId: String, val seeds: List<String>)

    fun parse(js: String): BuildInfo? {
        // Older bundles keep the literal: `!== "string" ? "12345" : ""`.
        BUILD_ID_REGEX.find(js)?.groupValues?.get(1)?.let { literalId ->
            extractSeeds(js)?.let { seeds -> return BuildInfo(literalId, seeds) }
        }

        // Newer ones route it through the same rotated table as the seeds (`const Mm=zt(520,520)`),
        // so it needs the same brute-forced rotation.
        val (tables, bases, aliases) = decodersFrom(js)
        val buildId = extractRotatedBuildId(js, tables, bases, aliases) ?: return null
        val seeds = extractSeedsWithTables(js, tables, bases, aliases) ?: return null
        return BuildInfo(buildId, seeds)
    }

    private class Base(val table: String, val offset: Int)
    private class Alias(val base: String, val argIndex: Int, val delta: Int)

    private data class Decoders(
        val tables: Map<String, List<String>>,
        val bases: Map<String, Base>,
        val aliases: Map<String, Alias>,
    )

    private fun decodersFrom(js: String): Decoders {
        val tables = readTables(js)
        val bases = BASE_DECODER_REGEX.findAll(js).associate { m ->
            m.groupValues[1] to Base(m.groupValues[4], fold(m.groupValues[3]))
        }
        val aliases = buildMap {
            // A seed may call a base decoder directly, so each base is its own identity alias.
            bases.keys.forEach { put(it, Alias(it, 0, 0)) }
            ALIAS_DECODER_REGEX.findAll(js).forEach { m ->
                val (name, firstParam, _, callee, arg, delta) = m.destructured
                if (callee !in bases) return@forEach
                // Which parameter the alias forwards tells us where the table index sits.
                put(name, Alias(callee, if (arg == firstParam) 0 else 1, if (delta.isEmpty()) 0 else fold(delta)))
            }
        }
        return Decoders(tables, bases, aliases)
    }

    /**
     * Finds the decoder call that yields `buildId`, narrowing from the most specific site pattern to
     * a full scan. Every candidate is confirmed by requiring the seeds to resolve under the same
     * rotation, which rules out unrelated numeric strings such as the mask salts.
     */
    private fun extractRotatedBuildId(
        js: String,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val candidates = LinkedHashSet<String>()

        // The mask function takes the buildId as its default argument: `function F_(e=Mm)`.
        val maskDefaultVar = DEFAULT_PARAM_REGEX.findAll(js)
            .map { it.groupValues[2] }
            .filter(String::isNotEmpty)
            .firstOrNull { name -> Regex("""\b${Regex.escape(name)}\s*=\s*$CALL_PATTERN""").containsMatchIn(js) }

        if (maskDefaultVar != null) {
            Regex("""\b${Regex.escape(maskDefaultVar)}\s*=\s*($CALL_PATTERN)""").findAll(js)
                .forEach { candidates.add(it.groupValues[1]) }
        }

        // Otherwise the assignment sits just above the seed array.
        val seedArray = SEED_ARRAY_REGEX.find(js)
        if (seedArray != null) {
            val start = seedArray.range.first
            val window = js.substring((start - SEED_WINDOW).coerceAtLeast(0), start)
            ASSIGNED_CALL_REGEX.findAll(window)
                .map { it.groupValues[1] }
                // A `+` means the seed array's own concatenated halves, not a lone buildId.
                .filterNot { it.contains('+') }
                .forEach(candidates::add)
        }

        if (candidates.isEmpty()) {
            ASSIGNED_CALL_REGEX.findAll(js)
                .map { it.groupValues[1] }
                .filterNot { it.contains('+') }
                .forEach(candidates::add)
        }

        candidates.firstNotNullOfOrNull { call ->
            decodeDigits(call, js, tables, bases, aliases)
        }?.let { return it }

        // Last resort: any call at all that decodes to a plausible buildId, skipping the seed array
        // itself so its halves are never mistaken for one.
        val seedRange = seedArray?.range
        for (match in CALL_REGEX.findAll(js)) {
            if (seedRange != null && match.range.first in seedRange) continue
            decodeDigits(match.value, js, tables, bases, aliases)?.let { return it }
        }
        return null
    }

    /** Decodes [call] under every rotation, keeping the one that also resolves the seeds. */
    private fun decodeDigits(
        call: String,
        js: String,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val aliasName = CALL_REGEX.find(call)?.groupValues?.get(1) ?: return null
        val alias = aliases[aliasName] ?: return null
        val base = bases[alias.base] ?: return null
        val table = tables[base.table] ?: return null

        for (rotation in table.indices) {
            val decoded = resolve(call, rotation, tables, bases, aliases) ?: continue
            if (!decoded.matches(BUILD_ID_DIGITS_REGEX)) continue
            if (extractSeedsWithTables(js, tables, bases, aliases, forcedRotation = rotation) == null) continue
            return decoded
        }
        return null
    }

    private fun extractSeeds(js: String): List<String>? {
        val (tables, bases, aliases) = decodersFrom(js)
        return extractSeedsWithTables(js, tables, bases, aliases)
    }

    private fun extractSeedsWithTables(
        js: String,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
        forcedRotation: Int? = null,
    ): List<String>? {
        for (match in SEED_ARRAY_REGEX.findAll(js)) {
            val calls = CALL_REGEX.findAll(match.groupValues[1]).map(MatchResult::value).toList()
            if (calls.size != MkissaCrypto.SEED_COUNT * 2) continue

            val table = CALL_REGEX.find(calls.first())
                ?.let { aliases[it.groupValues[1]] }
                ?.let { tables[bases[it.base]?.table] }
                ?: continue

            if (forcedRotation != null) {
                seedsAt(calls, forcedRotation, tables, bases, aliases)?.let { return it }
                continue
            }

            val matches = table.indices.mapNotNull { rotation ->
                seedsAt(calls, rotation, tables, bases, aliases)
            }
            // A chance match would silently yield a bad mask, so require an unambiguous answer.
            matches.singleOrNull()?.let { return it }
        }
        return null
    }

    private fun seedsAt(
        calls: List<String>,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): List<String>? {
        val seeds = calls.chunked(2).mapNotNull { (first, second) ->
            val a = resolve(first, rotation, tables, bases, aliases) ?: return@mapNotNull null
            val b = resolve(second, rotation, tables, bases, aliases) ?: return@mapNotNull null
            (a + b).takeIf(SEED_REGEX::matches)
        }
        return seeds.takeIf { it.size == MkissaCrypto.SEED_COUNT }
    }

    private fun resolve(
        call: String,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val match = CALL_REGEX.matchEntire(call) ?: return null
        val alias = aliases[match.groupValues[1]] ?: return null
        val base = bases[alias.base] ?: return null
        val table = tables[base.table]?.takeIf { it.isNotEmpty() } ?: return null

        val args = listOfNotNull(
            match.groupValues[2].toIntOrNull(),
            match.groupValues[3].toIntOrNull(),
        )
        val arg = args.getOrNull(alias.argIndex) ?: return null

        val index = arg + alias.delta - base.offset + rotation
        return table[((index % table.size) + table.size) % table.size]
    }

    private fun readTables(js: String): Map<String, List<String>> = buildMap {
        for (match in TABLE_HEAD_REGEX.findAll(js)) {
            readStringArray(js, match.range.last)?.let { put(match.groupValues[1], it) }
        }
    }

    /** Whitelist parser: returns null rather than a partial array if anything unexpected appears. */
    private fun readStringArray(js: String, open: Int): List<String>? {
        val items = mutableListOf<String>()
        var i = open + 1
        while (i < js.length) {
            when (val c = js[i]) {
                ']' -> return items

                ',', ' ' -> i++

                '"', '\'' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < js.length && js[i] != c) {
                        if (js[i] == '\\') {
                            sb.append(js[i + 1])
                            i += 2
                        } else {
                            sb.append(js[i])
                            i++
                        }
                    }
                    if (i >= js.length) return null
                    i++
                    items.add(sb.toString())
                }

                else -> return null
            }
        }
        return null
    }

    /**
     * Folds the `2935+-1459*2` arithmetic every integer is hidden behind; signs stack.
     *
     * The obfuscator also emits negative factors (`2461*-4`). The old term scan split those
     * mid-product and read the `-4` as a separate addition, which zeroed every decoder offset and
     * made the seed rotation unresolvable.
     */
    private fun fold(expression: String): Int {
        var total = 0
        for (term in TERM_REGEX.findAll(expression.replace(" ", "")).map(MatchResult::value)) {
            var sign = 1
            var body = term
            while (body.startsWith('+') || body.startsWith('-')) {
                if (body.startsWith('-')) sign = -sign
                body = body.substring(1)
            }

            // The term's sign folds into the first factor so Int.MIN_VALUE survives parsing.
            var value = parseFactor(sign, body.substringBefore('*')) ?: return 0
            val rest = body.substringAfter('*', "")
            if (rest.isNotEmpty()) {
                for (factor in rest.split('*')) value *= parseFactor(1, factor) ?: return 0
            }
            total += value
        }
        return total
    }

    /** Signs stack before the digits, so parity decides the result ("2*--4"). */
    private fun parseFactor(sign: Int, factor: String): Int? {
        var negative = sign < 0
        var digits = factor
        while (digits.startsWith('+') || digits.startsWith('-')) {
            if (digits.startsWith('-')) negative = !negative
            digits = digits.substring(1)
        }
        val magnitude = digits.toLongOrNull() ?: return null
        val signed = if (negative) -magnitude else magnitude
        return if (signed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) signed.toInt() else null
    }

    private val BUILD_ID_REGEX = Regex("""!==\s*["']string["']\s*\?\s*["'](\d+)["']\s*:\s*["']["']""")

    // A decoded buildId is only ever digits; the length bound keeps table noise out.
    private val BUILD_ID_DIGITS_REGEX = Regex("""\d{2,10}""")

    // The obfuscator names functions with `$` too (`$l`, `Cr`), which `\w` excludes. The `${'$'}`
    // interpolation yields the literal dollar sign without starting a template.
    private val IDENT = """[${'$'}A-Za-z0-9_]+"""

    private val TABLE_HEAD_REGEX = Regex("""function ($IDENT)\(\)\s*\{\s*(?:const|let|var)\s+$IDENT\s*=\s*\[""")

    private val BASE_DECODER_REGEX = Regex("""function ($IDENT)\(($IDENT)(?:,$IDENT)*\)\{return \2=\2-\(?([-\d+*\s]+?)\)?,($IDENT)\(\)\[\2\]\}""")

    // Two parameters exactly: the argIndex logic only distinguishes first from second.
    private val ALIAS_DECODER_REGEX = Regex("""function ($IDENT)\(($IDENT),($IDENT)\)\{return ($IDENT)\(($IDENT)((?:[-+][\d+*\s-]+)?)\)\}""")

    private val CALL_PATTERN = """($IDENT)\(\s*(-?\d+)\s*(?:,\s*(-?\d+)\s*)?\)"""
    private val CALL_REGEX = Regex(CALL_PATTERN)

    // `function F_(e=Mm)`: the mask builder defaults its first parameter to the decoded buildId.
    private val DEFAULT_PARAM_REGEX = Regex("""function\s+($IDENT)\s*\(\s*$IDENT\s*=\s*($IDENT)\s*[,)]""")

    private val ASSIGNED_CALL_REGEX = Regex("""\b$IDENT\s*=\s*($CALL_PATTERN)""")

    // How far above the seed array to look for the buildId assignment.
    private const val SEED_WINDOW = 2000

    private val SEED_ARRAY_REGEX = Regex("""=\[((?:$CALL_PATTERN\+$CALL_PATTERN,){3}$CALL_PATTERN\+$CALL_PATTERN)]""")

    private val SEED_REGEX = Regex("""[A-Za-z0-9+/]{11}=""")

    // Leading signs matched greedily; `fold` counts them. A product stays inside one term
    // (`2461*-4`) instead of splitting at every sign, so negative factors keep their place.
    private val TERM_REGEX = Regex("""[-+]*\d+(?:\*[-+]*\d+)*""")
}
