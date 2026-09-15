import MetanavCore
import UIKit

/// A tap for caution, a firm double buzz for stop. Phone only; the glasses have no motor.
@MainActor
final class Haptics {
    var enabled = true
    private let notification = UINotificationFeedbackGenerator()
    private let impact = UIImpactFeedbackGenerator(style: .heavy)

    func buzz(_ urgency: Urgency) {
        guard enabled else { return }
        switch urgency {
        case .stop:
            impact.impactOccurred(intensity: 1.0)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) { self.impact.impactOccurred(intensity: 1.0) }
        case .caution:
            notification.notificationOccurred(.warning)
        case .none:
            notification.notificationOccurred(.success)
        }
    }
}
