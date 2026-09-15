package com.metanav.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metanav.app.engine.NavigationController
import com.metanav.app.engine.RunState
import com.metanav.app.frames.SourceKind
import com.metanav.app.frames.SourceState
import com.metanav.core.AdvisoryPolicy
import com.metanav.core.SceneState
import com.metanav.core.Urgency

@Composable
fun NavigateScreen(
    controller: NavigationController,
    runState: RunState,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scene by controller.scene.collectAsStateWithLifecycle()
    val preview by controller.preview.collectAsStateWithLifecycle()
    val settings by controller.settings.collectAsStateWithLifecycle()
    val stats by controller.stats.collectAsStateWithLifecycle()
    val lastAdvisory by controller.lastAdvisory.collectAsStateWithLifecycle()

    val streaming = runState is RunState.Running && runState.source == SourceState.Streaming
    val accent by animateColorAsState(
        when {
            !streaming -> Palette.Muted
            scene.urgency == Urgency.STOP -> Palette.Red
            scene.urgency == Urgency.CAUTION -> Palette.Amber
            else -> Palette.Green
        }, label = "accent",
    )

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(sourceLabel(runState), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Row {
                IconButton(onClick = { controller.updateSettings { it.copy(voice = !it.voice) } }) {
                    Icon(if (settings.voice) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, contentDescription = "Voice", tint = Palette.Muted)
                }
                IconButton(onClick = { controller.setPreviewVisible(!settings.showPreview) }) {
                    Icon(if (settings.showPreview) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, contentDescription = "Preview", tint = Palette.Muted)
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Palette.Muted) }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Status card: the one thing to read at a glance.
        Surface(color = accent.copy(alpha = 0.14f), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                Text(
                    if (streaming) scene.headline else statusHeadline(runState),
                    style = MaterialTheme.typography.displayMedium, color = accent,
                )
                val detail = if (streaming) scene.detail else statusDetail(runState)
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(detail, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                lastAdvisory?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Surface),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview
            if (settings.showPreview && bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = "Camera preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (!streaming) {
                Text(statusDetail(runState).ifBlank { "Waiting for frames…" }, color = Palette.Muted)
            }
            SceneOverlay(scene = scene, accent = accent, modifier = Modifier.fillMaxSize())
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (stats.inferenceMs > 0) "${"%.1f".format(stats.processedFps)} fps · ${stats.inferenceMs} ms" else "",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            )
            Button(
                onClick = onStop,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text("Stop") }
        }
    }
}

/** Corridor band, a per-column distance strip, and boxes for confirmed obstacles. */
@Composable
fun SceneOverlay(scene: SceneState, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Corridor edges
        val cl = w * 0.30f
        val cr = w * 0.70f
        val edge = Color.White.copy(alpha = 0.25f)
        drawLine(edge, Offset(cl, h * 0.15f), Offset(cl, h), strokeWidth = 2f)
        drawLine(edge, Offset(cr, h * 0.15f), Offset(cr, h), strokeWidth = 2f)

        // Distance strip along the bottom: taller and warmer = closer.
        val cols = scene.columnDistances
        if (cols.isNotEmpty()) {
            val stripH = h * 0.16f
            val colW = w / cols.size
            for (i in cols.indices) {
                val d = cols[i]
                if (!d.isFinite()) continue
                val closeness = (1f - (d / 4f)).coerceIn(0.05f, 1f)
                val color = when {
                    d < 1.2f -> Palette.Red
                    d < 3f -> Palette.Amber
                    else -> Palette.Teal
                }.copy(alpha = 0.75f)
                val barH = stripH * closeness
                drawRect(color, Offset(i * colW, h - barH), Size(colW + 0.5f, barH))
            }
        }

        // Obstacle extents
        for (o in scene.obstacles) {
            val color = if (o.inCorridor) accent else Color.White.copy(alpha = 0.5f)
            val left = o.left * w
            val right = o.right * w
            val top = h * 0.35f
            val bottom = h * 0.80f
            drawRoundRect(color, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 3f))
        }
    }
    // Labels are drawn as composables so they get real text rendering.
    Box(modifier) {
        for (o in scene.obstacles) {
            Surface(
                color = Palette.Ink.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(start = 0.dp).align(Alignment.TopStart).offsetFraction(o.left, 0.30f),
            ) {
                Text(
                    "${o.label} · ${AdvisoryPolicy.shortDistance(o.distanceMeters, com.metanav.core.Units.METERS)}",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = if (o.inCorridor) accent else Palette.Muted,
                )
            }
        }
    }
}

private fun sourceLabel(runState: RunState): String = when (runState) {
    is RunState.Running -> if (runState.kind == SourceKind.GLASSES) "Glasses camera" else "Phone camera"
    is RunState.Starting -> if (runState.kind == SourceKind.GLASSES) "Glasses camera" else "Phone camera"
    else -> ""
}

private fun statusHeadline(runState: RunState): String = when (runState) {
    is RunState.Starting -> "Starting"
    is RunState.Running -> when (runState.source) {
        SourceState.Connecting -> "Connecting"
        SourceState.WaitingForDevice -> "Waiting"
        SourceState.Paused -> "Paused"
        SourceState.Streaming -> "Clear"
        SourceState.Idle -> "Stopped"
        is SourceState.Error -> "Problem"
    }
    else -> ""
}

private fun statusDetail(runState: RunState): String = when (runState) {
    is RunState.Starting -> "Loading models and opening the camera"
    is RunState.Running -> when (runState.source) {
        SourceState.Connecting -> "Talking to the glasses"
        SourceState.WaitingForDevice -> "Put on the glasses and unfold them"
        SourceState.Paused -> "Tap the glasses to resume"
        is SourceState.Error -> runState.source.message
        else -> ""
    }
    else -> ""
}
