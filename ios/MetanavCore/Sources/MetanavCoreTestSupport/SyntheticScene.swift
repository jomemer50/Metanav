import Foundation
import MetanavCore

/// Renders synthetic relative-depth maps from the same pinhole geometry the reasoner assumes:
/// flat ground plus optional upright boxes. Disparity is an arbitrary affine map of 1/z so the
/// tests exercise the calibration path exactly like a real depth model would.
public struct SyntheticScene {
    public struct Box {
        public var left: Float
        public var right: Float
        public var distance: Float
        public var heightMeters: Float

        public init(left: Float, right: Float, distance: Float, heightMeters: Float = 1.0) {
            self.left = left; self.right = right; self.distance = distance; self.heightMeters = heightMeters
        }
    }

    public let width: Int
    public let height: Int
    public let geometry: CameraGeometry
    let scaleA: Float
    let offsetB: Float
    private let ground: GroundPlaneModel
    private let focal: Float
    private let pitchRad: Double

    public init(width: Int = 64, height: Int = 96, geometry: CameraGeometry = CameraGeometry(), scaleA: Float = 1000, offsetB: Float = 5) {
        self.width = width
        self.height = height
        self.geometry = geometry
        self.scaleA = scaleA
        self.offsetB = offsetB
        ground = GroundPlaneModel(geometry: geometry, rows: height)
        focal = ground.focalLengthPx()
        pitchRad = Double(geometry.pitchDegrees) * .pi / 180
    }

    public func render(boxes: [Box] = [], noise: Float = 0, seed: UInt64 = 1) -> DepthMap {
        var values = [Float](repeating: 0, count: width * height)
        var rng = SplitMix64(seed: seed)
        for y in 0..<height {
            for x in 0..<width {
                var z = ground.groundDepth(row: y)
                if !z.isFinite { z = 60 } // "sky" / far wall
                let nx = (Float(x) + 0.5) / Float(width)
                for box in boxes {
                    if nx < box.left || nx >= box.right { continue }
                    if y < topRow(box) || box.distance > ground.groundDepth(row: y) { continue }
                    if box.distance < z { z = box.distance }
                }
                var disp = scaleA / z + offsetB
                if noise > 0 { disp += (rng.nextFloat() * 2 - 1) * noise * disp }
                values[y * width + x] = disp
            }
        }
        return DepthMap(width: width, height: height, values: values)
    }

    /// First image row (from the top) covered by an upright box at its distance.
    private func topRow(_ box: Box) -> Int {
        let dh = Double(geometry.heightMeters - box.heightMeters)
        let angleBelowHorizontal = atan(dh / Double(box.distance))
        let v = Double(focal) * tan(angleBelowHorizontal - pitchRad)
        return min(max(Int(Double(height) / 2 + v), 0), height - 1)
    }
}

public struct SplitMix64 {
    public var state: UInt64
    public init(seed: UInt64) { state = seed &+ 0x9E3779B97F4A7C15 }
    public mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
    public mutating func nextFloat() -> Float { Float(next() >> 40) / Float(1 << 24) }
}
