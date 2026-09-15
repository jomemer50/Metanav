package com.metanav.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.RegistrationState
import com.metanav.app.engine.NavigationController
import com.metanav.app.frames.SourceKind

@Composable
fun HomeScreen(
    controller: NavigationController,
    activity: Activity,
    failure: String?,
    onStart: (SourceKind) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val registration by controller.registration.collectAsStateWithLifecycle()
    val glassesPresent by controller.glassesPresent.collectAsStateWithLifecycle()
    val registered = registration == RegistrationState.REGISTERED

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Metanav", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Palette.Muted) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Walks with you. Says something only when something is in your way, how far it is, and which way to step.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.Muted,
            )
            Spacer(Modifier.height(28.dp))
            GlassesCard(
                registration = registration,
                glassesPresent = glassesPresent,
                onConnect = { controller.connectGlasses(activity) },
                onDisconnect = { controller.disconnectGlasses(activity) },
            )
            if (failure != null) {
                Spacer(Modifier.height(16.dp))
                Surface(color = Palette.Red.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp)) {
                    Text(failure, Modifier.padding(14.dp), color = Palette.Red, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!controller.depthModelBundled) {
                Spacer(Modifier.height(16.dp))
                Surface(color = Palette.Amber.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp)) {
                    Text(
                        "The depth model is not bundled in this build. Run scripts/fetch-models.sh, then rebuild.",
                        Modifier.padding(14.dp), color = Palette.Amber, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onStart(SourceKind.GLASSES) },
                enabled = registered && controller.depthModelBundled,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Teal, contentColor = Palette.Ink),
            ) { Text(if (glassesPresent || !registered) "Start with glasses" else "Start with glasses (put them on)", style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(
                onClick = { onStart(SourceKind.PHONE_CAMERA) },
                enabled = controller.depthModelBundled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Use the phone camera instead") }
            Text(
                "Guidance is spoken through whatever your phone plays audio on. With the glasses connected, that is the glasses.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun GlassesCard(
    registration: RegistrationState?,
    glassesPresent: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (label, color) = when (registration) {
        RegistrationState.REGISTERED -> if (glassesPresent) "Glasses connected" to Palette.Green else "Registered, glasses not on" to Palette.Amber
        RegistrationState.REGISTERING -> "Connecting…" to Palette.Amber
        RegistrationState.UNREGISTERING -> "Disconnecting…" to Palette.Amber
        RegistrationState.AVAILABLE -> "Glasses not connected" to Palette.Muted
        RegistrationState.UNAVAILABLE -> "Meta AI app not available" to Palette.Muted
        null -> "Waiting for Bluetooth permission" to Palette.Muted
    }
    Surface(color = Palette.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.size(10.dp))
                Text(label, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (registration) {
                    RegistrationState.REGISTERED -> "Camera frames stream from the glasses over Bluetooth. Tap the glasses' touchpad to pause."
                    RegistrationState.UNAVAILABLE -> "Install the Meta AI app, pair your glasses, and turn on Developer Mode (Settings › your glasses › Developer Mode)."
                    else -> "You'll be sent to the Meta AI app once to approve this app."
                },
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            )
            Spacer(Modifier.height(12.dp))
            when (registration) {
                RegistrationState.REGISTERED -> TextButton(onClick = onDisconnect) { Text("Disconnect", color = Palette.Muted) }
                RegistrationState.AVAILABLE -> Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Color.White),
                ) { Text("Connect glasses") }
                else -> Unit
            }
        }
    }
}
