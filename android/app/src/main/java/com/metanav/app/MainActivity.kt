package com.metanav.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.metanav.app.ui.AppScreen
import com.metanav.app.ui.MetanavTheme
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val controller get() = MetanavApp.controller(application)

    private val runtimePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.BLUETOOTH_CONNECT] == true) controller.initializeGlassesLink()
    }

    // Camera access on the glasses is granted inside the Meta AI app; this contract round-trips it.
    private var glassesPermissionContinuation: CancellableContinuation<Boolean>? = null
    private val glassesPermissionMutex = Mutex()
    private val glassesPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val granted = result.getOrNull() == PermissionStatus.Granted
        glassesPermissionContinuation?.resume(granted)
        glassesPermissionContinuation = null
    }

    private suspend fun requestGlassesCamera(): Boolean = glassesPermissionMutex.withLock {
        suspendCancellableCoroutine { cont ->
            glassesPermissionContinuation = cont
            cont.invokeOnCancellation { glassesPermissionContinuation = null }
            glassesPermission.launch(Permission.CAMERA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MetanavTheme {
                AppScreen(
                    controller = controller,
                    activity = this,
                    requestGlassesCamera = ::requestGlassesCamera,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val wanted = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) controller.initializeGlassesLink() else runtimePermissions.launch(missing.toTypedArray())
    }
}
