import SwiftUI
import RealityKit

/// Window mode: the live picture in an ordinary window, beside other apps.
///
/// The window shows the same texture the theater's screen uses, on its own entity, so
/// switching presentation does not touch the decoder. Room light and placed sound are
/// theater features; here the system places the audio around the window.
struct PlayerWindowView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openWindow) private var openWindow
    private var switcher = PresentationSwitch()
    @State private var screen = WindowScreen()

    var body: some View {
        // Read here, not inside the RealityView closures, so the view updates when the
        // stream's texture or shape changes.
        let texture = app.session.videoTexture
        let aspect = pictureAspect
        let showPicture = app.session.state == .streaming && !app.disconnected
        GeometryReader3D { proxy in
            RealityView { content in
                content.add(screen.entity)
            } update: { content in
                // The window's size in scene metres, from its two opposite corners. The
                // point conversion is the documented overload, so it is used twice
                // instead of relying on a rectangle conversion.
                let frame = proxy.frame(in: .local)
                let low = content.convert(frame.min, from: .local, to: .scene)
                let high = content.convert(frame.max, from: .local, to: .scene)
                screen.layout(width: abs(high.x - low.x), height: abs(high.y - low.y), aspect: aspect, texture: showPicture ? texture : nil)
            }
        }
        .overlay { if !showPicture { status } }
        .ornament(attachmentAnchor: .scene(.bottom)) { controls }
        .background(ImmersiveAudioSceneReader { app.portal.bindAudioScene($0) }.frame(width: 0, height: 0))
        .streamDisplayHint(app)
        .streamControllerEvents()
        .onDisappear { app.playerWindowDidClose() }
    }

    private var pictureAspect: Float {
        if app.session.negotiatedWidth > 0, app.session.negotiatedHeight > 0 {
            return Float(app.session.negotiatedWidth) / Float(app.session.negotiatedHeight)
        }
        if let config = app.session.activeConfiguration, config.eyeHeight > 0 {
            return Float(config.eyeWidth) / Float(config.eyeHeight)
        }
        return 16 / 9
    }

    private var controls: some View {
        HStack(spacing: 16) {
            Button("Settings", systemImage: "gearshape") { openWindow(id: AppModel.launcherWindowID, value: "main") }
            Button("Theater", systemImage: "sparkles.tv") { Task { await switcher.openTheater() } }
                .disabled(app.immersiveSpaceState == .inTransition)
            Button("Disconnect", systemImage: "xmark.circle") { app.disconnect() }
        }
        .labelStyle(.titleAndIcon).padding(12).glassBackgroundEffect()
        .streamControllerEvents()
    }

    private var status: some View {
        VStack(spacing: 16) {
            Text(app.disconnected ? "Disconnected" : (app.hasSession ? app.coordinator.status : "No stream")).font(.title2)
            if let message = app.message { Text(message).frame(maxWidth: 420).multilineTextAlignment(.center) }
            HStack {
                if app.disconnected {
                    Button("Reconnect") { Task { await app.reconnect() } }.disabled(!app.canReconnect)
                }
                Button("Return to Home") { Task { await switcher.returnHome() } }
            }
        }
        .padding(24).glassBackgroundEffect()
    }
}

/// The window's own screen entity. Plain state, deliberately not observed: it is
/// changed from the RealityView's update closure, where touching observed state would
/// ask SwiftUI to update the view it is in the middle of updating.
@MainActor
final class WindowScreen {
    let entity = ModelEntity()
    private var size = SIMD2<Float>.zero
    private var shown: TextureResource?
    private var hasMaterial = false

    init() { entity.name = "WindowScreen" }

    /// Fits a plane of the picture's shape inside the window, letterboxing as needed.
    func layout(width: Float, height: Float, aspect: Float, texture: TextureResource?) {
        guard width > 0.001, height > 0.001, aspect > 0 else { return }
        var w = width, h = width / aspect
        if h > height { h = height; w = height * aspect }
        let resized = abs(w - size.x) > 0.0005 || abs(h - size.y) > 0.0005 || entity.model == nil
        if resized {
            size = [w, h]
            entity.model = ModelComponent(mesh: .generatePlane(width: w, height: h, cornerRadius: min(w, h) * 0.02), materials: [material(texture)])
        } else if texture !== shown || !hasMaterial {
            entity.model?.materials = [material(texture)]
        }
        shown = texture; hasMaterial = true
    }

    private func material(_ texture: TextureResource?) -> UnlitMaterial {
        guard let texture else { return UnlitMaterial(color: .black) }
        var material = UnlitMaterial()
        material.color = .init(tint: .white, texture: .init(texture))
        return material
    }
}
