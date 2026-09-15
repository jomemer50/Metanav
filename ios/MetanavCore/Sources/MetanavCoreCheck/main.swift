import Foundation
import MetanavCore
import MetanavCoreTestSupport

// The same scenarios as the XCTest suite, runnable with `swift run MetanavCoreCheck`.
var failures = 0
func check(_ condition: @autoclosure () -> Bool, _ name: String, _ detail: @autoclosure () -> String = "") {
    if condition() { print("  ok   \(name)") } else { failures += 1; print("  FAIL \(name) \(detail())") }
}

let frameMs: Int64 = 150
func run(_ reasoner: ObstacleReasoner, frames: Int, detections: [Detection] = [], boxesAt: (Int) -> [SyntheticScene.Box]) -> [ReasonerOutput] {
    let scene = SyntheticScene()
    return (0..<frames).map { i in
        let depth = scene.render(boxes: boxesAt(i), noise: 0.02, seed: UInt64(i + 7))
        return reasoner.process(FrameObservation(timestampMs: Int64(i) * frameMs, depth: depth, detections: detections))
    }
}

print("Ground plane")
do {
    let scene = SyntheticScene()
    let cal = DepthScaleCalibrator()
    let ground = GroundPlaneModel(geometry: scene.geometry, rows: scene.height)
    let depth = scene.render()
    check(cal.update(depth: depth, ground: ground), "calibration accepts flat ground")
    let row = Int(Float(scene.height) * 0.7)
    let z = cal.metricDepth(depth[scene.width / 2, row])
    let expected = ground.groundDepth(row: row)
    check(abs(z - expected) / expected < 0.05, "metric depth recovered", "expected \(expected) got \(z)")
    let wall = scene.render(boxes: [.init(left: 0, right: 1, distance: 0.6, heightMeters: 3)])
    check(!DepthScaleCalibrator().update(depth: wall, ground: ground), "flat bottom rejected")
    check(DepthScaleCalibrator().update(depth: scene.render(noise: 0.05), ground: ground), "survives noise")
}

print("Phrasing")
do {
    check(AdvisoryPolicy.distancePhrase(0.9, units: .meters) == "very close", "very close")
    check(AdvisoryPolicy.distancePhrase(1.4, units: .meters) == "1 and a half meters", "1.5 m")
    check(AdvisoryPolicy.distancePhrase(2.1, units: .meters) == "2 meters", "2 m")
    check(AdvisoryPolicy.distancePhrase(2.1, units: .feet) == "7 feet", "7 ft")
    let policy = AdvisoryPolicy(config: ReasonerConfig())
    check(policy.phrase(label: "Chair", meters: 2.0, urgency: .caution, direction: .left) == "Chair ahead, 2 meters. Move left.", "caution phrase")
    check(policy.phrase(label: "Person", meters: 0.8, urgency: .stop, direction: .right) == "Stop. Person very close. Move right.", "stop phrase")
}

print("Scenarios")
do {
    let quiet = run(ObstacleReasoner(), frames: 15) { _ in [] }
    check(quiet.allSatisfy { $0.advisory == nil } && quiet.last!.scene.headline == "Clear", "flat ground stays quiet")

    let box = SyntheticScene.Box(left: 0.40, right: 0.70, distance: 2.0)
    let once = run(ObstacleReasoner(), frames: 20) { _ in [box] }.compactMap(\.advisory)
    check(once.count == 1, "box announced once", "\(once.map(\.text))")
    check(once.first.map { abs($0.distanceMeters - 2.0) < 0.4 && $0.direction == .left && $0.text.hasPrefix("Obstacle ahead, 2 meters") } ?? false, "distance + direction", "\(once)")

    let person = Detection(label: "person", confidence: 0.9, box: NormRect(x: 0.30, y: 0.20, width: 0.25, height: 0.70))
    let labeled = run(ObstacleReasoner(), frames: 12, detections: [person]) { _ in [SyntheticScene.Box(left: 0.30, right: 0.55, distance: 2.2, heightMeters: 1.7)] }.compactMap(\.advisory)
    check(labeled.first?.label == "Person" && labeled.first?.direction == .right, "detector label used", "\(labeled)")

    let off = run(ObstacleReasoner(), frames: 15) { _ in [SyntheticScene.Box(left: 0.02, right: 0.22, distance: 1.5)] }
    check(off.allSatisfy { $0.advisory == nil } && off.last!.scene.obstacles.contains { !$0.inCorridor }, "off-path obstacle silent but shown")

    let flicker = run(ObstacleReasoner(), frames: 25) { i in i % 5 == 0 ? [box] : [] }
    check(flicker.allSatisfy { $0.advisory == nil }, "flicker ignored")

    let approach = run(ObstacleReasoner(), frames: 40) { i in [SyntheticScene.Box(left: 0.38, right: 0.62, distance: max(3.0 - Float(i) * 0.065, 0.7))] }.compactMap(\.advisory)
    check((2...4).contains(approach.count) && approach.first?.urgency == .caution && approach.last?.urgency == .stop, "approach escalates without nagging", "\(approach.map(\.text))")

    let leaves = run(ObstacleReasoner(), frames: 40) { i in i < 12 ? [box] : [] }.compactMap { $0.advisory?.text }
    check(leaves.count == 2 && leaves.last == "Path clear", "path clear afterwards", "\(leaves)")

    let wall = run(ObstacleReasoner(), frames: 20) { i in i >= 6 ? [SyntheticScene.Box(left: 0, right: 1, distance: 0.7, heightMeters: 3)] : [] }.compactMap(\.advisory)
    check(wall.first?.urgency == .stop && wall.first?.direction == .stop, "wall filling frame -> stop", "\(wall.map(\.text))")

    let persistent = run(ObstacleReasoner(), frames: 270) { _ in [box] }.compactMap(\.advisory)
    check(persistent.count == 1 + ReasonerConfig().maxReminders, "limited reminders", "\(persistent.map(\.text))")
}

if failures == 0 { print("All checks passed.") } else { print("\(failures) check(s) failed."); exit(1) }
