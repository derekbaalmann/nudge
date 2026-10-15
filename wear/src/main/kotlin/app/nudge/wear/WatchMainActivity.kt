package app.nudge.wear

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import app.nudge.core.Status
import app.nudge.core.WatchAction
import app.nudge.core.WatchItem
import kotlinx.coroutines.launch

/** The watch app: a big record button and what's coming up. Tap a reminder for Done / Sleep. */
class WatchMainActivity : ComponentActivity() {
    private var selected by mutableStateOf<WatchItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchData.load(this)
        lifecycleScope.launch { WatchData.refresh(this@WatchMainActivity) }
        setContent { MaterialTheme { App() } }
    }

    @Composable
    private fun App() {
        val state by WatchData.state.collectAsState()
        val sel = selected
        BackHandler(sel != null) { selected = null }
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (sel == null) {
                item {
                    Chip(
                        onClick = { startActivity(Intent(this@WatchMainActivity, WatchCaptureActivity::class.java)) },
                        label = { Text("Remember something") },
                        icon = { Icon(Icons.Filled.Mic, null) },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = Red),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.items.isEmpty()) {
                    item { Text("Nothing coming up", color = Color.Gray, textAlign = TextAlign.Center) }
                }
                items(state.items, key = { it.id }) { r ->
                    Chip(
                        onClick = { selected = r },
                        label = { Text(r.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(r.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = when (r.status) {
                            Status.FIRING -> ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF7A1F22))
                            else -> ChipDefaults.secondaryChipColors()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item { ListHeader { Text(sel.text, textAlign = TextAlign.Center, maxLines = 3) } }
                item { ActionChip("Done", Accent) { act(WatchAction(sel.id, "done")) } }
                item { ActionChip("Sleep ${label(state.snoozeA)}") { act(WatchAction(sel.id, "snooze", state.snoozeA)) } }
                item { ActionChip("Sleep ${label(state.snoozeB)}") { act(WatchAction(sel.id, "snooze", state.snoozeB)) } }
                item { ActionChip("Back") { selected = null } }
            }
        }
    }

    private fun label(min: Int) = if (min >= 60 && min % 60 == 0) "${min / 60} hr" else "$min min"

    @Composable
    private fun ActionChip(label: String, color: Color? = null, onClick: () -> Unit) {
        Chip(
            onClick = onClick,
            label = { Text(label, color = if (color != null) Color.Black else Color.White) },
            colors = if (color != null) ChipDefaults.primaryChipColors(backgroundColor = color) else ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun act(a: WatchAction) {
        selected = null
        lifecycleScope.launch {
            val ok = WatchData.action(applicationContext, a)
            if (!ok) Toast.makeText(this@WatchMainActivity, "Phone not connected", Toast.LENGTH_SHORT).show()
        }
    }
}
