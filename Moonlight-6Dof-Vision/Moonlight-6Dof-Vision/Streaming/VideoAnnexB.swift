import Foundation

/// Copies borrowed Annex B bytes into the length-prefixed form required by VideoToolbox.
enum VideoAnnexB {
    static func lengthPrefixed(_ bytes: UnsafeBufferPointer<UInt8>) -> Data? {
        var units: [(prefix: Int, payload: Int)] = []
        var index = 0
        while index + 2 < bytes.count {
            if bytes[index] == 0 && bytes[index + 1] == 0 {
                let prefixLength: Int
                if bytes[index + 2] == 1 { prefixLength = 3 }
                else if index + 3 < bytes.count && bytes[index + 2] == 0 && bytes[index + 3] == 1 { prefixLength = 4 }
                else { index += 1; continue }
                units.append((index, index + prefixLength))
                index += prefixLength
            } else { index += 1 }
        }
        guard let first = units.first, bytes[..<first.prefix].allSatisfy({ $0 == 0 }) else { return nil }
        var result = Data()
        result.reserveCapacity(bytes.count + units.count)
        for (number, unit) in units.enumerated() {
            let end = number + 1 < units.count ? units[number + 1].prefix : bytes.count
            let count = end - unit.payload
            guard count > 0, let length = UInt32(exactly: count) else { return nil }
            var bigEndian = length.bigEndian
            withUnsafeBytes(of: &bigEndian) { result.append(contentsOf: $0) }
            result.append(contentsOf: bytes[unit.payload..<end])
        }
        return result
    }
}
