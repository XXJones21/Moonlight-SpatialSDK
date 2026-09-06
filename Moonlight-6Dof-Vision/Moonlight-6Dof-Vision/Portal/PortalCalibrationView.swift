import SwiftUI

struct PortalCalibrationView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        @Bindable var scene = app.portal
        VStack(alignment: .leading, spacing: 16) {
            Text("Portal Calibration").font(.title2)
            Text("Eye separation and head offset are explicit calibration values. They are not measurements from Vision Pro.").font(.caption).foregroundStyle(.secondary)
            HStack {
                Text("Eye separation (mm)")
                Slider(value: Binding(get: { scene.eyeSeparation * 1000 }, set: { scene.eyeSeparation = $0 / 1000; app.saveCalibration() }), in: 50...80, step: 1)
                Text("\(Int(scene.eyeSeparation * 1000))").monospacedDigit()
            }
            ForEach(0..<3, id: \.self) { axis in
                HStack {
                    Text("Head offset \(["X", "Y", "Z"][axis]) (cm)")
                    Slider(value: Binding(get: { Double(scene.deviceToHead[axis]) * 100 }, set: { scene.deviceToHead[axis] = Float($0 / 100); app.saveCalibration() }), in: -10...10, step: 0.5)
                    Text(String(format: "%.1f", scene.deviceToHead[axis] * 100)).monospacedDigit()
                }
            }
            Button("Recenter Portal") { scene.recenter() }.disabled(!scene.trackingValid && !scene.needsRecenter)
            Text(scene.trackingMessage).foregroundStyle(.secondary)
            Button("Close") { dismiss() }
        }.padding(24).frame(width: 560)
    }
}
