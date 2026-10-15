package app.nudge.engine

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.nudge.R
import app.nudge.core.Edge
import app.nudge.core.Status
import app.nudge.core.TargetKind
import app.nudge.core.Trigger
import app.nudge.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Nudge's always-on helper, a low-key foreground service. It does two jobs:
 *  - keeps the speech model loaded (Settings › Keep speech ready) so the widget, tile and the
 *    watch get answers in a fraction of a second instead of waiting several seconds for a load;
 *  - listens for wifi changes and charger plug/unplug, which Android only tells running apps.
 * Bluetooth doesn't need it (the manifest receiver gets those).
 */
class TriggerWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentSsid: String? = null
    private var wifiNetwork: Network? = null
    private var baselined = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')
                ?.takeIf { it.isNotBlank() && it != WifiManagerUnknown } ?: return
            if (ssid == currentSsid) return
            val old = currentSsid
            currentSsid = ssid
            wifiNetwork = network
            if (!baselined) { baselined = true; return } // already connected when we started
            scope.launch {
                if (old != null) wifi(old, Edge.EXIT)
                wifi(ssid, Edge.ENTER)
            }
        }

        override fun onLost(network: Network) {
            if (network != wifiNetwork) return
            val old = currentSsid ?: return
            currentSsid = null
            wifiNetwork = null
            baselined = true
            scope.launch { wifi(old, Edge.EXIT) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> scope.launch { Engine.onPower(Edge.ENTER); Store.flush() }
                Intent.ACTION_POWER_DISCONNECTED -> scope.launch { Engine.onPower(Edge.EXIT); Store.flush() }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> Unit // manifest receiver handles it
            }
        }
    }

    private fun wifi(ssid: String, edge: Edge) {
        Engine.onTarget(TargetKind.WIFI, edge) { id ->
            Store.target(id)?.value?.equals(ssid, ignoreCase = true) == true
        }
        Store.flush()
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Engine.init(this)
        val n = NotificationCompat.Builder(this, Notifier.CH_WATCHING)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle("Nudge is ready")
            .setContentText("Listening is instant; wifi & charger reminders are on")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notifier.mainPi(this))
            .setOngoing(true)
            .build()
        try {
            startForeground(Notifier.WATCHING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Log.e("NudgeWatch", "couldn't go foreground", e)
            stopSelf()
            return
        }
        if (Store.settings.value.keepReady) app.nudge.speech.Asr.warmUp(this)
        val cm = getSystemService(ConnectivityManager::class.java)
        // If wifi is already up, its first report is the starting point, not an arrival.
        baselined = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiCallback,
        )
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!needed()) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val WifiManagerUnknown = "<unknown ssid>"
        @Volatile var running = false

        fun needed(): Boolean {
            if (Store.settings.value.keepReady) return true
            if (Store.targets.value.any { it.kind == TargetKind.WIFI }) return true
            return Store.reminders.value.any { r ->
                val t = when (r.status) {
                    Status.WAITING -> r.trigger
                    Status.SNOOZED -> r.snoozeTrigger
                    else -> null
                }
                t is Trigger.Power || (t is Trigger.Target && t.kind == TargetKind.WIFI)
            }
        }

        /** Starts or stops the service to match what reminders need. Safe to call often. */
        fun sync(ctx: Context) {
            val want = needed()
            if (want == running) return
            val i = Intent(ctx, TriggerWatchService::class.java)
            if (want) {
                try {
                    ctx.startForegroundService(i)
                } catch (e: Exception) {
                    // Android blocks starting it from the background; the next app open or reboot will.
                    Log.w("NudgeWatch", "deferred start: ${e.message}")
                }
            } else {
                ctx.stopService(i)
            }
        }
    }
}
