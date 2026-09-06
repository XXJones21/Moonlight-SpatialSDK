import Foundation
import VideoToolbox
import Security

struct StreamPreferences: Codable, Equatable {
    var eyeWidth = 1280
    var eyeHeight = 720
    var fps = 60
    var codec = "auto"
    var audio = "Stereo"
    var hdr = false
    var fullRange = false
    var bitrateKbps: Int? = nil
    // 6DoF requires SBS plus frame identity strips; ordinary Moonlight uses one full image.
    var metadata = false
    var encodedWidth: Int { metadata ? eyeWidth * 2 : eyeWidth }
    var encodedHeight: Int { eyeHeight + (metadata ? 16 : 0) }
    var automaticBitrate: Int { min(150_000, max(5_000, Int(Double(encodedWidth * eyeHeight * fps) * 0.12 / 1000))) }
    static let resolutions = [[640,360],[854,480],[1280,720],[1920,1080],[2560,1440],[3840,2160]]
    static let bench = StreamPreferences(eyeWidth: 1920, eyeHeight: 1080, codec: "hevc", bitrateKbps: 50_000, metadata: true)
    static func load() -> Self {
        guard let data = UserDefaults.standard.data(forKey: "portal.stream"), let settings = try? JSONDecoder().decode(Self.self, from: data),
              resolutions.contains([settings.eyeWidth, settings.eyeHeight]), [30,60,90,120].contains(settings.fps),
              ["auto","h264","hevc","av1"].contains(settings.codec), ["Stereo","5.1","7.1"].contains(settings.audio) else { return Self() }
        return settings
    }
    func save() { UserDefaults.standard.set(try? JSONEncoder().encode(self), forKey: "portal.stream") }
    func videoFormats() throws -> Int32 {
        let hevc = VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)
        let av1 = VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1)
        var formats: Int32 = 0x0001
        switch codec {
        case "h264": break
        case "hevc": guard hevc else { throw StreamError.unavailable("HEVC hardware decoding is unavailable") }; formats = 0x0100
        case "av1": guard av1 else { throw StreamError.unavailable("AV1 hardware decoding is unavailable") }; formats = 0x1000
        default: if hevc { formats |= 0x0100 }; if av1 { formats |= 0x1000 }
        }
        if hdr {
            guard formats & 0x1100 != 0 else { throw StreamError.unavailable("HDR requires HEVC or AV1") }
            if formats & 0x0100 != 0 { formats |= 0x0200 }
            if formats & 0x1000 != 0 { formats |= 0x2000 }
        }
        return formats
    }
    var audioConfiguration: Int {
        switch audio { case "5.1": return (0x3f << 16) | (6 << 8) | 0xca; case "7.1": return (0x63f << 16) | (8 << 8) | 0xca; default: return (3 << 16) | (2 << 8) | 0xca }
    }
}
enum StreamError: LocalizedError {
    case unavailable(String)
    var errorDescription: String? { switch self { case .unavailable(let message): return message } }
}

struct SavedServer: Codable, Identifiable, Equatable {
    var id: String { address }
    var host: String
    var port: UInt16 = 47989
    var name: String
    var appID = "0"
    var address: String { host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)" }
}

enum PortalKeychain {
    static func data(for account: String) -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "MoonlightPortal.Hosts", kSecAttrAccount as String: account, kSecReturnData as String: true]
        var value: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &value) == errSecSuccess else { return nil }
        return value as? Data
    }
    static func save(_ data: Data, for account: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "MoonlightPortal.Hosts", kSecAttrAccount as String: account]
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem {
            guard SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary) == errSecSuccess else { throw StreamError.unavailable("Could not update pairing certificate") }
        } else if status != errSecSuccess { throw StreamError.unavailable("Could not store pairing certificate (\(status))") }
    }
    static func reset() { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "MoonlightPortal.Hosts"] as CFDictionary) }
}
