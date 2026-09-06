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
    private(set) var sixDoFEnabled = UserDefaults.standard.bool(forKey: "portal.sixDoFEnabled")
    private(set) var changingStreamMode = false
    var immersiveEffectsEnabled = false { didSet { updateLighting(); updateAudio() } }
    var roomDimming = UserDefaults.standard.bool(forKey: "portal.effect.dimming") { didSet { UserDefaults.standard.set(roomDimming, forKey: "portal.effect.dimming") } }
    var lightingEmission = UserDefaults.standard.bool(forKey: "portal.effect.lighting") { didSet { UserDefaults.standard.set(lightingEmission, forKey: "portal.effect.lighting"); updateLighting() } }
    var spatialAudio = UserDefaults.standard.bool(forKey: "portal.effect.audio") { didSet { UserDefaults.standard.set(spatialAudio, forKey: "portal.effect.audio"); updateAudio() } }
    var screenColor = SIMD3<Float>(repeating: 0.5)
    var message: String?
    var disconnected = false
    var canReconnect: Bool { !changingStreamMode && lastServer != nil && !connection.hasPendingStreamSettings && session.state != .stopping && session.state != .connecting }
    private var lastServer: SavedServer?
    private let lighting = PortalLightingSampler()
    private var previewToken = UUID()
    private var audioHead: simd_float4x4?
    init() {
        PortalDiagnostics.shared().start()
        coordinator = PortalSessionCoordinator(scene: portal, moonlight: session)
        PortalDiagnostics.shared().record("Loaded stream mode: 6DoF=\(sixDoFEnabled)")
        // Older installs always enabled metadata. Start in ordinary Moonlight unless explicitly opted in.
        connection.preferences.metadata = sixDoFEnabled
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
        guard !connection.hasPendingStreamSettings else {
            message = "Wait for the stream settings to save before connecting."
            return
        }
        var preferences = connection.preferences
        preferences.metadata = sixDoFEnabled
        lastServer = server; disconnected = false; message = nil; previewToken = UUID()
        await coordinator.start(server: server, preferences: preferences)
    }
    func reconnect() async {
        guard let server = lastServer else { return }
        await start(server: server)
    }
    func setSixDoFEnabled(_ enabled: Bool) {
        guard !changingStreamMode, enabled != sixDoFEnabled else { return }
        changingStreamMode = true
        let shouldRestart = !disconnected && session.activeConfiguration != nil
        PortalDiagnostics.shared().record("6DoF Window changed: \(sixDoFEnabled) -> \(enabled)")
        sixDoFEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "portal.sixDoFEnabled")
        connection.preferences.metadata = enabled
        connection.preferences.save()
        // Publish the binding synchronously, before another Connect action can
        // read it. Only teardown/restart needs to run asynchronously.
        Task {
            defer { changingStreamMode = false }
            if shouldRestart, let server = lastServer {
                guard !disconnected else { return }
                await start(server: server)
            } else if session.state == .idle {
                await preview()
            }
        }
    }
    func disconnect() {
        PortalDiagnostics.shared().record("Disconnect requested")
        disconnected = true; portal.revealControls(); previewToken = UUID()
        message = "Connection to \(lastServer?.name ?? "the host") ended"
        Task { await coordinator.stop() }
    }
    func returnHome() async { await coordinator.stop(); disconnected = false; message = nil }
    func spaceDidClose() {
        PortalDiagnostics.shared().record("Immersive space closed")
        immersiveSpaceState = .closed; portal.stopTracking(); previewToken = UUID()
        Task { await coordinator.stop() }
    }
    func preview() async {
        guard session.state == .idle else { return }
        let token = UUID(); previewToken = token
        do {
            portal.preparePreviewPanel(sixDoF: sixDoFEnabled)
            let texture = try StereoPreview.makeTexture(stereo: sixDoFEnabled)
            guard token == previewToken, session.state == .idle else { return }
            try await portal.install(texture: texture, isCurrent: { token == self.previewToken && self.session.state == .idle })
            guard token == previewToken, session.state == .idle else { return }
            portal.surface.components.set(OpacityComponent(opacity: 1))
        } catch { message = error.localizedDescription }
    }
}
