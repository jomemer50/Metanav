package com.metanav.core

/**
 * A relative inverse-depth map ("disparity"): larger values are closer to the camera.
 * Both MiDaS and Depth Anything emit this kind of output. Row-major, origin top-left.
 */
class DepthMap(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width > 0 && height > 0) { "DepthMap must have positive dimensions" }
        require(values.size == width * height) { "DepthMap values must be width*height" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]
}

/** Axis-aligned rectangle in normalized image coordinates (0..1, origin top-left). */
data class NormRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val left: Float get() = x
    val right: Float get() = x + width
    val top: Float get() = y
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2f
}

/** One object detection from the object detector (label is the raw model label). */
data class Detection(val label: String, val confidence: Float, val box: NormRect)

/** Everything the reasoner needs about one processed frame. */
data class FrameObservation(
    val timestampMs: Long,
    val depth: DepthMap?,
    val detections: List<Detection> = emptyList(),
)

/**
 * Where the camera sits on the wearer. The defaults describe glasses on an adult who is
 * looking slightly down while walking. The phone-camera fallback uses a lower height.
 */
data class CameraGeometry(
    val heightMeters: Float = 1.55f,
    /** Downward tilt of the optical axis in degrees (positive = looking down). */
    val pitchDegrees: Float = 8f,
    /** Vertical field of view of the frame in degrees. */
    val verticalFovDegrees: Float = 78f,
)

enum class Units { METERS, FEET }

/** Tunables for the obstacle reasoner. Defaults are sane for walking speed. */
data class ReasonerConfig(
    val geometry: CameraGeometry = CameraGeometry(),
    /** Obstacles farther than this are ignored entirely. */
    val alertDistanceMeters: Float = 3.0f,
    /** Closer than this is "stop now". */
    val urgentDistanceMeters: Float = 1.2f,
    /** Horizontal band (normalized) that counts as the walking path. */
    val corridorLeft: Float = 0.30f,
    val corridorRight: Float = 0.70f,
    /** Rows scanned for obstacles, as fractions of frame height. */
    val scanTop: Float = 0.15f,
    val scanBottom: Float = 0.97f,
    /** An obstacle pixel must be at least this much closer than the ground at its row. */
    val groundMarginRatio: Float = 0.80f,
    /** A column needs this fraction of scanned rows flagged before it counts. */
    val minColumnFillRatio: Float = 0.06f,
    /** Blobs narrower than this fraction of the frame width are noise. */
    val minBlobWidthRatio: Float = 0.07f,
    /** Temporal confirmation: seen in at least [confirmHits] of the last [confirmWindow] frames. */
    val confirmHits: Int = 2,
    val confirmWindow: Int = 3,
    /** Frames a track may go unseen before it is dropped. */
    val maxMissedFrames: Int = 3,
    /** Global minimum gap between spoken advisories about the same obstacle. */
    val cooldownMs: Long = 2500,
    /** A brand-new obstacle or an escalation to "stop" may interrupt after this long. */
    val newObstacleCooldownMs: Long = 500,
    val urgentCooldownMs: Long = 500,
    /** Re-announce the same obstacle when it got this much closer (meters). */
    val reannounceCloserBy: Float = 1.0f,
    /** Remind about a persisting obstacle after this long, at most [maxReminders] times. */
    val reminderIntervalMs: Long = 8000,
    val maxReminders: Int = 2,
    /** Say "Path clear" after an announced obstacle leaves the corridor. */
    val announceClear: Boolean = true,
    val units: Units = Units.METERS,
)

enum class Urgency { NONE, CAUTION, STOP }

enum class Direction { LEFT, RIGHT, STOP, NONE }

/** Something worth saying out loud (and buzzing about). */
data class Advisory(
    val text: String,
    val urgency: Urgency,
    val label: String,
    val distanceMeters: Float,
    val direction: Direction,
)

/** A confirmed obstacle for the UI overlay. Coordinates are normalized to the frame. */
data class ObstacleInfo(
    val id: Int,
    val label: String,
    val distanceMeters: Float,
    val left: Float,
    val right: Float,
    val inCorridor: Boolean,
)

/** Snapshot of the scene after processing one frame. */
data class SceneState(
    val urgency: Urgency = Urgency.NONE,
    val headline: String = "Clear",
    val detail: String = "",
    val direction: Direction = Direction.NONE,
    val nearestDistanceMeters: Float? = null,
    val obstacles: List<ObstacleInfo> = emptyList(),
    val calibrated: Boolean = false,
    /** Per-column nearest obstacle distance (meters, +inf when free). For overlays. */
    val columnDistances: FloatArray = FloatArray(0),
)

data class ReasonerOutput(val scene: SceneState, val advisory: Advisory?)
