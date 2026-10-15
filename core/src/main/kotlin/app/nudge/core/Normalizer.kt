package app.nudge.core

/**
 * A token of the cleaned-up utterance. [norm] is what the parser matches on (lowercase,
 * number words turned into digits); [surface] is what was said, used to rebuild the task text
 * so "pick up two boxes" doesn't come back as "pick up 2 boxes".
 */
data class Tok(val norm: String, val surface: String)

object Normalizer {

    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val ordinalUnits = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12,
        "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16,
        "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19,
    )
    private val ordinalTens = mapOf("twentieth" to 20, "thirtieth" to 30)

    val months = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sept" to 9, "sep" to 9, "october" to 10,
        "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )

    private val simpleSwaps = mapOf(
        "im" to "i'm", "tmrw" to "tomorrow", "tmr" to "tomorrow", "tomorow" to "tomorrow",
        "tommorow" to "tomorrow", "tonite" to "tonight", "everyday" to "every day",
        "mins" to "minutes", "min" to "minute", "hrs" to "hours", "hr" to "hour",
        "o'clock" to "oclock", "noon's" to "noon", "dont" to "don't", "midday" to "noon",
    )

    private val ic = setOf(RegexOption.IGNORE_CASE)

    /** Same cleanup for the matching copy and the as-said copy, so their words line up. */
    private fun clean(text: String): String = text
        .replace('’', '\'').replace('‘', '\'')
        .replace(Regex("""\ba\.m\.?""", ic), " am ")
        .replace(Regex("""\bp\.m\.?""", ic), " pm ")
        .replace(Regex("""(\d{1,2})[:.](\d{2})\b"""), "$1 $2") // 9:30, or 9.30 as Parakeet writes it
        .replace(Regex("""(\d)(am|pm)\b""", ic), "$1 $2")
        .replace(Regex("""[^A-Za-z0-9' ]"""), " ")
        .replace(Regex("""(^| )'+|'+( |$)"""), " ")

    fun tokens(text: String): List<Tok> {
        val asSaid = clean(text)
        // An all-caps recognizer ("PICK UP NAILS") shouldn't produce an all-caps reminder.
        val keepCase = text.any { it.isLowerCase() }
        val lw = asSaid.lowercase().split(' ').filter { it.isNotBlank() }
        val sw = asSaid.split(' ').filter { it.isNotBlank() }
        val raw = ArrayList<Tok>()
        lw.forEachIndexed { k, w ->
            val surface = if (keepCase) sw.getOrElse(k) { w } else w
            val swap = simpleSwaps[w]
            when {
                swap == null -> raw += Tok(w, surface)
                swap.contains(' ') -> swap.split(' ').forEach { raw += Tok(it, it) }
                else -> raw += Tok(swap, surface)
            }
        }

        // Merge spelled-out pieces the recognizer splits up.
        val merged = ArrayList<Tok>()
        var i = 0
        while (i < raw.size) {
            val w = raw[i].norm
            val next = raw.getOrNull(i + 1)?.norm
            val prevIsNum = merged.lastOrNull()?.let { isNumberish(it.norm) } == true
            when {
                (w == "p" || w == "a") && next == "m" && prevIsNum -> {
                    merged += Tok(w + "m", w + "m"); i += 2
                }
                w == "o" && next == "clock" -> { merged += Tok("oclock", "o'clock"); i += 2 }
                w == "wi" && (next == "fi" || next == "fy") -> { merged += Tok("wifi", "wifi"); i += 2 }
                // The old streaming model split these: "to morrow", "to night".
                w == "to" && next in setOf("morrow", "night", "nite") -> {
                    merged += Tok(if (next == "morrow") "tomorrow" else "tonight", if (next == "morrow") "tomorrow" else "tonight")
                    i += 2
                }
                w == "in" && next == "to" && raw.getOrNull(i + 2)?.norm == "the" -> {
                    merged += Tok("into", "into"); i += 2
                }
                else -> { merged += raw[i]; i++ }
            }
        }
        return numbers(merged)
    }

    private fun isNumberish(w: String) =
        w.firstOrNull()?.isDigit() == true || w in units || w in tens

    /** "twenty five" -> 25, "four thirty" -> 4 30, "fifteenth" -> 15th, "four oh five" -> 4 05. */
    private fun numbers(toks: List<Tok>): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < toks.size) {
            val w = toks[i].norm
            val next = toks.getOrNull(i + 1)?.norm
            val t = tens[w]
            when {
                t != null && next != null && (units[next] ?: 0) in 1..9 -> {
                    out += Tok((t + units.getValue(next)).toString(), toks[i].surface + " " + toks[i + 1].surface)
                    i += 2
                }
                t != null && next != null && (ordinalUnits[next] ?: 0) in 1..9 -> {
                    out += Tok("${t + ordinalUnits.getValue(next)}th".fixOrdinal(), toks[i].surface + " " + toks[i + 1].surface)
                    i += 2
                }
                t != null -> { out += Tok(t.toString(), toks[i].surface); i++ }
                w in units -> {
                    // "four oh five" -> 4 05
                    val after = toks.getOrNull(i + 2)?.norm
                    if (next == "oh" && after != null && (units[after] ?: 0) in 1..9) {
                        out += Tok(units.getValue(w).toString(), toks[i].surface)
                        out += Tok("0" + units.getValue(after), "oh " + toks[i + 2].surface)
                        i += 3
                    } else {
                        out += Tok(units.getValue(w).toString(), toks[i].surface); i++
                    }
                }
                w in ordinalTens -> { out += Tok("${ordinalTens.getValue(w)}th", toks[i].surface); i++ }
                w in ordinalUnits -> {
                    // "second" is usually the duration unless it reads like a date.
                    val prev = out.lastOrNull()?.norm
                    val dateLike = w != "second" || prev == "the" || prev in months || next == "of"
                    if (dateLike) out += Tok("${ordinalUnits.getValue(w)}th".fixOrdinal(), toks[i].surface)
                    else out += toks[i]
                    i++
                }
                else -> { out += toks[i]; i++ }
            }
        }
        return out
    }

    private fun String.fixOrdinal(): String {
        val n = dropLast(2).toInt()
        return Schedule.ordinal(n)
    }

    /** Normalized words of a spoken name, without articles or a trailing "wifi"/"network". */
    fun nameTokens(name: String): List<String> {
        val t = tokens(name).map { it.norm }.toMutableList()
        while (t.isNotEmpty() && t.first() in setOf("the", "my", "our", "a", "an")) t.removeAt(0)
        while (t.isNotEmpty() && t.last() in setOf("wifi", "network", "bluetooth", "internet")) t.removeAt(t.lastIndex)
        return t
    }
}
