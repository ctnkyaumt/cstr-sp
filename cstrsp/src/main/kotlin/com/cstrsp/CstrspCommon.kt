package com.cstrsp

import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

object CstrspCache {
    private const val CACHE_TTL_MS = 30_000L
    private val cacheMutex = Mutex()
    private val apiCache = HashMap<String, Pair<Long, Any>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> cached(key: String, fetch: suspend () -> T?): T? {
        cacheMutex.withLock {
            apiCache[key]?.let { (at, value) ->
                if (System.currentTimeMillis() - at < CACHE_TTL_MS) return value as T
            }
        }
        val fresh = try { fetch() } catch (e: Exception) { null } ?: return null
        cacheMutex.withLock { apiCache[key] = System.currentTimeMillis() to fresh }
        return fresh
    }

    suspend fun putCache(key: String, value: Any) {
        cacheMutex.withLock { apiCache[key] = System.currentTimeMillis() to value }
    }
}

// Appends the resolved quality to a source label, e.g. "WF - alpha" -> "WF - alpha (1080p)",
// using CloudStream's own int->label mapping so the name matches the quality badge exactly.
fun withQualityLabel(base: String, quality: Int): String {
    val label = Qualities.getStringByInt(quality)
    if (label.isEmpty() || base.contains(label, ignoreCase = true)) return base
    return "$base ($label)"
}

// Percent-encodes only the non-ASCII bytes of a URL, leaving ASCII untouched.
fun encodeUrlNonAscii(url: String): String {
    if (url.all { it.code <= 0x7F }) return url
    val sb = StringBuilder(url.length + 16)
    for (b in url.toByteArray(Charsets.UTF_8)) {
        val v = b.toInt() and 0xFF
        if (v <= 0x7F) sb.append(v.toChar())
        else sb.append('%').append("%02X".format(v))
    }
    return sb.toString()
}

// Resolves candidates (WebView-based loadExtractor calls) with bounded concurrency
suspend fun <T> List<T>.resolveConcurrently(action: suspend (T) -> Unit) = coroutineScope {
    val semaphore = Semaphore(4)
    map { item ->
        async {
            semaphore.withPermit {
                try {
                    action(item)
                } catch (e: Exception) {
                    // Skip this candidate; others continue independently.
                }
            }
        }
    }.awaitAll()
    Unit
}

// Isolates one source: a failure yields an empty list instead of killing the others.
suspend fun <T> safeList(block: suspend () -> List<T>): List<T> = try {
    block()
} catch (e: Exception) {
    e.printStackTrace()
    emptyList()
}

// --- Turkish search support & Query Matcher -----------------------------------------

private val trCountryNames = mapOf(
    "ispanya" to "spain", "portekiz" to "portugal", "almanya" to "germany",
    "fransa" to "france", "ingiltere" to "england", "italya" to "italy",
    "hollanda" to "netherlands", "belcika" to "belgium", "hirvatistan" to "croatia",
    "sirbistan" to "serbia", "polonya" to "poland", "avusturya" to "austria",
    "isvicre" to "switzerland", "isvec" to "sweden", "norvec" to "norway",
    "danimarka" to "denmark", "finlandiya" to "finland", "yunanistan" to "greece",
    "rusya" to "russia", "ukrayna" to "ukraine", "romanya" to "romania",
    "bulgaristan" to "bulgaria", "macaristan" to "hungary", "cekya" to "czech republic",
    "slovakya" to "slovakia", "slovenya" to "slovenia", "karadag" to "montenegro",
    "arnavutluk" to "albania", "iskocya" to "scotland", "galler" to "wales",
    "irlanda" to "ireland", "izlanda" to "iceland", "turkiye" to "turkey",
    "amerika" to "usa", "brezilya" to "brazil", "arjantin" to "argentina",
    "meksika" to "mexico", "kolombiya" to "colombia", "sili" to "chile",
    "peru" to "peru", "uruguay" to "uruguay", "ekvador" to "ecuador",
    "fas" to "morocco", "misir" to "egypt", "cezayir" to "algeria",
    "tunus" to "tunisia", "nijerya" to "nigeria", "senegal" to "senegal",
    "gana" to "ghana", "kamerun" to "cameroon", "japonya" to "japan",
    "katar" to "qatar", "iran" to "iran", "irak" to "iraq",
    "avustralya" to "australia", "cin" to "china", "hindistan" to "india",
    "cad" to "chad", "urdun" to "jordan", "kanada" to "canada",
    "endonezya" to "indonesia", "tayland" to "thailand", "lubnan" to "lebanon",
    "umman" to "oman", "suriye" to "syria"
)

private val trPhraseNames = mapOf(
    "suudi arabistan" to "saudi arabia", "guney kore" to "south korea",
    "kuzey kore" to "north korea", "guney afrika" to "south africa",
    "yeni zelanda" to "new zealand", "kuzey makedonya" to "north macedonia",
    "bosna hersek" to "bosnia", "kuzey irlanda" to "northern ireland",
    "fildisi sahili" to "ivory coast", "birlesik arap emirlikleri" to "united arab emirates"
)

private val devoicingPairs = listOf('t' to 'd', 'p' to 'b', 'k' to 'g')

private fun trFold(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        sb.append(
            when (c) {
                'İ', 'I', 'ı' -> 'i'
                'Ş', 'ş' -> 's'
                'Ğ', 'ğ' -> 'g'
                'Ü', 'ü' -> 'u'
                'Ö', 'ö' -> 'o'
                'Ç', 'ç' -> 'c'
                else -> c.lowercaseChar()
            }
        )
    }
    return sb.toString()
}

private val nonAlnumRegex = Regex("[^a-z0-9]+")

fun normalizeText(s: String): String =
    trFold(s).replace(nonAlnumRegex, " ").trim()

private fun devoicedVariants(word: String): Set<String> {
    if (word.isEmpty()) return setOf(word)
    val out = mutableSetOf(word)
    val last = word.last()
    for ((voiceless, voiced) in devoicingPairs) {
        if (last == voiceless) out.add(word.dropLast(1) + voiced)
        if (last == voiced) out.add(word.dropLast(1) + voiceless)
    }
    return out
}

private fun searchVariants(word: String): Set<String> {
    val variants = devoicedVariants(word).toMutableSet()
    variants.toList().forEach { v -> trCountryNames[v]?.let { variants.add(it) } }
    return variants
}

private val synonymGroups: List<Set<String>> = listOf(
    setOf("football", "soccer", "futbol"),
    setOf("basketball", "basketbol"),
    setOf("nba"),
    setOf("american football", "nfl", "amerikan futbolu"),
    setOf("baseball", "beyzbol", "mlb"),
    setOf("hockey", "ice hockey", "buz hokeyi", "hokey", "nhl"),
    setOf("tennis", "tenis"),
    setOf("volleyball", "voleybol"),
    setOf("handball", "hentbol"),
    setOf("rugby", "ragbi"),
    setOf("cricket", "kriket"),
    setOf("golf"),
    setOf("darts", "dart"),
    setOf("snooker", "billiards", "bilardo"),
    setOf("boxing", "boks"),
    setOf("mma", "ufc", "fight", "fighting", "dovus"),
    setOf("wrestling", "wwe", "gures"),
    setOf("f1", "formula 1", "formula1", "formula one", "formula", "grand prix"),
    setOf("motogp", "moto gp", "moto2", "moto3"),
    setOf("nascar"),
    setOf("indycar", "indy car"),
    setOf("yaris", "yarisi", "race", "racing", "motor sports", "motorsport", "motorsports"),
    setOf("champions league", "ucl", "sampiyonlar ligi", "sampiyonlar"),
    setOf("europa league", "uel", "avrupa ligi"),
    setOf("conference league", "konferans ligi"),
    setOf("premier league", "epl", "premier lig", "ingiltere ligi"),
    setOf("la liga", "laliga", "ispanya ligi"),
    setOf("serie a", "italya ligi"),
    setOf("bundesliga", "almanya ligi"),
    setOf("ligue 1", "fransa ligi"),
    setOf("super lig", "super league", "turkiye ligi"),
    setOf("world cup", "dunya kupasi", "mundial"),
    setOf("euro", "euros", "european championship", "avrupa sampiyonasi"),
    setOf("kupa", "cup")
)

private val synonymIndex: Map<String, Set<String>> by lazy {
    val m = HashMap<String, MutableSet<String>>()
    for (group in synonymGroups) for (alias in group) {
        m.getOrPut(alias) { mutableSetOf() }.addAll(group)
    }
    m
}

class QueryMatcher(val rawQuery: String) {
    private val normalizedQuery: String = normalizeText(rawQuery)
    private val translatedQuery: String = run {
        var s = normalizedQuery
        trPhraseNames.forEach { (tr, en) ->
            if (s.contains(tr)) s = s.replace(tr, en)
        }
        s
    }
    private val wholeQueryAliases: Set<String>? =
        synonymIndex[normalizedQuery] ?: synonymIndex[translatedQuery]
    private val embeddedAliasGroups: List<Set<String>> =
        synonymGroups.filter { group ->
            group.any { alias ->
                alias.contains(" ") &&
                    (normalizedQuery.contains(alias) || translatedQuery.contains(alias))
            }
        }
    private val tokenVariants: List<Set<String>> =
        translatedQuery.split(" ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                val variants = searchVariants(token).toMutableSet()
                synonymIndex[token]?.let { variants.addAll(it) }
                variants
            }

    fun matches(vararg fields: String?): Boolean {
        if (rawQuery.isBlank()) return true
        val hay = normalizeText(fields.filterNotNull().joinToString(" "))
        wholeQueryAliases?.let { group -> if (group.any { hay.contains(it) }) return true }
        embeddedAliasGroups.forEach { group -> if (group.any { hay.contains(it) }) return true }
        if (tokenVariants.isEmpty()) return true
        return tokenVariants.all { variants -> variants.any { hay.contains(it) } }
    }
}
