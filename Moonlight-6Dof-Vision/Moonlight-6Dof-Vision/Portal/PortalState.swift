import Foundation
import simd

struct PortalPose: Sendable {
    var position: SIMD3<Double>
    var orientation: simd_quatd
    private let rigid: Bool
    init(_ matrix: simd_float4x4) {
        let x = SIMD3(matrix.columns.0.x, matrix.columns.0.y, matrix.columns.0.z)
        let y = SIMD3(matrix.columns.1.x, matrix.columns.1.y, matrix.columns.1.z)
        let z = SIMD3(matrix.columns.2.x, matrix.columns.2.y, matrix.columns.2.z)
        rigid = [matrix.columns.0, matrix.columns.1, matrix.columns.2, matrix.columns.3].allSatisfy { column in (0..<4).allSatisfy { column[$0].isFinite } }
            && abs(simd_length_squared(x) - 1) < 0.001 && abs(simd_length_squared(y) - 1) < 0.001 && abs(simd_length_squared(z) - 1) < 0.001
            && abs(simd_dot(x, y)) < 0.001 && abs(simd_dot(x, z)) < 0.001 && abs(simd_dot(y, z)) < 0.001
            && simd_dot(simd_cross(x, y), z) > 0.999
            && abs(matrix.columns.0.w) < 0.001 && abs(matrix.columns.1.w) < 0.001 && abs(matrix.columns.2.w) < 0.001 && abs(matrix.columns.3.w - 1) < 0.001
        position = SIMD3<Double>(Double(matrix.columns.3.x), Double(matrix.columns.3.y), Double(matrix.columns.3.z))
        let q = simd_quatf(matrix).normalized
        orientation = simd_quatd(vector: SIMD4<Double>(Double(q.vector.x), Double(q.vector.y), Double(q.vector.z), Double(q.vector.w)))
    }
    var values: [Double] { [position.x, position.y, position.z, orientation.vector.x, orientation.vector.y, orientation.vector.z, orientation.vector.w] }
    var isValid: Bool { rigid && values.allSatisfy(\.isFinite) && abs(simd_length_squared(orientation.vector) - 1) <= 0.001 }
}

struct PortalState: Sendable {
    var sessionID: UInt64
    var trackingEpoch: UInt64
    var sequence: UInt64
    var geometryRevision: UInt64
    var sampleTimeNs: UInt64
    var targetTimeNs: UInt64
    var trackingValid: Bool
    var worldFromHead: PortalPose
    var worldFromPortal: PortalPose
    var width: Double
    var height: Double
    var ipd: Double

    func encode() -> Data? {
        guard sessionID != 0, trackingEpoch != 0, sequence != 0, geometryRevision != 0,
              worldFromHead.isValid, worldFromPortal.isValid, width.isFinite, height.isFinite, ipd.isFinite,
              width > 0, height > 0, ipd > 0, ipd <= 0.2 else { return nil }
        var data = Data("P6DV".utf8)
        data.appendLE(UInt16(1)); data.appendLE(UInt16(200))
        for value in [sessionID, trackingEpoch, sequence, geometryRevision, sampleTimeNs, targetTimeNs] { data.appendLE(value) }
        data.appendLE(UInt32(trackingValid ? 1 : 0)); data.appendLE(UInt32(0))
        for value in worldFromHead.values + worldFromPortal.values + [width, height, ipd] { data.appendLE(value.bitPattern) }
        return data
    }
    static func reset(session: UInt64, previous: UInt64, next: UInt64) -> Data? {
        guard session != 0, previous != 0, next > previous else { return nil }
        var data = Data("P6DR".utf8)
        data.appendLE(UInt16(1)); data.appendLE(UInt16(32))
        for value in [session, previous, next] { data.appendLE(value) }
        return data
    }
}

extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        Swift.withUnsafeBytes(of: &little) { append(contentsOf: $0) }
    }
    func integerLE<T: FixedWidthInteger>(at offset: Int, as: T.Type = T.self) -> T? {
        guard offset >= 0, offset + MemoryLayout<T>.size <= count else { return nil }
        return (0..<MemoryLayout<T>.size).reduce(T(0)) { $0 | T(self[startIndex + offset + $1]) << ($1 * 8) }
    }
}

struct PortalHostStatus: Decodable, Sendable {
    let version: Int
    let sessionID: String
    let trackingEpoch: String
    let acceptedSequence: String
    let geometryRevision: String
    let renderFrameID: String
    let trackingValid: Bool
    let outputMode: String
    let errorCode: String
}
