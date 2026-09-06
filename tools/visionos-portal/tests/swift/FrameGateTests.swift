import Foundation
import CoreVideo

@main
struct FrameGateTests {
    static func main() {
        var raw: CVPixelBuffer?
        precondition(CVPixelBufferCreate(kCFAllocatorDefault, 704, 32, kCVPixelFormatType_32BGRA, nil, &raw) == kCVReturnSuccess)
        let buffer = raw!
        let gate = PortalFrameGate()
        gate.configure(session: 1, epoch: 2, revision: 3, width: 704, height: 32, tracking: true)
        func writeTag(session: UInt64 = 1, epoch: UInt64 = 2, revision: UInt64 = 3, frame: UInt64 = 4, corruptCRC: Bool = false, mismatch: Bool = false) {
            var tag = Data([0x50,0x36,0x46,0x4d,1,1,0,0])
            for value in [session, epoch, revision, frame] {
                for shift in stride(from: 0, to: 64, by: 8) { tag.append(UInt8(truncatingIfNeeded: value >> shift)) }
            }
            var crc: UInt32 = 0xffffffff
            for byte in tag {
                crc ^= UInt32(byte)
                for _ in 0..<8 { crc = crc & 1 == 0 ? crc >> 1 : (crc >> 1) ^ 0xedb88320 }
            }
            crc = ~crc
            if corruptCRC { crc ^= 1 }
            for shift in stride(from: 0, to: 32, by: 8) { tag.append(UInt8(truncatingIfNeeded: crc >> shift)) }
            precondition(CVPixelBufferLockBaseAddress(buffer, []) == kCVReturnSuccess)
            defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
            let base = CVPixelBufferGetBaseAddress(buffer)!
            let stride = CVPixelBufferGetBytesPerRow(buffer)
            memset(base, 0, stride * 32)
            for eye in 0..<2 {
                for bit in 0..<352 where tag[bit / 8] & (1 << (7 - bit % 8)) != 0 {
                    for y in 0..<8 {
                        for x in 0..<2 {
                            let offset = (16 + bit / 176 * 8 + y) * stride + (eye * 352 + bit % 176 * 2 + x) * 4
                            base.storeBytes(of: UInt32.max, toByteOffset: offset, as: UInt32.self)
                        }
                    }
                }
            }
            if mismatch { base.storeBytes(of: UInt32.max, toByteOffset: 20 * stride + 353 * 4, as: UInt32.self) }
        }
        writeTag()
        precondition(gate.accepts(buffer))
        let identity = PortalFrameIdentity.read(buffer)!
        precondition(gate.presented(identity) && gate.fresh)
        precondition(!gate.presented(identity), "Repeated frame must not refresh visibility")
        writeTag(corruptCRC: true)
        precondition(!gate.accepts(buffer), "Bad CRC must remain hidden in 6DoF")
        writeTag(mismatch: true)
        precondition(!gate.accepts(buffer), "Eye tags must match")
        writeTag(revision: 2)
        precondition(!gate.accepts(buffer), "Old geometry must remain hidden")
        writeTag(session: 9)
        precondition(!gate.accepts(buffer), "Old connection must remain hidden")
        writeTag()
        gate.configure(session: 1, epoch: 2, revision: 3, width: 704, height: 32, tracking: false)
        precondition(!gate.accepts(buffer) && !gate.fresh)
        gate.close()
        precondition(!gate.accepts(buffer) && !gate.presented(identity))
        print("6DoF frame CRC, eye agreement, session/revision, repeat, tracking and close checks passed")
    }
}
