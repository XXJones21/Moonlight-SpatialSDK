import SwiftUI

@main
struct Moonlight_6Dof_VisionApp: App {
    @State private var appModel = AppModel()
    var body: some Scene {
        // The launcher: hosts, pairing, stream settings. Declared first, so it is the
        // scene the app opens with.
        WindowGroup(id: AppModel.launcherWindowID, for: String.self) { _ in ContentView().environment(appModel) } defaultValue: { "main" }
            .windowStyle(.plain)
            .defaultSize(width: 800, height: 550)

        // Window mode: the live picture beside other apps. Value-based like the
        // launcher, so asking for it twice brings the one window forward instead of
        // opening a second. It only makes sense with a stream, so the system neither
        // launches into it nor restores it.
        WindowGroup(id: AppModel.playerWindowID, for: String.self) { _ in PlayerWindowView().environment(appModel) } defaultValue: { "main" }
            .windowStyle(.plain)
            .defaultSize(width: 1280, height: 720)
            .restorationBehavior(.disabled)
            .defaultLaunchBehavior(.suppressed)

        // Theater mode: the screen in the room, with light and placed sound.
        ImmersiveSpace(id: appModel.immersiveSpaceID) {
            ImmersiveView().environment(appModel)
                .onAppear { appModel.immersiveSpaceState = .open }
        }
        .immersionStyle(selection: .constant(.mixed), in: .mixed)
    }
}
