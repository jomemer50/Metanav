import XCTest
@testable import MetanavCore
import MetanavCoreTestSupport

final class GroundPlaneTests: XCTestCase {
    func testGroundDepthDecreasesTowardBottomOfFrame() {
        let model = GroundPlaneModel(geometry: CameraGeometry(), rows: 100)
        XCTAssertTrue(model.groundDepth(row: 0).isInfinite || model.groundDepth(row: 0) > model.groundDepth(row: 50))
        XCTAssertGreaterThan(model.groundDepth(row: 50), model.groundDepth(row: 80))
        XCTAssertGreaterThan(model.groundDepth(row: 80), model.groundDepth(row: 99))
        XCTAssertGreaterThan(model.groundDepth(row: 99), 0.5)
    }

    func testCalibrationRecoversMetricDepth() {
        let scene = SyntheticScene()
        let calibrator = DepthScaleCalibrator()
        let ground = GroundPlaneModel(geometry: scene.geometry, rows: scene.height)
        let depth = scene.render()
        XCTAssertTrue(calibrator.update(depth: depth, ground: ground))
        let row = Int(Float(scene.height) * 0.7)
        let z = calibrator.metricDepth(depth[scene.width / 2, row])
        let expected = ground.groundDepth(row: row)
        XCTAssertLessThan(abs(z - expected) / expected, 0.05, "expected \(expected) got \(z)")
    }

    func testCalibrationRejectsFlatBottomOfFrame() {
        let scene = SyntheticScene()
        let calibrator = DepthScaleCalibrator()
        let ground = GroundPlaneModel(geometry: scene.geometry, rows: scene.height)
        let wall = scene.render(boxes: [.init(left: 0, right: 1, distance: 0.6, heightMeters: 3)])
        XCTAssertFalse(calibrator.update(depth: wall, ground: ground))
        XCTAssertFalse(calibrator.isCalibrated)
    }

    func testCalibrationSurvivesNoise() {
        let scene = SyntheticScene()
        let calibrator = DepthScaleCalibrator()
        let ground = GroundPlaneModel(geometry: scene.geometry, rows: scene.height)
        XCTAssertTrue(calibrator.update(depth: scene.render(noise: 0.05), ground: ground))
    }
}
