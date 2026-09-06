import SwiftUI
import RealityKit

@MainActor @Observable final class AppModel {
    let immersiveSpaceID = "ImmersiveSpace"
    enum ImmersiveSpaceState { case closed, inTransition, open }
    var immersiveSpaceState = ImmersiveSpaceState.closed
    let portal = PortalSceneController()
    let session = MoonlightSession()
    let connection = ConnectionViewModel()
    var immersiveEffectsEnabled = false
    var roomDimming = false
    var lightingEmission = false
    var spatialAudio = false
    var message: String?
    var disconnected = false
    init() {
        session.onTexture = { [weak self] texture, width, height in
            guard let self else { return }
            self.portal.setAspect(Float(width / 2) / Float(height))
            Task { do { try await self.portal.install(texture: texture) } catch { self.message = error.localizedDescription } }
        }
        session.onStarted = { [weak self] in self?.session.gamepad.setActive(true) }
        session.onEnded = { [weak self] error in self?.message = error; self?.disconnected = true }
    }
    func start(server: SavedServer) async { disconnected = false; message = nil; await session.start(server: server, preferences: connection.preferences) }
    func disconnect() { Task { await session.stop(); disconnected = true } }
}
