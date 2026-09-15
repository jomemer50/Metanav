package com.metanav.app.engine

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.metanav.app.feedback.HapticFeedback
import com.metanav.app.feedback.VoiceFeedback
import com.metanav.app.frames.FrameSource
import com.metanav.app.frames.GlassesFrameSource
import com.metanav.app.frames.PhoneCameraFrameSource
import com.metanav.app.frames.SourceKind
import com.metanav.app.frames.SourceState
import com.metanav.app.service.NavigationService
import com.metanav.app.vision.DepthEstimator
import com.metanav.core.SceneState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether guidance is running, and from what. */
sealed class RunState {
    data object Stopped : RunState()
    data class Starting(val kind: SourceKind) : RunState()
    data class Running(val kind: SourceKind, val source: SourceState) : RunState()
    data class Failed(val message: String) : RunState()
}

/**
 * App-scoped owner of everything that must outlive the screen: the frame source, the engine,
 * voice and haptics, and the link to the glasses. The UI only observes and calls start/stop.
 */
class NavigationController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _settings = MutableStateFlow(Settings.load(app))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _runState = MutableStateFlow<RunState>(RunState.Stopped)
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    private val _scene = MutableStateFlow(SceneState())
    val scene: StateFlow<SceneState> = _scene.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _stats = MutableStateFlow(NavigationEngine.Stats())
    val stats: StateFlow<NavigationEngine.Stats> = _stats.asStateFlow()

    private val _lastAdvisory = MutableStateFlow<String?>(null)
    val lastAdvisory: StateFlow<String?> = _lastAdvisory.asStateFlow()

    // Glasses link
    private val _datReady = MutableStateFlow(false)
    val datReady: StateFlow<Boolean> = _datReady.asStateFlow()
    private val _registration = MutableStateFlow<RegistrationState?>(null)
    val registration: StateFlow<RegistrationState?> = _registration.asStateFlow()
    private val _glassesPresent = MutableStateFlow(false)
    val glassesPresent: StateFlow<Boolean> = _glassesPresent.asStateFlow()

    val depthModelBundled: Boolean = DepthEstimator.isAvailable(app)

    private val voice = VoiceFeedback(app).apply { enabled = _settings.value.voice }
    private val haptics = HapticFeedback(app).apply { enabled = _settings.value.haptics }
    private var engine: NavigationEngine? = null
    private var source: FrameSource? = null
    private val deviceSelector by lazy { AutoDeviceSelector() }
    private var sessionJobs = ArrayList<Job>()

    /** Call once Bluetooth permission is granted; safe to call repeatedly. */
    fun initializeGlassesLink() {
        if (_datReady.value) return
        val result = Wearables.initialize(app)
        if (result.isFailure) {
            Log.w(TAG, "Wearables.initialize failed: ${result.errorOrNull()?.description}")
        }
        _datReady.value = true
        scope.launch { Wearables.registrationState.collect { _registration.value = it } }
        scope.launch { deviceSelector.activeDeviceFlow().collect { _glassesPresent.value = it != null } }
    }

    fun connectGlasses(activity: Activity) = Wearables.startRegistration(activity)

    fun disconnectGlasses(activity: Activity) = Wearables.startUnregistration(activity)

    fun updateSettings(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        Settings.save(app, updated)
        voice.enabled = updated.voice
        haptics.enabled = updated.haptics
        engine?.updateConfig(updated.toReasonerConfig())
    }

    /**
     * Start guidance. For the glasses, [requestGlassesCamera] is invoked when the Meta AI app still
     * has to grant camera access (it opens Meta AI and returns true when granted).
     */
    fun start(kind: SourceKind, requestGlassesCamera: suspend () -> Boolean) {
        if (_runState.value !is RunState.Stopped && _runState.value !is RunState.Failed) return
        _runState.value = RunState.Starting(kind)
        updateSettings { it.copy(source = kind) }
        scope.launch {
            try {
                if (kind == SourceKind.GLASSES) {
                    if (!_datReady.value) initializeGlassesLink()
                    val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
                    if (status != PermissionStatus.Granted && !requestGlassesCamera()) {
                        _runState.value = RunState.Failed("Camera access on the glasses was not granted.")
                        return@launch
                    }
                }
                val eng = engine ?: withContext(Dispatchers.Default) {
                    NavigationEngine(app, _settings.value.toReasonerConfig())
                }.also { engine = it; wireEngine(it) }
                if (!eng.hasDepthModel) {
                    _runState.value = RunState.Failed("Depth model missing. Run scripts/fetch-models.sh and rebuild.")
                    return@launch
                }
                eng.reset()
                val src: FrameSource = when (kind) {
                    SourceKind.GLASSES -> GlassesFrameSource(deviceSelector)
                    SourceKind.PHONE_CAMERA -> PhoneCameraFrameSource(app)
                }
                source = src
                NavigationService.start(app, kind)
                sessionJobs += scope.launch(Dispatchers.Default) { src.frames.collect { eng.submit(it) } }
                sessionJobs += scope.launch {
                    src.state.collect { s ->
                        _runState.value = when (s) {
                            is SourceState.Error -> RunState.Failed(s.message)
                            SourceState.Idle -> if (_runState.value is RunState.Running) RunState.Stopped else _runState.value
                            else -> RunState.Running(kind, s)
                        }
                        if (s is SourceState.Error || s is SourceState.Idle && _runState.value is RunState.Stopped) {
                            teardownSource()
                        }
                    }
                }
                src.start()
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                _runState.value = RunState.Failed(t.message ?: "Could not start")
                teardownSource()
            }
        }
    }

    fun stop() {
        voice.stop()
        teardownSource()
        _runState.value = RunState.Stopped
    }

    private fun teardownSource() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        source?.stop()
        source = null
        NavigationService.stop(app)
        _preview.value = null
    }

    private fun wireEngine(eng: NavigationEngine) {
        scope.launch { eng.scene.collect { _scene.value = it } }
        scope.launch { eng.preview.collect { _preview.value = it } }
        scope.launch { eng.stats.collect { _stats.value = it } }
        scope.launch {
            eng.advisories.collect { advisory ->
                _lastAdvisory.value = advisory.text
                voice.speak(advisory)
                haptics.buzz(advisory.urgency)
            }
        }
    }

    fun setPreviewVisible(visible: Boolean) = updateSettings { it.copy(showPreview = visible) }

    companion object { private const val TAG = "Metanav:Controller" }
}
