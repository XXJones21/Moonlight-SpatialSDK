import CoreImage
import CoreVideo
import QuartzCore
import Foundation

/// Reduces the video to one color at 10 Hz. The only CPU readback is a single pixel.
final class PortalLightingSampler: @unchecked Sendable {
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let lock = NSLock()
    private var lastSample: TimeInterval = 0
    private var average = SIMD3<Float>(repeating: 0.5)
    var onColor: (@Sendable (SIMD3<Float>) -> Void)?
    func sample(_ buffer: CVPixelBuffer) {
        lock.lock(); defer { lock.unlock() }
        let now = CACurrentMediaTime()
        guard now - lastSample >= 0.1 else { return }
        lastSample = now
        let source = CIImage(cvPixelBuffer: buffer)
        let extent = CGRect(x: 0, y: 16, width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer) - 16)
        let pixel = source.cropped(to: extent).applyingFilter("CIAreaAverage", parameters: [kCIInputExtentKey: CIVector(cgRect: extent)])
        var rgba = [UInt8](repeating: 0, count: 4)
        rgba.withUnsafeMutableBytes { bytes in
            guard let address = bytes.baseAddress else { return }
            context.render(pixel, toBitmap: address, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
        }
        let current = SIMD3(Float(rgba[0]), Float(rgba[1]), Float(rgba[2])) / 255
        average += (current - average) * 0.15
        onColor?(average)
    }
}
