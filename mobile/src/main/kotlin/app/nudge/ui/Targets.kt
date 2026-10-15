package app.nudge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nudge.core.SavedTarget
import app.nudge.core.TargetKind
import app.nudge.data.Store
import app.nudge.engine.Engine
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Add or edit a place, wifi network or bluetooth device, with its spoken aliases. */
@Composable
fun TargetEditorDialog(initial: SavedTarget, onDismiss: () -> Unit, onSaved: (SavedTarget) -> Unit, onDelete: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = initial.id.isEmpty()
    var kind by remember { mutableStateOf(initial.kind) }
    var label by remember { mutableStateOf(initial.label) }
    var value by remember { mutableStateOf(initial.value) }
    var address by remember { mutableStateOf(initial.address) }
    var latLng by remember { mutableStateOf(if (initial.lat != null) "${initial.lat}, ${initial.lng}" else "") }
    var radius by remember { mutableStateOf(initial.radiusM) }
    var aliases by remember { mutableStateOf(initial.aliases.joinToString(", ")) }
    var status by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) devices = bondedDevices(ctx)
    }

    fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add a place or connection" else "Edit ${initial.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isNew) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(TargetKind.PLACE to "Place", TargetKind.WIFI to "Wifi", TargetKind.BLUETOOTH to "Bluetooth").forEach { (k, l) ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(l) },
                            leadingIcon = { Icon(k.icon(), null) })
                    }
                }
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())

                when (kind) {
                    TargetKind.PLACE -> {
                        OutlinedButton(onClick = {
                            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                                locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                return@OutlinedButton
                            }
                            status = "Finding you…"
                            scope.launch {
                                status = try {
                                    @SuppressLint("MissingPermission")
                                    val loc = LocationServices.getFusedLocationProviderClient(ctx)
                                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                                    if (loc != null) {
                                        latLng = "%.6f, %.6f".format(loc.latitude, loc.longitude)
                                        "Got it (±${loc.accuracy.toInt()} m)"
                                    } else "Couldn't get a location"
                                } catch (e: Exception) { "Location failed: ${e.message}" }
                            }
                        }) { Text("Use where I am now") }
                        OutlinedTextField(latLng, { latLng = it }, label = { Text("lat, lng (or paste from Maps)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Radius: ${radius.toInt()} m", style = MaterialTheme.typography.bodySmall)
                        Slider(value = radius, onValueChange = { radius = it }, valueRange = 100f..1000f, steps = 17)
                    }
                    TargetKind.WIFI -> {
                        OutlinedButton(onClick = {
                            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                                locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                return@OutlinedButton
                            }
                            val ssid = currentSsid(ctx)
                            if (ssid != null) { value = ssid; if (label.isBlank()) label = ssid; status = null }
                            else status = "Not on wifi (or location is off)"
                        }) { Text("Use the wifi I'm on") }
                        OutlinedTextField(value, { value = it }, label = { Text("Network name (SSID)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    TargetKind.BLUETOOTH -> {
                        OutlinedButton(onClick = {
                            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            else devices = bondedDevices(ctx)
                        }) { Text("Choose a paired device") }
                        devices?.let { list ->
                            if (list.isEmpty()) Text("No paired devices found")
                            list.forEach { (name, mac) ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        value = name; address = mac
                                        if (label.isBlank()) label = name
                                        devices = null
                                    }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(TargetKind.BLUETOOTH.icon(), null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(name)
                                }
                            }
                        }
                        OutlinedTextField(value, { value = it; address = null }, label = { Text("Device name") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }

                OutlinedTextField(
                    aliases, { aliases = it },
                    label = { Text("Other names you might say") },
                    placeholder = { Text("van, work truck, work vehicle") },
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ll = latLng.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (kind == TargetKind.PLACE && ll.size != 2) { status = "Need a location"; return@TextButton }
                if (kind != TargetKind.PLACE && value.isBlank()) { status = "Need a name to match"; return@TextButton }
                val t = SavedTarget(
                    id = initial.id.ifEmpty { UUID.randomUUID().toString() },
                    kind = kind,
                    label = label.ifBlank { value }.trim(),
                    value = value.trim(),
                    address = if (kind == TargetKind.BLUETOOTH) address else null,
                    lat = ll.getOrNull(0), lng = ll.getOrNull(1),
                    radiusM = radius,
                    aliases = aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                )
                Store.upsertTarget(t)
                Engine.changed()
                onSaved(t)
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@SuppressLint("MissingPermission")
private fun bondedDevices(ctx: Context): List<Pair<String, String>> = try {
    ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices
        ?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first.lowercase() } ?: emptyList()
} catch (_: SecurityException) { emptyList() }

@Suppress("DEPRECATION")
private fun currentSsid(ctx: Context): String? {
    val ssid = ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.trim('"')
    return ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
}
