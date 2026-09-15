import CoreImage
import Foundation
import MetanavCore
import UIKit

/// Frame in, scene + advisory out. Runs the models on one serial queue and drops frames while busy.
final class NavigationEngine {
    struct Stats { var processedFps: Float = 0; var inferenceMs: Int = 0 }

    var onScene: ((SceneState) -> Void)?
    var onAdvisory: ((Advisory) -> Void)?
    var onPreview: ((UIImage) -> Void)?
    var onStats: ((Stats) -> Void)?

    private let queue = DispatchQueue(label: "metanav.vision", qos: .userInitiated)
    private let reasoner: ObstacleReasoner
    private let depth: DepthEstimator
    private let detector: ObjectDetector?
    private let ciContext = CIContext()
    private var busy = false
    private let busyLock = NSLock()
    private var lastProcessedAt: Int64 = 0
    private var frameCounter = 0
    private var lastDetections: [Detection] = []
    private var stats = Stats()

    /// Cap processing at ~8 fps: enough for walking speed, kind to the battery.
    private let minIntervalMs: Int64 = 120

    init(config: ReasonerConfig) throws {
        reasoner = ObstacleReasoner(config: config)
        depth = try DepthEstimator()
        detector = try? ObjectDetector()
    }

    func updateConfig(_ config: ReasonerConfig) {
        queue.async { self.reasoner.config = config }
    }

    func reset() {
        queue.async { self.reasoner.reset() }
    }

    /// Non-blocking: schedules the frame unless the previous one is still being processed.
    func submit(_ frame: CapturedFrame) {
        let now = nowMs()
        if now - lastProcessedAt < minIntervalMs { return }
        busyLock.lock()
        if busy { busyLock.unlock(); return }
        busy = true
        busyLock.unlock()
        lastProcessedAt = now
        queue.async { [self] in
            defer { busyLock.lock(); busy = false; busyLock.unlock() }
            process(frame)
        }
    }

    private func process(_ frame: CapturedFrame) {
        let started = nowMs()
        let depthMap: DepthMap?
        do { depthMap = try depth.estimate(frame.image) } catch {
            NSLog("[Metanav] depth failed: \(error.localizedDescription)")
            depthMap = nil
        }
        frameCounter += 1
        // The detector matters less than depth: run it every other frame.
        if let detector, frameCounter % 2 == 0 {
            lastDetections = (try? detector.detect(frame.image)) ?? lastDetections
        }
        let output = reasoner.process(FrameObservation(timestampMs: frame.timestampMs, depth: depthMap, detections: lastDetections))
        onScene?(output.scene)
        if let advisory = output.advisory { onAdvisory?(advisory) }
        if let onPreview, let image = previewImage(frame.image) { onPreview(image) }

        let elapsed = Int(nowMs() - started)
        let fps = 1000 / Float(max(Int64(elapsed), minIntervalMs))
        stats = Stats(processedFps: 0.8 * stats.processedFps + 0.2 * fps, inferenceMs: elapsed)
        onStats?(stats)
    }

    private func previewImage(_ image: FrameImage) -> UIImage? {
        switch image {
        case .cgImage(let cg): return UIImage(cgImage: cg)
        case .pixelBuffer(let pb):
            let ci = CIImage(cvPixelBuffer: pb)
            guard let cg = ciContext.createCGImage(ci, from: ci.extent) else { return nil }
            return UIImage(cgImage: cg)
        }
    }
}
