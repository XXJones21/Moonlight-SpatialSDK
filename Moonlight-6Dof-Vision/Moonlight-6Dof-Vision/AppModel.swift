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
    var immersiveEffectsEnabled = false { didSet { updateLighting(); updateAudio() } }
    var roomDimming = UserDefaults.standard.bool(forKey: "portal.effect.dimming") { didSet { UserDefaults.standard.set(roomDimming, forKey: "portal.effect.dimming") } }
    var lightingEmission = UserDefaults.standard.bool(forKey: "portal.effect.lighting") { didSet { UserDefaults.standard.set(lightingEmission, forKey: "portal.effect.lighting"); updateLighting() } }
    var spatialAudio = UserDefaults.standard.bool(forKey: "portal.effect.audio") { didSet { UserDefaults.standard.set(spatialAudio, forKey: "portal.effect.audio"); updateAudio() } }
    var screenColor = SIMD3<Float>(repeating: 0.5)
    var message: String?
    var disconnected = false
    var canReconnect: Bool { lastConnection != nil && session.state != .stopping && session.state != .connecting }
    private var lastConnection: (SavedServer, StreamPreferences)?
    private let lighting = PortalLightingSampler()
    private var previewToken = UUID()
    private var audioHead: simd_float4x4?
    init() {
        coordinator = PortalSessionCoordinator(scene: portal, moonlight: session)
        let ipd = UserDefaults.standard.double(forKey: "portal.calibration.ipd")
        portal.eyeSeparation = (0.05...0.08).contains(ipd) ? ipd : 0.064
        if let offset = UserDefaults.standard.array(forKey: "portal.calibration.offset") as? [Double], offset.count == 3, offset.allSatisfy({ $0.isFinite && abs($0) <= 0.1 }) {
            portal.deviceToHead = SIMD3(Float(offset[0]), Float(offset[1]), Float(offset[2]))
        }
        coordinator.onEnded = { [weak self] error in
            guard let self else { return }
            self.message = error; self.disconnected = true; self.portal.revealControls()
        }
        let lighting = self.lighting
        session.sampleLighting = { lighting.sample($0) }
        lighting.onColor = { [weak self] color in Task { @MainActor in self?.screenColor = color; self?.updateLighting() } }
        portal.onHeadSample = { [weak self] head in
            guard let self else { return }
            self.audioHead = head; self.updateAudio()
        }
    }
    var surroundingsEffect: SurroundingsEffect? {
        guard immersiveEffectsEnabled else { return nil }
        if lightingEmission {
            let brightness: Double = roomDimming ? 0.35 : 1
            return .colorMultiply(Color(.sRGB, red: brightness * (0.8 + 0.4 * Double(screenColor.x)), green: brightness * (0.8 + 0.4 * Double(screenColor.y)), blue: brightness * (0.8 + 0.4 * Double(screenColor.z)), opacity: 1))
        }
        return roomDimming ? .dark : nil
    }
    private func updateLighting() { portal.setLightingColor(screenColor, enabled: immersiveEffectsEnabled && lightingEmission) }
    private func updateAudio() {
        guard let head = audioHead else { return }
        CoreAudioRenderer.updatePortal(portal.root.transform.matrix, head: head, spatial: immersiveEffectsEnabled && spatialAudio)
    }
    func saveCalibration() {
        UserDefaults.standard.set(portal.eyeSeparation, forKey: "portal.calibration.ipd")
        UserDefaults.standard.set([Double(portal.deviceToHead.x), Double(portal.deviceToHead.y), Double(portal.deviceToHead.z)], forKey: "portal.calibration.offset")
        portal.calibrationChanged()
    }
    func start(server: SavedServer) async {
        let preferences = connection.preferences
        lastConnection = (server, preferences); disconnected = false; message = nil; previewToken = UUID()
        await coordinator.start(server: server, preferences: preferences)
    }
    func reconnect() async {
        guard let (server, preferences) = lastConnection else { return }
        disconnected = false; message = nil; previewToken = UUID()
        await coordinator.start(server: server, preferences: preferences)
    }
    func disconnect() {
        disconnected = true; portal.revealControls(); previewToken = UUID()
        message = "Connection to \(lastConnection?.0.name ?? "the host") ended"
        Task { await coordinator.stop() }
    }
    func returnHome() async { await coordinator.stop(); disconnected = false; message = nil }
    func spaceDidClose() {
        immersiveSpaceState = .closed; portal.stopTracking(); previewToken = UUID()
        Task { await coordinator.stop() }
    }
    func preview() async {
        guard session.state == .idle else { return }
        let token = UUID(); previewToken = token
        do {
            let texture = try StereoPreview.makeTexture()
            guard token == previewToken, session.state == .idle else { return }
            try await portal.install(texture: texture, isCurrent: { token == self.previewToken && self.session.state == .idle })
            guard token == previewToken, session.state == .idle else { return }
            portal.surface.components.set(OpacityComponent(opacity: 1))
        } catch { message = error.localizedDescription }
    }
}
