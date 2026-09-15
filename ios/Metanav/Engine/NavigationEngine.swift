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

    private let queue = DispatchQueue(label: "metanav.vision", qos: .userInteractive)
    private let previewQueue = DispatchQueue(label: "metanav.preview", qos: .utility)
    private var previewBusy = false
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

    /// Process as fast as the depth model allows, capped at ~20 fps.
    private let minIntervalMs: Int64 = 50

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
        // Depth is what triggers alerts, so it runs every frame; the detector only adds names and
        // runs every third frame.
        if let detector, frameCounter % 3 == 0 {
            lastDetections = (try? detector.detect(frame.image)) ?? lastDetections
        }
        let output = reasoner.process(FrameObservation(timestampMs: frame.timestampMs, depth: depthMap, detections: lastDetections))
        onScene?(output.scene)
        if let advisory = output.advisory { onAdvisory?(advisory) }
        schedulePreview(frame.image)

        let elapsed = Int(nowMs() - started)
        let fps = 1000 / Float(max(Int64(elapsed), minIntervalMs))
        stats = Stats(processedFps: 0.8 * stats.processedFps + 0.2 * fps, inferenceMs: elapsed)
        onStats?(stats)
    }

    /// Preview rendering never delays the next depth frame: it runs on its own low-priority queue
    /// and simply skips frames while it is behind.
    private func schedulePreview(_ image: FrameImage) {
        guard onPreview != nil else { return }
        busyLock.lock()
        if previewBusy { busyLock.unlock(); return }
        previewBusy = true
        busyLock.unlock()
        previewQueue.async { [self] in
            if let ui = previewImage(image) { onPreview?(ui) }
            busyLock.lock(); previewBusy = false; busyLock.unlock()
        }
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
