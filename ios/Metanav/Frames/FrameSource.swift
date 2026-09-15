import CoreGraphics
import CoreVideo
import Foundation

/// A frame as delivered by a camera. Raw glasses frames and phone frames arrive as pixel buffers;
/// a decoded fallback path can hand over a CGImage instead. Portrait, origin top-left.
enum FrameImage {
    case pixelBuffer(CVPixelBuffer)
    case cgImage(CGImage)

    var width: Int {
        switch self {
        case .pixelBuffer(let pb): return CVPixelBufferGetWidth(pb)
        case .cgImage(let img): return img.width
        }
    }

    var height: Int {
        switch self {
        case .pixelBuffer(let pb): return CVPixelBufferGetHeight(pb)
        case .cgImage(let img): return img.height
        }
    }
}

struct CapturedFrame {
    let image: FrameImage
    let timestampMs: Int64
}

enum SourceKind: String, Codable, CaseIterable {
    case glasses
    case phoneCamera
}

enum SourceState: Equatable {
    case idle
    case connecting
    case waitingForDevice
    case streaming
    case paused
    case error(String)
}

/// Anything that can deliver camera frames: the glasses over the DAT SDK, or the phone camera.
protocol FrameSource: AnyObject {
    var kind: SourceKind { get }
    /// Called on arbitrary threads.
    var onState: ((SourceState) -> Void)? { get set }
    /// Called on arbitrary threads at camera rate; keep it cheap and never block.
    var onFrame: ((CapturedFrame) -> Void)? { get set }
    func start() async
    func stop()
}

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
