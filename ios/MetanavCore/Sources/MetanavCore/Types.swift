import Foundation

/// A relative inverse-depth map ("disparity"): larger values are closer to the camera.
/// Both MiDaS and Depth Anything emit this kind of output. Row-major, origin top-left.
public struct DepthMap: Sendable {
    public let width: Int
    public let height: Int
    public let values: [Float]

    public init(width: Int, height: Int, values: [Float]) {
        precondition(width > 0 && height > 0, "DepthMap must have positive dimensions")
        precondition(values.count == width * height, "DepthMap values must be width*height")
        self.width = width
        self.height = height
        self.values = values
    }

    @inline(__always) public subscript(x: Int, y: Int) -> Float { values[y * width + x] }
}

/// Axis-aligned rectangle in normalized image coordinates (0..1, origin top-left).
public struct NormRect: Sendable, Equatable {
    public var x: Float
    public var y: Float
    public var width: Float
    public var height: Float

    public init(x: Float, y: Float, width: Float, height: Float) {
        self.x = x; self.y = y; self.width = width; self.height = height
    }

    public var left: Float { x }
    public var right: Float { x + width }
    public var top: Float { y }
    public var bottom: Float { y + height }
    public var centerX: Float { x + width / 2 }
}

/// One object detection from the object detector (label is the raw model label).
public struct Detection: Sendable, Equatable {
    public var label: String
    public var confidence: Float
    public var box: NormRect

    public init(label: String, confidence: Float, box: NormRect) {
        self.label = label; self.confidence = confidence; self.box = box
    }
}

/// Everything the reasoner needs about one processed frame.
public struct FrameObservation: Sendable {
    public var timestampMs: Int64
    public var depth: DepthMap?
    public var detections: [Detection]

    public init(timestampMs: Int64, depth: DepthMap?, detections: [Detection] = []) {
        self.timestampMs = timestampMs; self.depth = depth; self.detections = detections
    }
}

/// Where the camera sits on the wearer. Defaults describe glasses on an adult looking slightly
/// down while walking. The phone-camera fallback uses a lower height.
public struct CameraGeometry: Sendable, Equatable {
    public var heightMeters: Float
    /// Downward tilt of the optical axis in degrees (positive = looking down).
    public var pitchDegrees: Float
    /// Vertical field of view of the frame in degrees.
    public var verticalFovDegrees: Float

    public init(heightMeters: Float = 1.55, pitchDegrees: Float = 8, verticalFovDegrees: Float = 78) {
        self.heightMeters = heightMeters; self.pitchDegrees = pitchDegrees; self.verticalFovDegrees = verticalFovDegrees
    }
}

public enum Units: String, Sendable, CaseIterable, Codable { case meters, feet }

/// Tunables for the obstacle reasoner. Defaults are sane for walking speed.
public struct ReasonerConfig: Sendable, Equatable {
    public var geometry = CameraGeometry()
    /// Obstacles farther than this are ignored entirely.
    public var alertDistanceMeters: Float = 3.0
    /// Closer than this is "stop now".
    public var urgentDistanceMeters: Float = 1.2
    /// Horizontal band (normalized) that counts as the walking path.
    public var corridorLeft: Float = 0.30
    public var corridorRight: Float = 0.70
    /// Rows scanned for obstacles, as fractions of frame height.
    public var scanTop: Float = 0.15
    public var scanBottom: Float = 0.97
    /// An obstacle pixel must be at least this much closer than the ground at its row.
    public var groundMarginRatio: Float = 0.80
    /// A column needs this fraction of scanned rows flagged before it counts.
    public var minColumnFillRatio: Float = 0.06
    /// Blobs narrower than this fraction of the frame width are noise.
    public var minBlobWidthRatio: Float = 0.07
    /// Temporal confirmation: seen in at least `confirmHits` of the last `confirmWindow` frames.
    public var confirmHits: Int = 3
    public var confirmWindow: Int = 5
    /// Frames a track may go unseen before it is dropped.
    public var maxMissedFrames: Int = 4
    /// Global minimum gap between spoken advisories.
    public var cooldownMs: Int64 = 2500
    public var urgentCooldownMs: Int64 = 900
    /// Re-announce the same obstacle when it got this much closer (meters).
    public var reannounceCloserBy: Float = 1.0
    /// Remind about a persisting obstacle after this long, at most `maxReminders` times.
    public var reminderIntervalMs: Int64 = 8000
    public var maxReminders: Int = 2
    /// Say "Path clear" after an announced obstacle leaves the corridor.
    public var announceClear: Bool = true
    public var units: Units = .meters

    public init() {}
}

public enum Urgency: Sendable { case none, caution, stop }

public enum Direction: Sendable { case left, right, stop, none }

/// Something worth saying out loud (and buzzing about).
public struct Advisory: Sendable, Equatable {
    public var text: String
    public var urgency: Urgency
    public var label: String
    public var distanceMeters: Float
    public var direction: Direction
}

/// A confirmed obstacle for the UI overlay. Coordinates are normalized to the frame.
public struct ObstacleInfo: Sendable, Identifiable, Equatable {
    public var id: Int
    public var label: String
    public var distanceMeters: Float
    public var left: Float
    public var right: Float
    public var inCorridor: Bool
}

/// Snapshot of the scene after processing one frame.
public struct SceneState: Sendable {
    public var urgency: Urgency = .none
    public var headline: String = "Clear"
    public var detail: String = ""
    public var direction: Direction = .none
    public var nearestDistanceMeters: Float? = nil
    public var obstacles: [ObstacleInfo] = []
    public var calibrated: Bool = false
    /// Per-column nearest obstacle distance (meters, +inf when free). For overlays.
    public var columnDistances: [Float] = []

    public init() {}
}

public struct ReasonerOutput: Sendable {
    public var scene: SceneState
    public var advisory: Advisory?
}
