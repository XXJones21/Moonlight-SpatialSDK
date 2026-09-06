import SwiftUI
import RealityKit

@MainActor @Observable
final class AppModel {
    let immersiveSpaceID = "ImmersiveSpace"
    enum ImmersiveSpaceState { case closed, inTransition, open }
    var immersiveSpaceState = ImmersiveSpaceState.closed
    let portal = PortalSceneController()
    var immersiveEffectsEnabled = false
    var roomDimming = false
    var lightingEmission = false
    var spatialAudio = false
    var message: String?
    var disconnected = false
    func disconnect() { disconnected = true }
}
