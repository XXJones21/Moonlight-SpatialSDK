import Foundation
import CoreVideo
import QuartzCore

struct PortalFrameIdentity: Equatable, Sendable {
    let session: UInt64, epoch: UInt64, revision: UInt64, frame: UInt64
    static func read(_ pixelBuffer: CVPixelBuffer) -> Self? {
        let width = CVPixelBufferGetWidth(pixelBuffer), height = CVPixelBufferGetHeight(pixelBuffer)
        guard width % 2 == 0, width >= 352, height > 16 else { return nil }
        guard CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        let planar = CVPixelBufferIsPlanar(pixelBuffer)
        guard let base = planar ? CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) : CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        let stride = planar ? CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0) : CVPixelBufferGetBytesPerRow(pixelBuffer)
        let eyeWidth = width / 2, cell = eyeWidth / 176
        func bright(x: Int, y: Int) -> Bool? {
            let row = base.advanced(by: y * stride)
            switch format {
            case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange:
                return row.load(fromByteOffset: x, as: UInt8.self) > 127
            case kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange, kCVPixelFormatType_420YpCbCr10BiPlanarFullRange:
                return row.load(fromByteOffset: x * 2, as: UInt16.self) > 32767
            case kCVPixelFormatType_32BGRA:
                return row.load(fromByteOffset: x * 4 + 1, as: UInt8.self) > 127
            case kCVPixelFormatType_64RGBAHalf:
                return Float16(bitPattern: row.load(fromByteOffset: x * 8 + 2, as: UInt16.self)) > 0.5
            default: return nil
            }
        }
        func eye(_ eye: Int) -> Data? {
            var bytes = Data(repeating: 0, count: 44)
            for bit in 0..<352 {
                let x = eye * eyeWidth + (bit % 176) * cell + cell / 2
                let y = height - 16 + (bit / 176) * 8 + 4
                guard let white = bright(x: x, y: y) else { return nil }
                if white { bytes[bit / 8] |= 1 << (7 - bit % 8) }
            }
            return bytes
        }
        guard let left = eye(0), let right = eye(1), left == right,
              left.prefix(4) == Data("P6FM".utf8), left[4] == 1, left[5] == 1, left[6] == 0, left[7] == 0,
              crc32(left.prefix(40)) == left.integerLE(at: 40, as: UInt32.self),
              let session = left.integerLE(at: 8, as: UInt64.self), let epoch = left.integerLE(at: 16, as: UInt64.self),
              let revision = left.integerLE(at: 24, as: UInt64.self), let frame = left.integerLE(at: 32, as: UInt64.self),
              session != 0, epoch != 0, revision != 0 else { return nil }
        return Self(session: session, epoch: epoch, revision: revision, frame: frame)
    }
    private static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = (crc >> 1) ^ (crc & 1 == 1 ? 0xedb88320 : 0) }
        }
        return crc ^ 0xffffffff
    }
}

/// Shared by the decode and UI queues; UDP acknowledgments never open this gate.
final class PortalFrameGate: @unchecked Sendable {
    private let lock = NSLock()
    private var expected: PortalFrameIdentity?
    private var width = 0, height = 0
    private var tracking = false
    private var lastAccepted: UInt64 = 0
    private var lastPresented: UInt64 = 0
    private var presentedAt: TimeInterval = 0
    func configure(session: UInt64, epoch: UInt64, revision: UInt64, width: Int, height: Int, tracking: Bool) {
        lock.lock(); defer { lock.unlock() }
        let next = PortalFrameIdentity(session: session, epoch: epoch, revision: revision, frame: 0)
        if expected != next { lastAccepted = 0; lastPresented = 0; presentedAt = 0 }
        expected = next; self.width = width; self.height = height; self.tracking = tracking
        if !tracking { presentedAt = 0 }
    }
    func close() { lock.lock(); expected = nil; tracking = false; presentedAt = 0; lock.unlock() }
    private func matches(_ identity: PortalFrameIdentity) -> Bool {
        guard let expected else { return false }
        return identity.session == expected.session && identity.epoch == expected.epoch && identity.revision == expected.revision
    }
    func accepts(_ buffer: CVPixelBuffer) -> Bool {
        guard let identity = PortalFrameIdentity.read(buffer) else { return false }
        lock.lock(); defer { lock.unlock() }
        guard tracking, matches(identity), CVPixelBufferGetWidth(buffer) == width, CVPixelBufferGetHeight(buffer) == height, identity.frame >= lastAccepted else { return false }
        lastAccepted = identity.frame; return true
    }
    func presented(_ identity: PortalFrameIdentity) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard tracking, matches(identity), identity.frame > lastPresented else { return false }
        lastPresented = identity.frame; presentedAt = CACurrentMediaTime(); return true
    }
    var fresh: Bool { lock.lock(); defer { lock.unlock() }; return tracking && presentedAt != 0 && CACurrentMediaTime() - presentedAt < 0.25 }
}
