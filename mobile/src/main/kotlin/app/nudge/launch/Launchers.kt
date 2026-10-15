package app.nudge.launch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import app.nudge.R
import app.nudge.capture.CaptureActivity
import app.nudge.core.Schedule
import app.nudge.core.Status
import app.nudge.data.Store

fun captureIntent(ctx: Context): Intent = Intent(ctx, CaptureActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

fun capturePi(ctx: Context): PendingIntent =
    PendingIntent.getActivity(ctx, 10, captureIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** Home screen widget: one tap anywhere starts recording. Also shows what's next. */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
    }

    companion object {
        fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_record)
            v.setOnClickPendingIntent(R.id.widget_root, capturePi(ctx))
            val now = System.currentTimeMillis()
            val list = Store.reminders.value
            val firing = list.count { it.status == Status.FIRING }
            val next = list.filter { it.status == Status.WAITING && it.nextFireAt != null && it.nextFireAt!! > now }
                .minByOrNull { it.nextFireAt!! }
            val line = when {
                firing > 0 -> "$firing need${if (firing == 1) "s" else ""} you now"
                next != null -> "${Schedule.formatWhen(next.nextFireAt!!, now)} · ${next.text}"
                else -> "Tap to remember something"
            }
            v.setTextViewText(R.id.widget_next, line)
            return v
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RecordWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
        }
    }
}

/** Quick Settings tile: swipe down, tap, talk. Works from the lock screen. */
class RecordTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(capturePi(this))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(captureIntent(this))
        }
    }
}
