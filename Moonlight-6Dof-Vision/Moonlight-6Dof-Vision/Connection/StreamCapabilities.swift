import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif
import Observation
import VideoToolbox

struct EncodedStreamSize: Equatable {
    let width: Int
    let height: Int
    let metadataRows: Int

    init(eyeWidth: Int, eyeHeight: Int, metadata: Bool) {
        width = eyeWidth * 2
        metadataRows = metadata ? 16 : 0
        height = eyeHeight + metadataRows
    }

    init(_ preferences: StreamPreferences) {
        self.init(eyeWidth: preferences.eyeWidth, eyeHeight: preferences.eyeHeight, metadata: preferences.metadata)
    }
}

struct HostStreamMode: Hashable {
    let width: Int
    let height: Int
    let refreshRate: Double
}

/// Read the host's actual advertised tuples. Missing lists are unknown, never guessed limits.
private final class HostModeParser: NSObject, XMLParserDelegate {
    private var path: [String] = []
    private var fields: [String: String]?
    private var text = ""
    private var malformedMode = false
    private(set) var modes: Set<HostStreamMode> = []

    static func read(_ data: Data) -> Set<HostStreamMode>? {
        guard !data.isEmpty, data.count <= 1_048_576 else { return nil }
        let delegate = HostModeParser()
        let parser = XMLParser(data: data)
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        guard parser.parse() else { return nil }
        return delegate.modes
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        guard path.count < 64 else { parser.abortParsing(); return }
        path.append(elementName)
        text = ""
        if elementName == "SupportedDisplayMode" {
            guard fields == nil else { parser.abortParsing(); return }
            fields = [:]; malformedMode = false
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard text.utf8.count + string.utf8.count <= 4096 else { parser.abortParsing(); return }
        text += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        defer { if !path.isEmpty { path.removeLast() }; text = "" }
        if path.count >= 2, path[path.count - 2] == "SupportedDisplayMode", ["Width", "Height", "RefreshRate"].contains(elementName) {
            if fields?[elementName] != nil { malformedMode = true }
            fields?[elementName] = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if elementName == "SupportedDisplayMode" {
            if !malformedMode, let fields,
               let width = Int(fields["Width"] ?? ""), width > 0,
               let height = Int(fields["Height"] ?? ""), height > 0,
               let refreshRate = Double(fields["RefreshRate"] ?? ""), refreshRate.isFinite, refreshRate > 0 {
                modes.insert(HostStreamMode(width: width, height: height, refreshRate: refreshRate))
                if modes.count > 1024 { parser.abortParsing() }
            }
            fields = nil
        }
    }
}

@MainActor @Observable final class StreamCapabilities {
    enum State: Equatable { case idle, loading, loaded, noHost, failed(String) }
    private(set) var state = State.idle
    private(set) var deviceCodecs: [String: Bool] = [:]
    private(set) var hostCodecMask: UInt32?
    private(set) var hostModes: Set<HostStreamMode>?
    private(set) var hostName = ""
    private var client: MLClient?
    private var request = UUID()

    var isLoading: Bool { state == .loading }
    var deviceSummary: String {
        ["h264", "hevc", "av1"].filter { deviceCodecs[$0] == true }.map(Self.label).joined(separator: ", ")
    }
    var hostCodecSummary: String? {
        guard let mask = hostCodecMask else { return nil }
        return ["h264", "hevc", "av1"].filter { mask & Self.hostMask(for: $0, hdr: false) != 0 }.map(Self.label).joined(separator: ", ")
    }

    func load(server: SavedServer?) {
        cancel()
        deviceCodecs = ["h264": VTIsHardwareDecodeSupported(kCMVideoCodecType_H264),
                        "hevc": VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC),
                        "av1": VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1)]
        hostCodecMask = nil; hostModes = nil; hostName = server?.name ?? ""
        guard let server else { state = .noHost; return }
        state = .loading
        let generation = UUID(); request = generation
        let client = MLClient(); self.client = client
        client.event = { [weak self] name, payload in
            Task { @MainActor in
                guard let self, self.request == generation else { return }
                if name == "error" {
                    self.state = .failed(payload["message"] as? String ?? "Host capability query failed")
                } else if name == "host" {
                    guard let xml = payload["serverInfoXML"] as? Data, let modes = HostModeParser.read(xml) else {
                        self.state = .failed("The host returned unreadable capability data")
                        return
                    }
                    if let value = payload["codecs"] as? NSNumber, value.int64Value > 0, value.uint64Value <= UInt64(UInt32.max) {
                        self.hostCodecMask = value.uint32Value
                    }
                    self.hostModes = modes.isEmpty ? nil : modes
                    self.hostName = payload["name"] as? String ?? server.name
                    self.state = .loaded
                }
            }
        }
        client.inspect(address: server.address, certificate: PortalKeychain.data(for: server.address))
    }

    func cancel() {
        request = UUID()
        client?.event = { _, _ in }
        client?.stop { }
        client = nil
    }

    func supportsCodec(_ codec: String, hdr: Bool = false) -> Bool {
        if codec == "auto" { return ["h264", "hevc", "av1"].contains { supportsCodec($0, hdr: hdr) } }
        guard deviceCodecs[codec] == true, !hdr || codec != "h264" else { return false }
        guard let hostCodecMask else { return true } // Host support remains unknown until successfully reported.
        return hostCodecMask & Self.hostMask(for: codec, hdr: hdr) != 0
    }

    func supportsResolution(eyeWidth: Int, eyeHeight: Int, metadata: Bool) -> Bool {
        guard let hostModes else { return true }
        let encoded = EncodedStreamSize(eyeWidth: eyeWidth, eyeHeight: eyeHeight, metadata: metadata)
        return hostModes.contains { $0.width == encoded.width && $0.height == encoded.height }
    }

    func supportsMode(_ encoded: EncodedStreamSize, fps: Int) -> Bool {
        guard let hostModes else { return true }
        return hostModes.contains { $0.width == encoded.width && $0.height == encoded.height && abs($0.refreshRate - Double(fps)) < 0.01 }
    }

    func unavailableReason(for preferences: StreamPreferences) -> String? {
        if !supportsCodec(preferences.codec, hdr: preferences.hdr) {
            return preferences.hdr ? "This HDR codec selection is unavailable on the device or selected host." : "This codec is unavailable on the device or selected host."
        }
        let encoded = EncodedStreamSize(preferences)
        if !supportsMode(encoded, fps: preferences.fps) {
            return "The host does not advertise \(encoded.width) × \(encoded.height) at \(preferences.fps) FPS. Choose a listed combination or configure that full stereo mode on the host."
        }
        return nil
    }

    static func label(_ codec: String) -> String {
        switch codec { case "h264": return "H.264"; case "hevc": return "HEVC"; case "av1": return "AV1"; default: return "Auto" }
    }

    private static func hostMask(for codec: String, hdr: Bool) -> UInt32 {
        // Server SCM masks from the pinned common library differ from client VIDEO_FORMAT masks for AV1.
        switch codec {
        case "h264": return hdr ? 0 : UInt32(SCM_MASK_H264)
        case "hevc": return UInt32(hdr ? SCM_HEVC_MAIN10 : SCM_MASK_HEVC)
        case "av1": return UInt32(hdr ? SCM_AV1_MAIN10 : SCM_MASK_AV1)
        default: return 0
        }
    }
}
