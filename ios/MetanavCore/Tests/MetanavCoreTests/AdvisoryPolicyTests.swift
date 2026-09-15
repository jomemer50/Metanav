import XCTest
@testable import MetanavCore

final class AdvisoryPolicyTests: XCTestCase {
    func testDistancePhrases() {
        XCTAssertEqual(AdvisoryPolicy.distancePhrase(0.9, units: .meters), "very close")
        XCTAssertEqual(AdvisoryPolicy.distancePhrase(1.4, units: .meters), "1 and a half meters")
        XCTAssertEqual(AdvisoryPolicy.distancePhrase(2.1, units: .meters), "2 meters")
        XCTAssertEqual(AdvisoryPolicy.distancePhrase(2.6, units: .meters), "2 and a half meters")
        XCTAssertEqual(AdvisoryPolicy.distancePhrase(2.9, units: .meters), "3 meters")
        XCTAssertEqual(AdvisoryPolicy.distancePhrase(2.1, units: .feet), "7 feet")
    }

    func testPhrases() {
        let policy = AdvisoryPolicy(config: ReasonerConfig())
        XCTAssertEqual(policy.phrase(label: "Chair", meters: 2.0, urgency: .caution, direction: .left), "Chair ahead, 2 meters. Move left.")
        XCTAssertEqual(policy.phrase(label: "Person", meters: 0.8, urgency: .stop, direction: .right), "Stop. Person very close. Move right.")
        XCTAssertEqual(policy.phrase(label: "", meters: 0.8, urgency: .stop, direction: .stop), "Stop. Obstacle very close.")
        XCTAssertEqual(policy.phrase(label: "Obstacle", meters: 3.0, urgency: .caution, direction: .stop), "Obstacle ahead, 3 meters. Stop.")
    }
}
