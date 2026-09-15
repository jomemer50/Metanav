import CoreMedia
import Foundation
import MWDATCamera
import MWDATCore
import UIKit

/// Streams the glasses' camera through the Meta Wearables Device Access Toolkit.
/// Lifecycle: create + start a `DeviceSession`, wait for `.started`, attach a camera with an
/// uncompressed low-resolution stream, forward each frame's pixel buffer.
final class GlassesFrameSource: FrameSource {
    let kind = SourceKind.glasses
    var onState: ((SourceState) -> Void)?
    var onFrame: ((CapturedFrame) -> Void)?

    private let wearables: WearablesInterface
    private let deviceSelector: DeviceSelector
    private var session: DeviceSession?
    private var camera: MWDATCamera.Camera?
    private let tokens = ListenerTokenBag()
    private var failed = false

    init(wearables: WearablesInterface, deviceSelector: DeviceSelector) {
        self.wearables = wearables
        self.deviceSelector = deviceSelector
    }

    func start() async {
        guard session == nil else { return }
        failed = false
        onState?(.connecting)

        let session: DeviceSession
        do {
            session = try wearables.createSession(deviceSelector: deviceSelector)
        } catch {
            fail("Could not start a glasses session: \(error.localizedDescription)")
            return
        }
        self.session = session

        session.statePublisher.listen { [weak self] state in
            guard let self else { return }
            switch state {
            case .paused: self.onState?(.paused)
            case .stopped: if !self.failed { self.onState?(.idle) }
            default: break
            }
        }.store(in: tokens)
        session.errorPublisher.listen { error in
            NSLog("[Metanav] glasses session error: \(error.description)")
        }.store(in: tokens)

        do {
            try session.start()
        } catch {
            fail("Could not start the glasses session: \(error.localizedDescription)")
            return
        }

        // Wait (bounded) for the session to come up before attaching the camera. Polling the
        // published state avoids depending on whether the state stream replays its current value.
        var waitedMs = 0
        while session.state != .started && session.state != .stopped && waitedMs < 20_000 {
            try? await Task.sleep(for: .milliseconds(100))
            waitedMs += 100
        }
        guard session.state == .started else {
            fail("Timed out connecting to the glasses. Are they on, unfolded, and paired in Meta AI?")
            return
        }

        let config = StreamConfiguration(videoCodec: .raw, resolution: .low, frameRate: 15)
        let camera: MWDATCamera.Camera?
        do {
            camera = try session.addCamera(config: config)
        } catch {
            fail("Could not open the glasses camera: \(error.localizedDescription)")
            return
        }
        guard let camera else {
            fail("The glasses session was not ready for a camera.")
            return
        }
        self.camera = camera
        let stream = camera.stream

        stream.statePublisher.listen { [weak self] state in
            guard let self else { return }
            switch state {
            case .streaming: self.onState?(.streaming)
            case .starting, .waitingForDevice: self.onState?(.waitingForDevice)
            case .paused: self.onState?(.paused)
            case .stopped, .stopping: if !self.failed { self.onState?(.idle) }
            }
        }.store(in: tokens)
        stream.errorPublisher.listen { error in
            NSLog("[Metanav] glasses stream error: \(error.description)")
        }.store(in: tokens)
        stream.videoFramePublisher.listen { [weak self] frame in
            guard let self, let onFrame = self.onFrame else { return }
            let buffer = frame.sampleBuffer
            if let pixelBuffer = CMSampleBufferGetImageBuffer(buffer) {
                onFrame(CapturedFrame(image: .pixelBuffer(pixelBuffer), timestampMs: nowMs()))
            } else if let cgImage = frame.makeUIImage()?.cgImage {
                // Compressed frame (should not happen with `.raw`), decoded by the SDK.
                onFrame(CapturedFrame(image: .cgImage(cgImage), timestampMs: nowMs()))
            }
        }.store(in: tokens)

        stream.start()
    }

    func stop() {
        tokens.clear()
        camera?.stop()
        camera = nil
        session?.stop()
        session = nil
        if !failed { onState?(.idle) }
    }

    private func fail(_ message: String) {
        NSLog("[Metanav] \(message)")
        failed = true
        stop()
        onState?(.error(message))
    }
}
