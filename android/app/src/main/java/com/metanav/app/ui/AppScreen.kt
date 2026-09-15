package com.metanav.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metanav.app.engine.NavigationController
import com.metanav.app.engine.RunState

@Composable
fun AppScreen(
    controller: NavigationController,
    activity: Activity,
    requestGlassesCamera: suspend () -> Boolean,
) {
    val runState by controller.runState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (runState) {
            is RunState.Stopped, is RunState.Failed -> HomeScreen(
                controller = controller,
                activity = activity,
                failure = (runState as? RunState.Failed)?.message,
                onStart = { kind -> controller.start(kind, requestGlassesCamera) },
                onOpenSettings = { showSettings = true },
            )
            is RunState.Starting, is RunState.Running -> NavigateScreen(
                controller = controller,
                runState = runState,
                onStop = { controller.stop() },
                onOpenSettings = { showSettings = true },
            )
        }
        if (showSettings) {
            SettingsSheet(controller = controller, onDismiss = { showSettings = false })
        }
    }
}
