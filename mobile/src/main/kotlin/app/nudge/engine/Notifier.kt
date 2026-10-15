package app.nudge.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.nudge.R
import app.nudge.core.Reminder
import app.nudge.core.Schedule
import app.nudge.core.Status
import app.nudge.data.Store
import app.nudge.ui.MainActivity
import app.nudge.ui.SnoozeActivity

object Notifier {
    const val CH_REMIND = "remind"
    const val CH_HEADS_UP = "heads_up"
    const val CH_REVIEW = "review"
    const val CH_WATCHING = "watching"

    const val ACTION_DONE = "app.nudge.DONE"
    const val ACTION_SNOOZE = "app.nudge.SNOOZE"
    const val EXTRA_MINUTES = "minutes"

    const val REVIEW_ID = 1
    const val WATCHING_ID = 2

    val buzz = longArrayOf(0, 700, 250, 700, 250, 700)

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your reminders. Keep this loud."
                enableVibration(true)
                vibrationPattern = buzz
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_HEADS_UP, "Heads-ups", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warnings before a timed reminder"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REVIEW, "Daily review", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCHING, "Nudge is ready", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps speech instant and watches wifi/charger. Safe to hide."
                setShowBadge(false)
            },
        )
    }

    fun notifId(id: String) = 1000 + (id.hashCode() and 0x7fffffff) % 1_000_000

    private fun actionPi(ctx: Context, action: String, id: String, minutes: Int = 0): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("nudge://r/$id/$action/$minutes"))
            .putExtra(Alarms.EXTRA_ID, id)
            .putExtra(EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun sleepLabel(min: Int) = if (min >= 60 && min % 60 == 0) "Sleep ${min / 60}h" else "Sleep ${min}m"

    fun show(ctx: Context, r: Reminder) {
        val s = Store.settings.value
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, SnoozeActivity::class.java)
                .setData(Uri.parse("nudge://sheet/${r.id}"))
                .putExtra(Alarms.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sub = buildString {
            append(Schedule.describe(r.trigger, System.currentTimeMillis()))
            if (r.fireCount > 1) append(" · reminder #${r.fireCount}")
        }
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle(r.text)
            .setContentText(sub)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(buzz)
            .setWhen(r.lastFiredAt ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(false)
            .addAction(R.drawable.ic_check, "Done", actionPi(ctx, ACTION_DONE, r.id))
            .addAction(R.drawable.ic_snooze, sleepLabel(s.snoozeA), actionPi(ctx, ACTION_SNOOZE, r.id, s.snoozeA))
            .addAction(R.drawable.ic_snooze, sleepLabel(s.snoozeB), actionPi(ctx, ACTION_SNOOZE, r.id, s.snoozeB))
            .build()
        post(ctx, notifId(r.id), n)
    }

    fun headsUp(ctx: Context, r: Reminder) {
        val due = r.nextFireAt ?: return
        val mins = ((due - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
        val n = NotificationCompat.Builder(ctx, CH_HEADS_UP)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle("In $mins min: ${r.text}")
            .setContentText(Schedule.describe(r.trigger, System.currentTimeMillis()))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(due - System.currentTimeMillis())
            .setContentIntent(mainPi(ctx))
            .build()
        post(ctx, notifId(r.id), n)
    }

    fun review(ctx: Context) {
        val list = Store.reminders.value
        val open = list.count { it.status == Status.FIRING || (it.status == Status.SNOOZED) }
        val inbox = list.count { it.isInbox }
        val doneToday = Store.log.value.count {
            it.kind == app.nudge.core.LogKind.DONE && it.at > System.currentTimeMillis() - 24 * 3600_000L
        }
        if (open == 0 && inbox == 0) return
        val n = NotificationCompat.Builder(ctx, CH_REVIEW)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle("2-minute review")
            .setContentText("$doneToday done today · $open still open · $inbox in the inbox")
            .setAutoCancel(true)
            .setContentIntent(mainPi(ctx, review = true))
            .build()
        post(ctx, REVIEW_ID, n)
    }

    fun mainPi(ctx: Context, review: Boolean = false): PendingIntent = PendingIntent.getActivity(
        ctx, if (review) 2 else 3,
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_REVIEW, review)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun cancel(ctx: Context, id: String) = NotificationManagerCompat.from(ctx).cancel(notifId(id))

    @Suppress("MissingPermission")
    private fun post(ctx: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) nm.notify(id, n)
    }
}
