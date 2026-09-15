import CoreML
import Foundation
import MetanavCore
import Vision

/// COCO object detection with Apple's YOLOv3-tiny Core ML model (non-max suppression built in).
/// Used only to put a name on obstacles and to sanity-check distances; depth decides what is an
/// obstacle.
final class ObjectDetector {
    private let model: VNCoreMLModel

    static let modelName = "YOLOv3TinyInt8LUT"

    init() throws {
        guard let url = Bundle.main.url(forResource: Self.modelName, withExtension: "mlmodelc") else {
            throw NSError(domain: "Metanav", code: 11, userInfo: [NSLocalizedDescriptionKey: "Detector model not bundled."])
        }
        let config = MLModelConfiguration()
        config.computeUnits = .all
        model = try VNCoreMLModel(for: MLModel(contentsOf: url, configuration: config))
    }

    func detect(_ image: FrameImage) throws -> [Detection] {
        let request = VNCoreMLRequest(model: model)
        request.imageCropAndScaleOption = .scaleFill
        let handler: VNImageRequestHandler
        switch image {
        case .pixelBuffer(let pb): handler = VNImageRequestHandler(cvPixelBuffer: pb, orientation: .up)
        case .cgImage(let cg): handler = VNImageRequestHandler(cgImage: cg, orientation: .up)
        }
        try handler.perform([request])
        let observations = (request.results as? [VNRecognizedObjectObservation]) ?? []
        return observations.compactMap { obs in
            guard let top = obs.labels.first, top.confidence >= 0.35 else { return nil }
            // Vision boxes are normalized with the origin at the bottom-left; flip to top-left.
            let b = obs.boundingBox
            let box = NormRect(
                x: Float(b.origin.x),
                y: Float(1 - (b.origin.y + b.size.height)),
                width: Float(b.size.width),
                height: Float(b.size.height)
            )
            return Detection(label: top.identifier, confidence: top.confidence, box: box)
        }
    }
}
