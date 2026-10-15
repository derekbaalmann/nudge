package app.nudge.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.nudge.core.ReminderParser
import app.nudge.core.Schedule
import app.nudge.core.Trigger
import app.nudge.data.Store
import app.nudge.speech.Asr
import app.nudge.speech.VoiceCapture
import app.nudge.ui.Draft
import app.nudge.ui.NudgeTheme
import app.nudge.ui.ReminderEditor
import app.nudge.ui.icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Outlives the capture screen so a reminder said right before leaving still gets saved. */
private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * The record → confirm overlay. Opened by the widget, Quick Settings tile, app shortcut,
 * side-key launcher entry, or the big button in the app. Recording starts in onCreate,
 * before anything is drawn. While you talk there's only a waveform; after Stop the whole
 * recording is transcribed once and a confirm card saves itself unless you tap something.
 */
class CaptureActivity : ComponentActivity() {

    private enum class Phase { RECORDING, THINKING, CONFIRM, EDIT, EMPTY, TYPE, SAVED }

    private var phase by mutableStateOf(Phase.RECORDING)
    private var draft by mutableStateOf<Draft?>(null)
    private var savedLabel by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var capture by mutableStateOf<VoiceCapture?>(null)
    /** Saved or cancelled: leaving the screen does nothing more. */
    private var handled = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else { error = "Nudge needs the microphone to hear reminders."; phase = Phase.EMPTY }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Asr.warmUp(this)
        if (debugWav() != null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { NudgeTheme { Screen() } }
    }

    private fun startRecording() {
        capture?.cancel()
        error = null
        capture = VoiceCapture(this, debugWav()).also { it.start() }
        phase = Phase.RECORDING
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    /** Debug builds: `adb shell am start … --es test_wav name.wav` plays a file from app storage instead of the mic. */
    private fun debugWav(): java.io.File? {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val name = intent?.getStringExtra("test_wav") ?: return null
        return java.io.File(getExternalFilesDir(null), name)
    }

    private fun stopRecording() {
        val c = capture ?: return
        if (phase != Phase.RECORDING) return
        phase = Phase.THINKING
        val t0 = System.currentTimeMillis()
        lifecycleScope.launch {
            val heard = c.stop()
            Log.i("NudgeCapture", "result ${System.currentTimeMillis() - t0} ms after stop: \"$heard\"")
            error = c.error.value
            process(heard)
        }
    }

    private fun parse(text: String) =
        ReminderParser(Store.settings.value.parser).parse(text, Store.targets.value, System.currentTimeMillis())

    private fun process(text: String) {
        if (text.isBlank()) { phase = Phase.EMPTY; return }
        draft = Draft.from(parse(text))
        phase = Phase.CONFIRM
    }

    private fun save(fromEditor: Boolean = false) {
        if (handled) return
        val d = draft ?: return finish()
        handled = true
        // Saved without a when (from the card, unedited): flag it for the Inbox review.
        val r = d.commit(abandoned = !fromEditor && d.trigger == Trigger.None)
        savedLabel = r?.let { Schedule.describe(it.trigger, System.currentTimeMillis()) } ?: ""
        phase = Phase.SAVED
        lifecycleScope.launch { delay(550); finish() }
    }

    private fun cancel() {
        handled = true
        capture?.cancel()
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (handled || isChangingConfigurations) return
        handled = true
        // Never lose a thought: leaving the screen saves whatever we have.
        when (phase) {
            Phase.RECORDING, Phase.THINKING -> {
                val c = capture ?: return finish()
                saveScope.launch {
                    val text = c.stop()
                    if (text.isBlank()) return@launch
                    val p = parse(text)
                    Draft.from(p).commit(abandoned = p.trigger == null)
                }
            }
            Phase.CONFIRM -> draft?.let { it.commit(abandoned = it.trigger == Trigger.None) }
            Phase.EDIT -> draft?.commit(abandoned = true)
            else -> Unit
        }
        finish()
    }

    // ---- UI -----------------------------------------------------------------------------

    @Composable
    private fun Screen() {
        BackHandler { if (phase == Phase.CONFIRM || phase == Phase.EDIT) save(fromEditor = phase == Phase.EDIT) else cancel() }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, null) { if (phase == Phase.RECORDING) stopRecording() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Card(
                Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding().imePadding()
                    .heightIn(max = if (phase == Phase.EDIT) 720.dp else 520.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(8.dp),
            ) {
                when (phase) {
                    Phase.RECORDING -> Recording()
                    Phase.THINKING -> Thinking()
                    Phase.CONFIRM -> Confirm()
                    Phase.EDIT -> draft?.let { d ->
                        ReminderEditor(
                            draft = d,
                            onChange = { draft = it },
                            onSave = { save(fromEditor = true) },
                            onCancel = { cancel() },
                        )
                    }
                    Phase.EMPTY -> Empty()
                    Phase.TYPE -> Type()
                    Phase.SAVED -> Saved()
                }
            }
        }
    }

    @Composable
    private fun Recording() {
        Column(
            Modifier.fillMaxWidth().padding(24.dp)
                .clickable(remember { MutableInteractionSource() }, null) { stopRecording() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                Spacer(Modifier.width(8.dp))
                Text("Listening… say it all in one go", style = MaterialTheme.typography.titleMedium)
            }
            capture?.let { Waveform(it.level, MaterialTheme.colorScheme.error, Modifier.fillMaxWidth().height(96.dp)) }
            Button(
                onClick = { stopRecording() },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Done talking", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { cancel() }) { Text("Cancel") }
        }
    }

    @Composable
    private fun Thinking() {
        Column(
            Modifier.fillMaxWidth().padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text("Working on it…", style = MaterialTheme.typography.titleMedium)
        }
    }

    @Composable
    private fun Confirm() {
        val d = draft ?: return
        val seconds = Store.settings.collectAsState().value.confirmSeconds
        val progress = remember { Animatable(1f) }
        LaunchedEffect(d) {
            progress.snapTo(1f)
            progress.animateTo(0f, tween(seconds * 1000, easing = LinearEasing))
            save()
        }
        val missing = d.trigger == Trigger.None
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(d.text.ifBlank { "Reminder" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = if (missing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                Icon(if (missing) Icons.Outlined.HelpOutline else d.trigger.icon(), null, tint = color)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        when {
                            d.unresolved != null -> "Didn't recognize “${d.unresolved}”"
                            missing -> "No time or place heard"
                            else -> Schedule.describe(d.trigger, System.currentTimeMillis())
                        },
                        style = MaterialTheme.typography.titleMedium, color = color,
                    )
                    if (missing) Text("Goes to the Inbox unless you tap Edit", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (d.lead > 0) Text("Heads-up ${d.lead} min before", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { phase = Phase.EDIT }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Edit")
                }
                Button(onClick = { save() }, modifier = Modifier.weight(1.4f).height(56.dp)) {
                    Icon(Icons.Filled.Check, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Confirm")
                }
            }
            Text("Saves on its own in a moment", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }

    @Composable
    private fun Empty() {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(error ?: "Didn't catch that.", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { startRecording() }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Filled.Mic, null); Spacer(Modifier.width(6.dp)); Text("Try again")
                }
                OutlinedButton(onClick = { phase = Phase.TYPE }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Type it") }
            }
            TextButton(onClick = { cancel() }) { Text("Cancel") }
        }
    }

    @Composable
    private fun Type() {
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                text, { text = it }, label = { Text("e.g. pick up nails at 4pm tomorrow") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) process(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { cancel() }) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { process(text) }, enabled = text.isNotBlank()) { Text("Go") }
            }
        }
    }

    @Composable
    private fun Saved() {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("Saved", style = MaterialTheme.typography.titleLarge)
            if (savedLabel.isNotBlank()) Text(savedLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Scrolling bars of recent mic loudness: shows it's hearing you, without showing words. */
@Composable
fun Waveform(level: StateFlow<Float>, color: Color, modifier: Modifier = Modifier, bars: Int = 36) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    // Sampled on a clock rather than collected, so the bars keep moving through silence.
    LaunchedEffect(level) {
        while (true) {
            delay(50)
            history.removeAt(0)
            history.add(level.value)
        }
    }
    Canvas(modifier) {
        val gap = size.width / bars
        val w = gap * 0.55f
        history.forEachIndexed { i, v ->
            val h = (size.height * (0.06f + 0.94f * v)).coerceAtMost(size.height)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * (i + 1) / bars),
                topLeft = Offset(i * gap + (gap - w) / 2, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}
