package app.nudge.wear

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.wear.tiles.TileService
import app.nudge.core.NudgeJson
import app.nudge.core.Paths
import app.nudge.core.WatchAction
import app.nudge.core.WatchCapture
import app.nudge.core.WatchState
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * The watch's copy of what the phone knows: reminder list, saved places/devices and
 * settings. Cached on disk so the watch can still parse ("…when I get in the van")
 * with the phone out of range.
 */
object WatchData {
    private const val TAG = "NudgeWatchData"
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state
    @Volatile private var loaded = false

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "state.json"))

    fun load(ctx: Context): WatchState {
        if (!loaded) {
            loaded = true
            try {
                val f = file(ctx)
                if (f.baseFile.exists()) {
                    _state.value = NudgeJson.decodeFromString(WatchState.serializer(), f.readFully().decodeToString())
                }
            } catch (t: Throwable) { Log.w(TAG, "cache unreadable", t) }
        }
        return _state.value
    }

    fun update(ctx: Context, bytes: ByteArray) {
        try {
            val s = NudgeJson.decodeFromString(WatchState.serializer(), bytes.decodeToString())
            _state.value = s
            loaded = true
            val f = file(ctx)
            val out = f.startWrite()
            try { out.write(bytes); f.finishWrite(out) } catch (t: Throwable) { f.failWrite(out) }
            TileService.getUpdater(ctx).requestUpdate(RecordTileService::class.java)
        } catch (t: Throwable) {
            Log.e(TAG, "bad state", t)
        }
    }

    /** Pulls the latest state from the Data Layer (the phone may have updated it while we slept). */
    suspend fun refresh(ctx: Context) {
        try {
            val items = Wearable.getDataClient(ctx).dataItems.await()
            try {
                items.firstOrNull { it.uri.path == Paths.STATE }?.data?.let { update(ctx, it) }
            } finally {
                items.release()
            }
        } catch (t: Throwable) { Log.d(TAG, "refresh failed: ${t.message}") }
    }

    /** Hands a capture to the phone. Queued by the Data Layer if the phone is away. */
    suspend fun send(ctx: Context, c: WatchCapture) {
        val req = PutDataRequest.create(Paths.CAPTURE_PREFIX + c.id)
            .setData(NudgeJson.encodeToString(WatchCapture.serializer(), c).encodeToByteArray())
            .setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

    /** The phone wasn't reachable live: queue the raw recording for it to transcribe later. */
    suspend fun sendAudio(ctx: Context, id: String, pcm: ByteArray, at: Long) {
        val req = com.google.android.gms.wearable.PutDataMapRequest.create(Paths.AUDIO_PREFIX + id).apply {
            dataMap.putAsset("pcm", com.google.android.gms.wearable.Asset.createFromBytes(pcm))
            dataMap.putLong("at", at)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

    /** The phone to stream to, or null if none is connected. Prefers one that's nearby (Bluetooth). */
    suspend fun phoneNode(ctx: Context): String? = try {
        val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    } catch (t: Throwable) { null }

    /** Done / Sleep from the watch. Returns false if the phone isn't reachable. */
    suspend fun action(ctx: Context, a: WatchAction): Boolean = try {
        val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
        nodes.forEach {
            Wearable.getMessageClient(ctx).sendMessage(
                it.id, Paths.ACTION, NudgeJson.encodeToString(WatchAction.serializer(), a).encodeToByteArray(),
            ).await()
        }
        nodes.isNotEmpty()
    } catch (t: Throwable) {
        false
    }
}
