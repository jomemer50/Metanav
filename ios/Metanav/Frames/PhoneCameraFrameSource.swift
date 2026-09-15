import AVFoundation
import Foundation

/// Phone rear camera, for testing guidance without glasses (hold the phone at chest height,
/// pointing forward). Frames are delivered portrait via the connection's rotation angle.
final class PhoneCameraFrameSource: NSObject, FrameSource, AVCaptureVideoDataOutputSampleBufferDelegate {
    let kind = SourceKind.phoneCamera
    var onState: ((SourceState) -> Void)?
    var onFrame: ((CapturedFrame) -> Void)?

    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "metanav.phone-camera")
    private var configured = false

    func start() async {
        onState?(.connecting)
        let granted: Bool
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: granted = true
        case .notDetermined: granted = await AVCaptureDevice.requestAccess(for: .video)
        default: granted = false
        }
        guard granted else {
            onState?(.error("Camera access is off. Enable it in Settings › Metanav."))
            return
        }
        queue.async { [self] in
            if !configured {
                do { try configure() } catch {
                    onState?(.error("Phone camera unavailable: \(error.localizedDescription)"))
                    return
                }
            }
            session.startRunning()
            onState?(session.isRunning ? .streaming : .error("Phone camera did not start."))
        }
    }

    func stop() {
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
            onState?(.idle)
        }
    }

    private func configure() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .vga640x480
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw NSError(domain: "Metanav", code: 1, userInfo: [NSLocalizedDescriptionKey: "No back camera"])
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw NSError(domain: "Metanav", code: 2, userInfo: [NSLocalizedDescriptionKey: "Cannot add camera input"]) }
        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else { throw NSError(domain: "Metanav", code: 3, userInfo: [NSLocalizedDescriptionKey: "Cannot add video output"]) }
        session.addOutput(output)
        if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90 // portrait, like the glasses
        }
        configured = true
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        onFrame?(CapturedFrame(image: .pixelBuffer(pixelBuffer), timestampMs: nowMs()))
    }
}
