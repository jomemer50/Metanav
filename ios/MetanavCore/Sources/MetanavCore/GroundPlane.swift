import Foundation

/// Pinhole ground-plane model: for a camera at a known height and pitch, every image row below
/// the horizon corresponds to a known distance along the ground. Anything appearing at that row
/// but closer than the ground must stick up from it, i.e. it is an obstacle.
public final class GroundPlaneModel: @unchecked Sendable {
    public let geometry: CameraGeometry
    public let rows: Int
    private let focalPx: Float
    private let pitchRad: Double
    private let depthPerRow: [Float]

    public init(geometry: CameraGeometry, rows: Int) {
        self.geometry = geometry
        self.rows = rows
        let halfFov = Double(geometry.verticalFovDegrees) / 2 * .pi / 180
        let focal = Float((Double(rows) / 2) / tan(halfFov))
        let pitch = Double(geometry.pitchDegrees) * .pi / 180
        focalPx = focal
        pitchRad = pitch
        var table = [Float](repeating: .infinity, count: rows)
        for row in 0..<rows {
            let v = (Double(row) + 0.5) - Double(rows) / 2
            let angleBelowHorizontal = pitch + atan(v / Double(focal))
            if angleBelowHorizontal > 0.01 {
                table[row] = Float(Double(geometry.heightMeters) / tan(angleBelowHorizontal))
            }
        }
        depthPerRow = table
    }

    /// Perpendicular distance (meters) to the ground point seen at `row`, or +inf above the horizon.
    @inline(__always) public func groundDepth(row: Int) -> Float {
        depthPerRow[min(max(row, 0), rows - 1)]
    }

    /// Row index of the horizon (may be negative or beyond the frame).
    public var horizonRow: Int { Int(Double(rows) / 2 - Double(focalPx) * tan(pitchRad)) }

    public func focalLengthPx() -> Float { focalPx }
}

/// Converts relative inverse depth into meters. Relative depth models are affine-invariant in
/// inverse-depth space: disp = a * (1/z) + b. We fit a and b every frame from the bottom of the
/// frame, which is almost always ground at a geometrically known distance, then smooth over time.
public final class DepthScaleCalibrator {
    public private(set) var a: Float = 0
    public private(set) var b: Float = 0
    public var isCalibrated: Bool { a > 0 }
    /// True when the latest frame produced a usable fit (ground visible, not blocked).
    public private(set) var lastFitAccepted = false

    public init() {}

    /// Fit from the bottom rows of `depth`. Returns true when the fit was accepted. When the bottom
    /// of the frame is not ground (an obstacle fills it) the fit is rejected and the previous
    /// calibration is kept, which is exactly what makes that obstacle show up as an obstacle.
    @discardableResult
    public func update(depth: DepthMap, ground: GroundPlaneModel) -> Bool {
        let h = depth.height, w = depth.width
        let rowStart = Int(Float(h) * 0.80)
        let rowEnd = min(Int(Float(h) * 0.985), h - 1)
        let colStart = Int(Float(w) * 0.30)
        let colEnd = max(Int(Float(w) * 0.70), colStart + 1)

        var xs: [Float] = [], ys: [Float] = []
        var scratch = [Float](repeating: 0, count: colEnd - colStart)
        var row = rowStart
        while row <= rowEnd {
            let z = ground.groundDepth(row: row)
            if z.isFinite {
                for c in colStart..<colEnd { scratch[c - colStart] = depth[c, row] }
                xs.append(1 / z)
                ys.append(Self.median(scratch))
            }
            row += 1
        }
        lastFitAccepted = false
        if xs.count < 4 { return false }

        let n = Float(xs.count)
        let meanX = xs.reduce(0, +) / n
        let meanY = ys.reduce(0, +) / n
        var sxx: Float = 0, sxy: Float = 0, syy: Float = 0
        for i in 0..<xs.count {
            let dx = xs[i] - meanX, dy = ys[i] - meanY
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        if sxx <= 1e-9 || syy <= 1e-12 { return false }
        let slope = sxy / sxx
        let intercept = meanY - slope * meanX
        let r2 = (sxy * sxy) / (sxx * syy)

        // Ground shows a clear gradient toward the bottom of the frame. A flat profile means the
        // bottom of the frame is a wall, a table, a person: not ground. Keep the old calibration.
        let spread = (ys.max()! - ys.min()!) / max(abs(meanY), 1e-6)
        if slope <= 0 || r2 < 0.6 || spread < 0.10 { return false }

        if !isCalibrated {
            a = slope; b = intercept
        } else {
            a = 0.7 * a + 0.3 * slope
            b = 0.7 * b + 0.3 * intercept
        }
        lastFitAccepted = true
        return true
    }

    /// Meters for a relative disparity value; +inf when the value is at or beyond the horizon.
    @inline(__always) public func metricDepth(_ disparity: Float) -> Float {
        if a <= 0 { return .infinity }
        let d = disparity - b
        if d <= 1e-6 { return .infinity }
        return a / d
    }

    public func reset() { a = 0; b = 0; lastFitAccepted = false }

    static func median(_ values: [Float]) -> Float {
        let sorted = values.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
    }
}
