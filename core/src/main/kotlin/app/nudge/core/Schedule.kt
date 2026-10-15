package app.nudge.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object Schedule {

    /** The first time strictly after [afterMillis] that a repeating trigger fires. */
    fun nextRepeat(t: Trigger.Repeat, afterMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val after = Instant.ofEpochMilli(afterMillis).atZone(zone)
        val start = LocalDate.ofEpochDay(t.startEpochDay)
        val time = LocalTime.of(t.hour, t.minute)
        val rule = t.rule
        val n = rule.interval.coerceAtLeast(1)

        if (rule.unit == RepeatUnit.MINUTE || rule.unit == RepeatUnit.HOUR) {
            val stepMin = if (rule.unit == RepeatUnit.HOUR) 60L * n else n.toLong()
            val anchor = ZonedDateTime.of(start, time, zone)
            if (anchor.isAfter(after)) return anchor.toInstant().toEpochMilli()
            val elapsed = ChronoUnit.MINUTES.between(anchor, after)
            val steps = elapsed / stepMin + 1
            return anchor.plusMinutes(steps * stepMin).toInstant().toEpochMilli()
        }

        var day = maxOf(start, after.toLocalDate())
        repeat(800 * n) {
            if (matches(rule, start, day)) {
                val at = ZonedDateTime.of(day, time, zone)
                if (at.isAfter(after)) return at.toInstant().toEpochMilli()
            }
            day = day.plusDays(1)
        }
        // Unreachable for sane rules; fall back to a day later so nothing spins.
        return after.plusDays(1).toInstant().toEpochMilli()
    }

    private fun matches(rule: RepeatRule, start: LocalDate, day: LocalDate): Boolean {
        val n = rule.interval.coerceAtLeast(1)
        return when (rule.unit) {
            RepeatUnit.DAY -> ChronoUnit.DAYS.between(start, day) % n == 0L
            RepeatUnit.WEEK -> {
                val days = rule.daysOfWeek.ifEmpty { listOf(start.dayOfWeek.value) }
                if (day.dayOfWeek.value !in days) return false
                val weekStart = start.with(DayOfWeek.MONDAY)
                ChronoUnit.WEEKS.between(weekStart, day.with(DayOfWeek.MONDAY)) % n == 0L
            }
            RepeatUnit.MONTH -> {
                val months = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), day.withDayOfMonth(1))
                if (months % n != 0L) return false
                val want = rule.dayOfMonth ?: start.dayOfMonth
                val last = day.lengthOfMonth()
                val target = if (want == -1) last else minOf(want, last)
                day.dayOfMonth == target
            }
            RepeatUnit.YEAR -> {
                val years = day.year - start.year
                years % n == 0 && day.month == start.month &&
                    day.dayOfMonth == minOf(start.dayOfMonth, day.lengthOfMonth())
            }
            RepeatUnit.MINUTE, RepeatUnit.HOUR -> true
        }
    }

    // ---- Human-readable descriptions -------------------------------------------------------

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

    fun formatTime(h: Int, m: Int): String = LocalTime.of(h, m).format(timeFmt)

    fun formatWhen(epochMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val d = at.toLocalDate()
        val day = when (ChronoUnit.DAYS.between(today, d)) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            in 2L..6L -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            else -> d.format(dateFmt) + if (d.year != today.year) ", ${d.year}" else ""
        }
        return "$day ${at.format(timeFmt)}"
    }

    fun describe(trigger: Trigger, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String = when (trigger) {
        is Trigger.At -> formatWhen(trigger.epochMillis, nowMillis, zone)
        is Trigger.Repeat -> describeRepeat(trigger)
        is Trigger.Target -> when (trigger.kind) {
            TargetKind.PLACE -> (if (trigger.edge == Edge.ENTER) "Arrive at " else "Leave ") + trigger.label
            TargetKind.WIFI -> (if (trigger.edge == Edge.ENTER) "Join wifi " else "Leave wifi ") + trigger.label
            TargetKind.BLUETOOTH -> (if (trigger.edge == Edge.ENTER) "Connect to " else "Disconnect from ") + trigger.label
        }
        is Trigger.Power -> if (trigger.edge == Edge.ENTER) "Plug in to charge" else "Unplug charger"
        Trigger.None -> "Inbox · no trigger"
    }

    fun describeRepeat(t: Trigger.Repeat, withTime: Boolean = true): String {
        val r = t.rule
        val n = r.interval
        val time = formatTime(t.hour, t.minute)
        val start = LocalDate.ofEpochDay(t.startEpochDay)
        val every = when (r.unit) {
            RepeatUnit.MINUTE -> return "Every $n min from $time"
            RepeatUnit.HOUR -> return if (n == 1) "Every hour from $time" else "Every $n hours from $time"
            RepeatUnit.DAY -> when (n) { 1 -> "Every day"; 2 -> "Every other day"; else -> "Every $n days" }
            RepeatUnit.WEEK -> {
                val days = r.daysOfWeek.ifEmpty { listOf(start.dayOfWeek.value) }.sorted()
                val names = when (days) {
                    listOf(1, 2, 3, 4, 5) -> "weekday"
                    listOf(6, 7) -> "weekend"
                    listOf(1, 2, 3, 4, 5, 6, 7) -> "day"
                    else -> days.joinToString(", ") {
                        DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.US)
                    }
                }
                when (n) { 1 -> "Every $names"; 2 -> "Every other $names"; else -> "Every $n weeks on $names" }
            }
            RepeatUnit.MONTH -> {
                val dom = r.dayOfMonth ?: start.dayOfMonth
                val on = if (dom == -1) "last day" else ordinal(dom)
                if (n == 1) "Monthly on the $on" else "Every $n months on the $on"
            }
            RepeatUnit.YEAR -> "Yearly on " + start.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        }
        return if (withTime) "$every · $time" else every
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    fun epochMillis(date: LocalDate, h: Int, m: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(date, LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
}
