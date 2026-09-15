import Foundation

/// Decides *whether* to speak and *what* to say. The goal is to be helpful without nagging:
/// announce a new obstacle once, again if it gets a meter closer or becomes urgent, and remind at
/// most twice if it just sits there. A global cooldown keeps advisories from piling up.
public final class AdvisoryPolicy {
    /// Sentinel for "has not happened yet"; timestamps are never negative.
    public static let never: Int64 = -1

    /// Milliseconds elapsed since `then`, or a huge number when it never happened.
    public static func since(_ nowMs: Int64, _ then: Int64) -> Int64 {
        then < 0 ? Int64.max / 2 : nowMs - then
    }

    private let config: ReasonerConfig
    private var lastSpokenAtMs = never
    private var lastSpokenUrgency: Urgency = .none
    private var lastAlertTrackId = -1
    private var lastAlertAtMs = never
    private var clearPending = false
    private var lastClearAtMs = never

    public init(config: ReasonerConfig) { self.config = config }

    public func reset() {
        lastSpokenAtMs = Self.never
        lastSpokenUrgency = .none
        lastAlertTrackId = -1
        lastAlertAtMs = Self.never
        lastClearAtMs = Self.never
        clearPending = false
    }

    private enum Reason { case new, closer, escalated, reminder }

    public func decide(nowMs: Int64, candidate: Track?, direction: Direction, corridorBlocked: Bool) -> Advisory? {
        guard let candidate else { return maybeClear(nowMs: nowMs, corridorBlocked: corridorBlocked) }
        let z = candidate.distanceMeters
        let urgency: Urgency = z < config.urgentDistanceMeters ? .stop : .caution
        guard let reason = speakReason(candidate, z: z, urgency: urgency, nowMs: nowMs) else { return nil }

        let cooldown = urgency == .stop ? config.urgentCooldownMs : config.cooldownMs
        let sinceSpoken = Self.since(nowMs, lastSpokenAtMs)
        if sinceSpoken < cooldown {
            // Let an escalation to STOP interrupt a normal cooldown, nothing else.
            let escalation = urgency == .stop && lastSpokenUrgency != .stop && sinceSpoken >= config.urgentCooldownMs
            if !escalation { return nil }
        }

        candidate.announcedCount += 1
        candidate.lastAnnouncedDistance = z
        candidate.lastAnnouncedAtMs = nowMs
        candidate.lastAnnouncedUrgency = urgency
        if reason == .reminder { candidate.reminders += 1 }
        lastSpokenAtMs = nowMs
        lastSpokenUrgency = urgency
        lastAlertTrackId = candidate.id
        lastAlertAtMs = nowMs
        clearPending = config.announceClear

        return Advisory(
            text: phrase(label: candidate.label, meters: z, urgency: urgency, direction: direction),
            urgency: urgency,
            label: candidate.label,
            distanceMeters: z,
            direction: direction
        )
    }

    private func speakReason(_ track: Track, z: Float, urgency: Urgency, nowMs: Int64) -> Reason? {
        if track.announcedCount == 0 { return .new }
        if urgency == .stop && track.lastAnnouncedUrgency != .stop { return .escalated }
        if z <= track.lastAnnouncedDistance - config.reannounceCloserBy { return .closer }
        let sinceLast = Self.since(nowMs, track.lastAnnouncedAtMs)
        if sinceLast >= config.reminderIntervalMs && track.reminders < config.maxReminders && z <= track.lastAnnouncedDistance + 0.3 {
            return .reminder
        }
        return nil
    }

    private func maybeClear(nowMs: Int64, corridorBlocked: Bool) -> Advisory? {
        if !clearPending || corridorBlocked { return nil }
        // Wait a beat so a momentary gap does not produce "clear" followed by the same obstacle.
        if Self.since(nowMs, lastAlertAtMs) < 1500 { return nil }
        if Self.since(nowMs, lastClearAtMs) < 5000 { clearPending = false; return nil }
        clearPending = false
        lastClearAtMs = nowMs
        lastSpokenAtMs = nowMs
        lastSpokenUrgency = .none
        return Advisory(text: "Path clear", urgency: .none, label: "", distanceMeters: .infinity, direction: .none)
    }

    public func phrase(label: String, meters: Float, urgency: Urgency, direction: Direction) -> String {
        let what = label.isEmpty ? Labels.generic : label
        let move: String
        switch direction {
        case .left: move = "Move left."
        case .right: move = "Move right."
        case .stop: move = "Stop."
        case .none: move = ""
        }
        if urgency == .stop {
            let tail = (direction == .left || direction == .right) ? " \(move)" : ""
            return "Stop. \(what) very close.\(tail)"
        }
        let tail = move.isEmpty ? "" : " \(move)"
        return "\(what) ahead, \(Self.distancePhrase(meters, units: config.units)).\(tail)"
    }

    public static func distancePhrase(_ meters: Float, units: Units) -> String {
        if meters < 1.2 { return "very close" }
        switch units {
        case .meters:
            let halves = Int((meters * 2).rounded())
            let whole = halves / 2
            let half = halves % 2 == 1
            if whole == 0 { return "half a meter" }
            if half && whole == 1 { return "1 and a half meters" }
            if half { return "\(whole) and a half meters" }
            if whole == 1 { return "1 meter" }
            return "\(whole) meters"
        case .feet:
            let feet = max(Int((meters * 3.28084).rounded()), 1)
            return feet == 1 ? "1 foot" : "\(feet) feet"
        }
    }

    public static func shortDistance(_ meters: Float, units: Units) -> String {
        switch units {
        case .meters: return String(format: "%.1f m", meters)
        case .feet: return "\(Int((meters * 3.28084).rounded())) ft"
        }
    }
}
