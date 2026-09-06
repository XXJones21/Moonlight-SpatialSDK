import SwiftUI
import VideoToolbox

struct StreamConfigurationView: View {
    let saved: StreamPreferences
    let apply: (StreamPreferences) -> Void
    @State private var draft: StreamPreferences
    @State private var savedMessage = false
    init(saved: StreamPreferences, apply: @escaping (StreamPreferences) -> Void) { self.saved = saved; self.apply = apply; _draft = State(initialValue: saved) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Configure Stream").font(.headline)
            Picker("Resolution per eye", selection: Binding(get: { "\(draft.eyeWidth)x\(draft.eyeHeight)" }, set: { value in
                let parts = value.split(separator: "x").compactMap { Int($0) }; if parts.count == 2 { draft.eyeWidth = parts[0]; draft.eyeHeight = parts[1] }
            })) {
                ForEach(StreamPreferences.resolutions, id: \.self) { size in Text("\(size[0]) × \(size[1])").tag("\(size[0])x\(size[1])") }
            }
            Picker("FPS", selection: $draft.fps) { ForEach([30,60,90,120], id: \.self) { Text("\($0)").tag($0) } }
            Picker("Codec", selection: $draft.codec) {
                Text("Auto").tag("auto"); Text("H.264").tag("h264"); Text("HEVC").tag("hevc"); Text("AV1").tag("av1")
            }
            Picker("Audio", selection: $draft.audio) { ForEach(["Stereo","5.1","7.1"], id: \.self) { Text($0).tag($0) } }
            Toggle("HDR", isOn: $draft.hdr)
            Toggle("Full Range", isOn: $draft.fullRange)
            HStack {
                Button("Apply") { apply(draft); savedMessage = true }
                if savedMessage { Text("Saved for the next connection").font(.caption).foregroundStyle(.secondary) }
            }
        }.padding(16).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .onChange(of: draft) { savedMessage = false }
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
            Toggle("Enable spatial audio", isOn: $app.spatialAudio)
            Toggle("Room Dimming", isOn: $app.roomDimming)
            Toggle("Lighting Emission", isOn: $app.lightingEmission)
            Toggle("Reflections", isOn: .constant(false)).disabled(true)
            Text("Reflections are unavailable in this version.").font(.caption).foregroundStyle(.secondary)
            Button("Close") { dismiss() }
        }.padding(24).frame(width: 440)
    }
}
