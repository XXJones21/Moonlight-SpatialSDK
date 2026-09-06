import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var app
    var body: some View {
        VStack(spacing: 20) {
            Label("Moonlight Connection", systemImage: "moon.stars.fill").font(.title)
            Divider()
            Text(app.portal.trackingMessage)
            if let message = app.message { Text(message).foregroundStyle(.red) }
            ToggleImmersiveSpaceButton()
            Button("Recenter Portal") { app.portal.recenter() }.disabled(!app.portal.trackingValid && !app.portal.needsRecenter)
            Text("Drag the plane to move it. Use two hands to turn it. Drag a corner to resize.").foregroundStyle(.secondary)
        }.padding(24).frame(minWidth: 650, minHeight: 400)
    }
}
