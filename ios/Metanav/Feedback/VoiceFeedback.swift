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
            try session.setCategory(.playback, mode: .voicePrompt, options: [.mixWithOthers, .duckOthers])
        } catch {
            NSLog("[Metanav] audio session category failed: \(error.localizedDescription)")
        }
    }

    /// Call when guidance starts: keeps the audio session (and with it the Bluetooth route to the
    /// glasses) open for the whole run, and warms up the speech engine, so the first real
    /// advisory is not delayed by a cold start.
    func begin() {
        try? session.setActive(true, options: [])
        let warmup = AVSpeechUtterance(string: " ")
        warmup.volume = 0
        warmup.voice = preferredVoice
        synthesizer.speak(warmup)
    }

    /// Call when guidance stops: releases the audio session so other audio gets its volume back.
    func end() {
        synthesizer.stopSpeaking(at: .immediate)
        try? session.setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private var preferredVoice: AVSpeechSynthesisVoice? {
        AVSpeechSynthesisVoice(language: Locale.current.identifier) ?? AVSpeechSynthesisVoice(language: "en-US")
    }

    func speak(_ advisory: Advisory) {
        speak(advisory.text, urgent: advisory.urgency == .stop)
    }

    func speak(_ text: String, urgent: Bool) {
        guard enabled else { return }
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: urgent ? .immediate : .word) }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = preferredVoice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 1.05
        utterance.prefersAssistiveTechnologySettings = true
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {}
}
