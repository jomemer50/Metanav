import Foundation

/// A blob followed across frames so that one noisy frame can neither trigger nor cancel an alert.
public final class Track {
    public let id: Int
    public private(set) var left: Float
    public private(set) var right: Float
    public private(set) var distanceMeters: Float
    public private(set) var lastSeenFrame: Int
    public let firstSeenFrame: Int
    private var labelVotes: [String: Int] = [:]
    /// Frame indices in which this track was observed (bounded by the confirmation window).
    private var hitFrames: [Int] = []

    // Announcement bookkeeping, owned by the policy.
    var announcedCount = 0
    var lastAnnouncedDistance: Float = .infinity
    var lastAnnouncedAtMs: Int64 = AdvisoryPolicy.never
    var lastAnnouncedUrgency: Urgency = .none
    var reminders = 0

    init(id: Int, blob: Blob, frameIndex: Int) {
        self.id = id
        left = blob.left
        right = blob.right
        distanceMeters = blob.distanceMeters
        lastSeenFrame = frameIndex
        firstSeenFrame = frameIndex
        hitFrames = [frameIndex]
    }

    public var centerX: Float { (left + right) / 2 }

    public var label: String {
        labelVotes.max { $0.value < $1.value }?.key ?? Labels.generic
    }

    func update(blob: Blob, frameIndex: Int, window: Int) {
        left = 0.5 * left + 0.5 * blob.left
        right = 0.5 * right + 0.5 * blob.right
        distanceMeters = 0.6 * distanceMeters + 0.4 * blob.distanceMeters
        lastSeenFrame = frameIndex
        hitFrames.append(frameIndex)
        hitFrames.removeAll { $0 <= frameIndex - window }
    }

    func vote(_ label: String) { labelVotes[label, default: 0] += 1 }

    func fuseDistance(_ meters: Float) {
        // Size-based estimates are coarse; blend gently and only when they roughly agree.
        let ratio = meters / distanceMeters
        if ratio >= 0.5 && ratio <= 2.0 { distanceMeters = 0.75 * distanceMeters + 0.25 * meters }
    }

    public func hitsInWindow(frameIndex: Int, window: Int) -> Int {
        hitFrames.filter { $0 > frameIndex - window }.count
    }

    public func isConfirmed(frameIndex: Int, config: ReasonerConfig) -> Bool {
        hitsInWindow(frameIndex: frameIndex, window: config.confirmWindow) >= config.confirmHits
    }

    public func overlapsCorridor(_ config: ReasonerConfig) -> Bool {
        let overlap = min(right, config.corridorRight) - max(left, config.corridorLeft)
        if overlap <= 0 { return false }
        let needed = min(0.4 * (right - left), 0.08)
        return overlap >= needed
    }
}

public final class ObstacleTracker {
    private let config: ReasonerConfig
    private var tracks: [Track] = []
    private var nextId = 1
    private var frameIndex = 0

    public init(config: ReasonerConfig) { self.config = config }

    public var active: [Track] { tracks }
    public func currentFrame() -> Int { frameIndex }

    @discardableResult
    public func update(blobs: [Blob]) -> [Track] {
        frameIndex += 1
        var unmatched = blobs
        // Greedy association by horizontal overlap, closest-first.
        for track in tracks.sorted(by: { $0.distanceMeters < $1.distanceMeters }) {
            var bestIndex: Int? = nil
            var bestScore: Float = 0
            for (i, blob) in unmatched.enumerated() {
                let score = Self.overlap1d(track.left, track.right, blob.left, blob.right)
                let centerGap = abs(track.centerX - blob.centerX)
                let s: Float = (score > 0.3 || centerGap < 0.15) ? max(score, 0.3 - centerGap) : 0
                if s > bestScore { bestScore = s; bestIndex = i }
            }
            if let i = bestIndex {
                track.update(blob: unmatched[i], frameIndex: frameIndex, window: config.confirmWindow)
                unmatched.remove(at: i)
            }
        }
        for blob in unmatched {
            tracks.append(Track(id: nextId, blob: blob, frameIndex: frameIndex))
            nextId += 1
        }
        tracks.removeAll { frameIndex - $0.lastSeenFrame > config.maxMissedFrames }
        return tracks
    }

    /// Attach detector labels (and size-based distances) to tracks seen this frame.
    public func applyDetections(_ detections: [Detection], ground: GroundPlaneModel, frameHeightPx: Int) {
        guard !detections.isEmpty else { return }
        for track in tracks where track.lastSeenFrame == frameIndex {
            var best: Detection? = nil
            var bestOverlap: Float = 0
            for det in detections {
                if det.confidence < 0.35 { continue }
                if det.box.height < 0.10 || det.box.bottom < 0.30 { continue }
                let overlap = Self.overlap1d(track.left, track.right, det.box.left, det.box.right)
                if overlap > bestOverlap { bestOverlap = overlap; best = det }
            }
            if let best, bestOverlap >= 0.4 {
                track.vote(Labels.friendlyName(best.label))
                let truncated = best.box.top < 0.02 || best.box.bottom > 0.98
                if let known = Labels.knownHeightMeters(best.label), !truncated {
                    let boxHeightPx = best.box.height * Float(frameHeightPx)
                    track.fuseDistance(known * ground.focalLengthPx() / boxHeightPx)
                }
            }
        }
    }

    public func reset() {
        tracks.removeAll()
        frameIndex = 0
    }

    /// Intersection over the smaller extent, in 1D.
    static func overlap1d(_ l1: Float, _ r1: Float, _ l2: Float, _ r2: Float) -> Float {
        let inter = min(r1, r2) - max(l1, l2)
        if inter <= 0 { return 0 }
        let smaller = min(r1 - l1, r2 - l2)
        return smaller <= 1e-6 ? 0 : inter / smaller
    }
}
