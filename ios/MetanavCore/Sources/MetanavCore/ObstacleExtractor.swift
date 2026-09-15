import Foundation

/// A vertical slab of "stuff closer than the ground" found in one frame.
public struct Blob: Sendable, Equatable {
    /// Normalized horizontal extent.
    public var left: Float
    public var right: Float
    public var distanceMeters: Float

    public var centerX: Float { (left + right) / 2 }
    public var width: Float { right - left }
}

public struct Extraction: Sendable {
    public var blobs: [Blob]
    /// Nearest obstacle distance per column (meters, +inf when free).
    public var columnDistances: [Float]
    /// How far ahead the left / right thirds are free (meters, capped).
    public var leftFreeMeters: Float
    public var rightFreeMeters: Float
}

/// Turns a calibrated depth map into obstacle blobs. Per column we look for pixels that are both
/// within the alert distance and clearly closer than the ground would be at that row; enough such
/// pixels make the column "blocked" at the 20th-percentile distance. Adjacent blocked columns form
/// blobs.
public struct ObstacleExtractor {
    let config: ReasonerConfig

    public init(config: ReasonerConfig) { self.config = config }

    public func extract(depth: DepthMap, calibrator: DepthScaleCalibrator, ground: GroundPlaneModel) -> Extraction {
        let w = depth.width, h = depth.height
        var columnDistances = [Float](repeating: .infinity, count: w)
        guard calibrator.isCalibrated else {
            return Extraction(blobs: [], columnDistances: columnDistances, leftFreeMeters: config.alertDistanceMeters, rightFreeMeters: config.alertDistanceMeters)
        }
        let rowStart = min(max(Int(Float(h) * config.scanTop), 0), h - 1)
        let rowEnd = min(max(Int(Float(h) * config.scanBottom), rowStart + 1), h)
        let rowsScanned = rowEnd - rowStart
        let minCount = max(2, Int(Float(rowsScanned) * config.minColumnFillRatio))
        let alert = config.alertDistanceMeters

        var groundThreshold = [Float](repeating: .infinity, count: h)
        for y in rowStart..<rowEnd {
            let g = ground.groundDepth(row: y)
            groundThreshold[y] = g.isFinite ? g * config.groundMarginRatio : .infinity
        }

        var scratch = [Float](repeating: 0, count: rowsScanned)
        let values = depth.values
        for x in 0..<w {
            var count = 0
            for y in rowStart..<rowEnd {
                let z = calibrator.metricDepth(values[y * w + x])
                if z < alert && z < groundThreshold[y] {
                    scratch[count] = z
                    count += 1
                }
            }
            if count >= minCount {
                columnDistances[x] = Self.percentile(scratch, count: count, p: 0.20)
            }
        }
        Self.smoothColumns(&columnDistances)

        let blobs = findBlobs(columnDistances, w: w)
        let leftFree = freeDistance(columnDistances, start: Int(Float(w) * 0.05), end: Int(Float(w) * 0.35))
        let rightFree = freeDistance(columnDistances, start: Int(Float(w) * 0.65), end: Int(Float(w) * 0.95))
        return Extraction(blobs: blobs, columnDistances: columnDistances, leftFreeMeters: leftFree, rightFreeMeters: rightFree)
    }

    private func findBlobs(_ columns: [Float], w: Int) -> [Blob] {
        let alert = config.alertDistanceMeters
        let minWidth = max(2, Int(Float(w) * config.minBlobWidthRatio))
        var blobs: [Blob] = []
        var runStart = -1
        var gap = 0
        var x = 0
        while x <= w {
            let blocked = x < w && columns[x] < alert
            if blocked {
                if runStart < 0 { runStart = x }
                gap = 0
            } else if runStart >= 0 {
                gap += 1
                if gap > 2 || x == w {
                    let runEnd = x - gap
                    if runEnd - runStart >= minWidth {
                        blobs.append(makeBlob(columns, start: runStart, end: runEnd, w: w))
                    }
                    runStart = -1
                    gap = 0
                }
            }
            x += 1
        }
        return blobs
    }

    private func makeBlob(_ columns: [Float], start: Int, end: Int, w: Int) -> Blob {
        var values = [Float](repeating: 0, count: end - start)
        var n = 0
        for x in start..<end where columns[x].isFinite { values[n] = columns[x]; n += 1 }
        let distance = n > 0 ? Self.percentile(values, count: n, p: 0.25) : config.alertDistanceMeters
        return Blob(left: Float(start) / Float(w), right: Float(end) / Float(w), distanceMeters: distance)
    }

    private func freeDistance(_ columns: [Float], start: Int, end: Int) -> Float {
        let cap = config.alertDistanceMeters + 1
        var sum: Float = 0
        var n = 0
        var x = start
        while x < min(end, columns.count) {
            sum += min(columns[x], cap)
            n += 1
            x += 1
        }
        return n == 0 ? cap : sum / Float(n)
    }

    private static func smoothColumns(_ columns: inout [Float]) {
        guard columns.count >= 3 else { return }
        let copy = columns
        for x in 1..<(columns.count - 1) {
            columns[x] = median3(copy[x - 1], copy[x], copy[x + 1])
        }
    }

    @inline(__always) private static func median3(_ a: Float, _ b: Float, _ c: Float) -> Float {
        max(min(a, b), min(max(a, b), c))
    }

    static func percentile(_ values: [Float], count: Int, p: Float) -> Float {
        let sorted = values[0..<count].sorted()
        let idx = min(max(Int(p * Float(count - 1)), 0), count - 1)
        return sorted[idx]
    }
}
