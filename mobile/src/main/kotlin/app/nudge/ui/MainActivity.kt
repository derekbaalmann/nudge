package app.nudge.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nudge.core.LogEntry
import app.nudge.core.LogKind
import app.nudge.core.Reminder
import app.nudge.core.Schedule
import app.nudge.core.Status
import app.nudge.core.Trigger
import app.nudge.data.Store
import app.nudge.engine.Alarms
import app.nudge.engine.Engine
import app.nudge.engine.TriggerWatchService
import app.nudge.launch.captureIntent
import app.nudge.speech.Asr
import app.nudge.wear.WearSync
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REVIEW = "review"
        const val EXTRA_EDIT = "edit"
    }

    private var editing by mutableStateOf<Draft?>(null)
    private var reviewing by mutableStateOf(false)

    /** Ids picked in selection mode (long-press a reminder to start). Empty = not selecting. */
    private val selected = mutableStateListOf<String>()
    private val selecting get() = selected.isNotEmpty()

    private fun toggle(id: String) { if (id in selected) selected.remove(id) else selected.add(id) }

    /** Tap opens the editor, or adds/removes it while selecting; long-press starts selecting. */
    @OptIn(ExperimentalFoundationApi::class)
    private fun Modifier.pickable(r: Reminder): Modifier = combinedClickable(
        onClick = { if (selecting) toggle(r.id) else editing = Draft.from(r) },
        onLongClick = { toggle(r.id) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Asr.warmUp(this) // so the record button is instant
        enableEdgeToEdge()
        handle(intent)
        // Foreground is the one place Android reliably lets the watcher service start.
        TriggerWatchService.sync(this)
        Alarms.scheduleReview(this)
        WearSync.pushState(this)
        setContent { NudgeTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_REVIEW, false) == true) reviewing = true
        i?.getStringExtra(EXTRA_EDIT)?.let { id -> Store.reminder(id)?.let { editing = Draft.from(it) } }
    }

    private enum class Screen { HOME, SETTINGS }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        val settings by Store.settings.collectAsState()
        if (!settings.onboarded) { Onboarding(); return }

        BackHandler(screen != Screen.HOME) { screen = Screen.HOME }
        when (screen) {
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
            Screen.HOME -> Home(onSettings = { screen = Screen.SETTINGS })
        }

        editing?.let { d ->
            ModalBottomSheet(
                onDismissRequest = { editing = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                ReminderEditor(
                    draft = d,
                    onChange = { editing = it },
                    onSave = { d.commit(); editing = null },
                    onCancel = { editing = null },
                    onDelete = d.existingId?.let { id -> { Engine.delete(id); editing = null } },
                )
            }
        }
        if (reviewing) ModalBottomSheet(onDismissRequest = { reviewing = false }) { Review() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Home(onSettings: () -> Unit) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val reminders by Store.reminders.collectAsState()
        val inboxCount = reminders.count { it.isInbox }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val tabIds = when (tab) {
            0 -> reminders.filter { it.status != Status.DONE && !it.isInbox }.map { it.id }
            1 -> reminders.filter { it.isInbox }.map { it.id }
            else -> emptyList()
        }
        // Reminders can vanish (done from a notification); drop them from the selection.
        androidx.compose.runtime.LaunchedEffect(reminders) {
            val live = reminders.map { it.id }.toSet()
            if (selected.any { it !in live }) selected.retainAll(live)
        }

        fun bulk(verb: String, action: (String) -> Unit) {
            val snapshot = selected.mapNotNull { Store.reminder(it) }
            selected.clear()
            snapshot.forEach { action(it.id) }
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val n = snapshot.size
                val res = snackbar.showSnackbar("$verb $n reminder${if (n == 1) "" else "s"}", "Undo", duration = SnackbarDuration.Long)
                if (res == SnackbarResult.ActionPerformed) Engine.putBack(snapshot)
            }
        }

        BackHandler(selecting) { selected.clear() }
        Scaffold(
            topBar = {
                if (selecting) TopAppBar(
                    navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Filled.Close, "Stop selecting") } },
                    title = { Text("${selected.size} selected") },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    actions = {
                        IconButton(onClick = {
                            if (selected.size == tabIds.size) selected.clear() else { selected.clear(); selected.addAll(tabIds) }
                        }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                        IconButton(onClick = { bulk("Marked done:", Engine::done) }) { Icon(Icons.Outlined.DoneAll, "Mark done") }
                        IconButton(onClick = { bulk("Deleted", Engine::delete) }) { Icon(Icons.Outlined.Delete, "Delete") }
                    },
                ) else TopAppBar(
                    title = { Text("Nudge", fontWeight = FontWeight.Bold) },
                    actions = {
                        TextButton(onClick = { reviewing = true }) { Text("Review") }
                        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (!selecting) ExtendedFloatingActionButton(
                    onClick = { startActivity(captureIntent(this)) },
                    icon = { Icon(Icons.Filled.Mic, null, Modifier.size(28.dp)) },
                    text = { Text("Remember something", style = MaterialTheme.typography.titleMedium) },
                    modifier = Modifier.height(64.dp),
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0; selected.clear() }, text = { Text("Upcoming") })
                    Tab(selected = tab == 1, onClick = { tab = 1; selected.clear() }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Inbox")
                            if (inboxCount > 0) { Spacer(Modifier.width(6.dp)); Badge { Text("$inboxCount") } }
                        }
                    })
                    Tab(selected = tab == 2, onClick = { tab = 2; selected.clear() }, text = { Text("History") })
                }
                when (tab) {
                    0 -> Upcoming(reminders)
                    1 -> Inbox(reminders)
                    else -> History()
                }
            }
        }
    }

    // ---- Upcoming -----------------------------------------------------------------------

    @Composable
    private fun Upcoming(all: List<Reminder>) {
        val now = System.currentTimeMillis()
        val firing = all.filter { it.status == Status.FIRING }.sortedByDescending { it.lastFiredAt }
        val sleeping = all.filter { it.status == Status.SNOOZED }.sortedBy { it.nextFireAt ?: Long.MAX_VALUE }
        val waiting = all.filter { it.status == Status.WAITING && it.trigger != Trigger.None }
        val timed = waiting.filter { it.nextFireAt != null }.sortedBy { it.nextFireAt }
        val contextual = waiting.filter { it.nextFireAt == null }
        if (firing.isEmpty() && sleeping.isEmpty() && waiting.isEmpty()) {
            EmptyState("Nothing coming up.", "Tap the button and say something like\n“pick up nails at 4pm tomorrow”.")
            return
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (firing.isNotEmpty()) {
                item { SectionLabel("Needs you now") }
                items(firing, key = { it.id }) { FiringCard(it) }
            }
            if (sleeping.isNotEmpty()) {
                item { SectionLabel("Sleeping") }
                items(sleeping, key = { it.id }) { ReminderRow(it, WearSync.describe(it, now)) }
            }
            if (timed.isNotEmpty()) {
                item { SectionLabel("Coming up") }
                items(timed, key = { it.id }) { r ->
                    val line = Schedule.formatWhen(r.nextFireAt!!, now) +
                        if (r.trigger is Trigger.Repeat) " · " + Schedule.describeRepeat(r.trigger as Trigger.Repeat, withTime = false) else ""
                    ReminderRow(r, line)
                }
            }
            if (contextual.isNotEmpty()) {
                item { SectionLabel("When…") }
                items(contextual, key = { it.id }) { ReminderRow(it, Schedule.describe(it.trigger, now)) }
            }
        }
    }

    @Composable
    private fun SectionLabel(s: String) = Text(
        s, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )

    @Composable
    private fun FiringCard(r: Reminder) {
        val s = Store.settings.value
        val picked = r.id in selected
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier.fillMaxWidth().pickable(r),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) { SelectMark(picked); Spacer(Modifier.width(12.dp)) }
                    Text(r.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(Schedule.describe(r.trigger, System.currentTimeMillis()), style = MaterialTheme.typography.bodyMedium)
                if (!selecting) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { Engine.done(r.id) }) { Text("Done") }
                    TextButton(onClick = { Engine.snooze(r.id, s.snoozeA) }) { Text("Sleep ${s.snoozeA}m") }
                    TextButton(onClick = { Engine.snooze(r.id, s.snoozeB) }) {
                        Text(if (s.snoozeB % 60 == 0) "Sleep ${s.snoozeB / 60}h" else "Sleep ${s.snoozeB}m")
                    }
                }
            }
        }
    }

    @Composable
    private fun ReminderRow(r: Reminder, line: String) {
        val picked = r.id in selected
        Card(
            Modifier.fillMaxWidth().pickable(r),
            colors = if (picked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else CardDefaults.cardColors(),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) SelectMark(picked) else Icon(r.trigger.icon(), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = if (picked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // ---- Inbox --------------------------------------------------------------------------

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Inbox(all: List<Reminder>) {
        val items = all.filter { it.isInbox }.sortedByDescending { it.createdAt }
        if (items.isEmpty()) {
            EmptyState("Inbox is empty.", "Things you say without a when go here.\nThe evening review brings them back.")
            return
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { r -> InboxCard(r) }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun InboxCard(r: Reminder) {
        val s = Store.settings.value
        val zone = ZoneId.systemDefault()
        val picked = r.id in selected
        Card(
            Modifier.fillMaxWidth().pickable(r),
            colors = if (picked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else CardDefaults.cardColors(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) { SelectMark(picked); Spacer(Modifier.width(12.dp)) }
                    Text(r.text, style = MaterialTheme.typography.titleMedium)
                }
                if (r.needsReview) Text("Needs a when", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary)
                if (!selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = {
                        Engine.save(r.copy(trigger = Trigger.At(System.currentTimeMillis() + 3_600_000L)))
                    }, label = { Text("In 1 hr") })
                    AssistChip(onClick = {
                        Engine.save(r.copy(trigger = Trigger.At(Schedule.epochMillis(LocalDate.now(zone).plusDays(1), s.parser.defaultHour, 0, zone))))
                    }, label = { Text("Tomorrow") })
                    AssistChip(onClick = { editing = Draft.from(r) }, label = { Text("Pick…") })
                    AssistChip(onClick = { Engine.done(r.id) }, label = { Text("Done") })
                }
            }
        }
    }

    @Composable
    private fun SelectMark(picked: Boolean) = Icon(
        if (picked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
        if (picked) "Selected" else "Not selected",
        tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    )

    // ---- History ------------------------------------------------------------------------

    @Composable
    private fun History() {
        val log by Store.log.collectAsState()
        val zone = ZoneId.systemDefault()
        val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
        val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val entries = log.asReversed().take(500)
        if (entries.isEmpty()) { EmptyState("No history yet.", ""); return }
        val grouped = entries.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 110.dp)) {
            grouped.forEach { (day, list) ->
                item(key = "d$day") {
                    SectionLabel(if (day == LocalDate.now(zone)) "Today" else day.format(dayFmt))
                }
                items(list, key = { "${it.reminderId}-${it.at}-${it.kind}" }) { e -> HistoryRow(e, timeFmt, zone) }
            }
        }
    }

    @Composable
    private fun HistoryRow(e: LogEntry, timeFmt: DateTimeFormatter, zone: ZoneId) {
        val r = Store.reminder(e.reminderId)
        val verb = when (e.kind) {
            LogKind.CREATED -> "Added"
            LogKind.FIRED -> "Reminded"
            LogKind.SNOOZED -> "Slept"
            LogKind.DONE -> "Done"
            LogKind.DELETED -> "Deleted"
            LogKind.EDITED -> "Edited"
        }
        Row(
            Modifier.fillMaxWidth().clickable(enabled = r != null) { r?.let { editing = Draft.from(it) } }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Instant.ofEpochMilli(e.at).atZone(zone).format(timeFmt), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
            Column(Modifier.weight(1f)) {
                Text(e.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(verb + (e.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r?.status == Status.DONE && e.kind == LogKind.DONE) {
                TextButton(onClick = { Engine.restore(r.id) }) { Text("Undo") }
            }
        }
    }

    // ---- Review -------------------------------------------------------------------------

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Review() {
        val all by Store.reminders.collectAsState()
        val log by Store.log.collectAsState()
        val dayAgo = System.currentTimeMillis() - 24 * 3_600_000L
        val done = log.count { it.kind == LogKind.DONE && it.at > dayAgo }
        val fired = log.count { it.kind == LogKind.FIRED && it.at > dayAgo }
        val open = all.filter { it.status == Status.FIRING || it.status == Status.SNOOZED }
        val inbox = all.filter { it.isInbox }
        val s = Store.settings.value
        val zone = ZoneId.systemDefault()
        val tomorrow = Schedule.epochMillis(LocalDate.now(zone).plusDays(1), s.parser.defaultHour, 0, zone)
        LazyColumn(contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Today", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("$fired reminders went off · $done done", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open.isNotEmpty()) {
                item { SectionLabel("Still open") }
                items(open, key = { "o" + it.id }) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.text, style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(onClick = { Engine.done(r.id) }, label = { Text("Done") })
                                AssistChip(onClick = { Engine.snoozeUntil(r.id, Trigger.At(tomorrow), "tomorrow") }, label = { Text("Tomorrow") })
                                AssistChip(onClick = { Engine.delete(r.id) }, label = { Text("Drop it") })
                            }
                        }
                    }
                }
            }
            if (inbox.isNotEmpty()) {
                item { SectionLabel("Inbox: give each one a when") }
                items(inbox, key = { "i" + it.id }) { r -> InboxCard(r) }
            }
            if (open.isEmpty() && inbox.isEmpty()) item { Text("All clear. Nice.", style = MaterialTheme.typography.titleMedium) }
        }
    }

    @Composable
    private fun EmptyState(title: String, body: String) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
