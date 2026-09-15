package com.metanav.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.metanav.app.frames.RgbFrame
import com.metanav.app.vision.DepthEstimator
import com.metanav.app.vision.ObjectDetector
import com.metanav.core.Advisory
import com.metanav.core.Detection
import com.metanav.core.FrameObservation
import com.metanav.core.ObstacleReasoner
import com.metanav.core.ReasonerConfig
import com.metanav.core.SceneState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Frame in, scene + advisory out. Runs the models on one background thread and drops frames while busy. */
class NavigationEngine(context: Context, config: ReasonerConfig) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "metanav-vision").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val busy = AtomicBoolean(false)
    private val reasoner = ObstacleReasoner(config)
    private val depth: DepthEstimator? = runCatching { DepthEstimator(context) }.onFailure { Log.e(TAG, "Depth model unavailable", it) }.getOrNull()
    private val detector: ObjectDetector? = runCatching { ObjectDetector(context) }.onFailure { Log.w(TAG, "Object detector unavailable", it) }.getOrNull()

    private val _scene = MutableStateFlow(SceneState())
    val scene: StateFlow<SceneState> = _scene.asStateFlow()

    private val _advisories = MutableSharedFlow<Advisory>(extraBufferCapacity = 4)
    val advisories: SharedFlow<Advisory> = _advisories.asSharedFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    val hasDepthModel: Boolean get() = depth != null

    private var lastProcessedAt = 0L
    private var frameCounter = 0
    private var lastDetections: List<Detection> = emptyList()

    data class Stats(val processedFps: Float = 0f, val inferenceMs: Long = 0)

    fun updateConfig(config: ReasonerConfig) {
        executor.execute { reasoner.config = config }
    }

    fun reset() {
        executor.execute { reasoner.reset(); _scene.value = SceneState() }
    }

    /** Non-blocking: schedules the frame unless the previous one is still being processed. */
    fun submit(frame: RgbFrame) {
        _preview.value = frame.bitmap
        val now = System.currentTimeMillis()
        if (now - lastProcessedAt < MIN_INTERVAL_MS) return
        if (!busy.compareAndSet(false, true)) return
        lastProcessedAt = now
        executor.execute {
            try { process(frame) } catch (t: Throwable) { Log.e(TAG, "frame processing failed", t) } finally { busy.set(false) }
        }
    }

    private fun process(frame: RgbFrame) {
        val started = System.currentTimeMillis()
        val depthMap = depth?.estimate(frame.bitmap)
        frameCounter++
        // The detector is the slower, less important model: run it every other frame.
        val det = detector
        if (det != null && frameCounter % 2 == 0) {
            lastDetections = runCatching { det.detect(frame.bitmap) }.getOrDefault(lastDetections)
        }
        val out = reasoner.process(FrameObservation(frame.timestampMs, depthMap, lastDetections))
        _scene.value = out.scene
        out.advisory?.let { _advisories.tryEmit(it) }
        val elapsed = System.currentTimeMillis() - started
        val prev = _stats.value
        val fps = 1000f / (System.currentTimeMillis() - started).coerceAtLeast(MIN_INTERVAL_MS)
        _stats.value = Stats(processedFps = 0.8f * prev.processedFps + 0.2f * fps, inferenceMs = elapsed)
    }

    override fun close() {
        executor.shutdownNow()
        depth?.close()
        detector?.close()
    }

    companion object {
        private const val TAG = "Metanav:Engine"
        /** Cap processing at ~8 fps: enough for walking speed, kind to the battery. */
        private const val MIN_INTERVAL_MS = 120L
    }
}
