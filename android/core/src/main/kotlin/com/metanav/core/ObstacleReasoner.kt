package com.metanav.core

import kotlin.math.max

/**
 * The whole pipeline after the neural nets: calibrate depth against the ground plane, extract
 * blobs, track them over time, label them with detections, and decide what (if anything) to say.
 * Pure Kotlin, no Android dependencies, deterministic given the inputs.
 */
class ObstacleReasoner(config: ReasonerConfig = ReasonerConfig()) {
    var config: ReasonerConfig = config
        set(value) {
            val geometryChanged = value.geometry != field.geometry
            field = value
            extractor = ObstacleExtractor(value)
            tracker = ObstacleTracker(value)
            policy = AdvisoryPolicy(value)
            if (geometryChanged) { groundCache = null; calibrator.reset() }
        }

    private val calibrator = DepthScaleCalibrator()
    private var extractor = ObstacleExtractor(config)
    private var tracker = ObstacleTracker(config)
    private var policy = AdvisoryPolicy(config)
    private var groundCache: GroundPlaneModel? = null
    private var lastScene = SceneState()

    val isCalibrated: Boolean get() = calibrator.isCalibrated

    fun reset() {
        calibrator.reset()
        tracker.reset()
        policy.reset()
        lastScene = SceneState()
    }

    fun process(observation: FrameObservation): ReasonerOutput {
        val depth = observation.depth ?: return ReasonerOutput(lastScene, null)
        val ground = groundFor(depth.height)
        calibrator.update(depth, ground)

        val extraction = extractor.extract(depth, calibrator, ground)
        val tracks = tracker.update(extraction.blobs)
        tracker.applyDetections(observation.detections, ground, depth.height)
        val frame = tracker.currentFrame()

        val confirmed = tracks.filter { it.isConfirmed(frame, config) && it.lastSeenFrame == frame }
        val inCorridor = confirmed.filter { it.overlapsCorridor(config) && it.distanceMeters < config.alertDistanceMeters }
        val candidate = inCorridor.minByOrNull { it.distanceMeters }

        val direction = if (candidate != null) chooseDirection(candidate, extraction) else Direction.NONE
        val advisory = policy.decide(observation.timestampMs, candidate, direction, corridorBlocked = candidate != null)

        val urgency = when {
            candidate == null -> Urgency.NONE
            candidate.distanceMeters < config.urgentDistanceMeters -> Urgency.STOP
            else -> Urgency.CAUTION
        }
        val nearest = confirmed.minOfOrNull { it.distanceMeters }
        val scene = SceneState(
            urgency = urgency,
            headline = when {
                !calibrator.isCalibrated -> "Calibrating"
                candidate == null -> "Clear"
                urgency == Urgency.STOP -> "Stop"
                else -> candidate.label
            },
            detail = when {
                !calibrator.isCalibrated -> "Point the camera down the path"
                candidate == null -> nearest?.let { "Nearest ${AdvisoryPolicy.shortDistance(it, config.units)}, off path" } ?: ""
                else -> "${AdvisoryPolicy.shortDistance(candidate.distanceMeters, config.units)} · ${directionText(direction)}"
            },
            direction = direction,
            nearestDistanceMeters = candidate?.distanceMeters ?: nearest,
            obstacles = confirmed.map {
                ObstacleInfo(it.id, it.label, it.distanceMeters, it.left, it.right, it.overlapsCorridor(config))
            },
            calibrated = calibrator.isCalibrated,
            columnDistances = extraction.columnDistances,
        )
        lastScene = scene
        return ReasonerOutput(scene, advisory)
    }

    private fun chooseDirection(candidate: Track, extraction: Extraction): Direction {
        val z = candidate.distanceMeters
        val leftFree = extraction.leftFreeMeters
        val rightFree = extraction.rightFreeMeters
        val minUseful = max(1.5f, z + 0.5f)
        val leftOk = leftFree >= minUseful
        val rightOk = rightFree >= minUseful
        // Prefer the side away from the obstacle's bulk; fall back to whichever side is freer.
        val awayFromObstacle = if (candidate.centerX > 0.5f) Direction.LEFT else Direction.RIGHT
        return when {
            leftOk && rightOk -> awayFromObstacle
            leftOk -> Direction.LEFT
            rightOk -> Direction.RIGHT
            else -> Direction.STOP
        }
    }

    private fun directionText(direction: Direction) = when (direction) {
        Direction.LEFT -> "move left"
        Direction.RIGHT -> "move right"
        Direction.STOP -> "stop"
        Direction.NONE -> ""
    }

    private fun groundFor(rows: Int): GroundPlaneModel {
        val cached = groundCache
        if (cached != null && cached.rows == rows) return cached
        return GroundPlaneModel(config.geometry, rows).also { groundCache = it }
    }
}
