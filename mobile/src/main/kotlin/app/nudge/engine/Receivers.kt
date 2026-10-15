package app.nudge.engine

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.nudge.core.Edge
import app.nudge.core.TargetKind
import app.nudge.data.Store
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs [block] off the main thread and keeps the process alive until state is on disk. */
private fun BroadcastReceiver.work(ctx: Context, block: () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            Store.init(ctx)
            Engine.init(ctx)
            block()
            Store.flush()
        } finally {
            pending.finish()
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = work(ctx) {
        when (intent.action) {
            Alarms.ACTION_ALARM -> intent.getStringExtra(Alarms.EXTRA_ID)?.let(Engine::onAlarm)
            Alarms.ACTION_REVIEW -> {
                Notifier.review(ctx)
                Alarms.scheduleReview(ctx)
            }
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = work(ctx) {
        val id = intent.getStringExtra(Alarms.EXTRA_ID) ?: return@work
        when (intent.action) {
            Notifier.ACTION_DONE -> Engine.done(id)
            Notifier.ACTION_SNOOZE -> Engine.snooze(id, intent.getIntExtra(Notifier.EXTRA_MINUTES, 15))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = work(ctx) {
        Engine.rescheduleAll()
    }
}

class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val edge = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> Edge.ENTER
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> Edge.EXIT
            else -> return
        }
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }) ?: return
        val canName = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        @Suppress("MissingPermission")
        val name = if (canName) device.name else null
        val address = device.address
        work(ctx) { onBluetooth(edge, name, address) }
    }

    companion object {
        fun onBluetooth(edge: Edge, name: String?, address: String?) {
            Engine.onTarget(TargetKind.BLUETOOTH, edge) { targetId ->
                val t = Store.target(targetId) ?: return@onTarget false
                (t.address != null && t.address.equals(address, ignoreCase = true)) ||
                    (name != null && t.value.equals(name, ignoreCase = true))
            }
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val edge = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> Edge.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> Edge.EXIT
            else -> return
        }
        val ids = event.triggeringGeofences?.map { it.requestId }?.toSet() ?: return
        work(ctx) { Engine.onTarget(TargetKind.PLACE, edge) { it in ids } }
    }
}
