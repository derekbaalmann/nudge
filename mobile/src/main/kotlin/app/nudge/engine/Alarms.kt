package app.nudge.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.nudge.core.Reminder
import app.nudge.core.Status
import app.nudge.data.Store
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object Alarms {
    const val ACTION_ALARM = "app.nudge.ALARM"
    const val ACTION_REVIEW = "app.nudge.REVIEW"
    const val EXTRA_ID = "id"

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun pi(ctx: Context, id: String, create: Boolean): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .setData(Uri.parse("nudge://alarm/$id"))
            .putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, 0, i, flags)
    }

    /** When this reminder next needs to wake up, or null if it's waiting on something else. */
    fun wakeTime(r: Reminder): Long? = when (r.status) {
        Status.WAITING -> r.nextFireAt?.let { due ->
            if (r.leadMinutes > 0 && !r.leadFired) due - r.leadMinutes * 60_000L else due
        }
        Status.FIRING, Status.SNOOZED -> r.nextFireAt
        Status.DONE -> null
    }

    fun set(ctx: Context, r: Reminder) {
        val at = wakeTime(r) ?: return cancel(ctx, r.id)
        exact(ctx, at, pi(ctx, r.id, true)!!)
    }

    fun cancel(ctx: Context, id: String) {
        pi(ctx, id, false)?.let { am(ctx).cancel(it); it.cancel() }
    }

    private fun exact(ctx: Context, at: Long, p: PendingIntent) {
        val am = am(ctx)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun scheduleReview(ctx: Context) {
        val s = Store.settings.value
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_REVIEW)
        val p = PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!s.reviewEnabled) { am(ctx).cancel(p); return }
        val zone = ZoneId.systemDefault()
        var at = LocalDateTime.of(LocalDate.now(zone), LocalTime.of(s.reviewHour, s.reviewMinute))
        if (!at.atZone(zone).toInstant().isAfter(java.time.Instant.now().plusSeconds(30))) at = at.plusDays(1)
        exact(ctx, at.atZone(zone).toInstant().toEpochMilli(), p)
    }
}
