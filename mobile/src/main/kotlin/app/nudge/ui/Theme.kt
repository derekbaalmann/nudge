package app.nudge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.nudge.core.TargetKind
import app.nudge.core.Trigger

@Composable
fun NudgeTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = runCatching { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
        .getOrElse {
            if (dark) darkColorScheme(primary = Color(0xFFFFB86B), secondary = Color(0xFF8AD1C2))
            else lightColorScheme(primary = Color(0xFFB45F06), secondary = Color(0xFF2E7D6F))
        }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun Trigger.icon(): ImageVector = when (this) {
    is Trigger.At -> Icons.Outlined.Schedule
    is Trigger.Repeat -> Icons.Outlined.Repeat
    is Trigger.Target -> kind.icon()
    is Trigger.Power -> Icons.Outlined.BatteryChargingFull
    Trigger.None -> Icons.Outlined.Inbox
}

fun TargetKind.icon(): ImageVector = when (this) {
    TargetKind.PLACE -> Icons.Outlined.Place
    TargetKind.WIFI -> Icons.Outlined.Wifi
    TargetKind.BLUETOOTH -> Icons.Outlined.Bluetooth
}
