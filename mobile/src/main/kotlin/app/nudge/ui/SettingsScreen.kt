package app.nudge.ui

import android.Manifest
import android.app.AlarmManager
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.nudge.R
import app.nudge.core.ParserConfig
import app.nudge.core.ReminderParser
import app.nudge.core.SavedTarget
import app.nudge.core.Schedule
import app.nudge.core.TargetKind
import app.nudge.data.Settings
import app.nudge.data.Store
import app.nudge.engine.Alarms
import app.nudge.engine.Engine
import app.nudge.launch.RecordTileService
import app.nudge.launch.RecordWidget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val s by Store.settings.collectAsState()
    fun set(f: (Settings) -> Settings) { Store.updateSettings(f); Engine.changed() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Get to it fast") { QuickLaunch() }
            Section("Places & connections") { TargetsList() }
            Section("Notifications") {
                ChipRow("First Sleep button", listOf(5, 10, 15, 20, 30), s.snoozeA, { "$it min" }) { v -> set { it.copy(snoozeA = v) } }
                ChipRow("Second Sleep button", listOf(30, 60, 120, 180), s.snoozeB, { if (it % 60 == 0) "${it / 60} hr" else "$it min" }) { v ->
                    set { it.copy(snoozeB = v) }
                }
                ChipRow("Nag every", listOf(2, 5, 10, 15), s.nagMinutes, { "$it min" }) { v -> set { it.copy(nagMinutes = v) } }
                SwitchRow("Nag new reminders by default", s.nagByDefault) { v -> set { it.copy(nagByDefault = v) } }
                SwitchRow("Strong buzz on the watch", s.watchBuzz) { v -> set { it.copy(watchBuzz = v) } }
            }
            Section("Capture") {
                val ctx = LocalContext.current
                val installed = app.nudge.speech.Asr.installed(ctx)
                Text(
                    if (installed) "Speech model: Parakeet 0.6B, on this phone"
                    else "Speech model missing. Run tools/install.ps1 on the PC with the phone plugged in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                SwitchRow("Keep speech ready (instant start, ~200 MB memory)", s.keepReady) { v ->
                    set { it.copy(keepReady = v) }
                }
                ChipRow("Confirm card auto-saves after", listOf(3, 5, 8, 10), s.confirmSeconds, { "$it s" }) { v ->
                    set { it.copy(confirmSeconds = v) }
                }
            }
            Section("Daily review") {
                SwitchRow("Evening review notification", s.reviewEnabled) { v ->
                    set { it.copy(reviewEnabled = v) }
                }
                var pick by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { pick = true }, enabled = s.reviewEnabled) {
                    Text("At ${Schedule.formatTime(s.reviewHour, s.reviewMinute)}")
                }
                if (pick) TimeDialog(s.reviewHour, s.reviewMinute, onDismiss = { pick = false }) { h, m ->
                    pick = false
                    set { it.copy(reviewHour = h, reviewMinute = m) }
                }
            }
            Section("What times mean") {
                val p = s.parser
                fun setP(f: (ParserConfig) -> ParserConfig) = set { it.copy(parser = f(it.parser)) }
                HourStepper("“Morning”", p.morningHour) { v -> setP { it.copy(morningHour = v) } }
                HourStepper("“Afternoon”", p.afternoonHour) { v -> setP { it.copy(afternoonHour = v) } }
                HourStepper("“Evening”", p.eveningHour) { v -> setP { it.copy(eveningHour = v) } }
                HourStepper("“Tonight”", p.nightHour) { v -> setP { it.copy(nightHour = v) } }
                HourStepper("A day with no time", p.defaultHour) { v -> setP { it.copy(defaultHour = v) } }
            }
            Section("Try the parser") { ParserPlayground() }
            Section("Permissions") { PermissionList() }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, options: List<Int>, value: Int, fmt: (Int) -> String, onPick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = value == o, onClick = { onPick(o) }, label = { Text(fmt(o)) }) }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange((hour + 23) % 24) }) { Text("−") }
        Text(Schedule.formatTime(hour, 0), Modifier.width(80.dp))
        TextButton(onClick = { onChange((hour + 1) % 24) }) { Text("+") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(h: Int, m: Int, onDismiss: () -> Unit, onPicked: (Int, Int) -> Unit) {
    val st = rememberTimePickerState(h, m, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPicked(st.hour, st.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(st) },
    )
}

@Composable
private fun QuickLaunch() {
    val ctx = LocalContext.current
    Text("Fastest ways to start recording:", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val mgr = ctx.getSystemService(AppWidgetManager::class.java)
            if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(ctx, RecordWidget::class.java), null, null)
        }) { Text("Add widget") }
        OutlinedButton(onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(ctx, RecordTileService::class.java), "Nudge",
                    AndroidIcon.createWithResource(ctx, R.drawable.ic_mic), ctx.mainExecutor,
                ) { }
            }
        }) { Text("Add Quick Settings tile") }
    }
    Text(
        "• Side key: Settings › Advanced features › Side button › Double press › Open app › “Nudge Record”.\n" +
            "• Watch: Settings › Advanced features › Customize buttons › Home key double press › “Nudge Record”, " +
            "or add the Nudge tile and swipe to it.\n" +
            "• Long-press the Nudge app icon for a “Record” shortcut.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TargetsList() {
    val targets by Store.targets.collectAsState()
    var editing by remember { mutableStateOf<SavedTarget?>(null) }
    Text(
        "Give each one every name you might say. Example: a car stereo called SYNC with “van, work truck, work vehicle”.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    targets.forEach { t ->
        Row(
            Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(t.kind.icon(), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.label, style = MaterialTheme.typography.bodyLarge)
                val sub = buildList {
                    if (t.kind != TargetKind.PLACE && t.value != t.label) add(t.value)
                    if (t.aliases.isNotEmpty()) add("also: " + t.aliases.joinToString(", "))
                }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
    }
    Button(onClick = { editing = SavedTarget(id = "", kind = TargetKind.BLUETOOTH, label = "") }) { Text("Add") }
    editing?.let { t ->
        TargetEditorDialog(
            initial = t,
            onDismiss = { editing = null },
            onSaved = { editing = null },
            onDelete = if (t.id.isEmpty()) null else ({ Store.removeTarget(t.id); Engine.changed(); editing = null }),
        )
    }
}

@Composable
private fun ParserPlayground() {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(text, { text = it }, label = { Text("Type what you'd say") }, modifier = Modifier.fillMaxWidth())
    if (text.isNotBlank()) {
        val r = ReminderParser(Store.settings.value.parser).parse(text, Store.targets.value, System.currentTimeMillis())
        Text("Task: ${r.task}")
        Text("When: " + (r.trigger?.let { Schedule.describe(it, System.currentTimeMillis()) }
            ?: r.unresolvedPhrase?.let { "? don't know “$it” yet" } ?: "nothing (goes to Inbox)"))
        if (r.leadMinutes > 0) Text("Heads-up: ${r.leadMinutes} min before")
    }
}

// ---- Permissions -----------------------------------------------------------------------

private data class Perm(val title: String, val why: String, val granted: Boolean, val ask: () -> Unit)

@Composable
fun PermissionList() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; Engine.changed(); onPauseOrDispose { } }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++; Engine.changed() }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; Engine.changed() }

    @Suppress("UNUSED_EXPRESSION") refresh
    fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val pm = ctx.getSystemService(PowerManager::class.java)
    val perms = listOfNotNull(
        Perm("Microphone", "To hear reminders", has(Manifest.permission.RECORD_AUDIO)) {
            single.launch(Manifest.permission.RECORD_AUDIO)
        },
        if (android.os.Build.VERSION.SDK_INT >= 33) Perm("Notifications", "To remind you", has(Manifest.permission.POST_NOTIFICATIONS)) {
            single.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else null,
        Perm("Exact alarms", "So reminders fire on the minute", ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        },
        Perm("Location", "For place reminders and reading the wifi name", has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            multi.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        Perm("Location: Allow all the time", "Place and wifi reminders work with the phone in your pocket",
            has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                multi.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        },
        Perm("Nearby devices", "To know when you connect to the van (bluetooth)", has(Manifest.permission.BLUETOOTH_CONNECT)) {
            single.launch(Manifest.permission.BLUETOOTH_CONNECT)
        },
        Perm("Battery: Unrestricted", "Samsung puts apps to sleep; this stops it", pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        },
    )
    perms.forEach { p ->
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !p.granted) { p.ask() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (p.granted) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                tint = if (p.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.bodyLarge)
                Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!p.granted) TextButton(onClick = p.ask) { Text("Allow") }
        }
    }
}

@Composable
fun Onboarding() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Nudge", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Say it, and it gets remembered. Everything stays on your phone; speech is understood offline.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Allow these once (microphone and notifications are the must-haves):", style = MaterialTheme.typography.titleSmall)
        PermissionList()
        Button(
            onClick = { Store.updateSettings { it.copy(onboarded = true) }; Alarms.scheduleReview(ctx) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Let's go") }
    }
}
