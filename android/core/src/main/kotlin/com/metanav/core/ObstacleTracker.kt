package com.metanav.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A blob followed across frames so that one noisy frame can neither trigger nor cancel an alert. */
class Track internal constructor(val id: Int, blob: Blob, frameIndex: Int) {
    var left: Float = blob.left
        private set
    var right: Float = blob.right
        private set
    var distanceMeters: Float = blob.distanceMeters
        private set
    var lastSeenFrame: Int = frameIndex
        private set
    var firstSeenFrame: Int = frameIndex
        private set
    private val labelVotes = HashMap<String, Int>()

    /** Ring of frame indices in which this track was observed. */
    private val hitFrames = ArrayDeque<Int>()

    // Announcement bookkeeping, owned by the policy.
    var announcedCount: Int = 0
    var lastAnnouncedDistance: Float = Float.POSITIVE_INFINITY
    var lastAnnouncedAtMs: Long = AdvisoryPolicy.NEVER
    var lastAnnouncedUrgency: Urgency = Urgency.NONE
    var reminders: Int = 0

    val centerX: Float get() = (left + right) / 2f

    val label: String
        get() = labelVotes.maxByOrNull { it.value }?.key ?: Labels.GENERIC

    init { hitFrames.addLast(frameIndex) }

    internal fun update(blob: Blob, frameIndex: Int, window: Int) {
        left = 0.5f * left + 0.5f * blob.left
        right = 0.5f * right + 0.5f * blob.right
        distanceMeters = 0.5f * distanceMeters + 0.5f * blob.distanceMeters
        lastSeenFrame = frameIndex
        hitFrames.addLast(frameIndex)
        while (hitFrames.isNotEmpty() && hitFrames.first() <= frameIndex - window) hitFrames.removeFirst()
    }

    internal fun vote(label: String) {
        labelVotes[label] = (labelVotes[label] ?: 0) + 1
    }

    internal fun fuseDistance(meters: Float) {
        // Size-based estimates are coarse; blend gently and only when they roughly agree.
        val ratio = meters / distanceMeters
        if (ratio in 0.5f..2.0f) distanceMeters = 0.75f * distanceMeters + 0.25f * meters
    }

    fun hitsInWindow(frameIndex: Int, window: Int): Int = hitFrames.count { it > frameIndex - window }

    fun isConfirmed(frameIndex: Int, config: ReasonerConfig): Boolean =
        hitsInWindow(frameIndex, config.confirmWindow) >= config.confirmHits

    fun overlapsCorridor(config: ReasonerConfig): Boolean {
        val overlap = min(right, config.corridorRight) - max(left, config.corridorLeft)
        if (overlap <= 0f) return false
        val needed = min(0.4f * (right - left), 0.08f)
        return overlap >= needed
    }
}

class ObstacleTracker(private val config: ReasonerConfig) {
    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var frameIndex = 0

    val active: List<Track> get() = tracks

    fun currentFrame(): Int = frameIndex

    fun update(blobs: List<Blob>): List<Track> {
        frameIndex++
        val unmatched = blobs.toMutableList()
        // Greedy association by horizontal overlap, closest-first.
        for (track in tracks.sortedBy { it.distanceMeters }) {
            var best: Blob? = null
            var bestScore = 0f
            for (blob in unmatched) {
                val score = overlap1d(track.left, track.right, blob.left, blob.right)
                val centerGap = abs(track.centerX - blob.centerX)
                val s = if (score > 0.3f || centerGap < 0.15f) max(score, 0.3f - centerGap) else 0f
                if (s > bestScore) { bestScore = s; best = blob }
            }
            if (best != null) {
                track.update(best, frameIndex, config.confirmWindow)
                unmatched.remove(best)
            }
        }
        for (blob in unmatched) tracks.add(Track(nextId++, blob, frameIndex))
        tracks.removeAll { frameIndex - it.lastSeenFrame > config.maxMissedFrames }
        return tracks
    }

    /** Attach detector labels (and size-based distances) to tracks seen this frame. */
    fun applyDetections(detections: List<Detection>, ground: GroundPlaneModel, frameHeightPx: Int) {
        if (detections.isEmpty()) return
        for (track in tracks) {
            if (track.lastSeenFrame != frameIndex) continue
            var best: Detection? = null
            var bestOverlap = 0f
            for (det in detections) {
                if (det.confidence < 0.35f) continue
                if (det.box.height < 0.10f || det.box.bottom < 0.30f) continue
                val overlap = overlap1d(track.left, track.right, det.box.left, det.box.right)
                if (overlap > bestOverlap) { bestOverlap = overlap; best = det }
            }
            if (best != null && bestOverlap >= 0.4f) {
                track.vote(Labels.friendlyName(best.label))
                val known = Labels.knownHeightMeters(best.label)
                val truncated = best.box.top < 0.02f || best.box.bottom > 0.98f
                if (known != null && !truncated) {
                    val boxHeightPx = best.box.height * frameHeightPx
                    val z = known * ground.focalLengthPx() / boxHeightPx
                    track.fuseDistance(z)
                }
            }
        }
    }

    fun reset() {
        tracks.clear()
        frameIndex = 0
    }

    /** Intersection over the smaller extent, in 1D. */
    private fun overlap1d(l1: Float, r1: Float, l2: Float, r2: Float): Float {
        val inter = min(r1, r2) - max(l1, l2)
        if (inter <= 0f) return 0f
        val smaller = min(r1 - l1, r2 - l2)
        return if (smaller <= 1e-6f) 0f else inter / smaller
    }
}
