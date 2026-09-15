package com.metanav.app.frames

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One RGB frame ready for the vision models. Portrait, origin top-left. */
class RgbFrame(val bitmap: Bitmap, val timestampMs: Long)

enum class SourceKind { GLASSES, PHONE_CAMERA }

sealed class SourceState {
    data object Idle : SourceState()
    data object Connecting : SourceState()
    data object WaitingForDevice : SourceState()
    data object Streaming : SourceState()
    data object Paused : SourceState()
    data class Error(val message: String) : SourceState()
}

/** Anything that can deliver camera frames: the glasses over DAT, or the phone camera. */
interface FrameSource {
    val kind: SourceKind
    val state: StateFlow<SourceState>
    val frames: Flow<RgbFrame>
    suspend fun start()
    fun stop()
}
