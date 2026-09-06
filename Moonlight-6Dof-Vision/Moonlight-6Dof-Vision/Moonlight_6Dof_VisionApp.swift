import SwiftUI

@main
struct Moonlight_6Dof_VisionApp: App {
    @State private var appModel = AppModel()
    var body: some Scene {
        WindowGroup(id: "settings", for: String.self) { _ in ContentView().environment(appModel) } defaultValue: { "main" }
            .windowStyle(.plain)
            .defaultSize(width: 800, height: 550)
        ImmersiveSpace(id: appModel.immersiveSpaceID) {
            ImmersiveView().environment(appModel)
                .onAppear { appModel.immersiveSpaceState = .open }
        }
        .immersionStyle(selection: .constant(.mixed), in: .mixed)
    }
}
