package app.nudge.engine

import android.content.Context
import app.nudge.core.Edge
import app.nudge.core.LogKind
import app.nudge.core.Reminder
import app.nudge.core.Schedule
import app.nudge.core.Status
import app.nudge.core.TargetKind
import app.nudge.core.Trigger
import app.nudge.data.Store
import app.nudge.launch.RecordWidget
import app.nudge.wear.WearSync
import java.util.UUID

/**
 * All reminder state changes go through here so alarms, geofences, the connection
 * watcher, notifications, the widget and the watch stay in step.
 */
object Engine {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun now() = System.currentTimeMillis()

    // ---- Creating and editing ---------------------------------------------------------

    fun create(
        text: String,
        trigger: Trigger,
        leadMinutes: Int = 0,
        transcript: String? = null,
        needsReview: Boolean = false,
        nag: Boolean = Store.settings.value.nagByDefault,
        id: String = UUID.randomUUID().toString(),
    ): Reminder {
        Store.reminder(id)?.let { return it } // watch captures can arrive twice
        val r = armed(
            Reminder(
                id = id,
                text = text.ifBlank { "Reminder" },
                trigger = trigger,
                createdAt = now(),
                transcript = transcript,
                leadMinutes = leadMinutes,
                nag = nag,
                needsReview = needsReview,
            ),
        )
        Store.upsert(r)
        Store.log(r, LogKind.CREATED, Schedule.describe(r.trigger, now()))
        Alarms.set(app, r)
        changed()
        return r
    }

    /** Saves an edited reminder and starts it waiting on its (possibly new) trigger. */
    fun save(edited: Reminder) {
        Notifier.cancel(app, edited.id)
        val r = armed(edited.copy(needsReview = edited.needsReview && edited.trigger == Trigger.None))
        Store.upsert(r)
        Store.log(r, LogKind.EDITED, Schedule.describe(r.trigger, now()))
        Alarms.set(app, r)
        WearSync.quiet(app, r.id)
        changed()
    }

    fun delete(id: String) {
        val r = Store.reminder(id) ?: return
        Notifier.cancel(app, id)
        Alarms.cancel(app, id)
        Store.removeReminder(id)
        Store.log(r, LogKind.DELETED)
        WearSync.quiet(app, id)
        changed()
    }

    /** Waiting state with the next time-based wake-up filled in. */
    fun armed(r: Reminder): Reminder = when (val t = r.trigger) {
        is Trigger.At -> r.copy(status = Status.WAITING, nextFireAt = t.epochMillis, leadFired = false, snoozeTrigger = null)
        is Trigger.Repeat -> r.copy(
            status = Status.WAITING, nextFireAt = Schedule.nextRepeat(t, now()), leadFired = false, snoozeTrigger = null,
        )
        else -> r.copy(status = Status.WAITING, nextFireAt = null, snoozeTrigger = null)
    }

    // ---- Firing -----------------------------------------------------------------------

    fun onAlarm(id: String) {
        val r = Store.reminder(id) ?: return
        val now = now()
        when (r.status) {
            Status.WAITING -> {
                val due = r.nextFireAt ?: return
                if (r.leadMinutes > 0 && !r.leadFired && now < due - 30_000) {
                    val l = r.copy(leadFired = true)
                    Store.upsert(l)
                    Notifier.headsUp(app, l)
                    Alarms.set(app, l)
                } else if (now >= due - 60_000) {
                    fire(r)
                } else {
                    Alarms.set(app, r) // woke early; go back to sleep
                }
            }
            Status.SNOOZED -> fire(r)
            Status.FIRING -> fire(r, nagging = true)
            Status.DONE -> Unit
        }
    }

    fun fire(r: Reminder, nagging: Boolean = false) {
        val s = Store.settings.value
        val now = now()
        val f = r.copy(
            status = Status.FIRING,
            lastFiredAt = now,
            fireCount = r.fireCount + 1,
            snoozeTrigger = null,
            nextFireAt = if (r.nag) now + s.nagMinutes * 60_000L else null,
        )
        Store.upsert(f)
        if (!nagging) Store.log(f, LogKind.FIRED, if (r.status == Status.SNOOZED) "after sleep" else null)
        Notifier.show(app, f)
        Alarms.set(app, f)
        if (s.watchBuzz) WearSync.buzz(app, f)
        changed()
    }

    // ---- Responses --------------------------------------------------------------------

    fun done(id: String) {
        val r = Store.reminder(id) ?: return
        Notifier.cancel(app, id)
        WearSync.quiet(app, id)
        val now = now()
        val next = if (r.trigger is Trigger.Repeat) {
            armed(r).copy(doneAt = now)
        } else {
            r.copy(status = Status.DONE, doneAt = now, nextFireAt = null, snoozeTrigger = null)
        }
        Store.upsert(next)
        Store.log(r, LogKind.DONE)
        Alarms.set(app, next)
        changed()
    }

    fun snooze(id: String, minutes: Int) {
        val r = Store.reminder(id) ?: return
        snoozeUntil(id, Trigger.At(now() + minutes * 60_000L), label = "$minutes min", base = r)
    }

    fun snoozeUntil(id: String, wake: Trigger, label: String? = null, base: Reminder? = null) {
        val r = base ?: Store.reminder(id) ?: return
        Notifier.cancel(app, id)
        WearSync.quiet(app, id)
        val s = when (wake) {
            is Trigger.At -> r.copy(status = Status.SNOOZED, nextFireAt = wake.epochMillis, snoozeTrigger = null)
            is Trigger.Target, is Trigger.Power -> r.copy(status = Status.SNOOZED, nextFireAt = null, snoozeTrigger = wake)
            else -> r.copy(status = Status.WAITING, nextFireAt = null, snoozeTrigger = null)
        }
        Store.upsert(s)
        Store.log(r, LogKind.SNOOZED, label ?: Schedule.describe(wake, now()))
        Alarms.set(app, s)
        changed()
    }

    /** Undo for bulk Done/Delete: restores these reminders exactly as they were. */
    fun putBack(snapshot: List<Reminder>) {
        snapshot.forEach { r ->
            Store.upsert(r)
            Alarms.set(app, r)
            if (r.status == Status.FIRING) Notifier.show(app, r)
        }
        changed()
    }

    /** Brings a finished reminder back. */
    fun restore(id: String) {
        val r = Store.reminder(id) ?: return
        save(r.copy(doneAt = null))
    }

    // ---- Context events ---------------------------------------------------------------

    fun onTarget(kind: TargetKind, edge: Edge, matches: (String) -> Boolean) {
        val hits = Store.reminders.value.filter { r ->
            val t = when (r.status) {
                Status.WAITING -> r.trigger
                Status.SNOOZED -> r.snoozeTrigger
                else -> null
            }
            t is Trigger.Target && t.kind == kind && t.edge == edge && matches(t.targetId)
        }
        hits.forEach { fire(it) }
    }

    fun onPower(edge: Edge) {
        Store.reminders.value.filter { r ->
            val t = if (r.status == Status.WAITING) r.trigger else if (r.status == Status.SNOOZED) r.snoozeTrigger else null
            t is Trigger.Power && t.edge == edge
        }.forEach { fire(it) }
    }

    // ---- Housekeeping -----------------------------------------------------------------

    /** After boot, an update or a clock change: re-arm everything. */
    fun rescheduleAll() {
        val now = now()
        Store.editReminders { list ->
            list.map { r ->
                // A repeating reminder whose occurrence was missed while off: point at the next one.
                if (r.status == Status.WAITING && r.trigger is Trigger.Repeat && (r.nextFireAt ?: 0) < now - 60 * 60_000L) {
                    armed(r)
                } else r
            }
        }
        Store.reminders.value.forEach { Alarms.set(app, it) }
        Alarms.scheduleReview(app)
        changed()
    }

    /** Something changed: refresh everything that mirrors reminder state. */
    fun changed() {
        Geofences.sync(app)
        TriggerWatchService.sync(app)
        WearSync.pushState(app)
        RecordWidget.updateAll(app)
    }
}
