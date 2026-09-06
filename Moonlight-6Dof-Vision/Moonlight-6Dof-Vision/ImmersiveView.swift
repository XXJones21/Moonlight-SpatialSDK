import SwiftUI
import RealityKit

struct ImmersiveView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace
    @Environment(\.scenePhase) private var scenePhase
    @State private var updateSubscription: EventSubscription?

    var body: some View {
        RealityView { content, attachments in
            content.add(app.portal.root)
            if let controls = attachments.entity(for: "controls") { app.portal.shelf.addChild(controls) }
            if let recovery = attachments.entity(for: "recovery") { app.portal.root.addChild(recovery); recovery.position = [0, 0, 0.03] }
            if let tracking = attachments.entity(for: "tracking") { app.portal.root.addChild(tracking); tracking.position = [0, 0, 0.035] }
            updateSubscription = content.subscribe(to: SceneEvents.Update.self) { _ in Task { @MainActor in app.portal.tick() } }
            await app.preview()
        } update: { _, attachments in
            attachments.entity(for: "recovery")?.isEnabled = app.disconnected
            attachments.entity(for: "tracking")?.isEnabled = app.portal.needsRecenter && !app.disconnected
        } attachments: {
            Attachment(id: "controls") {
                HStack(spacing: 16) {
                    Button("Settings", systemImage: "gearshape") { openWindow(id: "settings", value: "main"); app.portal.keepShelfVisible() }
                    Button("Resize", systemImage: "arrow.up.left.and.arrow.down.right") { app.portal.resetSize(); app.portal.keepShelfVisible() }
                        .help("Reset panel size")
                    Button("Immersive", systemImage: app.immersiveEffectsEnabled ? "sun.max.fill" : "sun.max") { app.immersiveEffectsEnabled.toggle(); app.portal.keepShelfVisible() }
                        .tint(app.immersiveEffectsEnabled ? .blue : nil)
                    Button("Disconnect", systemImage: "xmark.circle") { app.disconnect() }
                }
                .labelStyle(.titleAndIcon).padding(12).glassBackgroundEffect()
                .onHover { app.portal.shelfHover($0) }
            }
            Attachment(id: "recovery") {
                VStack(spacing: 16) {
                    Text("Disconnected").font(.title2)
                    Text(app.message ?? "The connection ended").frame(maxWidth: 420).multilineTextAlignment(.center)
                    HStack {
                        Button("Reconnect") { Task { await app.reconnect() } }.disabled(!app.canReconnect)
                        Button("Return to Home") { Task { await app.returnHome(); openWindow(id: "settings", value: "main"); await dismissImmersiveSpace() } }
                        Button("Cancel", role: .cancel) { app.disconnected = false; app.portal.revealControls() }
                    }
                }.padding(24).glassBackgroundEffect()
            }
            Attachment(id: "tracking") {
                VStack(spacing: 12) {
                    Text(app.portal.trackingMessage).multilineTextAlignment(.center).frame(maxWidth: 360)
                    Button("Recenter Portal") { app.portal.recenter() }
                    Button("Settings") { openWindow(id: "settings", value: "main") }
                }.padding(20).glassBackgroundEffect()
            }
        }
        .gesture(DragGesture().targetedToAnyEntity().onChanged { value in
            if value.entity.name.hasPrefix("Portal") { app.portal.drag(value) }
        }.onEnded { _ in app.portal.endDrag() })
        .simultaneousGesture(RotateGesture3D(constrainedToAxis: .y).targetedToAnyEntity().onChanged { value in
            if value.entity.name.hasPrefix("Portal") { app.portal.rotate(value) }
        }.onEnded { _ in app.portal.endRotate() })
        .simultaneousGesture(SpatialTapGesture().targetedToAnyEntity().onEnded { _ in app.portal.revealControls() })
        .preferredSurroundingsEffect(app.surroundingsEffect)
        .task { await app.portal.startTracking() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await app.portal.startTracking() } }
            else { app.portal.stopTracking(); app.coordinator.suspend() }
        }
        .onDisappear { updateSubscription?.cancel(); updateSubscription = nil; app.spaceDidClose(); openWindow(id: "settings", value: "main") }
    }
}
