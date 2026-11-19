import SwiftUI

/// One screen for every option the cinematic work adds, grouped by what it changes.
///
/// Light and sound options act in the theater while Effects is on in the control bar
/// and their master switches (Lighting Emission, spatial audio) are on. Those two
/// masters are repeated at the top of their sections so nothing has to be hunted for.
struct CinemaSettingsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var app = app
        @Bindable var settings = app.settings
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack {
                    Text("Cinema Options").font(.title2)
                    Spacer()
                    Button("Close") { dismiss() }
                }

                section("Picture") {
                    Toggle("Smooth frame pacing", isOn: $settings.values.framePacing)
                    note("Holds a frame back when one is already waiting, so frames are shown at an even rhythm. Applies from the next connection.")
                    Toggle("Ask for a matching refresh rate", isOn: $settings.values.refreshRateHint)
                    note("Tells the system the stream's frame rate. The headset may ignore it.")
                }

                section("Light") {
                    Toggle("Lighting Emission", isOn: $app.lightingEmission)
                    Toggle("Room Dimming", isOn: $app.roomDimming)
                    Toggle("Room light", isOn: $settings.values.roomLight)
                    note("Projects a soft copy of the picture from the screen onto your real floor, walls and desk.")
                    slider("Room light strength", value: $settings.values.roomLightStrength, in: 0...3, format: "%.1f×")
                    slider("Room light reach", value: $settings.values.roomLightReach, in: 1...8, format: "%.1f m")
                    note("Reach is the main cost. Both lights shorten automatically as the headset warms.")
                    Toggle("Flip room light vertically", isOn: $settings.values.roomLightFlipVertical)
                    note("Use this if the floor takes the colour of the top of the picture.")
                    Toggle("Bias light", isOn: $settings.values.biasLight)
                    note("A glow on the wall behind the screen, in the picture's average colour.")
                    slider("Bias light strength", value: $settings.values.biasLightStrength, in: 0...3, format: "%.1f×")
                    Toggle("Room tint", isOn: $settings.values.roomTint)
                    note("Tints your whole view of the room with the picture's average colour. This was the original lighting effect.")
                    Toggle("Use the room's shape", isOn: $settings.values.roomMesh)
                    note("Lets light land on real surfaces. Real furniture in front of the screen will also hide it. Asks for permission to sense your surroundings.")
                }

                section("Sound") {
                    Toggle("Enable spatial audio", isOn: $app.spatialAudio)
                    Picker("Arrangement", selection: $settings.values.soundMode) {
                        ForEach(SoundMode.allCases) { Text($0.label).tag($0) }
                    }.pickerStyle(.segmented)
                    note(settings.values.soundMode.detail)
                    Text("Now playing: \(app.soundStatus)").font(.caption)
                    slider("Low-frequency level", value: $settings.values.lowFrequencyGain, in: -24...0, format: "%.0f dB")
                    note("Placed speakers only. The low-frequency channel plays without a direction.")
                    slider("Lip-sync delay", value: $settings.values.lipSyncMilliseconds, in: 0...300, format: "%.0f ms")
                    note("Delays sound to meet a late picture. Applies to the surround bed and placed speakers.")
                    HStack {
                        Button(app.speakerCheck == nil ? "Speaker check" : "Playing: \(app.speakerCheck ?? "")") { app.runSpeakerCheck() }
                            .disabled(app.speakerCheck != nil)
                        Button("Centre speakers on me") { app.reseatSpeakers() }
                            .disabled(settings.values.soundMode != .speakers)
                    }
                    note("The check plays a tone from each channel in turn. It needs the theater open with a stream playing.")
                }

                section("Screen") {
                    slider("Curve", value: $settings.values.curve, in: 0...1, format: "%.2f")
                    slider("Tilt", value: $settings.values.tiltDegrees, in: -30...30, format: "%.0f°")
                    Toggle("Remember size and distance", isOn: $settings.values.restorePlacement)
                    ForEach(0..<3, id: \.self) { index in
                        HStack {
                            Text("Preset \(index + 1)")
                            Spacer()
                            Button("Use") { app.applyPreset(index) }.disabled(settings.values.presets[index] == nil)
                            Button("Save current") { app.savePreset(index) }
                        }
                    }
                    note("A preset keeps the screen's size, distance, height, curve and tilt, and puts it back in front of you.")
                }

                section("Session") {
                    Toggle("Start in a window", isOn: $settings.values.startInWindow)
                    Toggle("Connect when the app opens", isOn: $settings.values.resumeOnLaunch)
                    Toggle("Reconnect automatically", isOn: $settings.values.autoReconnect)
                    note("Tries three times, a little longer apart each time, when a stream drops by itself.")
                    Toggle("Show statistics above the screen", isOn: $settings.values.showStatistics)
                    Toggle("Forward keyboard and mouse", isOn: $settings.values.keyboardAndMouse)
                    note("Experimental. Hold Menu and Options together on a controller to bring the controls back at any time.")
                }
            }
            .padding(24)
        }
        .frame(width: 560, height: 640)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private func note(_ text: String) -> some View {
        Text(text).font(.caption).foregroundStyle(.secondary)
    }

    private func slider<V: BinaryFloatingPoint>(_ title: String, value: Binding<V>, in range: ClosedRange<V>, format: String) -> some View where V.Stride: BinaryFloatingPoint {
        HStack {
            Text(title)
            Slider(value: value, in: range)
            Text(String(format: format, Double(value.wrappedValue))).monospacedDigit().frame(width: 70, alignment: .trailing)
        }
    }
}
