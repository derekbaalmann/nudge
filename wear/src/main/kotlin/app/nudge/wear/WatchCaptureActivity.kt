package app.nudge.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import app.nudge.core.Edge
import app.nudge.core.NudgeJson
import app.nudge.core.Paths
import app.nudge.core.WatchDraft
import app.nudge.core.Schedule
import app.nudge.core.TargetKind
import app.nudge.core.Trigger
import app.nudge.core.WatchCapture
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val BYTES_PER_SECOND = WatchRecorder.RATE * 2

val Red = Color(0xFFE5484D)
val Accent = Color(0xFFFFB86B)

/**
 * Watch capture. Recording starts the instant this opens (tile, complication, button
 * double-press) and streams live to the phone, which transcribes with its full model and
 * sends the words back as you speak. On stop the phone replies with what it understood
 * and the watch shows a 5-second confirm that saves itself. If the phone can't be reached,
 * the recording is queued and the phone handles it when it's back.
 */
class WatchCaptureActivity : ComponentActivity() {

    private enum class Phase { RECORDING, THINKING, CONFIRM, PICK, EMPTY, SAVED }

    private var phase by mutableStateOf(Phase.RECORDING)
    private var draft by mutableStateOf<WatchDraft?>(null)
    private var text by mutableStateOf("")
    private var trigger by mutableStateOf<Trigger>(Trigger.None)
    private var queued by mutableStateOf(false)
    private var noMic by mutableStateOf(false)
    private var recorder by mutableStateOf<WatchRecorder?>(null)
    private var captureId = UUID.randomUUID().toString()
    private var startedAt = System.currentTimeMillis()
    private var result = CompletableDeferred<WatchDraft>()
    private var handled = false

    private val listener = MessageClient.OnMessageReceivedListener { e ->
        when (e.path) {
            Paths.RESULT -> runCatching {
                val d = NudgeJson.decodeFromString(WatchDraft.serializer(), e.data.decodeToString())
                if (d.id == captureId) result.complete(d)
            }
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else { noMic = true; phase = Phase.EMPTY }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Wearable.getMessageClient(this).addListener(listener)
        if (debugWav() != null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WatchData.load(this)
        lifecycleScope.launch { WatchData.refresh(this@WatchCaptureActivity) }
        setContent { MaterialTheme { Screen() } }
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(listener)
        super.onDestroy()
    }

    private fun startRecording() {
        recorder?.cancel()
        captureId = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        result = CompletableDeferred()
        handled = false
        val rec = WatchRecorder(this, debugWav())
        recorder = rec
        rec.start()
        phase = Phase.RECORDING
        runCatching { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
        // Meanwhile, open the audio pipe to the phone; everything recorded so far goes first.
        val id = captureId
        lifecycleScope.launch {
            try {
                val node = WatchData.phoneNode(this@WatchCaptureActivity) ?: return@launch
                val channels = Wearable.getChannelClient(this@WatchCaptureActivity)
                val ch = channels.openChannel(node, "${Paths.AUDIO_STREAM}/$id").await()
                if (recorder === rec) rec.attach(channels.getOutputStream(ch).await())
            } catch (t: Throwable) {
                Log.w("NudgeCapture", "no live link to phone: ${t.message}")
            }
        }
    }

    /** Debug builds: `--es test_wav name.wav` plays a file from app storage instead of the mic. */
    private fun debugWav(): java.io.File? {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val name = intent?.getStringExtra("test_wav") ?: return null
        return java.io.File(getExternalFilesDir(null), name)
    }

    private fun stop() {
        val rec = recorder ?: return
        if (phase != Phase.RECORDING) return
        phase = Phase.THINKING
        val t0 = System.currentTimeMillis()
        lifecycleScope.launch {
            val audio = rec.stop()
            if (audio.size < BYTES_PER_SECOND / 4) { phase = Phase.EMPTY; return@launch } // under a quarter second
            val d = if (rec.streamedOk()) withTimeoutOrNull(8_000) { result.await() } else null
            Log.i("NudgeCapture", "phone answered ${System.currentTimeMillis() - t0} ms after stop: $d")
            if (d == null) { queue(audio); return@launch }
            if (d.transcript.isBlank()) { phase = Phase.EMPTY; return@launch }
            draft = d
            text = d.task
            trigger = d.trigger ?: Trigger.None
            phase = Phase.CONFIRM // always: it saves itself, and Edit is there if it's wrong
        }
    }

    /** No answer from the phone: hand it the recording to deal with later. */
    private fun queue(audio: ByteArray) {
        handled = true
        val app = applicationContext
        val id = captureId
        val at = startedAt
        sendScope.launch { runCatching { WatchData.sendAudio(app, id, audio, at) } }
        queued = true
        phase = Phase.SAVED
        lifecycleScope.launch { delay(1500); finish() }
    }

    private fun save(t: Trigger = trigger, learnTargetId: String? = null, abandoned: Boolean = false) {
        if (handled) return
        handled = true
        val d = draft ?: return finish()
        val capture = WatchCapture(
            id = d.id,
            text = text.ifBlank { d.transcript.lowercase() },
            trigger = t,
            leadMinutes = d.leadMinutes,
            transcript = d.transcript,
            needsReview = abandoned && t == Trigger.None,
            learnAlias = if (learnTargetId != null) d.unresolvedPhrase else null,
            learnTargetId = learnTargetId,
            createdAt = startedAt,
        )
        sendScope.launch { runCatching { WatchData.send(applicationContext, capture) } }
        trigger = t
        phase = Phase.SAVED
        lifecycleScope.launch { delay(600); finish() }
    }

    override fun onStop() {
        super.onStop()
        if (handled || isChangingConfigurations) return
        // Wrist down or swiped away: keep what was said.
        when (phase) {
            Phase.RECORDING, Phase.THINKING -> {
                handled = true
                val rec = recorder ?: return finish()
                val app = applicationContext
                val id = captureId
                val at = startedAt
                sendScope.launch {
                    val audio = rec.stop()
                    if (audio.size >= BYTES_PER_SECOND / 4) WatchData.sendAudio(app, id, audio, at)
                }
            }
            Phase.CONFIRM -> save(abandoned = trigger == Trigger.None)
            Phase.PICK -> save(abandoned = true)
            else -> Unit
        }
        finish()
    }

    // ---- UI -----------------------------------------------------------------------------

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            when (phase) {
                Phase.RECORDING -> Recording()
                Phase.THINKING -> Thinking()
                Phase.CONFIRM -> Confirm()
                Phase.PICK -> Pick()
                Phase.EMPTY -> Empty()
                Phase.SAVED -> Saved()
            }
        }
    }

    @Composable
    private fun Recording() {
        Column(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { stop() }.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Listening…", style = MaterialTheme.typography.title3, color = Color.White)
            Spacer(Modifier.height(12.dp))
            recorder?.let { Waveform(it.level, Red, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(64.dp)) }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(52.dp).clip(CircleShape).background(Red), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Stop, "Stop", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text("Tap to stop", style = MaterialTheme.typography.caption2, color = Color.Gray)
        }
    }

    @Composable
    private fun Thinking() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(indicatorColor = Accent)
            Spacer(Modifier.height(10.dp))
            Text("Working on it…", color = Color.White)
        }
    }

    @Composable
    private fun Confirm() {
        val seconds = WatchData.state.collectAsState().value.confirmSeconds
        val progress = remember { Animatable(1f) }
        LaunchedEffect(Unit) {
            progress.animateTo(0f, tween(seconds * 1000, easing = LinearEasing))
            save(abandoned = trigger == Trigger.None)
        }
        val d = draft
        val line = when {
            d?.unresolvedPhrase != null && trigger == Trigger.None -> "Didn't recognize “${d.unresolvedPhrase}” · goes to Inbox"
            trigger == Trigger.None -> "No time or place heard · goes to Inbox"
            else -> Schedule.describe(trigger, System.currentTimeMillis())
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = progress.value, modifier = Modifier.fillMaxSize().padding(2.dp),
                indicatorColor = Accent, trackColor = Color.DarkGray, strokeWidth = 5.dp,
            )
            Column(
                Modifier.padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(line, color = Accent, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { phase = Phase.PICK },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                    ) { Icon(Icons.Filled.Edit, "Edit") }
                    Button(
                        onClick = { save(abandoned = trigger == Trigger.None) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Accent),
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                    ) { Icon(Icons.Filled.Check, "Confirm", tint = Color.Black) }
                }
            }
        }
    }

    @Composable
    private fun Pick() {
        val s = WatchData.state.collectAsState().value
        val p = draft
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                ListHeader {
                    Text(text.ifBlank { "Reminder" }, textAlign = TextAlign.Center, maxLines = 2, color = Color.White)
                }
            }
            if (p?.unresolvedPhrase != null) {
                item { Text("“${p.unresolvedPhrase}” is…", color = Accent, fontSize = 13.sp) }
                s.targets.forEach { t ->
                    item {
                        PickChip(t.label) { save(Trigger.Target(t.id, t.label, t.kind, p.unresolvedEdge ?: Edge.ENTER), learnTargetId = t.id) }
                    }
                }
            }
            item { PickChip("In ${s.snoozeA} min") { save(Trigger.At(now + s.snoozeA * 60_000L)) } }
            item { PickChip("In 1 hour") { save(Trigger.At(now + 3_600_000L)) } }
            item {
                val tonight = Schedule.epochMillis(today, s.parser.nightHour, 0, zone)
                PickChip("Tonight") { save(Trigger.At(if (tonight > now) tonight else now + 3_600_000L)) }
            }
            item {
                PickChip("Tomorrow ${Schedule.formatTime(s.parser.defaultHour, 0)}") {
                    save(Trigger.At(Schedule.epochMillis(today.plusDays(1), s.parser.defaultHour, 0, zone)))
                }
            }
            if (p?.unresolvedPhrase == null) s.targets.forEach { t ->
                item {
                    val verb = if (t.kind == TargetKind.PLACE) "At" else "Connect"
                    PickChip("$verb ${t.label}") { save(Trigger.Target(t.id, t.label, t.kind, Edge.ENTER)) }
                }
            }
            item { PickChip("Inbox (decide later)") { save(Trigger.None) } }
            item { PickChip("Say it again", secondary = true) { startRecording() } }
            item { PickChip("Cancel", secondary = true) { handled = true; finish() } }
        }
    }

    @Composable
    private fun PickChip(label: String, secondary: Boolean = false, onClick: () -> Unit) {
        Chip(
            onClick = onClick,
            label = { Text(label, maxLines = 2) },
            colors = if (secondary) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF3A2E22)),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Empty() {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
            Text(
                if (noMic) "Allow the microphone to record" else "Didn't catch that",
                textAlign = TextAlign.Center, color = Color.White,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { startRecording() }, colors = ButtonDefaults.buttonColors(backgroundColor = Red)) {
                Icon(Icons.Filled.Mic, "Try again", tint = Color.White)
            }
        }
    }

    @Composable
    private fun Saved() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (queued) "Saved. Your phone will sort it out when it's back in range."
                else Schedule.describe(trigger, System.currentTimeMillis()),
                color = Color.White, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** Scrolling bars of recent mic loudness: shows it's hearing you, without showing words. */
@Composable
fun Waveform(level: kotlinx.coroutines.flow.StateFlow<Float>, color: Color, modifier: Modifier = Modifier, bars: Int = 24) {
    val history = remember { androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    // Sampled on a clock rather than collected, so the bars keep moving through silence.
    LaunchedEffect(level) {
        while (true) {
            delay(50)
            history.removeAt(0)
            history.add(level.value)
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val gap = size.width / bars
        val w = gap * 0.55f
        history.forEachIndexed { i, v ->
            val h = (size.height * (0.08f + 0.92f * v)).coerceAtMost(size.height)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * (i + 1) / bars),
                topLeft = androidx.compose.ui.geometry.Offset(i * gap + (gap - w) / 2, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
            )
        }
    }
}
