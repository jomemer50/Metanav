package com.metanav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metanav.app.engine.NavigationController
import com.metanav.core.Units
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(controller: NavigationController, onDismiss: () -> Unit) {
    val s by controller.settings.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            ToggleRow("Voice guidance", s.voice) { v -> controller.updateSettings { it.copy(voice = v) } }
            ToggleRow("Vibrate on the phone", s.haptics) { v -> controller.updateSettings { it.copy(haptics = v) } }
            ToggleRow("Say \"path clear\" afterwards", s.announceClear) { v -> controller.updateSettings { it.copy(announceClear = v) } }

            Column {
                Text("Units", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Units.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = s.units == u,
                            onClick = { controller.updateSettings { it.copy(units = u) } },
                            shape = SegmentedButtonDefaults.itemShape(i, Units.entries.size),
                        ) { Text(if (u == Units.METERS) "Meters" else "Feet") }
                    }
                }
            }

            SliderRow(
                title = "Warn within",
                value = s.alertDistanceMeters,
                range = 1.5f..5f,
                display = if (s.units == Units.METERS) "${"%.1f".format(s.alertDistanceMeters)} m" else "${(s.alertDistanceMeters * 3.28084f).roundToInt()} ft",
            ) { v -> controller.updateSettings { it.copy(alertDistanceMeters = (v * 2).roundToInt() / 2f) } }

            SliderRow(
                title = "Camera height",
                value = s.cameraHeightMeters,
                range = 0.9f..2.0f,
                display = "${"%.2f".format(s.cameraHeightMeters)} m",
                hint = "Glasses: your eye height. Phone held at the chest: about 1.3 m. Distances depend on this.",
            ) { v -> controller.updateSettings { it.copy(cameraHeightMeters = (v * 20).roundToInt() / 20f) } }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, hint: String? = null, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(display, style = MaterialTheme.typography.bodyLarge, color = Palette.Teal)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    }
}
