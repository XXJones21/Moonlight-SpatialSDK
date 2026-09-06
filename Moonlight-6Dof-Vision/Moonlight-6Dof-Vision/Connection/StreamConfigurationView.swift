import SwiftUI
import VideoToolbox

struct StreamConfigurationView: View {
    @Environment(AppModel.self) private var app
    let saved: StreamPreferences
    let apply: (StreamPreferences) -> Void
    @State private var draft: StreamPreferences
    @State private var savedMessage = false
    @State private var capabilities = StreamCapabilities()
    init(saved: StreamPreferences, apply: @escaping (StreamPreferences) -> Void) { self.saved = saved; self.apply = apply; _draft = State(initialValue: saved) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Configure Stream").font(.headline)
            capabilityFeedback
            Picker(app.sixDoFEnabled ? "Resolution per eye" : "Resolution", selection: Binding(get: { "\(draft.eyeWidth)x\(draft.eyeHeight)" }, set: { value in
                let parts = value.split(separator: "x").compactMap { Int($0) }; if parts.count == 2 { draft.eyeWidth = parts[0]; draft.eyeHeight = parts[1] }
            })) {
                ForEach(StreamPreferences.resolutions, id: \.self) { size in
                    Text("\(size[0]) × \(size[1])").tag("\(size[0])x\(size[1])")
                        .disabled(!capabilities.supportsResolution(eyeWidth: size[0], eyeHeight: size[1], metadata: draft.metadata))
                }
            }
            Text(encodedSizeDescription).font(.caption).foregroundStyle(.secondary)
            Picker("FPS", selection: $draft.fps) {
                ForEach([30,60,90,120], id: \.self) { fps in
                    Text("\(fps)").tag(fps).disabled(!capabilities.supportsMode(EncodedStreamSize(draft), fps: fps))
                }
            }
            Picker("Codec", selection: $draft.codec) {
                ForEach(["auto", "h264", "hevc", "av1"], id: \.self) { codec in
                    Text(StreamCapabilities.label(codec)).tag(codec).disabled(!capabilities.supportsCodec(codec, hdr: draft.hdr))
                }
            }
            Picker("Audio", selection: $draft.audio) { ForEach(["Stereo","5.1","7.1"], id: \.self) { Text($0).tag($0) } }
            Toggle("HDR", isOn: $draft.hdr)
                .disabled(!draft.hdr && !capabilities.supportsCodec(draft.codec, hdr: true))
            Toggle("Full Range", isOn: $draft.fullRange)
            if let reason = capabilities.unavailableReason(for: draft), !capabilities.isLoading {
                Text(reason).font(.caption).foregroundStyle(.orange)
            }
            if draft == saved || savedMessage {
                Text("Saved automatically for Connect and Reconnect. An active stream keeps its current settings until reconnected.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Selection not saved yet. Wait for capabilities or resolve the message above before connecting. Closing this configuration discards unsaved changes.")
                    .font(.caption).foregroundStyle(.orange)
            }
        }.padding(16).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .onChange(of: draft) { saveIfValid() }
            .onChange(of: saved) { _, value in if draft != value { draft = value } }
            .onChange(of: capabilities.state) { saveIfValid() }
            .onChange(of: app.sixDoFEnabled) { _, enabled in draft.metadata = enabled }
            .task(id: app.connection.selected?.address) { draft.metadata = app.sixDoFEnabled; capabilities.load(server: app.connection.selected) }
            .onDisappear { capabilities.cancel(); app.connection.hasPendingStreamSettings = false }
    }

    private func saveIfValid() {
        app.connection.hasPendingStreamSettings = draft != saved
        guard !capabilities.isLoading, capabilities.unavailableReason(for: draft) == nil else {
            savedMessage = false
            return
        }
        if draft != saved { apply(draft) }
        app.connection.hasPendingStreamSettings = false
        savedMessage = true
    }

    private var encodedSizeDescription: String {
        let encoded = EncodedStreamSize(draft)
        let metadata = encoded.metadataRows > 0 ? ", including \(encoded.metadataRows) frame-identity rows" : ""
        return draft.metadata
            ? "6DoF stereo: \(encoded.width) × \(encoded.height) encoded pixels\(metadata)."
            : "Desktop stream: \(encoded.width) × \(encoded.height), shown in both eyes."
    }

    @ViewBuilder private var capabilityFeedback: some View {
        VStack(alignment: .leading, spacing: 5) {
            switch capabilities.state {
            case .idle, .loading:
                ProgressView("Loading device and host capabilities…")
            case .noHost:
                Text("No host selected. Device capabilities are loaded; host limits are unknown.")
                    .foregroundStyle(.secondary)
            case .failed(let message):
                Text("Could not load host capabilities: \(message)").foregroundStyle(.orange)
                Text("Host limits are unknown. Device codec availability still applies.").foregroundStyle(.secondary)
                Button("Retry capabilities") { capabilities.load(server: app.connection.selected) }
            case .loaded:
                Label("Capabilities loaded from \(capabilities.hostName)", systemImage: "checkmark.circle")
                if let codecs = capabilities.hostCodecSummary {
                    Text("Host codecs: \(codecs.isEmpty ? "none of the available formats" : codecs)").foregroundStyle(.secondary)
                } else {
                    Text("Host codec support was not reported and remains unknown.").foregroundStyle(.secondary)
                }
                if let modes = capabilities.hostModes {
                    Text("Filtering against \(modes.count) host-advertised display modes using the requested stream size.")
                        .foregroundStyle(.secondary)
                } else {
                    Text("The host did not report usable display modes. Resolution and FPS limits are unknown.")
                        .foregroundStyle(.secondary)
                }
                Button("Refresh capabilities") { capabilities.load(server: app.connection.selected) }
            }
            if !capabilities.deviceCodecs.isEmpty {
                Text("Device hardware decoding: \(capabilities.deviceSummary.isEmpty ? "none reported" : capabilities.deviceSummary).")
                    .foregroundStyle(.secondary)
                Text("Hardware decoder availability does not establish maximum resolution, FPS, or HDR profile support; those device limits remain unverified.")
                    .foregroundStyle(.secondary)
            }
        }.font(.caption)
    }
}

struct DeviceCapabilitiesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var capabilities: [(String, Bool)] = []
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Device Capabilities").font(.title2)
            ForEach(capabilities, id: \.0) { name, supported in Label(name, systemImage: supported ? "checkmark.circle" : "minus.circle") }
            Text("Hardware decode availability is reported by VideoToolbox. The selected resolution and frame rate still depend on the host and device.").font(.caption).foregroundStyle(.secondary)
            Button("Close") { dismiss() }
        }.padding(24).frame(width: 460)
        .task { capabilities = [("H.264", VTIsHardwareDecodeSupported(kCMVideoCodecType_H264)), ("HEVC", VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)), ("AV1", VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1))] }
    }
}

struct ImmersiveOptionsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        @Bindable var app = app
        VStack(alignment: .leading, spacing: 16) {
            Text("Immersive Options").font(.title2)
            StreamModeToggle()
            Text("Off streams the normal desktop. On uses the supported game's stereo portal output. Changing this restarts an active stream.")
                .font(.caption).foregroundStyle(.secondary)
            if app.changingStreamMode { ProgressView("Changing stream mode…") }
            Toggle("Enable spatial audio", isOn: $app.spatialAudio)
            Toggle("Room Dimming", isOn: $app.roomDimming)
            Toggle("Lighting Emission", isOn: $app.lightingEmission)
            Toggle("Reflections", isOn: .constant(false)).disabled(true)
            Text("Reflections are unavailable in this version.").font(.caption).foregroundStyle(.secondary)
            Button("Close") { dismiss() }
        }.padding(24).frame(width: 440)
    }
}

/// The same control is available beside Connect and in Immersive Options.
struct StreamModeToggle: View {
    @Environment(AppModel.self) private var app
    var body: some View {
        Toggle("6DoF Window", isOn: Binding(get: { app.sixDoFEnabled }, set: app.setSixDoFEnabled))
            .disabled(app.changingStreamMode || app.session.state == .connecting || app.session.state == .stopping)
    }
}
