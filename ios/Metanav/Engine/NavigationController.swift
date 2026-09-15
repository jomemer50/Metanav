import Combine
import Foundation
import MetanavCore
import MWDATCore
import UIKit

enum RunState: Equatable {
    case stopped
    case starting(SourceKind)
    case running(SourceKind, SourceState)
    case failed(String)
}

/// App-scoped owner of everything that must outlive a screen: the frame source, the engine,
/// voice and haptics, and the link to the glasses. Views observe it and call start/stop.
@MainActor
final class NavigationController: ObservableObject {
    @Published private(set) var settings: Settings = Settings.load()
    @Published private(set) var runState: RunState = .stopped
    @Published private(set) var scene = SceneState()
    @Published private(set) var preview: UIImage?
    @Published private(set) var stats = NavigationEngine.Stats()
    @Published private(set) var lastAdvisory: String?
    @Published private(set) var registration: RegistrationState
    @Published private(set) var glassesPresent = false

    let depthModelBundled = DepthEstimator.isBundled

    private let wearables: WearablesInterface
    private let deviceSelector: AutoDeviceSelector
    private let voice = VoiceFeedback()
    private let haptics = Haptics()
    private var engine: NavigationEngine?
    private var source: FrameSource?
    private var registrationTask: Task<Void, Never>?
    private var deviceTask: Task<Void, Never>?

    init(wearables: WearablesInterface) {
        self.wearables = wearables
        deviceSelector = AutoDeviceSelector(wearables: wearables)
        registration = wearables.registrationState
        voice.enabled = settings.voice
        haptics.enabled = settings.haptics
        registrationTask = Task { [weak self] in
            for await state in wearables.registrationStateStream() { self?.registration = state }
        }
        deviceTask = Task { [weak self] in
            guard let self else { return }
            for await device in self.deviceSelector.activeDeviceStream() { self.glassesPresent = device != nil }
        }
    }

    // MARK: Glasses link

    func connectGlasses() {
        Task {
            do { try await wearables.startRegistration() } catch {
                runState = .failed(error.localizedDescription)
            }
        }
    }

    func disconnectGlasses() {
        Task { try? await wearables.startUnregistration() }
    }

    // MARK: Settings

    func update(_ transform: (inout Settings) -> Void) {
        var updated = settings
        transform(&updated)
        guard updated != settings else { return }
        settings = updated
        updated.save()
        voice.enabled = updated.voice
        haptics.enabled = updated.haptics
        engine?.updateConfig(updated.reasonerConfig())
    }

    // MARK: Run

    func start(_ kind: SourceKind) {
        switch runState {
        case .stopped, .failed: break
        default: return
        }
        runState = .starting(kind)
        update { $0.source = kind }
        Task { await startAsync(kind) }
    }

    private func startAsync(_ kind: SourceKind) async {
        if kind == .glasses {
            // Camera access on the glasses is granted inside the Meta AI app.
            do {
                var status = try await wearables.checkPermissionStatus(.camera)
                if status != .granted { status = try await wearables.requestPermission(.camera) }
                guard status == .granted else {
                    runState = .failed("Camera access on the glasses was not granted.")
                    return
                }
            } catch {
                runState = .failed("Could not check glasses permissions: \(error.localizedDescription)")
                return
            }
        }

        if engine == nil {
            // Model loading can take a couple of seconds the first time; keep it off the main actor.
            let config = settings.reasonerConfig()
            do {
                let created = try await Task.detached(priority: .userInitiated) { try NavigationEngine(config: config) }.value
                wire(created)
                engine = created
            } catch {
                runState = .failed(error.localizedDescription)
                return
            }
        }
        guard let engine else { return }
        engine.reset()

        let source: FrameSource
        switch kind {
        case .glasses: source = GlassesFrameSource(wearables: wearables, deviceSelector: deviceSelector)
        case .phoneCamera: source = PhoneCameraFrameSource()
        }
        self.source = source
        // Capture the engine itself: this closure runs on the camera thread.
        source.onFrame = { frame in engine.submit(frame) }
        source.onState = { [weak self] state in
            Task { @MainActor in self?.sourceStateChanged(kind, state) }
        }
        UIApplication.shared.isIdleTimerDisabled = true
        await source.start()
    }

    private func sourceStateChanged(_ kind: SourceKind, _ state: SourceState) {
        switch state {
        case .error(let message):
            teardownSource()
            runState = .failed(message)
        case .idle:
            if case .running = runState { teardownSource(); runState = .stopped }
        default:
            runState = .running(kind, state)
        }
    }

    func stop() {
        voice.stop()
        teardownSource()
        runState = .stopped
    }

    private func teardownSource() {
        source?.onFrame = nil
        source?.onState = nil
        source?.stop()
        source = nil
        preview = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func wire(_ engine: NavigationEngine) {
        engine.onScene = { [weak self] scene in Task { @MainActor in self?.scene = scene } }
        engine.onPreview = { [weak self] image in Task { @MainActor in self?.preview = image } }
        engine.onStats = { [weak self] stats in Task { @MainActor in self?.stats = stats } }
        engine.onAdvisory = { [weak self] advisory in
            Task { @MainActor in
                guard let self else { return }
                self.lastAdvisory = advisory.text
                self.voice.speak(advisory)
                self.haptics.buzz(advisory.urgency)
            }
        }
    }
}
