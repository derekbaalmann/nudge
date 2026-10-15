package app.nudge.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ParserConfig(
    val morningHour: Int = 8,
    val afternoonHour: Int = 14,
    val eveningHour: Int = 18,
    val nightHour: Int = 20,
    /** Used when a day is given without a time ("tomorrow", "on Friday"). */
    val defaultHour: Int = 9,
)

data class ParseResult(
    /** What to do, cleaned up: "Pick up nails". */
    val task: String,
    /** Null when no usable trigger was understood. */
    val trigger: Trigger?,
    val leadMinutes: Int = 0,
    /** "when I get to <this>" where <this> matched no saved place/wifi/device. */
    val unresolvedPhrase: String? = null,
    val unresolvedEdge: Edge? = null,
    val transcript: String,
) {
    /** The parse didn't fully work: open the editor instead of auto-saving. */
    val needsEdit: Boolean get() = unresolvedPhrase != null || task.isBlank()
}

/**
 * Offline, rule-based reminder parser. Takes what the speech recognizer heard
 * ("PICK UP NAILS AT FOUR P M TOMORROW") and pulls out the task and the trigger.
 */
class ReminderParser(private val config: ParserConfig = ParserConfig()) {

    fun parse(
        input: String,
        targets: List<SavedTarget>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ParseResult = Run(input, targets, Instant.ofEpochMilli(nowMillis).atZone(zone), config).result()

    private enum class Part { MORNING, AFTERNOON, EVENING, NIGHT }

    private class Run(
        val input: String,
        val targets: List<SavedTarget>,
        val now: ZonedDateTime,
        val cfg: ParserConfig,
    ) {
        val toks = Normalizer.tokens(input)
        val used = BooleanArray(toks.size)
        val today: LocalDate = now.toLocalDate()

        // What we found.
        var repeat: RepeatRule? = null
        var date: LocalDate? = null
        var hour: Int? = null
        var minute = 0
        var meridiem: String? = null
        var exactHour = false // hour already final (noon, after lunch, 24h, am/pm known)
        var dayBump = 0
        var part: Part? = null
        var relativeMinutes: Long? = null
        var relativeDays: Long? = null
        var lead = 0
        var target: Trigger.Target? = null
        var power: Edge? = null
        var unresolved: String? = null
        var unresolvedEdge: Edge? = null

        fun w(i: Int): String? = if (i in toks.indices && !used[i]) toks[i].norm else null
        fun num(i: Int): Int? = w(i)?.toIntOrNull()
        fun ord(i: Int): Int? = w(i)?.let { Regex("""^(\d{1,2})(st|nd|rd|th)$""").find(it)?.groupValues?.get(1)?.toInt() }
        fun use(from: Int, toExclusive: Int) { for (k in from until toExclusive) if (k in used.indices) used[k] = true }

        /**
         * Matches a space-separated pattern at [i]. Alternatives with '|', optional with a trailing '?'.
         * Returns the number of tokens matched, or -1.
         */
        fun seq(i: Int, pattern: String): Int {
            var j = i
            for (el in pattern.split(' ')) {
                val optional = el.endsWith("?")
                val alts = el.removeSuffix("?").split('|')
                if (w(j) in alts) j++ else if (!optional) return -1
            }
            return j - i
        }

        fun result(): ParseResult {
            stripCommand()
            findLead()
            findRepeat()
            findCondition()
            findRelative()
            findDates()
            findTimes()
            stripCommand()
            return ParseResult(
                task = task(),
                trigger = buildTrigger(),
                leadMinutes = lead,
                unresolvedPhrase = unresolved,
                unresolvedEdge = unresolvedEdge,
                transcript = input.trim(),
            )
        }

        // ---- Filler ------------------------------------------------------------------------

        private val leadingFiller = listOf(
            "hey", "ok", "okay", "um", "uh", "so", "please", "nudge", "and", "then", "also", "oh",
            "remind me to", "remind me that i need to", "remind me that i have to", "remind me that",
            "remind me about", "remind me", "reminder to", "reminder", "set a reminder to",
            "set a reminder for", "set a reminder", "set reminder", "add a reminder to", "add a reminder",
            "create a reminder", "i need to", "i have to", "i've got to", "i got to", "i gotta",
            "i should", "i must", "remember to", "don't forget to", "need to", "have to", "gotta", "to",
        ).map { it.split(' ') }.sortedByDescending { it.size }

        fun stripCommand() {
            var changed = true
            while (changed) {
                changed = false
                val first = used.indexOfFirst { !it }
                if (first < 0) return
                for (p in leadingFiller) {
                    if (p.indices.all { k -> w(first + k) == p[k] }) {
                        use(first, first + p.size); changed = true; break
                    }
                }
            }
        }

        // ---- Durations ---------------------------------------------------------------------

        private val unitMinutes = mapOf(
            "minute" to 1L, "minutes" to 1L, "hour" to 60L, "hours" to 60L,
            "day" to 1440L, "days" to 1440L, "week" to 10080L, "weeks" to 10080L,
            "month" to 43200L, "months" to 43200L,
        )

        /** Parses "20 minutes", "an hour and a half", "half an hour", "2 hours and 15 minutes". */
        fun duration(i: Int): Pair<Long, Int>? {
            var j = i
            var total = 0L
            var any = false
            while (true) {
                var n: Double? = null
                var len = seq(j, "half a|an hour")
                if (len > 0) { total += 30; j += len; any = true } else {
                    len = seq(j, "a couple of?")
                    if (len > 0) n = 2.0
                    if (n == null) { len = seq(j, "a few"); if (len > 0) n = -1.0 }
                    if (n == null) { len = seq(j, "a|an|one"); if (len > 0) n = 1.0 }
                    if (n == null) { num(j)?.let { n = it.toDouble(); len = 1 } }
                    if (n == null) break
                    var k = j + len
                    if (seq(k, "and a half") == 3) { n = n!! + 0.5; k += 3 }
                    val unit = unitMinutes[w(k)] ?: break
                    k++
                    var amount = if (n == -1.0) (if (unit == 1L) 5.0 else 3.0) else n!!
                    if (seq(k, "and a half") == 3) { amount += 0.5; k += 3 }
                    total += (amount * unit).toLong()
                    j = k; any = true
                }
                if (w(j) == "and" && (num(j + 1) != null || w(j + 1) in setOf("a", "an", "half"))) {
                    val save = j
                    j++
                    val before = total
                    val more = duration(j)
                    if (more == null) { j = save; break }
                    total = before + more.first; j = more.second
                }
                break
            }
            return if (any) total to j else null
        }

        fun findLead() {
            for (i in toks.indices) {
                // "with a 30 minute warning", "give me a 10 minute heads up"
                val pre = maxOf(seq(i, "with|give a|an"), seq(i, "and? give me a|an"))
                if (pre > 0) {
                    val d = duration(i + pre) ?: continue
                    val post = maxOf(seq(d.second, "warning"), seq(d.second, "heads up"), seq(d.second, "heads-up"))
                    if (post > 0) { lead = d.first.toInt(); use(i, d.second + post); return }
                }
                // "and warn me 20 minutes before"
                val verb = seq(i, "and? warn|remind|tell|ping|notify|alert me")
                if (verb > 0) {
                    val d = duration(i + verb) ?: continue
                    val post = maxOf(seq(d.second, "before|early|ahead|beforehand"), seq(d.second, "in advance"))
                    if (post > 0) { lead = d.first.toInt(); use(i, d.second + post); return }
                }
            }
        }

        // ---- Repeats -----------------------------------------------------------------------

        private val dayNames = mapOf(
            "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5,
            "saturday" to 6, "sunday" to 7,
            "mondays" to 1, "tuesdays" to 2, "wednesdays" to 3, "thursdays" to 4, "fridays" to 5,
            "saturdays" to 6, "sundays" to 7,
            "mon" to 1, "tue" to 2, "tues" to 2, "thu" to 4, "thur" to 4, "thurs" to 4,
            "fri" to 5,
        )

        /** "monday and wednesday", "monday wednesday and friday" from [i]. */
        fun dayList(i: Int): Pair<List<Int>, Int>? {
            val days = mutableListOf<Int>()
            var j = i
            while (true) {
                val d = dayNames[w(j)] ?: break
                days += d; j++
                val k = if (w(j) == "and" || w(j) == "or") j + 1 else j
                if (dayNames[w(k)] != null) j = k else break
            }
            return if (days.isEmpty()) null else days.distinct() to j
        }

        fun setPart(p: String?): Boolean {
            part = when (p) {
                "morning" -> Part.MORNING
                "afternoon" -> Part.AFTERNOON
                "evening" -> Part.EVENING
                "night" -> Part.NIGHT
                else -> return false
            }
            return true
        }

        fun findRepeat() {
            for (i in toks.indices) {
                if (used[i]) continue
                val s = w(i)
                var end = -1
                var rule: RepeatRule? = null
                when (s) {
                    "every", "each" -> {
                        var j = i + 1
                        var interval = 1
                        if (w(j) == "other") { interval = 2; j++ }
                        else if (num(j) != null) { interval = num(j)!!; j++ }
                        else if (ord(j) != null && w(j + 1) in setOf("day", "week", "month", "year")) { interval = ord(j)!!; j++ }
                        val x = w(j)
                        when {
                            x == "day" || x == "days" -> { rule = RepeatRule(RepeatUnit.DAY, interval); end = j + 1 }
                            x == "weekday" || x == "weekdays" -> { rule = RepeatRule(RepeatUnit.WEEK, interval, listOf(1, 2, 3, 4, 5)); end = j + 1 }
                            x == "weekend" || x == "weekends" -> { rule = RepeatRule(RepeatUnit.WEEK, interval, listOf(6, 7)); end = j + 1 }
                            x == "week" || x == "weeks" -> { rule = RepeatRule(RepeatUnit.WEEK, interval); end = j + 1 }
                            x == "month" || x == "months" -> { rule = RepeatRule(RepeatUnit.MONTH, interval); end = j + 1 }
                            x == "year" || x == "years" -> { rule = RepeatRule(RepeatUnit.YEAR, interval); end = j + 1 }
                            x == "hour" || x == "hours" -> { rule = RepeatRule(RepeatUnit.HOUR, interval); end = j + 1 }
                            x == "minute" || x == "minutes" -> { rule = RepeatRule(RepeatUnit.MINUTE, interval); end = j + 1 }
                            x in setOf("morning", "afternoon", "evening", "night") -> {
                                setPart(x); rule = RepeatRule(RepeatUnit.DAY, interval); end = j + 1
                            }
                            dayNames[x] != null -> {
                                val (days, e) = dayList(j)!!
                                rule = RepeatRule(RepeatUnit.WEEK, interval, days); end = e
                            }
                            ord(j) != null -> {
                                // "every 15th (of the month)"
                                rule = RepeatRule(RepeatUnit.MONTH, 1, dayOfMonth = ord(j)); end = j + 1
                                val of = seq(end, "of the? month")
                                if (of > 0) end += of
                            }
                        }
                    }
                    "daily" -> { rule = RepeatRule(RepeatUnit.DAY); end = i + 1 }
                    "nightly" -> { setPart("night"); rule = RepeatRule(RepeatUnit.DAY); end = i + 1 }
                    "weekly" -> { rule = RepeatRule(RepeatUnit.WEEK); end = i + 1 }
                    "biweekly" -> { rule = RepeatRule(RepeatUnit.WEEK, 2); end = i + 1 }
                    "monthly" -> { rule = RepeatRule(RepeatUnit.MONTH); end = i + 1 }
                    "yearly", "annually" -> { rule = RepeatRule(RepeatUnit.YEAR); end = i + 1 }
                    "hourly" -> { rule = RepeatRule(RepeatUnit.HOUR); end = i + 1 }
                    "weekdays" -> { rule = RepeatRule(RepeatUnit.WEEK, 1, listOf(1, 2, 3, 4, 5)); end = i + 1 }
                    "weekends" -> { rule = RepeatRule(RepeatUnit.WEEK, 1, listOf(6, 7)); end = i + 1 }
                    "mondays", "tuesdays", "wednesdays", "thursdays", "fridays", "saturdays", "sundays" -> {
                        val (days, e) = dayList(i)!!
                        rule = RepeatRule(RepeatUnit.WEEK, 1, days); end = e
                    }
                    "last" -> if (seq(i, "last day of every|each|the month") == 5) {
                        rule = RepeatRule(RepeatUnit.MONTH, 1, dayOfMonth = -1); end = i + 5
                    }
                    else -> {
                        // "the 1st of every month"
                        val o = ord(i)
                        if (o != null) {
                            val len = seq(i + 1, "of every|each month")
                            if (len > 0) { rule = RepeatRule(RepeatUnit.MONTH, 1, dayOfMonth = o); end = i + 1 + len }
                        }
                    }
                }
                var found: RepeatRule = rule ?: continue
                // Trailing detail: "every week on friday", "every month on the 15th".
                var start = i
                if (w(i - 1) == "on" || w(i - 1) == "the") start = i - 1
                if (w(start - 1) == "on") start--
                if (found.unit == RepeatUnit.WEEK) {
                    val on = if (w(end) == "on") end + 1 else end
                    val dl = dayList(on)
                    if (dl != null) { found = found.copy(daysOfWeek = dl.first); end = dl.second }
                }
                if (found.unit == RepeatUnit.MONTH) {
                    val len = maxOf(seq(end, "on the?"), 0)
                    val o = ord(end + len)
                    if (o != null) { found = found.copy(dayOfMonth = o); end += len + 1 }
                }
                if (found.unit == RepeatUnit.DAY && setPart(w(end))) end++
                use(start, end)
                repeat = found
                // "starting tomorrow" / "from monday"
                for (k in toks.indices) if (w(k) in setOf("starting", "beginning", "from") && k + 1 < toks.size) {
                    val nx = toks[k + 1].norm
                    if (nx in dayNames || nx in setOf("tomorrow", "today", "next", "on") || nx in Normalizer.months) used[k] = true
                }
                return
            }
        }

        // ---- Conditions ("when I get in the van") ------------------------------------------

        private val enterVerbs = listOf(
            "get back to", "get back home", "get back", "get to", "get into", "get in", "get on",
            "get home", "getting to", "getting in", "getting into", "getting home",
            "arrive at", "arrive to", "arrive home", "arrive in", "arrive", "reach", "am at", "am in", "am on",
            "am home", "am back home", "am back", "'m at", "go to", "stop at", "stop by", "pull into",
            "pull in to", "pull up to", "pull up at", "pull in at", "connect to", "am connected to",
            "hop in", "hop into", "jump in", "jump into", "start", "turn on", "walk into", "walk in to",
            "come home", "come back home", "come back", "drive to", "head to", "go into",
        ).map { it.split(' ') }.sortedByDescending { it.size }

        private val exitVerbs = listOf(
            "get out of", "getting out of", "get off", "leave", "leaving", "am leaving", "exit",
            "disconnect from", "am disconnected from", "walk out of", "step out of", "head out of",
            "head out", "drive away from", "turn off",
        ).map { it.split(' ') }.sortedByDescending { it.size }

        private val homeVerbs = setOf("get back home", "get back", "get home", "getting home", "arrive home",
            "am home", "am back home", "am back", "come home", "come back home", "come back")

        private val articles = setOf("the", "my", "our", "a", "an", "this", "that")

        fun findCondition() {
            for (i in toks.indices) {
                if (used[i]) continue
                // Opener + subject.
                val opener = listOf("as soon as", "next time", "the next time", "when", "once", "whenever", "after", "if")
                    .map { it.split(' ') }.firstOrNull { p -> p.indices.all { w(i + it) == p[it] } } ?: continue
                var j = i + opener.size
                // Expand "i'm" to subject + "am".
                var subjectAm = false
                when (w(j)) {
                    "i", "we" -> { j++; if (w(j) == "am" || w(j) == "are") { subjectAm = true; j++ } }
                    "i'm", "we're" -> { j++; subjectAm = true }
                    else -> continue
                }

                // Power first: "plug in my phone", "put my phone on the charger".
                val plug = maxOf(
                    seq(j, "plug|plugged in|it? my? phone? in?"),
                    seq(j, "put|set|plug my|the? phone on|in the? charger"),
                    seq(j, "charge|charging my|the? phone?"),
                    seq(j, "start charging"),
                )
                val unplug = maxOf(
                    seq(j, "unplug|unplugged my|the? phone?"),
                    seq(j, "take my|the? phone off the? charger"),
                )
                if (unplug > 0 || plug > 0) {
                    // Don't confuse "plug in" with "plug my van in"; seq() only matched phone-ish words.
                    power = if (unplug > 0) Edge.EXIT else Edge.ENTER
                    use(i, j + maxOf(plug, unplug)); return
                }

                fun verbAt(list: List<List<String>>): List<String>? = list.firstOrNull { v ->
                    if (v.first() == "'m" || v.first() == "am") {
                        if (!subjectAm) return@firstOrNull false
                        val rest = v.drop(1)
                        rest.indices.all { w(j + it) == rest[it] }
                    } else v.indices.all { w(j + it) == v[it] }
                }

                val exitV = verbAt(exitVerbs)
                val enterV = if (exitV == null) verbAt(enterVerbs) else null
                val verb = exitV ?: enterV ?: (if (subjectAm) listOf("am") else null) ?: continue
                val edge = if (exitV != null) Edge.EXIT else Edge.ENTER
                val verbLen = if (verb.first() == "'m" || verb.first() == "am") verb.size - 1 else verb.size
                var k = j + verbLen

                val verbText = verb.joinToString(" ").removePrefix("'m ")
                if (verbText in homeVerbs || (verb.first() == "am" && verbText.endsWith("home"))) {
                    finishTarget(i, k, k, listOf("home"), edge)
                    return
                }
                // Bare "when I leave" means leaving home.
                if (exitV != null && (k >= toks.size || w(k) == null || isBoundary(w(k)!!))) {
                    finishTarget(i, k, k, listOf("home"), edge)
                    return
                }
                while (w(k) in articles) k++
                val m = matchTarget(k)
                if (m != null) {
                    var e = m.second
                    while (w(e) in setOf("wifi", "network", "bluetooth", "parking", "lot")) e++
                    use(i, e)
                    target = Trigger.Target(m.first.id, m.first.label, m.first.kind, edge)
                    return
                }
                // Not a saved thing: remember the phrase so the editor can ask.
                var e = k
                val startOfUtterance = used.take(i).all { it }
                while (e < toks.size && w(e) != null && !isBoundary(w(e)!!)) {
                    if (startOfUtterance && e - k >= 3) break
                    e++
                }
                if (e == k) continue
                finishTarget(i, k, e, toks.subList(k, e).map { it.norm }, edge)
                return
            }

            // "pick up nails at home depot" — only for saved places.
            for (i in toks.indices) {
                if (w(i) != "at") continue
                var k = i + 1
                while (w(k) in articles) k++
                val m = matchTarget(k) ?: continue
                if (m.first.kind != TargetKind.PLACE) continue
                use(i, m.second)
                target = Trigger.Target(m.first.id, m.first.label, m.first.kind, Edge.ENTER)
                return
            }
        }

        private fun isBoundary(word: String) = word in setOf(
            "remind", "to", "and", "then", "please", "so", "i", "i'm", "tomorrow", "today", "tonight",
            "at", "by", "on", "every",
        )

        private fun finishTarget(from: Int, objStart: Int, objEnd: Int, phrase: List<String>, edge: Edge) {
            val hit = bestTarget(phrase)
            use(from, objEnd)
            if (hit != null) {
                target = Trigger.Target(hit.id, hit.label, hit.kind, edge)
            } else {
                unresolved = if (objEnd > objStart) toks.subList(objStart, objEnd).joinToString(" ") { it.surface }
                else phrase.joinToString(" ")
                unresolvedEdge = edge
            }
        }

        /** Longest saved-target name starting at [k]. Returns the target and the end index. */
        fun matchTarget(k: Int): Pair<SavedTarget, Int>? {
            for (len in minOf(6, toks.size - k) downTo 1) {
                if ((k until k + len).any { used[it] }) continue
                val words = toks.subList(k, k + len).map { it.norm }
                val hit = bestTarget(words) ?: continue
                return hit to (k + len)
            }
            return null
        }

        fun bestTarget(words: List<String>): SavedTarget? {
            val said = words.filter { it !in articles }
            if (said.isEmpty()) return null
            val saidJoined = said.joinToString(" ")
            var best: SavedTarget? = null
            var bestScore = Int.MAX_VALUE
            for (t in targets) for (name in t.spokenNames) {
                val nt = Normalizer.nameTokens(name)
                if (nt.isEmpty()) continue
                val joined = nt.joinToString(" ")
                // The full name ("home wifi") beats a stripped one ("home") so "home" means the place.
                val full = Normalizer.tokens(name).map { it.norm }.dropWhile { it in articles }.joinToString(" ")
                val score = when {
                    full == saidJoined -> 0
                    joined == saidJoined -> 5
                    joined.replace(" ", "") == saidJoined.replace(" ", "") -> 5
                    else -> {
                        val d = levenshtein(joined, saidJoined)
                        val tol = when { joined.length <= 3 -> 0; joined.length <= 6 -> 1; else -> 2 }
                        if (d <= tol) 10 * d else continue
                    }
                }
                if (score < bestScore) { best = t; bestScore = score }
            }
            return best
        }

        // ---- Relative times ----------------------------------------------------------------

        fun findRelative() {
            for (i in toks.indices) {
                if (w(i) == "in" || w(i) == "after" || w(i) == "within") {
                    val d = duration(i + 1)
                    if (d != null) {
                        setRelative(d.first); use(i, d.second); return
                    }
                    // Bare "in 5" at the very end means minutes.
                    if (num(i + 1) != null && (i + 2 >= toks.size)) {
                        setRelative(num(i + 1)!!.toLong()); use(i, i + 2); return
                    }
                }
                val d = duration(i)
                if (d != null && seq(d.second, "from now") == 2) {
                    setRelative(d.first); use(i, d.second + 2); return
                }
            }
        }

        private fun setRelative(minutes: Long) {
            if (minutes % 1440L == 0L) relativeDays = minutes / 1440L
            relativeMinutes = minutes
        }

        // ---- Dates -------------------------------------------------------------------------

        fun findDates() {
            var i = 0
            while (i < toks.size) {
                if (used[i]) { i++; continue }
                val s = w(i)!!
                var len = 0
                when {
                    seq(i, "the? day after tomorrow") > 0 -> {
                        len = seq(i, "the? day after tomorrow"); date = today.plusDays(2)
                    }
                    s == "tomorrow" -> { len = 1; date = today.plusDays(1) }
                    s == "today" -> { len = 1; date = today }
                    s == "tonight" -> { len = 1; date = today; part = Part.NIGHT }
                    seq(i, "this morning|afternoon|evening") == 2 -> { setPart(w(i + 1)); date = today; len = 2 }
                    seq(i, "in the morning|afternoon|evening") == 3 -> { setPart(w(i + 2)); len = 3 }
                    seq(i, "at night") == 2 -> { part = Part.NIGHT; len = 2 }
                    seq(i, "this weekend") == 2 -> {
                        val sat = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                        date = sat; len = 2
                    }
                    seq(i, "next week") == 2 -> {
                        date = today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY)); len = 2
                    }
                    seq(i, "next month") == 2 -> { date = today.plusMonths(1).withDayOfMonth(1); len = 2 }
                    else -> {
                        len = weekday(i).takeIf { it > 0 } ?: monthDay(i).takeIf { it > 0 } ?: mealTime(i)
                    }
                }
                if (len > 0) {
                    // Swallow a leading "on"/"by"/"for".
                    var st = i
                    if (w(i - 1) in setOf("on", "by", "for", "starting", "from", "later")) st = i - 1
                    use(st, i + len)
                    // "tomorrow morning"
                    val after = i + len
                    if (setPart(w(after))) used[after] = true
                    else if (seq(after, "in the morning|afternoon|evening") == 3) { setPart(w(after + 2)); use(after, after + 3) }
                    else if (seq(after, "at night") == 2) { part = Part.NIGHT; use(after, after + 2) }
                    i = after
                } else i++
            }
        }

        private fun weekday(i: Int): Int {
            var j = i
            if (w(j) in setOf("this", "next", "coming", "on")) j++
            if (w(j) == "this" && w(j - 1) == "on") j++
            val d = dayNames[w(j)] ?: return 0
            // Plural "mondays" belongs to repeats.
            if (w(j)!!.endsWith("s") && w(j) != "tues" && w(j) != "thurs") return 0
            // "next friday" is treated as the coming Friday; the confirm card shows the date anyway.
            var days = (d - today.dayOfWeek.value + 7) % 7
            if (days == 0) days = 7
            date = today.plusDays(days.toLong())
            return j + 1 - i
        }

        private fun monthDay(i: Int): Int {
            // "october 15th", "oct 15", "october the 15th"
            val m = Normalizer.months[w(i)]
            if (m != null) {
                var j = i + 1
                if (w(j) == "the") j++
                val d = ord(j) ?: num(j)?.takeIf { it in 1..31 } ?: return 0
                setMonthDay(m, d)
                return j + 1 - i
            }
            // "the 15th of october" / "the 15th"
            var j = i
            if (w(j) == "the") j++
            val d = ord(j) ?: return 0
            if (w(j + 1) == "of" && Normalizer.months[w(j + 2)] != null) {
                setMonthDay(Normalizer.months.getValue(w(j + 2)!!), d)
                return j + 3 - i
            }
            if (d !in 1..31) return 0
            var candidate = today.withDayOfMonth(minOf(d, today.lengthOfMonth()))
            if (!candidate.isAfter(today)) {
                val nm = today.plusMonths(1)
                candidate = nm.withDayOfMonth(minOf(d, nm.lengthOfMonth()))
            }
            date = candidate
            return j + 1 - i
        }

        private fun setMonthDay(m: Int, d: Int) {
            var candidate = LocalDate.of(today.year, m, 1).let { it.withDayOfMonth(minOf(d, it.lengthOfMonth())) }
            if (candidate.isBefore(today)) candidate = candidate.plusYears(1)
            date = candidate
        }

        private fun mealTime(i: Int): Int {
            val table = listOf(
                "at lunch" to (12 to 0), "at lunchtime" to (12 to 0), "around lunch" to (12 to 0),
                "after lunch" to (13 to 0), "before lunch" to (11 to 30),
                "at dinner" to (18 to 0), "at dinnertime" to (18 to 0), "before dinner" to (17 to 30),
                "after dinner" to (19 to 0), "after work" to (17 to 0), "before work" to (7 to 30),
                "before bed" to (21 to 30), "at bedtime" to (21 to 30), "before i go to bed" to (21 to 30),
                "at breakfast" to (7 to 30), "after breakfast" to (8 to 30),
                "when i get up" to (cfg.morningHour to 0), "when i wake up" to (cfg.morningHour to 0),
                "after i get up" to (cfg.morningHour to 0), "after i wake up" to (cfg.morningHour to 0),
                "first thing in the morning" to (cfg.morningHour to 0), "first thing" to (cfg.morningHour to 0),
            )
            for ((phrase, hm) in table) {
                val n = phrase.split(' ')
                if (n.indices.all { w(i + it) == n[it] }) {
                    if (hour == null) { hour = hm.first; minute = hm.second; exactHour = true }
                    return n.size
                }
            }
            return 0
        }

        // ---- Times -------------------------------------------------------------------------

        fun findTimes() {
            for (i in toks.indices) {
                if (used[i] || hour != null) continue
                val s = w(i)!!
                val prep = s in setOf("at", "by", "around", "about", "before")
                val j = if (prep) i + 1 else i
                when {
                    w(j) == "noon" -> { hour = 12; minute = 0; exactHour = true; use(i, j + 1) }
                    w(j) == "midnight" -> { hour = 0; minute = 0; exactHour = true; dayBump = 1; use(i, j + 1) }
                    seq(j, "half|quarter past|after") == 2 && num(j + 2) != null -> {
                        hour = num(j + 2); minute = if (w(j) == "half") 30 else 15; amPm(j + 3).let { use(i, it) }
                    }
                    seq(j, "quarter to|till|til|of") == 2 && num(j + 2) != null -> {
                        val of = num(j + 2)!!
                        hour = if (of <= 1) 12 else of - 1; minute = 45
                        amPm(j + 3).let { use(i, it) }
                    }
                    num(j) != null -> {
                        val n = num(j)!!
                        val digits = w(j)!!.length
                        var k = j + 1
                        var h: Int
                        var m = 0
                        if (digits >= 3 && n in 100..2359 && n % 100 < 60) { h = n / 100; m = n % 100 }
                        else if (n in 0..24) {
                            h = n
                            val mm = w(k)
                            if (mm != null && mm.all { it.isDigit() } && mm.toInt() in 0..59 &&
                                (mm.length == 2 || mm.toInt() >= 10)) { m = mm.toInt(); k++ }
                        } else continue
                        val hasMer = w(k) == "am" || w(k) == "pm"
                        val hasOclock = w(k) == "oclock" || w(k + (if (hasMer) 1 else 0)) == "oclock"
                        // Without a preposition, require am/pm/o'clock so "buy 2 boxes" isn't a time.
                        if (!prep && !hasMer && !hasOclock) continue
                        // "at 4" followed by a unit is a quantity, not a time.
                        if (w(k) in unitMinutes) continue
                        hour = h; minute = m
                        if (h == 0 || h > 12) exactHour = true
                        use(i, amPm(k))
                    }
                }
            }
        }

        /** Reads an optional am/pm and o'clock at [k]; returns the index after them. */
        private fun amPm(k: Int): Int {
            var e = k
            if (w(e) == "am" || w(e) == "pm") { meridiem = w(e); e++ }
            if (w(e) == "oclock") e++
            if (meridiem == null && (w(e) == "am" || w(e) == "pm")) { meridiem = w(e); e++ }
            if (seq(e, "in the morning|afternoon|evening") == 3) { setPart(w(e + 2)); e += 3 }
            else if (seq(e, "at night") == 2) { part = Part.NIGHT; e += 2 }
            else if (w(e) == "tonight") { part = Part.NIGHT; date = date ?: today; e++ }
            return e
        }

        // ---- Assemble ----------------------------------------------------------------------

        fun partHour(p: Part) = when (p) {
            Part.MORNING -> cfg.morningHour
            Part.AFTERNOON -> cfg.afternoonHour
            Part.EVENING -> cfg.eveningHour
            Part.NIGHT -> cfg.nightHour
        }

        /** 24h hour when am/pm can be decided without looking at the clock, else null. */
        fun decidedHour(h: Int, guessForDate: Boolean): Int? {
            if (exactHour) return h
            meridiem?.let { return if (it == "am") h % 12 else h % 12 + 12 }
            part?.let { return if (it == Part.MORNING) h % 12 else h % 12 + 12 }
            if (!guessForDate) return null
            return when (h) { in 7..11 -> h; 12 -> 12; else -> h % 12 + 12 }
        }

        fun buildTrigger(): Trigger? {
            val nowMs = now.toInstant().toEpochMilli()
            val zone = now.zone

            repeat?.let { rule ->
                val start = date ?: today
                val (h, m) = when {
                    rule.unit == RepeatUnit.HOUR || rule.unit == RepeatUnit.MINUTE ->
                        if (hour != null) (decidedHour(hour!!, true)!! to minute) else (now.hour to now.minute)
                    hour != null -> decidedHour(hour!!, true)!! to minute
                    part != null -> partHour(part!!) to 0
                    else -> cfg.defaultHour to 0
                }
                return Trigger.Repeat(rule, h, m, start.toEpochDay())
            }
            target?.let { return it }
            power?.let { return Trigger.Power(it) }
            if (unresolved != null) return null

            val rel = relativeMinutes
            if (rel != null) {
                val days = relativeDays
                if (days != null && (hour != null || part != null)) {
                    date = today.plusDays(days)
                } else {
                    return Trigger.At(nowMs + rel * 60_000L)
                }
            }

            if (date == null && hour == null && part == null) return null

            val h0 = hour
            if (h0 != null) {
                val d = date
                if (d != null) {
                    val h = decidedHour(h0, true)!!
                    return Trigger.At(Schedule.epochMillis(d.plusDays(dayBump.toLong()), h, minute, zone))
                }
                val decided = decidedHour(h0, false)
                val candidates = if (decided != null) listOf(decided) else listOf(h0 % 12, h0 % 12 + 12)
                for (dayOff in 0L..1L) for (h in candidates) {
                    val ms = Schedule.epochMillis(today.plusDays(dayOff + dayBump), h, minute, zone)
                    if (ms > nowMs) return Trigger.At(ms)
                }
                return Trigger.At(Schedule.epochMillis(today.plusDays(1), decidedHour(h0, true)!!, minute, zone))
            }

            // Day and/or part of day, no clock time.
            val p = part
            val d = date
            if (d == null && p != null) {
                // "in the morning": the next one.
                val ms = Schedule.epochMillis(today, partHour(p), 0, zone)
                return Trigger.At(if (ms > nowMs) ms else Schedule.epochMillis(today.plusDays(1), partHour(p), 0, zone))
            }
            val day = d!!
            val h = p?.let { partHour(it) } ?: if (day == today) cfg.eveningHour else cfg.defaultHour
            val ms = Schedule.epochMillis(day, h, 0, zone)
            return Trigger.At(if (ms > nowMs) ms else nowMs + 60 * 60_000L)
        }

        private val trailingFiller = setOf(
            "at", "on", "by", "in", "to", "and", "when", "the", "please", "remind", "me", "then", "so",
            "for", "around", "about", "starting", "from", "that", "of", "every", "each", "next", "this",
            "ok", "okay", "thanks", "thank", "you", "with", "a", "an", "until", "till", "before", "after",
        )

        fun task(): String {
            val words = toks.indices.filter { !used[it] }.map { toks[it] }.toMutableList()
            // A task never starts with "by"; the recognizer means "buy".
            if (words.firstOrNull()?.norm == "by" && words.size > 1) words[0] = Tok("buy", "buy")
            while (words.isNotEmpty() && words.last().norm in trailingFiller) words.removeAt(words.lastIndex)
            while (words.isNotEmpty() && words.first().norm in setOf("and", "to", "then", "that", "so", "please")) words.removeAt(0)
            // "remind me to" left over in the middle, e.g. "when … remind me to get gas" after other strips.
            val text = words.joinToString(" ") { it.surface }
                .replace(Regex("""\bremind me (to |that |about )?"""), "")
                .replace(Regex("""\bi('m|'ll|'ve|'d)?\b""")) { "I" + (it.groups[1]?.value ?: "") }
                .replace(Regex("""\s+"""), " ").trim()
            return text.replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                cur.copyInto(prev)
            }
            return prev[b.length]
        }
    }
}
