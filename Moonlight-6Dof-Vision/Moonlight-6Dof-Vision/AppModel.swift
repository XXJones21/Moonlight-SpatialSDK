import SwiftUI
import RealityKit
import QuartzCore

@MainActor @Observable final class AppModel {
    let immersiveSpaceID = "ImmersiveSpace"
    static let launcherWindowID = "settings"
    static let playerWindowID = "player"
    enum ImmersiveSpaceState { case closed, inTransition, open }
    var immersiveSpaceState = ImmersiveSpaceState.closed

    /// Where the stream is being shown. The session belongs to the app, not to either
    /// scene, so moving between the window and the theater never reconnects.
    enum Presentation { case launcher, window, theater }
    var presentation = Presentation.launcher {
        didSet { if presentation != oldValue { presentationChanged() } }
    }

    let portal = PortalSceneController()
    let session = MoonlightSession()
    let connection = ConnectionViewModel()
    let coordinator: PortalSessionCoordinator
    let settings = CinemaSettings()
    let roomMesh = RoomMesh()
    private let lightRig = RoomLightRig()
    private let stage = SurroundStage()
    private let inputs = StreamKeyboardMouse()

    private(set) var sixDoFEnabled = AppFeatures.sixDoF && UserDefaults.standard.bool(forKey: "portal.sixDoFEnabled")
    private(set) var changingStreamMode = false
    var immersiveEffectsEnabled = false { didSet { updateLighting(); refreshSound() } }
    var roomDimming = UserDefaults.standard.bool(forKey: "portal.effect.dimming") { didSet { UserDefaults.standard.set(roomDimming, forKey: "portal.effect.dimming") } }
    var lightingEmission = UserDefaults.standard.bool(forKey: "portal.effect.lighting") { didSet { UserDefaults.standard.set(lightingEmission, forKey: "portal.effect.lighting"); updateLighting() } }
    var spatialAudio = UserDefaults.standard.bool(forKey: "portal.effect.audio") { didSet { UserDefaults.standard.set(spatialAudio, forKey: "portal.effect.audio"); refreshSound() } }
    var screenColor = SIMD3<Float>(repeating: 0.5)
    var message: String?
    var disconnected = false
    /// The channel a running speaker check is playing, for the settings screen.
    var speakerCheck: String?
    /// What the stage is doing right now, for the settings screen.
    var soundStatus = "System audio"
    var canReconnect: Bool { !changingStreamMode && lastServer != nil && !connection.hasPendingStreamSettings && session.state != .stopping && session.state != .connecting }
    /// True while a stream exists that a scene could show.
    var hasSession: Bool { session.activeConfiguration != nil && !disconnected }

    private var lastServer: SavedServer?
    private let lighting = PortalLightingSampler()
    private var previewToken = UUID()
    private var audioHead: simd_float4x4?
    private var userEnded = false
    private var reconnectAttempts = 0
    private var reconnectTask: Task<Void, Never>?
    private var speakerCheckTask: Task<Void, Never>?
    private var housekeeping: Task<Void, Never>?
    private var resumedOnLaunch = false

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
            self.scheduleReconnect()
        }
        let lighting = self.lighting
        session.sampleLighting = { lighting.sample($0) }
        lighting.onColor = { [weak self] color in Task { @MainActor in self?.screenColor = color; self?.updateLighting() } }
        portal.onHeadSample = { [weak self] head in
            guard let self else { return }
            self.audioHead = head; self.updateAudio()
        }

        // Cinematic additions. Light and sound hang from the screen's root so they
        // follow it wherever it is moved.
        lightRig.attach(to: portal.root)
        stage.attach(to: portal.root)
        lightRig.onThermalChange = { [weak self] in self?.updateLighting() }
        session.onAmbientTexture = { [weak self] texture in
            self?.lightRig.setAmbientTexture(texture); self?.updateLighting()
        }
        session.gamepad.onControlsChord = { [weak self] in self?.portal.revealControls() }
        portal.onArrangementSettled = { [weak self] in self?.saveArrangement() }
        settings.onChange = { [weak self] in self?.settingsChanged() }
        portal.curve = settings.values.curve
        portal.tiltDegrees = settings.values.tiltDegrees
        if settings.values.restorePlacement, let last = settings.values.lastPlacement {
            portal.apply(last, reposition: false)
        }
        // Stream state changes on its own schedule, so a slow tick keeps sound and
        // input in step with it in either presentation.
        housekeeping = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self else { return }
                self.refreshSound(); self.refreshInputs()
            }
        }
    }

    // MARK: - Light

    var surroundingsEffect: SurroundingsEffect? {
        guard immersiveEffectsEnabled else { return nil }
        if lightingEmission && settings.values.roomTint {
            let brightness: Double = roomDimming ? 0.35 : 1
            return .colorMultiply(Color(.sRGB, red: brightness * (0.8 + 0.4 * Double(screenColor.x)), green: brightness * (0.8 + 0.4 * Double(screenColor.y)), blue: brightness * (0.8 + 0.4 * Double(screenColor.z)), opacity: 1))
        }
        return roomDimming ? .dark : nil
    }

    private var lightsWanted: Bool { immersiveEffectsEnabled && lightingEmission && presentation == .theater }

    private func updateLighting() {
        let lit = lightsWanted
        portal.setLightingColor(screenColor, enabled: lit)
        lightRig.update(enabled: lit, values: settings.values, average: screenColor)
        // The decoder only renders the blurred copy while a light is using it.
        session.ambientSwitch.isOn = lit && settings.values.roomLight && !sixDoFEnabled
        let meshWanted = lit && settings.values.roomMesh && (settings.values.roomLight || settings.values.biasLight) && immersiveSpaceState == .open
        if meshWanted != roomMesh.running { if meshWanted { roomMesh.start() } else { roomMesh.stop() } }
    }

    // MARK: - Sound

    private var stageMode: SoundMode {
        guard presentation == .theater, immersiveSpaceState == .open, immersiveEffectsEnabled, spatialAudio,
              session.state == .streaming, !sixDoFEnabled else { return .system }
        return settings.values.soundMode
    }

    private func seatInScreenSpace() -> SIMD3<Float>? {
        guard let head = audioHead else { return nil }
        let position = SIMD3(head.columns.3.x, head.columns.3.y, head.columns.3.z)
        return portal.root.convert(position: position, from: nil)
    }

    private func refreshSound() {
        let feed = SurroundFeed.shared()
        feed.delayMilliseconds = Int32(settings.values.lipSyncMilliseconds.rounded())
        stage.apply(mode: stageMode, channels: Int(feed.channels), screenWidth: portal.width,
                    seat: seatInScreenSpace(), lowFrequencyGain: settings.values.lowFrequencyGain)
        let status = stage.playing ? "\(stage.mode.label), \(stage.channels) channels" : "System audio"
        if status != soundStatus { soundStatus = status }
    }

    private func updateAudio() {
        guard let head = audioHead else { return }
        // The mono mix-down the original spatial toggle used is retired: the stage
        // above replaces it, and the engine keeps every channel when it plays.
        CoreAudioRenderer.updatePortal(portal.root.transform.matrix, head: head, spatial: false)
    }

    /// Moves the side and back speakers to stand around where the viewer is now.
    func reseatSpeakers() {
        guard let seat = seatInScreenSpace() else { return }
        stage.reseat(seat, screenWidth: portal.width, lowFrequencyGain: settings.values.lowFrequencyGain)
    }

    /// Plays a tone from each channel in turn. Only audible while the stage is playing.
    func runSpeakerCheck() {
        guard speakerCheckTask == nil, stage.playing else { return }
        speakerCheckTask = Task { [weak self] in
            let feed = SurroundFeed.shared()
            for step in SurroundStage.checkOrder(channels: Int(feed.channels)) {
                guard let self, !Task.isCancelled else { break }
                self.speakerCheck = step.name
                feed.testChannel = Int32(step.channel)
                try? await Task.sleep(for: .milliseconds(1500))
            }
            feed.testChannel = -1
            self?.speakerCheck = nil; self?.speakerCheckTask = nil
        }
    }

    // MARK: - Screen arrangement

    private func settingsChanged() {
        portal.curve = settings.values.curve
        portal.tiltDegrees = settings.values.tiltDegrees
        updateLighting(); refreshSound(); refreshInputs()
    }

    private func saveArrangement() {
        settings.values.lastPlacement = portal.arrangement()
    }

    func savePreset(_ index: Int) {
        guard settings.values.presets.indices.contains(index) else { return }
        settings.values.presets[index] = portal.arrangement()
    }

    func applyPreset(_ index: Int) {
        guard settings.values.presets.indices.contains(index), let preset = settings.values.presets[index] else { return }
        portal.apply(preset, reposition: true)
        settings.values.curve = preset.curve
        settings.values.tiltDegrees = preset.tiltDegrees
        saveArrangement()
    }

    private func refreshInputs() {
        inputs.setActive(settings.values.keyboardAndMouse && session.state == .streaming && !disconnected)
    }

    // MARK: - Session

    func saveCalibration() {
        UserDefaults.standard.set(portal.eyeSeparation, forKey: "portal.calibration.ipd")
        UserDefaults.standard.set([Double(portal.deviceToHead.x), Double(portal.deviceToHead.y), Double(portal.deviceToHead.z)], forKey: "portal.calibration.offset")
        portal.calibrationChanged()
    }

    /// `automatic` marks a retry the app started itself, which must not reset the retry count.
    func start(server: SavedServer, automatic: Bool = false) async {
        guard !connection.hasPendingStreamSettings else {
            message = "Wait for the stream settings to save before connecting."
            return
        }
        if !automatic { reconnectAttempts = 0; reconnectTask?.cancel(); reconnectTask = nil }
        userEnded = false
        var preferences = connection.preferences
        preferences.metadata = sixDoFEnabled
        lighting.metadataRows = sixDoFEnabled ? 16 : 0
        session.useFramePacing = settings.values.framePacing
        lastServer = server; disconnected = false; message = nil; previewToken = UUID()
        await coordinator.start(server: server, preferences: preferences)
    }

    func reconnect(automatic: Bool = false) async {
        guard let server = lastServer else { return }
        await start(server: server, automatic: automatic)
    }

    /// Retries a dropped stream a few times with a growing pause, unless the viewer ended it.
    private func scheduleReconnect() {
        guard settings.values.autoReconnect, !userEnded, reconnectAttempts < 3, lastServer != nil else { return }
        reconnectAttempts += 1
        let attempt = reconnectAttempts
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Double(1 << (attempt - 1))))
            guard let self, !Task.isCancelled, self.disconnected, !self.userEnded else { return }
            self.message = "Reconnecting, attempt \(attempt) of 3…"
            await self.reconnect(automatic: true)
        }
    }

    func setSixDoFEnabled(_ enabled: Bool) {
        guard AppFeatures.sixDoF, !changingStreamMode, enabled != sixDoFEnabled else { return }
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
        userEnded = true; reconnectTask?.cancel(); reconnectTask = nil
        disconnected = true; portal.revealControls(); previewToken = UUID()
        message = "Connection to \(lastServer?.name ?? "the host") ended"
        Task { await coordinator.stop() }
    }

    func returnHome() async {
        userEnded = true; reconnectTask?.cancel(); reconnectTask = nil
        await coordinator.stop(); disconnected = false; message = nil
        presentation = .launcher
    }

    // MARK: - Presentation

    private func presentationChanged() {
        PortalDiagnostics.shared().record("Presentation: \(presentation)")
        if presentation == .window {
            // The immersive scene pauses the coordinator when it goes to the background.
            // In the window nothing depends on head tracking, so it runs again.
            coordinator.resume()
        }
        updateLighting(); refreshSound()
    }

    /// Called when the immersive space has gone away, by any route. Returns true when a
    /// live stream is being kept and the player window should show it.
    @discardableResult func spaceDidClose() -> Bool {
        PortalDiagnostics.shared().record("Immersive space closed")
        immersiveSpaceState = .closed; portal.stopTracking(); previewToken = UUID()
        audioHead = nil
        stage.stop(); roomMesh.stop()
        if presentation == .window {
            // A deliberate switch: the caller set the presentation before closing the space.
            refreshSound(); return true
        }
        if presentation == .theater && hasSession {
            // The Digital Crown or Home button closed the space. Keep the stream and
            // fall back to the window instead of dropping the viewer to the launcher.
            presentation = .window
            return true
        }
        presentation = .launcher
        Task {
            await coordinator.stop()
            if immersiveSpaceState == .closed { portal.bindAudioScene(nil) }
        }
        return false
    }

    /// Called when the player window closes while it was the place showing the stream.
    func playerWindowDidClose() {
        guard presentation == .window else { return }
        PortalDiagnostics.shared().record("Player window closed")
        userEnded = true; reconnectTask?.cancel(); reconnectTask = nil
        presentation = .launcher
        Task {
            await coordinator.stop()
            disconnected = false
            if immersiveSpaceState == .closed { portal.bindAudioScene(nil) }
        }
    }

    /// One automatic connection per launch, when the option is on and a paired host is selected.
    func shouldResumeOnLaunch() -> Bool {
        guard settings.values.resumeOnLaunch, !resumedOnLaunch, connection.paired, session.state == .idle else { return false }
        resumedOnLaunch = true
        return true
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
