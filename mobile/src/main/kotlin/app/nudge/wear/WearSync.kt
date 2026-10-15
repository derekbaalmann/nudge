package app.nudge.wear

import android.content.Context
import android.util.Log
import app.nudge.core.NudgeJson
import app.nudge.core.Paths
import app.nudge.core.Reminder
import app.nudge.core.Schedule
import app.nudge.core.Status
import app.nudge.core.WatchCapture
import app.nudge.core.WatchItem
import app.nudge.core.WatchState
import app.nudge.core.WatchDraft
import app.nudge.core.ReminderParser
import app.nudge.speech.Asr
import app.nudge.speech.PcmTranscriber
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataMapItem
import app.nudge.core.Trigger
import app.nudge.data.Store
import app.nudge.engine.Engine
import app.nudge.engine.TriggerWatchService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object WearSync {
    private const val TAG = "NudgeWearSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    /** Publishes the reminder list, targets and settings for the watch (debounced). */
    fun pushState(ctx: Context) {
        val app = ctx.applicationContext
        pending?.cancel()
        pending = scope.launch {
            delay(300)
            val now = System.currentTimeMillis()
            val s = Store.settings.value
            val items = Store.reminders.value
                .filter { it.status != Status.DONE }
                .sortedWith(compareBy<Reminder>({ it.status.order() }, { it.nextFireAt ?: Long.MAX_VALUE }))
                .take(40)
                .map { r ->
                    WatchItem(
                        id = r.id,
                        text = r.text,
                        description = describe(r, now),
                        status = r.status,
                        sortKey = r.nextFireAt ?: Long.MAX_VALUE,
                    )
                }
            val state = WatchState(
                items = items,
                targets = Store.targets.value,
                parser = s.parser,
                confirmSeconds = s.confirmSeconds,
                snoozeA = s.snoozeA,
                snoozeB = s.snoozeB,
                updatedAt = now,
            )
            try {
                val req = PutDataRequest.create(Paths.STATE)
                    .setData(NudgeJson.encodeToString(WatchState.serializer(), state).encodeToByteArray())
                    .setUrgent()
                Wearable.getDataClient(app).putDataItem(req).await()
            } catch (e: Exception) {
                Log.d(TAG, "no watch: ${e.message}")
            }
        }
    }

    private fun Status.order() = when (this) { Status.FIRING -> 0; Status.SNOOZED -> 1; Status.WAITING -> 2; Status.DONE -> 3 }

    fun describe(r: Reminder, now: Long): String = when (r.status) {
        Status.SNOOZED -> "Sleeping · " + (r.nextFireAt?.let { Schedule.formatWhen(it, now) }
            ?: r.snoozeTrigger?.let { Schedule.describe(it, now) } ?: "")
        Status.FIRING -> "Now · " + Schedule.describe(r.trigger, now)
        else -> if (r.trigger is Trigger.Repeat && r.nextFireAt != null) {
            Schedule.formatWhen(r.nextFireAt!!, now) + " · " + Schedule.describeRepeat(r.trigger as Trigger.Repeat, withTime = false)
        } else Schedule.describe(r.trigger, now)
    }

    fun buzz(ctx: Context, r: Reminder) = message(ctx, Paths.BUZZ, "${r.id}\n${r.text}")

    fun quiet(ctx: Context, id: String) = message(ctx, Paths.QUIET, id)

    private fun message(ctx: Context, path: String, body: String) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(app).connectedNodes.await()
                nodes.forEach { Wearable.getMessageClient(app).sendMessage(it.id, path, body.encodeToByteArray()) }
            } catch (e: Exception) {
                Log.d(TAG, "message $path failed: ${e.message}")
            }
        }
    }
}

/** Receives captures and Done/Sleep taps from the watch. */
class PhoneListenerService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Engine.init(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val client = Wearable.getDataClient(this)
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED) continue
            val item = e.dataItem
            val path = item.uri.path ?: continue
            if (path.startsWith(Paths.AUDIO_PREFIX)) {
                handleQueuedAudio(item.freeze())
                client.deleteDataItems(item.uri)
                continue
            }
            if (!path.startsWith(Paths.CAPTURE_PREFIX)) continue
            val data = item.data ?: continue
            try {
                val c = NudgeJson.decodeFromString(WatchCapture.serializer(), data.decodeToString())
                if (c.learnAlias != null && c.learnTargetId != null) learnAlias(c.learnTargetId!!, c.learnAlias!!)
                Engine.create(
                    text = c.text,
                    trigger = c.trigger,
                    leadMinutes = c.leadMinutes,
                    transcript = c.transcript,
                    needsReview = c.needsReview,
                    id = c.id,
                )
            } catch (t: Throwable) {
                Log.e("NudgeWearSync", "bad capture", t)
            }
            client.deleteDataItems(item.uri)
        }
        Store.flush()
    }

    /**
     * The watch streams its mic here while you talk. When the stream ends we transcribe it and
     * send back what we understood for the watch's confirm screen.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(Paths.AUDIO_STREAM)) return
        val id = channel.path.substringAfterLast('/')
        val node = channel.nodeId
        val app = applicationContext
        Asr.warmUp(app)
        TriggerWatchService.sync(app)
        streamScope.launch {
            val channels = Wearable.getChannelClient(app)
            val messages = Wearable.getMessageClient(app)
            try {
                val input = channels.getInputStream(channel).await()
                val tx = PcmTranscriber(app)
                val buf = ByteArray(3200)
                input.use {
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        tx.feed(buf, n)
                    }
                }
                val t0 = System.currentTimeMillis()
                val heard = tx.finish() ?: ""
                val p = parse(heard, Engine.now())
                val draft = WatchDraft.from(id, p)
                messages.sendMessage(
                    node, Paths.RESULT, NudgeJson.encodeToString(WatchDraft.serializer(), draft).encodeToByteArray(),
                ).await()
                Log.i("NudgeWearSync", "watch audio done ${System.currentTimeMillis() - t0} ms: \"$heard\"")
            } catch (t: Throwable) {
                Log.e("NudgeWearSync", "audio stream failed", t)
            } finally {
                runCatching { channels.close(channel) }
            }
        }
    }

    /** A recording the watch couldn't stream live (phone was away): transcribe and save it now. */
    private fun handleQueuedAudio(item: com.google.android.gms.wearable.DataItem) {
        try {
            val map = DataMapItem.fromDataItem(item).dataMap
            val asset = map.getAsset("pcm") ?: return
            val at = map.getLong("at", Engine.now())
            val id = item.uri.path!!.substringAfterLast('/')
            if (Store.reminder(id) != null) return
            val pcm = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset)).inputStream.use { it.readBytes() }
            Asr.warmUp(this)
            val tx = PcmTranscriber(this)
            tx.feed(pcm)
            val heard = tx.finish()
            if (heard == null) {
                // Model missing: at least leave a trace so it isn't silently lost.
                Engine.create("Voice note from watch (couldn't transcribe)", Trigger.None, needsReview = true, id = id)
                return
            }
            if (heard.isBlank()) return
            val p = parse(heard, at)
            Engine.create(
                text = p.task.ifBlank { heard.lowercase() },
                trigger = p.trigger ?: Trigger.None,
                leadMinutes = p.leadMinutes,
                transcript = heard,
                needsReview = p.trigger == null,
                id = id,
            )
        } catch (t: Throwable) {
            Log.e("NudgeWearSync", "queued audio failed", t)
        }
    }

    private fun parse(heard: String, at: Long) =
        ReminderParser(Store.settings.value.parser).parse(heard, Store.targets.value, at)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Paths.ACTION) return
        try {
            val a = NudgeJson.decodeFromString(app.nudge.core.WatchAction.serializer(), event.data.decodeToString())
            when (a.action) {
                "done" -> Engine.done(a.id)
                "snooze" -> Engine.snooze(a.id, a.minutes)
            }
            Store.flush()
        } catch (t: Throwable) {
            Log.e("NudgeWearSync", "bad action", t)
        }
    }

    companion object {
        private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun learnAlias(targetId: String, alias: String) {
            val t = Store.target(targetId) ?: return
            val clean = alias.trim().lowercase()
            if (clean.isBlank() || t.spokenNames.any { it.equals(clean, ignoreCase = true) }) return
            Store.upsertTarget(t.copy(aliases = t.aliases + clean))
        }
    }
}
