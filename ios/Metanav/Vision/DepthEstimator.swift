import CoreML
import CoreVideo
import Foundation
import MetanavCore
import Vision

/// Monocular relative depth with Depth Anything V2 Small (Core ML, Apple's conversion). Runs on
/// the Neural Engine in ~30 ms. The model is stretched over the whole frame (no crop) so rows of
/// the depth map line up with rows of the frame, which the ground-plane math relies on.
final class DepthEstimator {
    private let model: VNCoreMLModel
    /// Output is 518x518; we keep every other pixel. 259x259 is plenty for column reasoning.
    private let stride = 2

    static let modelName = "DepthAnythingV2SmallF16"

    static var isBundled: Bool { Bundle.main.url(forResource: modelName, withExtension: "mlmodelc") != nil }

    init() throws {
        guard let url = Bundle.main.url(forResource: Self.modelName, withExtension: "mlmodelc") else {
            throw NSError(domain: "Metanav", code: 10, userInfo: [NSLocalizedDescriptionKey: "Depth model not bundled. Run scripts/fetch-models.sh ios and rebuild."])
        }
        let config = MLModelConfiguration()
        config.computeUnits = .all
        model = try VNCoreMLModel(for: MLModel(contentsOf: url, configuration: config))
    }

    func estimate(_ image: FrameImage) throws -> DepthMap? {
        let request = VNCoreMLRequest(model: model)
        request.imageCropAndScaleOption = .scaleFill
        let handler: VNImageRequestHandler
        switch image {
        case .pixelBuffer(let pb): handler = VNImageRequestHandler(cvPixelBuffer: pb, orientation: .up)
        case .cgImage(let cg): handler = VNImageRequestHandler(cgImage: cg, orientation: .up)
        }
        try handler.perform([request])
        guard let observation = request.results?.first as? VNPixelBufferObservation else { return nil }
        return Self.depthMap(from: observation.pixelBuffer, stride: stride)
    }

    /// Reads a single-channel depth image in any of the formats Core ML emits.
    static func depthMap(from buffer: CVPixelBuffer, stride: Int) -> DepthMap? {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let srcW = CVPixelBufferGetWidth(buffer)
        let srcH = CVPixelBufferGetHeight(buffer)
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        let w = srcW / stride
        let h = srcH / stride
        guard w > 0, h > 0 else { return nil }
        var values = [Float](repeating: 0, count: w * h)
        let format = CVPixelBufferGetPixelFormatType(buffer)

        switch format {
        case kCVPixelFormatType_OneComponent32Float, kCVPixelFormatType_DepthFloat32, kCVPixelFormatType_DisparityFloat32:
            for y in 0..<h {
                let row = base.advanced(by: y * stride * rowBytes).assumingMemoryBound(to: Float.self)
                for x in 0..<w { values[y * w + x] = row[x * stride] }
            }
        case kCVPixelFormatType_OneComponent16Half, kCVPixelFormatType_DepthFloat16, kCVPixelFormatType_DisparityFloat16:
            for y in 0..<h {
                let row = base.advanced(by: y * stride * rowBytes).assumingMemoryBound(to: Float16.self)
                for x in 0..<w { values[y * w + x] = Float(row[x * stride]) }
            }
        case kCVPixelFormatType_OneComponent8:
            for y in 0..<h {
                let row = base.advanced(by: y * stride * rowBytes).assumingMemoryBound(to: UInt8.self)
                for x in 0..<w { values[y * w + x] = Float(row[x * stride]) }
            }
        case kCVPixelFormatType_OneComponent16:
            for y in 0..<h {
                let row = base.advanced(by: y * stride * rowBytes).assumingMemoryBound(to: UInt16.self)
                for x in 0..<w { values[y * w + x] = Float(row[x * stride]) }
            }
        case kCVPixelFormatType_32BGRA, kCVPixelFormatType_32ARGB:
            // Grayscale packed into a color buffer: any channel will do.
            for y in 0..<h {
                let row = base.advanced(by: y * stride * rowBytes).assumingMemoryBound(to: UInt8.self)
                for x in 0..<w { values[y * w + x] = Float(row[x * stride * 4 + 1]) }
            }
        default:
            NSLog("[Metanav] unsupported depth pixel format \(format)")
            return nil
        }
        return DepthMap(width: w, height: h, values: values)
    }
}
