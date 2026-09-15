import AVFoundation
import Foundation
import MetanavCore

/// Speaks advisories through the current audio route. With the glasses connected they are the
/// phone's Bluetooth audio output, so guidance plays on their open-ear speakers. Urgent advisories
/// interrupt whatever is still being spoken; nothing is ever queued behind stale guidance.
final class VoiceFeedback: NSObject, AVSpeechSynthesizerDelegate {
    var enabled = true
    private let synthesizer = AVSpeechSynthesizer()
    private let session = AVAudioSession.sharedInstance()

    override init() {
        super.init()
        synthesizer.delegate = self
        do {
            try session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
        } catch {
            NSLog("[Metanav] audio session category failed: \(error.localizedDescription)")
        }
    }

    func speak(_ advisory: Advisory) {
        speak(advisory.text, urgent: advisory.urgency == .stop)
    }

    func speak(_ text: String, urgent: Bool) {
        guard enabled else { return }
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: urgent ? .immediate : .word) }
        try? session.setActive(true, options: [])
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier) ?? AVSpeechSynthesisVoice(language: "en-US")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 1.05
        utterance.prefersAssistiveTechnologySettings = true
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        try? session.setActive(false, options: [.notifyOthersOnDeactivation])
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        // Give other audio its volume back once we're done talking.
        if !synthesizer.isSpeaking { try? session.setActive(false, options: [.notifyOthersOnDeactivation]) }
    }
}
