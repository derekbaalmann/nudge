package app.nudge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nudge.core.Edge
import app.nudge.core.ParseResult
import app.nudge.core.Reminder
import app.nudge.core.RepeatRule
import app.nudge.core.RepeatUnit
import app.nudge.core.SavedTarget
import app.nudge.core.Schedule
import app.nudge.core.TargetKind
import app.nudge.core.Trigger
import app.nudge.data.Store
import app.nudge.engine.Engine
import app.nudge.wear.PhoneListenerService
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** A reminder being created or edited. */
data class Draft(
    val existingId: String? = null,
    val text: String,
    val trigger: Trigger,
    val lead: Int = 0,
    val nag: Boolean = true,
    val transcript: String? = null,
    /** "post office" heard but not known; choosing a target learns it as an alias. */
    val unresolved: String? = null,
    val unresolvedEdge: Edge? = null,
    val learnTargetId: String? = null,
) {
    val hasTrigger get() = trigger != Trigger.None

    /** Saves it. [abandoned] marks a capture the user walked away from mid-edit. */
    fun commit(abandoned: Boolean = false): Reminder? {
        if (text.isBlank() && trigger == Trigger.None) return null
        if (learnTargetId != null && unresolved != null) PhoneListenerService.learnAlias(learnTargetId, unresolved)
        val existing = existingId?.let { Store.reminder(it) }
        return if (existing != null) {
            val r = existing.copy(text = text.trim(), trigger = trigger, leadMinutes = lead, nag = nag)
            Engine.save(r); r
        } else {
            Engine.create(
                text = text.trim(),
                trigger = trigger,
                leadMinutes = lead,
                transcript = transcript,
                needsReview = abandoned && trigger == Trigger.None,
                nag = nag,
            )
        }
    }

    companion object {
        fun from(p: ParseResult) = Draft(
            text = p.task,
            trigger = p.trigger ?: Trigger.None,
            lead = p.leadMinutes,
            nag = Store.settings.value.nagByDefault,
            transcript = p.transcript,
            unresolved = p.unresolvedPhrase,
            unresolvedEdge = p.unresolvedEdge,
        )

        fun from(r: Reminder) = Draft(
            existingId = r.id, text = r.text, trigger = r.trigger, lead = r.leadMinutes,
            nag = r.nag, transcript = r.transcript,
        )
    }
}

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun today() = LocalDate.now(zone)
private fun now() = System.currentTimeMillis()

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditor(
    draft: Draft,
    onChange: (Draft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val targets by Store.targets.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var repeating by remember { mutableStateOf(false) }
    var newTarget by remember { mutableStateOf<SavedTarget?>(null) }
    val s = Store.settings.collectAsState().value

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = draft.text,
            onValueChange = { onChange(draft.copy(text = it)) },
            label = { Text("Reminder") },
            textStyle = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        draft.transcript?.takeIf { it.isNotBlank() }?.let {
            Text("Heard: “${it.lowercase()}”", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The parser heard a name it doesn't know.
        if (draft.unresolved != null && draft.learnTargetId == null && draft.trigger == Trigger.None) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You said “${draft.unresolved}”. Which one is that?", fontWeight = FontWeight.SemiBold)
                    Text("I'll remember it for next time.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targets.forEach { t ->
                            AssistChip(
                                onClick = {
                                    onChange(draft.copy(
                                        trigger = Trigger.Target(t.id, t.label, t.kind, draft.unresolvedEdge ?: Edge.ENTER),
                                        learnTargetId = t.id,
                                    ))
                                },
                                label = { Text(t.label) },
                                leadingIcon = { Icon(t.kind.icon(), null, Modifier.size(18.dp)) },
                            )
                        }
                        AssistChip(
                            onClick = {
                                newTarget = SavedTarget(id = "", kind = TargetKind.PLACE,
                                    label = draft.unresolved.replaceFirstChar { it.uppercase() })
                            },
                            label = { Text("New…") },
                            leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }

        // Current trigger.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(draft.trigger.icon(), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                Schedule.describe(draft.trigger, now()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text("When", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun set(t: Trigger) = onChange(draft.copy(trigger = t))
            QuickChip("In ${s.snoozeA} min") { set(Trigger.At(now() + s.snoozeA * 60_000L)) }
            QuickChip("In 1 hour") { set(Trigger.At(now() + 3_600_000L)) }
            QuickChip("Tonight") {
                val ms = Schedule.epochMillis(today(), s.parser.nightHour, 0)
                set(Trigger.At(if (ms > now()) ms else now() + 3_600_000L))
            }
            QuickChip("Tomorrow ${Schedule.formatTime(s.parser.defaultHour, 0)}") {
                set(Trigger.At(Schedule.epochMillis(today().plusDays(1), s.parser.defaultHour, 0)))
            }
            QuickChip("This weekend") {
                val sat = today().with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SATURDAY))
                set(Trigger.At(Schedule.epochMillis(sat, s.parser.defaultHour, 0)))
            }
            AssistChip(onClick = { picking = true }, label = { Text("Pick…") },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)) })
            AssistChip(onClick = { repeating = true }, label = { Text("Repeat…") },
                leadingIcon = { Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp)) })
            FilterChip(selected = draft.trigger == Trigger.Power(Edge.ENTER), onClick = { set(Trigger.Power(Edge.ENTER)) },
                label = { Text("Plug in") }, leadingIcon = { Icon(Icons.Outlined.BatteryChargingFull, null, Modifier.size(18.dp)) })
            FilterChip(selected = draft.trigger == Trigger.None, onClick = { set(Trigger.None) },
                label = { Text("Inbox") }, leadingIcon = { Icon(Icons.Outlined.Inbox, null, Modifier.size(18.dp)) })
        }

        Text("Where / connected to", style = MaterialTheme.typography.labelLarge)
        val current = draft.trigger as? Trigger.Target
        val edge = current?.edge ?: draft.unresolvedEdge ?: Edge.ENTER
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            targets.forEach { t ->
                FilterChip(
                    selected = current?.targetId == t.id,
                    onClick = { onChange(draft.copy(trigger = Trigger.Target(t.id, t.label, t.kind, edge))) },
                    label = { Text(t.label) },
                    leadingIcon = { Icon(t.kind.icon(), null, Modifier.size(18.dp)) },
                )
            }
            AssistChip(
                onClick = { newTarget = SavedTarget(id = "", kind = TargetKind.PLACE, label = "") },
                label = { Text("Add place / wifi / device") },
                leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) },
            )
        }
        if (current != null || draft.trigger is Trigger.Power) {
            val isEnter = current?.edge == Edge.ENTER || (draft.trigger as? Trigger.Power)?.edge == Edge.ENTER
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = isEnter, onClick = {
                    onChange(draft.copy(trigger = withEdge(draft.trigger, Edge.ENTER)))
                }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(enterLabel(draft.trigger)) }
                SegmentedButton(selected = !isEnter, onClick = {
                    onChange(draft.copy(trigger = withEdge(draft.trigger, Edge.EXIT)))
                }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(exitLabel(draft.trigger)) }
            }
        }

        if (draft.trigger is Trigger.At || draft.trigger is Trigger.Repeat) {
            Text("Heads-up before", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 30, 60).forEach { m ->
                    FilterChip(selected = draft.lead == m, onClick = { onChange(draft.copy(lead = m)) },
                        label = { Text(if (m == 0) "None" else if (m == 60) "1 hour" else "$m min") })
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep nagging until done")
                Text("Re-alerts every ${s.nagMinutes} min", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = draft.nag, onCheckedChange = { onChange(draft.copy(nag = it)) })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onSave, enabled = draft.text.isNotBlank()) { Text("Save") }
        }
    }

    if (picking) DateTimePicker(
        initial = (draft.trigger as? Trigger.At)?.epochMillis,
        onDismiss = { picking = false },
        onPicked = { picking = false; onChange(draft.copy(trigger = Trigger.At(it))) },
    )
    if (repeating) RepeatDialog(
        initial = draft.trigger as? Trigger.Repeat,
        onDismiss = { repeating = false },
        onPicked = { repeating = false; onChange(draft.copy(trigger = it)) },
    )
    newTarget?.let { t ->
        TargetEditorDialog(
            initial = t,
            onDismiss = { newTarget = null },
            onSaved = { saved ->
                newTarget = null
                onChange(draft.copy(
                    trigger = Trigger.Target(saved.id, saved.label, saved.kind, draft.unresolvedEdge ?: Edge.ENTER),
                    learnTargetId = if (draft.unresolved != null) saved.id else null,
                ))
            },
        )
    }
}

private fun withEdge(t: Trigger, e: Edge): Trigger = when (t) {
    is Trigger.Target -> t.copy(edge = e)
    is Trigger.Power -> Trigger.Power(e)
    else -> t
}

private fun enterLabel(t: Trigger) = when ((t as? Trigger.Target)?.kind) {
    TargetKind.PLACE -> "Arrive"
    TargetKind.WIFI, TargetKind.BLUETOOTH -> "Connect"
    null -> "Plug in"
}

private fun exitLabel(t: Trigger) = when ((t as? Trigger.Target)?.kind) {
    TargetKind.PLACE -> "Leave"
    TargetKind.WIFI, TargetKind.BLUETOOTH -> "Disconnect"
    null -> "Unplug"
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) = AssistChip(onClick = onClick, label = { Text(label) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePicker(initial: Long?, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val start = Instant.ofEpochMilli(initial ?: (now() + 3_600_000L)).atZone(zone)
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(start.hour, (start.minute / 5) * 5, is24Hour = false)
    if (date == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    date = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        ?: today()
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { onPicked(Schedule.epochMillis(date!!, timeState.hour, timeState.minute)) }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            text = { TimePicker(timeState) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepeatDialog(initial: Trigger.Repeat?, onDismiss: () -> Unit, onPicked: (Trigger.Repeat) -> Unit) {
    val s = Store.settings.value
    var unit by remember { mutableStateOf(initial?.rule?.unit ?: RepeatUnit.DAY) }
    var interval by remember { mutableStateOf(initial?.rule?.interval ?: 1) }
    val days = remember { mutableStateListOf<Int>().apply { addAll(initial?.rule?.daysOfWeek ?: listOf(today().dayOfWeek.value)) } }
    var dom by remember { mutableStateOf(initial?.rule?.dayOfMonth ?: today().dayOfMonth) }
    val time = rememberTimePickerState(initial?.hour ?: s.parser.defaultHour, initial?.minute ?: 0, is24Hour = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(RepeatUnit.HOUR to "Hours", RepeatUnit.DAY to "Days", RepeatUnit.WEEK to "Weeks",
                        RepeatUnit.MONTH to "Months", RepeatUnit.YEAR to "Years").forEach { (u, l) ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(l) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every")
                    Spacer(Modifier.width(8.dp))
                    listOf(1, 2, 3, 4).forEach { n ->
                        FilterChip(selected = interval == n, onClick = { interval = n }, label = { Text("$n") },
                            modifier = Modifier.padding(end = 4.dp))
                    }
                }
                if (unit == RepeatUnit.WEEK) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = d in days,
                            onClick = { if (d in days) { if (days.size > 1) days.remove(d) } else days.add(d) },
                            label = { Text(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.US)) },
                        )
                    }
                }
                if (unit == RepeatUnit.MONTH) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On day")
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = if (dom == -1) "last" else dom.toString(),
                        onValueChange = { v -> dom = if (v.startsWith("l")) -1 else v.toIntOrNull()?.coerceIn(1, 31) ?: dom },
                        singleLine = true, modifier = Modifier.width(90.dp),
                    )
                }
                TimePicker(time)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rule = RepeatRule(
                    unit = unit,
                    interval = interval,
                    daysOfWeek = if (unit == RepeatUnit.WEEK) days.sorted() else emptyList(),
                    dayOfMonth = if (unit == RepeatUnit.MONTH) dom else null,
                )
                onPicked(Trigger.Repeat(rule, time.hour, time.minute, today().toEpochDay()))
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
