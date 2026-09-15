import Foundation

/// The whole pipeline after the neural nets: calibrate depth against the ground plane, extract
/// blobs, track them over time, label them with detections, and decide what (if anything) to say.
/// Pure Swift, no UIKit, deterministic given the inputs. Not thread-safe: call from one queue.
public final class ObstacleReasoner {
    public var config: ReasonerConfig {
        didSet {
            extractor = ObstacleExtractor(config: config)
            tracker = ObstacleTracker(config: config)
            policy = AdvisoryPolicy(config: config)
            if config.geometry != oldValue.geometry { groundCache = nil; calibrator.reset() }
        }
    }

    private let calibrator = DepthScaleCalibrator()
    private var extractor: ObstacleExtractor
    private var tracker: ObstacleTracker
    private var policy: AdvisoryPolicy
    private var groundCache: GroundPlaneModel?
    private var lastScene = SceneState()

    public init(config: ReasonerConfig = ReasonerConfig()) {
        self.config = config
        extractor = ObstacleExtractor(config: config)
        tracker = ObstacleTracker(config: config)
        policy = AdvisoryPolicy(config: config)
    }

    public var isCalibrated: Bool { calibrator.isCalibrated }

    public func reset() {
        calibrator.reset()
        tracker.reset()
        policy.reset()
        lastScene = SceneState()
    }

    public func process(_ observation: FrameObservation) -> ReasonerOutput {
        guard let depth = observation.depth else { return ReasonerOutput(scene: lastScene, advisory: nil) }
        let ground = groundFor(rows: depth.height)
        calibrator.update(depth: depth, ground: ground)

        let extraction = extractor.extract(depth: depth, calibrator: calibrator, ground: ground)
        let tracks = tracker.update(blobs: extraction.blobs)
        tracker.applyDetections(observation.detections, ground: ground, frameHeightPx: depth.height)
        let frame = tracker.currentFrame()

        let confirmed = tracks.filter { $0.isConfirmed(frameIndex: frame, config: config) && $0.lastSeenFrame == frame }
        let inCorridor = confirmed.filter { $0.overlapsCorridor(config) && $0.distanceMeters < config.alertDistanceMeters }
        let candidate = inCorridor.min { $0.distanceMeters < $1.distanceMeters }

        let direction: Direction = candidate.map { chooseDirection($0, extraction) } ?? .none
        let advisory = policy.decide(nowMs: observation.timestampMs, candidate: candidate, direction: direction, corridorBlocked: candidate != nil)

        let urgency: Urgency
        if let candidate {
            urgency = candidate.distanceMeters < config.urgentDistanceMeters ? .stop : .caution
        } else {
            urgency = .none
        }
        let nearest = confirmed.map(\.distanceMeters).min()

        var scene = SceneState()
        scene.urgency = urgency
        if !calibrator.isCalibrated {
            scene.headline = "Calibrating"
            scene.detail = "Point the camera down the path"
        } else if let candidate {
            scene.headline = urgency == .stop ? "Stop" : candidate.label
            scene.detail = "\(AdvisoryPolicy.shortDistance(candidate.distanceMeters, units: config.units)) · \(directionText(direction))"
        } else {
            scene.headline = "Clear"
            scene.detail = nearest.map { "Nearest \(AdvisoryPolicy.shortDistance($0, units: config.units)), off path" } ?? ""
        }
        scene.direction = direction
        scene.nearestDistanceMeters = candidate?.distanceMeters ?? nearest
        scene.obstacles = confirmed.map {
            ObstacleInfo(id: $0.id, label: $0.label, distanceMeters: $0.distanceMeters, left: $0.left, right: $0.right, inCorridor: $0.overlapsCorridor(config))
        }
        scene.calibrated = calibrator.isCalibrated
        scene.columnDistances = extraction.columnDistances
        lastScene = scene
        return ReasonerOutput(scene: scene, advisory: advisory)
    }

    private func chooseDirection(_ candidate: Track, _ extraction: Extraction) -> Direction {
        let z = candidate.distanceMeters
        let minUseful = max(1.5, z + 0.5)
        let leftOk = extraction.leftFreeMeters >= minUseful
        let rightOk = extraction.rightFreeMeters >= minUseful
        // Prefer the side away from the obstacle's bulk; fall back to whichever side is freer.
        let awayFromObstacle: Direction = candidate.centerX > 0.5 ? .left : .right
        switch (leftOk, rightOk) {
        case (true, true): return awayFromObstacle
        case (true, false): return .left
        case (false, true): return .right
        default: return .stop
        }
    }

    private func directionText(_ direction: Direction) -> String {
        switch direction {
        case .left: return "move left"
        case .right: return "move right"
        case .stop: return "stop"
        case .none: return ""
        }
    }

    private func groundFor(rows: Int) -> GroundPlaneModel {
        if let cached = groundCache, cached.rows == rows { return cached }
        let model = GroundPlaneModel(geometry: config.geometry, rows: rows)
        groundCache = model
        return model
    }
}
