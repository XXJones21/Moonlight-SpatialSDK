import SwiftUI

/// Moves the stream between the player window and the theater.
///
/// The order in each direction is the platform's rule, not a preference: an app cannot
/// close its last scene, so the destination opens before the origin closes. The
/// presentation is recorded before anything closes, which is how the closing scene's
/// `onDisappear` knows the stream is moving and must not be stopped.
struct PresentationSwitch: DynamicProperty {
    @Environment(AppModel.self) private var app
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow
    @Environment(\.openImmersiveSpace) private var openImmersiveSpace
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace

    /// Opens the theater and closes the player window. Returns false if the space did not open.
    @MainActor @discardableResult func openTheater() async -> Bool {
        if app.immersiveSpaceState == .open {
            app.presentation = .theater
            dismissWindow(id: AppModel.playerWindowID, value: "main")
            return true
        }
        guard app.immersiveSpaceState == .closed else { return false }
        app.immersiveSpaceState = .inTransition
        switch await openImmersiveSpace(id: app.immersiveSpaceID) {
        case .opened:
            app.immersiveSpaceState = .open
            app.presentation = .theater
            dismissWindow(id: AppModel.playerWindowID, value: "main")
            return true
        case .userCancelled:
            app.immersiveSpaceState = .closed
            return false
        case .error:
            app.message = "The immersive space could not open"
            app.immersiveSpaceState = .closed
            return false
        @unknown default:
            app.immersiveSpaceState = .closed
            return false
        }
    }

    /// Opens the player window and closes the theater, keeping the stream.
    @MainActor func openPlayerWindow() async {
        app.presentation = .window
        openWindow(id: AppModel.playerWindowID, value: "main")
        if app.immersiveSpaceState != .closed { await dismissImmersiveSpace() }
    }

    /// Ends the stream and returns to the launcher from either presentation.
    @MainActor func returnHome() async {
        await app.returnHome()
        openWindow(id: AppModel.launcherWindowID, value: "main")
        dismissWindow(id: AppModel.playerWindowID, value: "main")
        if app.immersiveSpaceState != .closed { await dismissImmersiveSpace() }
    }
}
