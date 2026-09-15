package com.metanav.app.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Phone rear camera through CameraX, for testing the guidance without glasses (hold the phone at
 * chest height pointing forward). Owns its own lifecycle so it keeps running when the Activity
 * goes away; the foreground service keeps the process alive.
 */
class PhoneCameraFrameSource(private val context: Context) : FrameSource, LifecycleOwner {
    override val kind = SourceKind.PHONE_CAMERA

    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<RgbFrame>(replay = 0, extraBufferCapacity = 1)
    override val frames: Flow<RgbFrame> = _frames.asSharedFlow()

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    override suspend fun start() {
        if (provider != null) return
        _state.value = SourceState.Connecting
        withContext(Dispatchers.Main) {
            registry.currentState = Lifecycle.State.CREATED
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            provider = cameraProvider
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> onImage(image) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this@PhoneCameraFrameSource, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                registry.currentState = Lifecycle.State.STARTED
                _state.value = SourceState.Streaming
            } catch (t: Throwable) {
                _state.value = SourceState.Error("Phone camera unavailable: ${t.message}")
            }
        }
    }

    private fun onImage(image: ImageProxy) {
        image.use {
            val rotation = it.imageInfo.rotationDegrees
            var bitmap = it.toBitmap()
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            }
            _frames.tryEmit(RgbFrame(bitmap, System.currentTimeMillis()))
        }
    }

    override fun stop() {
        val p = provider ?: return
        provider = null
        ContextCompat.getMainExecutor(context).execute {
            try { p.unbindAll() } catch (_: Throwable) {}
            registry.currentState = Lifecycle.State.DESTROYED
        }
        _state.value = SourceState.Idle
    }
}
