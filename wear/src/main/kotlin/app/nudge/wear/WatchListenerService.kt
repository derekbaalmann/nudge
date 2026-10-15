package app.nudge.wear

import android.os.VibrationEffect
import android.os.Vibrator
import app.nudge.core.Paths
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Phone -> watch: state updates, and "buzz hard, a reminder just went off". */
class WatchListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == Paths.STATE) {
                e.dataItem.data?.let { WatchData.update(this, it) }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val vib = getSystemService(Vibrator::class.java) ?: return
        when (event.path) {
            Paths.BUZZ -> {
                // Three long, strong pulses: hard to miss even mid-task.
                val timings = longArrayOf(0, 600, 250, 600, 250, 900)
                val amps = intArrayOf(0, 255, 0, 255, 0, 255)
                vib.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            }
            Paths.QUIET -> vib.cancel()
        }
    }
}
