package app.nudge.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nudge.core.Edge
import app.nudge.core.Schedule
import app.nudge.core.TargetKind
import app.nudge.core.Trigger
import app.nudge.data.Store
import app.nudge.engine.Alarms
import app.nudge.engine.Engine
import java.time.LocalDate
import java.time.ZoneId

/** Opened by tapping a reminder notification: every way to put it off, plus Done. */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(Alarms.EXTRA_ID)
        val r = id?.let { Store.reminder(it) }
        if (r == null) { finish(); return }
        setShowWhenLocked(true)
        setContent { NudgeTheme { Sheet(r.id) } }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Sheet(id: String) {
        val r = Store.reminder(id) ?: return
        val s = Store.settings.value
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun sleep(min: Int) { Engine.snooze(id, min); finish() }
        fun until(t: Trigger, label: String? = null) { Engine.snoozeUntil(id, t, label); finish() }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .clickable(remember { MutableInteractionSource() }, null) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Card(
                Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()
                    .clickable(remember { MutableInteractionSource() }, null) { },
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(r.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(Schedule.describe(r.trigger, now), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { Engine.done(id); finish() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Filled.Check, null, Modifier.size(20.dp)); Text("  Done")
                    }
                    Text("Sleep for", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 30, 60, 120, 240).forEach { m ->
                            AssistChip(onClick = { sleep(m) }, label = { Text(if (m < 60) "$m min" else "${m / 60} hr") })
                        }
                    }
                    Text("Until", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tonight = Schedule.epochMillis(today, s.parser.nightHour, 0, zone)
                        if (tonight > now) AssistChip(onClick = { until(Trigger.At(tonight), "tonight") }, label = { Text("Tonight") })
                        AssistChip(
                            onClick = { until(Trigger.At(Schedule.epochMillis(today.plusDays(1), s.parser.defaultHour, 0, zone)), "tomorrow") },
                            label = { Text("Tomorrow ${Schedule.formatTime(s.parser.defaultHour, 0)}") },
                        )
                        Store.targets.value.forEach { t ->
                            val (edge, verb) = when (t.kind) {
                                TargetKind.PLACE -> Edge.ENTER to "I get to"
                                else -> Edge.ENTER to "I connect to"
                            }
                            AssistChip(
                                onClick = { until(Trigger.Target(t.id, t.label, t.kind, edge)) },
                                label = { Text("$verb ${t.label}") },
                                leadingIcon = { Icon(t.kind.icon(), null, Modifier.size(18.dp)) },
                            )
                        }
                        AssistChip(onClick = { until(Trigger.Power(Edge.ENTER)) }, label = { Text("I plug in") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            startActivity(Intent(this@SnoozeActivity, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_EDIT, id))
                            finish()
                        }) { Text("Edit") }
                        OutlinedButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
