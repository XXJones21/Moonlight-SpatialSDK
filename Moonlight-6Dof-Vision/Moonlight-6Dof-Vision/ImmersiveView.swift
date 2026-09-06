import SwiftUI
import RealityKit

struct ImmersiveView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace
    @State private var updateSubscription: EventSubscription?

    var body: some View {
        RealityView { content, attachments in
            content.add(app.portal.root)
            if let controls = attachments.entity(for: "controls") { app.portal.shelf.addChild(controls) }
            updateSubscription = content.subscribe(to: SceneEvents.Update.self) { _ in
                Task { @MainActor in app.portal.tick() }
            }
            do { try await app.portal.install(texture: StereoPreview.makeTexture()) }
            catch { app.message = "Could not load stereo material: \(error.localizedDescription)" }
        } attachments: {
            Attachment(id: "controls") {
                HStack(spacing: 16) {
                    Button("Settings", systemImage: "gearshape") { openWindow(id: "settings"); app.portal.keepShelfVisible() }
                    Button("Resize", systemImage: "arrow.up.left.and.arrow.down.right") { app.portal.resetSize() }
                    Button("Immersive", systemImage: app.immersiveEffectsEnabled ? "sun.max.fill" : "sun.max") { app.immersiveEffectsEnabled.toggle() }
                        .tint(app.immersiveEffectsEnabled ? .blue : nil)
                    Button("Disconnect", systemImage: "xmark.circle") { Task { app.disconnect(); await dismissImmersiveSpace() } }
                }
                .labelStyle(.titleAndIcon)
                .padding(12)
                .glassBackgroundEffect()
                .onHover { active in if active { app.portal.keepShelfVisible() } }
            }
        }
        .gesture(DragGesture().targetedToAnyEntity().onChanged { app.portal.drag($0) }.onEnded { _ in app.portal.endDrag() })
        .simultaneousGesture(RotateGesture3D(constrainedToAxis: .y).targetedToAnyEntity().onChanged { app.portal.rotate($0) }.onEnded { _ in app.portal.endRotate() })
        .simultaneousGesture(SpatialTapGesture().targetedToAnyEntity().onEnded { _ in app.portal.revealControls() })
        .preferredSurroundingsEffect(app.immersiveEffectsEnabled && app.roomDimming ? .dark : nil)
        .task { await app.portal.startTracking() }
        .onDisappear { updateSubscription?.cancel(); updateSubscription = nil; app.portal.stopTracking(); app.immersiveSpaceState = .closed; openWindow(id: "settings") }
    }
}
