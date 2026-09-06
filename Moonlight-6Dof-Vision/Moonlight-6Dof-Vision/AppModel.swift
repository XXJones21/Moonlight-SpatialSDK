import SwiftUI
import RealityKit

@MainActor @Observable final class AppModel {
    let immersiveSpaceID = "ImmersiveSpace"
    enum ImmersiveSpaceState { case closed, inTransition, open }
    var immersiveSpaceState = ImmersiveSpaceState.closed
    let portal = PortalSceneController()
    let session = MoonlightSession()
    let connection = ConnectionViewModel()
    let coordinator: PortalSessionCoordinator
    var immersiveEffectsEnabled = false
    var roomDimming = false
    var lightingEmission = false
    var spatialAudio = false
    var message: String?
    var disconnected = false
    private var lastConnection: (SavedServer, StreamPreferences)?
    init() {
        coordinator = PortalSessionCoordinator(scene: portal, moonlight: session)
        coordinator.onEnded = { [weak self] error in self?.message = error; self?.disconnected = true; self?.portal.revealControls() }
    }
    func start(server: SavedServer) async {
        let preferences = connection.preferences
        lastConnection = (server, preferences); disconnected = false; message = nil
        await coordinator.start(server: server, preferences: preferences)
    }
    func reconnect() async {
        guard let (server, preferences) = lastConnection else { return }
        disconnected = false; message = nil
        await coordinator.start(server: server, preferences: preferences)
    }
    func disconnect() {
        disconnected = true; portal.revealControls()
        message = "Connection to \(lastConnection?.0.name ?? "the host") ended"
        Task { await coordinator.stop() }
    }
    func spaceDidClose() {
        immersiveSpaceState = .closed; portal.stopTracking()
        Task { await coordinator.stop() }
    }
}
