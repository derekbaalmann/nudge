package app.nudge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderParserTest {
    private val zone = ZoneId.of("America/Chicago")

    // Friday, Oct 9 2026, 10:00 AM.
    private val now = LocalDateTime.of(2026, 10, 9, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 10, 9)

    private val targets = listOf(
        SavedTarget("bt1", TargetKind.BLUETOOTH, "SYNC", "SYNC", aliases = listOf("van", "work vehicle", "work truck")),
        SavedTarget("wifi1", TargetKind.WIFI, "Home wifi", "NETGEAR42", aliases = listOf("home wifi")),
        SavedTarget("p1", TargetKind.PLACE, "Home", lat = 1.0, lng = 1.0),
        SavedTarget("p2", TargetKind.PLACE, "Work", lat = 2.0, lng = 2.0, aliases = listOf("the shop")),
        SavedTarget("p3", TargetKind.PLACE, "Home Depot", lat = 3.0, lng = 3.0),
    )

    private val parser = ReminderParser()
    private fun p(s: String, nowMs: Long = now) = parser.parse(s, targets, nowMs, zone)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Trigger.At(Schedule.epochMillis(d, h, m, zone))

    @Test fun timeToday() {
        val r = p("Pick up nails at 4pm")
        assertEquals("Pick up nails", r.task)
        assertEquals(at(today, 16), r.trigger)
    }

    @Test fun spokenAsrTimeTomorrow() {
        val r = p("PICK UP NAILS AT FOUR P M TOMORROW")
        assertEquals("Pick up nails", r.task)
        assertEquals(at(today.plusDays(1), 16), r.trigger)
    }

    @Test fun recognizerSplitsWords() {
        val r = p("pick up nails at four p m to morrow")
        assertEquals("Pick up nails", r.task)
        assertEquals(at(today.plusDays(1), 16), r.trigger)
        assertEquals(at(today, 20), p("call mom to night").trigger)
    }

    @Test fun parakeetStyleOutput() {
        // The accurate model writes punctuation, capitals and digits ("9.30").
        val r = p("Remind me to call the dentist tomorrow at 9.30.")
        assertEquals("Call the dentist", r.task)
        assertEquals(at(today.plusDays(1), 9, 30), r.trigger)
        assertEquals(at(today, 17, 15), p("Pick up the dry cleaning today at 5.15.").trigger)
        assertEquals(Trigger.At(now + 20 * 60_000), p("In 20 minutes, check on the laundry.").trigger)
        assertEquals("Check on the laundry", p("In 20 minutes, check on the laundry.").task)
        val meds = p("Every weekday at 8, remind me to take my meds.")
        assertEquals("Take my meds", meds.task)
        assertEquals(8, (meds.trigger as Trigger.Repeat).hour)
        assertEquals("Send Sarah the invoice", p("Send Sarah the invoice today before lunch.").task)
        assertEquals("bt1", (p("Remind me to get gas when I get in the van.").trigger as Trigger.Target).targetId)
        assertEquals(at(today.plusDays(1), 16), p("Pick up nails at 4 p.m. tomorrow.").trigger)
        assertEquals(at(today.plusDays(1), 16), p("Pick up nails at 4 PM tomorrow").trigger)
    }

    @Test fun phrasesFromRealUse() {
        val doc = p("Doctor Smith later today at 5 PM.")
        assertEquals("Doctor Smith", doc.task)
        assertEquals(at(today, 17), doc.trigger)
        val bank = p("Remember to check the bank account when I get up in the morning.")
        assertEquals("Check the bank account", bank.task)
        assertEquals(at(today.plusDays(1), 8), bank.trigger) // 10 AM now, so tomorrow morning
        assertEquals("Check on the auction", p("Check on the auction at 5 PM today.").task)
    }

    @Test fun ambiguousHourPicksNextOccurrence() {
        // 10 AM now: "at 4" means 4 PM, "at 11" means 11 AM.
        assertEquals(at(today, 16), p("call mom at four").trigger)
        assertEquals(at(today, 11), p("call mom at eleven").trigger)
        // "at 9" has passed as AM, so 9 PM.
        assertEquals(at(today, 21), p("take meds at nine").trigger)
    }

    @Test fun minutesAndOclock() {
        assertEquals(at(today, 16, 30), p("remind me to stretch at four thirty").trigger)
        assertEquals(at(today, 16, 5), p("stretch at four oh five pm").trigger)
        assertEquals(at(today, 17), p("stretch at 5 o'clock").trigger)
        assertEquals(at(today, 14, 15), p("meeting at 2:15").trigger)
        assertEquals(at(today, 15, 45), p("meeting at quarter to four").trigger)
        assertEquals(at(today, 12), p("lunch with Sam at noon").trigger)
    }

    @Test fun relative() {
        val r = p("remind me in twenty minutes to check the oven")
        assertEquals("Check the oven", r.task)
        assertEquals(Trigger.At(now + 20 * 60_000), r.trigger)
        assertEquals(Trigger.At(now + 90 * 60_000), p("move the car in an hour and a half").trigger)
        assertEquals(Trigger.At(now + 30 * 60_000), p("in half an hour call back").trigger)
        assertEquals(Trigger.At(now + 135 * 60_000), p("in 2 hours and 15 minutes leave").trigger)
        assertEquals(Trigger.At(now + 10 * 60_000), p("check laundry 10 minutes from now").trigger)
    }

    @Test fun relativeDaysWithTime() {
        assertEquals(at(today.plusDays(2), 15), p("pay rent in 2 days at 3pm").trigger)
    }

    @Test fun datesAndParts() {
        assertEquals(at(today, 20), p("take out the trash tonight").trigger)
        assertEquals(at(today.plusDays(1), 8), p("call the bank tomorrow morning").trigger)
        assertEquals(at(today.plusDays(1), 9), p("call the bank tomorrow").trigger)
        // Friday -> Monday.
        assertEquals(at(LocalDate.of(2026, 10, 12), 9), p("send invoice on Monday").trigger)
        assertEquals(at(LocalDate.of(2026, 10, 12), 15), p("send invoice monday at 3").trigger)
        assertEquals(at(LocalDate.of(2026, 10, 15), 9), p("renew tags on the fifteenth").trigger)
        assertEquals(at(LocalDate.of(2026, 11, 3), 9), p("vote november third").trigger)
        assertEquals(at(today.plusDays(2), 9), p("oil change the day after tomorrow").trigger)
    }

    @Test fun mealTimes() {
        assertEquals(at(today, 13), p("call the dentist after lunch").trigger)
        assertEquals(at(today, 21, 30), p("lay out clothes before bed").trigger)
    }

    @Test fun repeats() {
        val daily = p("take meds every day at 8am").trigger as Trigger.Repeat
        assertEquals(RepeatUnit.DAY, daily.rule.unit)
        assertEquals(8, daily.hour)
        assertEquals("Take meds", p("take meds every day at 8am").task)

        val wk = p("trash goes out every tuesday and friday at 7 pm").trigger as Trigger.Repeat
        assertEquals(listOf(2, 5), wk.rule.daysOfWeek)
        assertEquals(19, wk.hour)

        val wd = p("standup every weekday at nine thirty").trigger as Trigger.Repeat
        assertEquals(listOf(1, 2, 3, 4, 5), wd.rule.daysOfWeek)
        assertEquals(9, wd.hour); assertEquals(30, wd.minute)

        val m = p("pay rent on the first of every month").trigger as Trigger.Repeat
        assertEquals(RepeatUnit.MONTH, m.rule.unit); assertEquals(1, m.rule.dayOfMonth)
        assertEquals("Pay rent", p("pay rent on the first of every month").task)

        val other = p("water plants every other day").trigger as Trigger.Repeat
        assertEquals(2, other.rule.interval)

        val nightly = p("brush teeth every night").trigger as Trigger.Repeat
        assertEquals(20, nightly.hour)

        val hourly = p("drink water every 2 hours").trigger as Trigger.Repeat
        assertEquals(RepeatUnit.HOUR, hourly.rule.unit); assertEquals(2, hourly.rule.interval)
    }

    @Test fun bluetoothAlias() {
        val r = p("Remind me to get gas when I get in the van")
        assertEquals("Get gas", r.task)
        assertEquals(Trigger.Target("bt1", "SYNC", TargetKind.BLUETOOTH, Edge.ENTER), r.trigger)
        assertEquals("bt1", (p("when I get in the work truck remind me to get gas").trigger as Trigger.Target).targetId)
        assertEquals("Get gas", p("when I get in the work truck remind me to get gas").task)
    }

    @Test fun leaving() {
        val r = p("grab the tape measure when I get out of the van")
        assertEquals(Edge.EXIT, (r.trigger as Trigger.Target).edge)
        val home = p("lock the back door when I leave")
        assertEquals(Trigger.Target("p1", "Home", TargetKind.PLACE, Edge.EXIT), home.trigger)
        assertEquals("Lock the back door", home.task)
    }

    @Test fun places() {
        assertEquals(Trigger.Target("p1", "Home", TargetKind.PLACE, Edge.ENTER), p("feed the dog when I get home").trigger)
        assertEquals("p3", (p("pick up nails when I get to home depot").trigger as Trigger.Target).targetId)
        assertEquals("p3", (p("pick up nails at home depot").trigger as Trigger.Target).targetId)
        assertEquals("p2", (p("next time I'm at the shop ask about the drill").trigger as Trigger.Target).targetId)
        assertEquals("Ask about the drill", p("next time I'm at the shop ask about the drill").task)
    }

    @Test fun fuzzyNames() {
        // Recognizer mishears "Home Depot" as "home depo".
        assertEquals("p3", (p("pick up nails when I get to home depo").trigger as Trigger.Target).targetId)
    }

    @Test fun unknownTargetAsksUser() {
        val r = p("buy stamps when I get to the post office")
        assertNull(r.trigger)
        assertEquals("post office", r.unresolvedPhrase)
        assertEquals(Edge.ENTER, r.unresolvedEdge)
        assertEquals("Buy stamps", r.task)
        assertEquals("Buy stamps", p("by stamps when I get to the post office").task)
        assertTrue(r.needsEdit)
    }

    @Test fun wifiAndPower() {
        assertEquals("wifi1", (p("start the dishwasher when I connect to home wifi").trigger as Trigger.Target).targetId)
        assertEquals(Trigger.Power(Edge.ENTER), p("set an alarm when I plug in my phone").trigger)
        assertEquals("Set an alarm", p("set an alarm when I plug in my phone").task)
        assertEquals(Trigger.Power(Edge.EXIT), p("grab my keys when I unplug").trigger)
    }

    @Test fun noTrigger() {
        val r = p("look into new tires")
        assertNull(r.trigger)
        assertEquals("Look into new tires", r.task)
        assertTrue(!r.needsEdit)
    }

    @Test fun quantitiesAreNotTimes() {
        val r = p("buy 2 boxes of screws")
        assertNull(r.trigger)
        assertEquals("Buy 2 boxes of screws", r.task)
        assertEquals("Buy two boxes of screws", p("buy two boxes of screws").task)
    }

    @Test fun leadWarning() {
        val r = p("dentist at 3pm with a 30 minute warning")
        assertEquals(30, r.leadMinutes)
        assertEquals("Dentist", r.task)
        assertEquals(at(today, 15), r.trigger)
    }

    @Test fun nextRepeatComputation() {
        val t = Trigger.Repeat(RepeatRule(RepeatUnit.WEEK, 1, listOf(2, 5)), 19, 0, today.toEpochDay())
        val first = Schedule.nextRepeat(t, now, zone)
        assertEquals(Schedule.epochMillis(today, 19, 0, zone), first) // today is Friday
        val second = Schedule.nextRepeat(t, first, zone)
        assertEquals(Schedule.epochMillis(LocalDate.of(2026, 10, 13), 19, 0, zone), second)

        val last = Trigger.Repeat(RepeatRule(RepeatUnit.MONTH, 1, dayOfMonth = -1), 9, 0, today.toEpochDay())
        assertEquals(Schedule.epochMillis(LocalDate.of(2026, 10, 31), 9, 0, zone), Schedule.nextRepeat(last, now, zone))
    }
}
