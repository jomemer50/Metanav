import XCTest
@testable import MetanavCore
import MetanavCoreTestSupport

final class ObstacleReasonerTests: XCTestCase {
    private let frameMs: Int64 = 150

    private func run(
        _ reasoner: ObstacleReasoner,
        scene: SyntheticScene = SyntheticScene(),
        frames: Int,
        detections: [Detection] = [],
        boxesAt: (Int) -> [SyntheticScene.Box]
    ) -> [ReasonerOutput] {
        (0..<frames).map { i in
            let t = Int64(i) * frameMs
            let depth = scene.render(boxes: boxesAt(i), noise: 0.02, seed: UInt64(i + 7))
            return reasoner.process(FrameObservation(timestampMs: t, depth: depth, detections: detections))
        }
    }

    func testFlatGroundStaysQuiet() {
        let results = run(ObstacleReasoner(), frames: 15) { _ in [] }
        XCTAssertTrue(results.allSatisfy { $0.advisory == nil })
        XCTAssertEqual(results.last!.scene.urgency, .none)
        XCTAssertTrue(results.last!.scene.calibrated)
        XCTAssertEqual(results.last!.scene.headline, "Clear")
    }

    func testBoxInPathIsAnnouncedOnce() {
        let box = SyntheticScene.Box(left: 0.40, right: 0.70, distance: 2.0)
        let results = run(ObstacleReasoner(), frames: 20) { _ in [box] }
        let advisories = results.compactMap(\.advisory)
        XCTAssertEqual(advisories.count, 1, "\(advisories)")
        let a = advisories[0]
        XCTAssertEqual(a.urgency, .caution)
        XCTAssertLessThan(abs(a.distanceMeters - 2.0), 0.4, "distance \(a.distanceMeters)")
        XCTAssertEqual(a.direction, .left)
        XCTAssertTrue(a.text.hasPrefix("Obstacle ahead, 2 meters"), a.text)
        XCTAssertTrue(a.text.hasSuffix("Move left."), a.text)
        XCTAssertNil(results[0].advisory)
        XCTAssertEqual(results.last!.scene.urgency, .caution)
    }

    func testDetectorLabelNamesTheObstacle() {
        let box = SyntheticScene.Box(left: 0.30, right: 0.55, distance: 2.2, heightMeters: 1.7)
        let person = Detection(label: "person", confidence: 0.9, box: NormRect(x: 0.30, y: 0.20, width: 0.25, height: 0.70))
        let results = run(ObstacleReasoner(), frames: 12, detections: [person]) { _ in [box] }
        let a = results.compactMap(\.advisory).first!
        XCTAssertEqual(a.label, "Person")
        XCTAssertTrue(a.text.hasPrefix("Person ahead"), a.text)
        XCTAssertEqual(a.direction, .right)
    }

    func testOffPathObstacleIsNotAnnounced() {
        let box = SyntheticScene.Box(left: 0.02, right: 0.22, distance: 1.5)
        let results = run(ObstacleReasoner(), frames: 15) { _ in [box] }
        XCTAssertTrue(results.allSatisfy { $0.advisory == nil })
        XCTAssertEqual(results.last!.scene.urgency, .none)
        XCTAssertTrue(results.last!.scene.obstacles.contains { !$0.inCorridor })
    }

    func testFlickerIsIgnored() {
        let box = SyntheticScene.Box(left: 0.40, right: 0.65, distance: 2.0)
        let results = run(ObstacleReasoner(), frames: 25) { i in i % 5 == 0 ? [box] : [] }
        XCTAssertTrue(results.allSatisfy { $0.advisory == nil })
    }

    func testApproachingObstacleEscalatesWithoutNagging() {
        let results = run(ObstacleReasoner(), frames: 40) { i in
            let d = max(3.0 - Float(i) * 0.065, 0.7)
            return [SyntheticScene.Box(left: 0.38, right: 0.62, distance: d)]
        }
        let advisories = results.compactMap(\.advisory)
        XCTAssertTrue((2...4).contains(advisories.count), "\(advisories.map(\.text))")
        XCTAssertEqual(advisories.first!.urgency, .caution)
        XCTAssertEqual(advisories.last!.urgency, .stop)
        XCTAssertTrue(advisories.last!.text.hasPrefix("Stop."), advisories.last!.text)
        for i in 1..<advisories.count { XCTAssertLessThan(advisories[i].distanceMeters, advisories[i - 1].distanceMeters) }
    }

    func testPathClearIsSpokenAfterObstacleLeaves() {
        let box = SyntheticScene.Box(left: 0.40, right: 0.65, distance: 2.0)
        let results = run(ObstacleReasoner(), frames: 40) { i in i < 12 ? [box] : [] }
        let texts = results.compactMap { $0.advisory?.text }
        XCTAssertEqual(texts.count, 2, "\(texts)")
        XCTAssertEqual(texts.last, "Path clear")
    }

    func testWallFillingTheFrameTriggersStop() {
        let wall = SyntheticScene.Box(left: 0, right: 1, distance: 0.7, heightMeters: 3)
        let results = run(ObstacleReasoner(), frames: 20) { i in i >= 6 ? [wall] : [] }
        let advisories = results.compactMap(\.advisory)
        XCTAssertFalse(advisories.isEmpty)
        XCTAssertEqual(advisories.first?.urgency, .stop)
        XCTAssertEqual(advisories.first?.direction, .stop)
        XCTAssertLessThan(advisories.first!.distanceMeters, 1.2)
    }

    func testPersistentObstacleGetsLimitedReminders() {
        let box = SyntheticScene.Box(left: 0.40, right: 0.65, distance: 2.0)
        let results = run(ObstacleReasoner(), frames: 270) { _ in [box] }
        let advisories = results.compactMap(\.advisory)
        XCTAssertEqual(advisories.count, 1 + ReasonerConfig().maxReminders, "\(advisories.map(\.text))")
    }

    func testNothingHappensWithoutDepth() {
        let out = ObstacleReasoner().process(FrameObservation(timestampMs: 0, depth: nil))
        XCTAssertNil(out.advisory)
    }
}
