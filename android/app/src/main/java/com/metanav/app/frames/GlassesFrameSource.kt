package com.metanav.app.frames

import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streams the glasses' camera through the Meta Wearables Device Access Toolkit.
 * Lifecycle: create + start a [DeviceSession], wait for STARTED, attach a camera with an
 * uncompressed low-resolution stream, convert each I420 frame to a bitmap.
 */
class GlassesFrameSource(private val deviceSelector: DeviceSelector) : FrameSource {
    override val kind = SourceKind.GLASSES

    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<RgbFrame>(replay = 0, extraBufferCapacity = 1)
    override val frames: Flow<RgbFrame> = _frames.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var frameJob: Job? = null
    private var reuseBitmap: Bitmap? = null

    override suspend fun start() {
        if (session != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { this.scope = it }
        _state.value = SourceState.Connecting

        val created = Wearables.createSession(deviceSelector).getOrElse { error ->
            fail("Could not start a glasses session: ${error.message ?: error}")
            return
        }
        session = created
        scope.launch {
            created.state.collect { s ->
                when (s) {
                    DeviceSessionState.PAUSED -> _state.value = SourceState.Paused
                    DeviceSessionState.STOPPED -> if (_state.value !is SourceState.Error) _state.value = SourceState.Idle
                    else -> Unit
                }
            }
        }
        scope.launch {
            created.errors.collect { error -> Log.w(TAG, "Session error: ${error.description}") }
        }
        created.start()

        val started = withTimeoutOrNull(20_000) {
            created.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
        }
        if (started != DeviceSessionState.STARTED) {
            fail(if (started == null) "Timed out connecting to the glasses. Are they on and unfolded?" else "The glasses session stopped before it started.")
            return
        }

        val config = StreamConfiguration(
            videoQuality = VideoQuality.LOW,
            frameRate = FRAME_RATE,
            compressVideo = false,
        )
        val cam = created.addCamera(config).getOrElse { error ->
            fail("Could not open the glasses camera: ${error.message ?: error}")
            return
        }
        camera = cam
        val stream = cam.stream
        scope.launch {
            stream.state.collect { s ->
                _state.value = when (s) {
                    StreamState.STREAMING -> SourceState.Streaming
                    StreamState.STARTING, StreamState.STARTED -> SourceState.WaitingForDevice
                    StreamState.PAUSED -> SourceState.Paused
                    StreamState.STOPPED, StreamState.CLOSED, StreamState.STOPPING ->
                        if (_state.value is SourceState.Error) _state.value else SourceState.Idle
                }
            }
        }
        scope.launch {
            stream.errorStream.collect { error -> Log.w(TAG, "Stream error: ${error.description}") }
        }
        frameJob = scope.launch {
            stream.videoStream.collect { frame ->
                if (frame.isCompressed || frame.isCodecConfig) return@collect
                val bmp = YuvConverter.i420ToBitmap(frame.buffer, frame.width, frame.height, null) ?: return@collect
                _frames.tryEmit(RgbFrame(bmp, System.currentTimeMillis()))
            }
        }
        stream.start().onFailure { error, _ ->
            fail("Could not start streaming: ${error.description}")
        }
    }

    override fun stop() {
        frameJob?.cancel(); frameJob = null
        try { camera?.stop() } catch (t: Throwable) { Log.w(TAG, "camera.stop failed", t) }
        camera = null
        try { session?.stop() } catch (t: Throwable) { Log.w(TAG, "session.stop failed", t) }
        session = null
        scope?.cancel(); scope = null
        reuseBitmap = null
        if (_state.value !is SourceState.Error) _state.value = SourceState.Idle
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _state.value = SourceState.Error(message)
        stop()
        _state.value = SourceState.Error(message)
    }

    companion object {
        private const val TAG = "Metanav:Glasses"
        private const val FRAME_RATE = 15
    }
}
