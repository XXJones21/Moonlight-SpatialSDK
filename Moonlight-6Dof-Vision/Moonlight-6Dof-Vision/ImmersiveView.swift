import SwiftUI
import RealityKit

struct ImmersiveView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace
    @Environment(\.scenePhase) private var scenePhase
    @State private var updateSubscription: EventSubscription?
    private var switcher = PresentationSwitch()

    var body: some View {
        RealityView { content, attachments in
            PortalDiagnostics.shared().record("Immersive RealityView created")
            content.add(app.portal.root)
            // The reconstructed room lives in world space, beside the screen, so the
            // screen's light has real surfaces to land on.
            content.add(app.roomMesh.root)
            if let stats = attachments.entity(for: "stats") { app.portal.root.addChild(stats) }
            if let controls = attachments.entity(for: "controls") { app.portal.shelf.addChild(controls) }
            if let recovery = attachments.entity(for: "recovery") { app.portal.root.addChild(recovery); recovery.position = [0, 0, 0.03] }
            if let tracking = attachments.entity(for: "tracking") { app.portal.root.addChild(tracking); tracking.position = [0, 0, 0.035] }
            updateSubscription = content.subscribe(to: SceneEvents.Update.self) { _ in Task { @MainActor in app.portal.tick() } }
            await app.preview()
        } update: { _, attachments in
            attachments.entity(for: "recovery")?.isEnabled = app.disconnected
            if let stats = attachments.entity(for: "stats") {
                stats.position = [0, app.portal.height / 2 + 0.12, 0.02]
                stats.isEnabled = app.settings.values.showStatistics && app.hasSession
            }
            if let tracking = attachments.entity(for: "tracking") {
                // Enabling 6DoF can create this attachment after RealityView.make.
                if tracking.parent !== app.portal.root {
                    app.portal.root.addChild(tracking)
                    tracking.position = [0, 0, 0.035]
                }
                tracking.isEnabled = app.sixDoFEnabled && app.portal.needsRecenter && !app.disconnected
            }
        } attachments: {
            Attachment(id: "controls") {
                HStack(spacing: 16) {
                    Button("Settings", systemImage: "gearshape") { openWindow(id: "settings", value: "main"); app.portal.keepShelfVisible() }
                    Button("Resize", systemImage: "arrow.up.left.and.arrow.down.right") { app.portal.resetSize(); app.portal.keepShelfVisible() }
                        .help("Reset panel size")
                    if !app.sixDoFEnabled {
                        Button("Window", systemImage: "macwindow") { Task { await switcher.openPlayerWindow() } }
                            .help("Keep playing in a window beside other apps")
                    }
                    Button("Effects", systemImage: app.immersiveEffectsEnabled ? "sun.max.fill" : "sun.max") { app.immersiveEffectsEnabled.toggle(); app.portal.keepShelfVisible() }
                        .tint(app.immersiveEffectsEnabled ? .blue : nil)
                    Button("Disconnect", systemImage: "xmark.circle") { app.disconnect() }
                }
                .labelStyle(.titleAndIcon).padding(12).glassBackgroundEffect()
                .streamControllerEvents()
                .onHover { app.portal.shelfHover($0) }
            }
            Attachment(id: "recovery") {
                VStack(spacing: 16) {
                    Text("Disconnected").font(.title2)
                    Text(app.message ?? "The connection ended").frame(maxWidth: 420).multilineTextAlignment(.center)
                    HStack {
                        Button("Reconnect") { Task { await app.reconnect() } }.disabled(!app.canReconnect)
                            .help("Reconnect to the same app using the latest saved stream settings.")
                        Button("Return to Home") { Task { await switcher.returnHome() } }
                        Button("Cancel", role: .cancel) { app.disconnected = false; app.portal.revealControls() }
                    }
                }.padding(24).glassBackgroundEffect().streamControllerEvents()
            }
            Attachment(id: "stats") {
                Text(app.session.statistics).font(.caption2.monospaced()).multilineTextAlignment(.leading)
                    .padding(10).glassBackgroundEffect()
            }
            if app.sixDoFEnabled {
                Attachment(id: "tracking") {
                    VStack(spacing: 12) {
                        Text(app.portal.trackingMessage).multilineTextAlignment(.center).frame(maxWidth: 360)
                        Button("Recenter Portal") { app.portal.recenter() }
                        Button("Settings") { openWindow(id: "settings", value: "main") }
                    }.padding(20).glassBackgroundEffect().streamControllerEvents()
                }
            }
        }
        .gesture(DragGesture().targetedToAnyEntity().onChanged { value in
            if value.entity.name.hasPrefix("Portal") { app.portal.drag(value) }
        }.onEnded { _ in app.portal.endDrag() })
        .simultaneousGesture(RotateGesture3D(constrainedToAxis: .y).targetedToAnyEntity().onChanged { value in
            if value.entity.name.hasPrefix("Portal") { app.portal.rotate(value) }
        }.onEnded { _ in app.portal.endRotate() })
        .simultaneousGesture(SpatialTapGesture().targetedToAnyEntity().onEnded { _ in app.portal.revealControls() })
        .streamControllerEvents()
        .background(ImmersiveAudioSceneReader { app.portal.bindAudioScene($0) }.frame(width: 0, height: 0))
        .preferredSurroundingsEffect(app.surroundingsEffect)
        .streamDisplayHint(app)
        .task { await app.portal.startTracking() }
        .onChange(of: scenePhase) { _, phase in
            PortalDiagnostics.shared().record("Immersive scene phase: \(String(describing: phase))")
            if phase == .active { app.coordinator.resume(); Task { await app.portal.startTracking() } }
            else if phase == .background {
                app.portal.stopTracking()
                // Moving to the window also sends this scene to the background. The
                // stream is still being watched then, so it is not paused.
                if app.presentation != .window { app.coordinator.suspend() }
            }
        }
        .onDisappear {
            updateSubscription?.cancel(); updateSubscription = nil
            // The space can close without any code of ours running: the Digital Crown
            // and the Home button both do it. With a live stream the viewer lands in
            // the player window; otherwise in the launcher.
            let keepsStream = app.spaceDidClose()
            openWindow(id: keepsStream ? AppModel.playerWindowID : AppModel.launcherWindowID, value: "main")
        }
    }
}
