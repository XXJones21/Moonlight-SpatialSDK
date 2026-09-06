import Foundation

extension PortalHostStatus {
    /// Same restricted flat-ASCII grammar as PortalCore, including duplicate rejection.
    static func decode(_ data: Data) -> Self? {
        guard !data.isEmpty, data.count <= 1024, data.last == 10 else { return nil }
        let bytes = Array(data); var index = 0
        func space() { while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 } }
        func take(_ byte: UInt8) -> Bool {
            space(); guard index < bytes.count, bytes[index] == byte else { return false }; index += 1; return true
        }
        func quoted() -> String? {
            guard take(34) else { return nil }; let start = index
            while index < bytes.count && bytes[index] != 34 {
                guard (32...126).contains(bytes[index]), bytes[index] != 92 else { return nil }; index += 1
            }
            guard index < bytes.count else { return nil }
            let value = String(decoding: bytes[start..<index], as: UTF8.self); index += 1; return value
        }
        guard take(123) else { return nil }
        var fields: [String: (text: String, quoted: Bool)] = [:]
        while true {
            guard let key = quoted(), fields[key] == nil, take(58) else { return nil }
            space(); guard index < bytes.count else { return nil }
            if bytes[index] == 34 {
                guard let value = quoted() else { return nil }; fields[key] = (value, true)
            } else {
                let start = index
                while index < bytes.count && ((97...122).contains(bytes[index]) || (48...57).contains(bytes[index])) { index += 1 }
                guard start < index else { return nil }; fields[key] = (String(decoding: bytes[start..<index], as: UTF8.self), false)
            }
            guard fields.count <= 9 else { return nil }
            if take(125) { break }
            guard take(44) else { return nil }
        }
        space()
        let names: Set<String> = ["version", "sessionID", "trackingEpoch", "acceptedSequence", "geometryRevision", "renderFrameID", "trackingValid", "outputMode", "errorCode"]
        guard index == bytes.count, Set(fields.keys) == names, fields["version"]?.text == "1", fields["version"]?.quoted == false else { return nil }
        for name in ["sessionID", "trackingEpoch", "acceptedSequence", "geometryRevision", "renderFrameID"] {
            guard let field = fields[name], field.quoted, let value = UInt64(field.text), String(value) == field.text else { return nil }
        }
        guard let valid = fields["trackingValid"], !valid.quoted, ["true", "false"].contains(valid.text),
              let mode = fields["outputMode"], mode.quoted, !mode.text.isEmpty, mode.text.utf8.count <= 32,
              mode.text.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95 }),
              let error = fields["errorCode"], error.quoted,
              ["none", "staleTracking", "invalidGeometry", "unsupportedRuntime", "outputUnavailable"].contains(error.text),
              valid.text != "true" || error.text == "none",
              let result = try? JSONDecoder().decode(Self.self, from: data), result.sessionID != "0", result.trackingEpoch != "0" else { return nil }
        return result
    }
}
