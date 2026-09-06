import Foundation
import RealityKit
import Observation

@MainActor @Observable final class PortalSessionCoordinator {
    enum State: String { case idle, connecting, awaitingTracking, streaming, reconfiguring, recovering, failed }
    var state = State.idle
    var status = "Not connected"
    var renderFrameID: UInt64 = 0
    var lastHostStatus: PortalHostStatus?
    private let scene: PortalSceneController
    private let moonlight: MoonlightSession
    private let transport = PortalTransport()
    private let gate = PortalFrameGate()
    private var sessionID: UInt64 = 0
    private var sequence: UInt64 = 0
    private var preferences: StreamPreferences?
    private var watchdog: Task<Void, Never>?
    private var active = false
    private var startToken = UUID()
    private var sourceAvailable = false
    private var textureReady = false
    private var textureToken = UUID()
    private var sourceStatusAt = Date.distantPast
    private var presentedAt = Date.distantPast
    private var suspended = false
    private var sixDoFEnabled: Bool { preferences?.metadata == true }
    var onEnded: ((String) -> Void)?

    init(scene: PortalSceneController, moonlight: MoonlightSession) {
        self.scene = scene; self.moonlight = moonlight
        scene.onSample = { [weak self] head, valid, epoch, time in self?.sample(head: head, valid: valid, epoch: epoch, time: time) }
        scene.onEpoch = { [weak self] previous, next in
            guard let self, self.active, self.sixDoFEnabled else { return }
            self.transport.requestReset(session: self.sessionID, previous: previous, next: next)
            self.sourceAvailable = false; self.updateGate(); self.hide(); self.state = .recovering
        }
        scene.onGeometryChanged = { [weak self] in
            guard let self, self.active, self.sixDoFEnabled else { return }
            self.updateGate(); self.hide(); self.state = .reconfiguring; self.status = "Updating portal view…"
        }
        let gate = self.gate
        moonlight.onDecodedBuffer = { gate.accepts($0) }
        moonlight.onPresentedFrame = { [weak self] identity in
            guard let self, self.active, !self.suspended else { return }
            if self.sixDoFEnabled {
                guard self.textureReady, self.sourceAvailable,
                      Date().timeIntervalSince(self.sourceStatusAt) <= 0.3,
                      let identity, gate.presented(identity) else { return }
                self.renderFrameID = identity.frame
            }
            self.presentedAt = Date()
            self.revealIfReady()
        }
        moonlight.onTexture = { [weak self] texture, width, height in
            guard let self, self.active, let preferences = self.preferences else { return }
            guard width == preferences.encodedWidth, height == preferences.eyeHeight else {
                self.fail("The host returned \(width) × \(height) content. Expected \(preferences.encodedWidth) × \(preferences.eyeHeight). Match the capture display to the selected stream resolution."); return
            }
            self.hide(); self.textureReady = false
            let sessionToken = self.startToken, textureToken = UUID(); self.textureToken = textureToken
            Task { @MainActor in
                let current = { self.active && self.startToken == sessionToken && self.textureToken == textureToken }
                do {
                    try await self.scene.install(texture: texture, isCurrent: current)
                    if current() { self.textureReady = true; self.revealIfReady() }
                } catch { if current() { self.fail("Video material failed: \(error.localizedDescription)") } }
            }
        }
        moonlight.onEnded = { [weak self] message in self?.fail(message) }
        transport.onStatus = { [weak self] status in Task { @MainActor in self?.receive(status) } }
        transport.onError = { [weak self] error in Task { @MainActor in
            guard let self, self.active, self.sixDoFEnabled else { return }; self.sourceAvailable = false; self.hide(); self.state = .recovering; self.status = "Pose connection: \(error)"
        } }
    }

    func start(server: SavedServer, preferences: StreamPreferences) async {
        let token = UUID(); startToken = token
        await tearDown()
        guard startToken == token else { return }
        self.preferences = preferences
        sessionID = UInt64.random(in: 1...UInt64.max); sequence = 0
        active = true; suspended = false; presentedAt = .distantPast; sourceAvailable = false; state = .connecting; status = "Connecting…"
        scene.spawnPanel(sixDoF: preferences.metadata)
        scene.setAspect(Float(preferences.eyeWidth) / Float(preferences.eyeHeight))
        updateGate(); hide()
        if sixDoFEnabled { transport.connect(host: server.host) }
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(50))
                guard let self, self.active, !Task.isCancelled else { return }
                let stale = self.sixDoFEnabled
                    ? (!self.gate.fresh || !self.sourceAvailable || Date().timeIntervalSince(self.sourceStatusAt) > 0.3)
                    : Date().timeIntervalSince(self.presentedAt) > 1
                if stale {
                    self.hide()
                    if self.state == .streaming { self.state = .recovering; self.status = self.sixDoFEnabled ? "Waiting for a current portal frame…" : "Waiting for video…" }
                }
            }
        }
        await moonlight.start(server: server, preferences: preferences)
        guard startToken == token, active else { return }
        if case .failed(let message) = moonlight.state { fail(message) }
        else if state != .streaming { state = .awaitingTracking; status = sixDoFEnabled ? "Waiting for tracking and portal video…" : "Waiting for Moonlight video…" }
    }

    func stop() async {
        startToken = UUID()
        await tearDown()
    }
    private func tearDown() async {
        active = false; textureToken = UUID(); textureReady = false; gate.close(); transport.stop()
        watchdog?.cancel(); watchdog = nil; sourceAvailable = false; lastHostStatus = nil
        hide(); preferences = nil; state = .idle; status = "Not connected"
        await moonlight.stop()
    }
    func suspend() {
        suspended = true; presentedAt = .distantPast
        sourceAvailable = false; gate.close(); hide()
        if active { state = .recovering; status = "Tracking paused" }
    }
    func resume() { suspended = false }
    private func revealIfReady() {
        guard active, !suspended, textureReady else { return }
        if sixDoFEnabled {
            guard gate.fresh, sourceAvailable, Date().timeIntervalSince(sourceStatusAt) <= 0.3 else { return }
        } else {
            guard Date().timeIntervalSince(presentedAt) <= 1 else { return }
        }
        state = .streaming; status = sixDoFEnabled ? "Streaming 6DoF portal" : "Streaming Moonlight"
        scene.surface.components.set(OpacityComponent(opacity: 1))
        moonlight.gamepad.setActive(true)
    }
    private func updateGate() {
        guard let preferences, sixDoFEnabled else { gate.close(); return }
        gate.configure(session: sessionID, epoch: scene.trackingEpoch, revision: scene.revision, width: preferences.encodedWidth,
                       height: preferences.encodedHeight, tracking: scene.trackingValid)
    }
    private func sample(head: simd_float4x4, valid: Bool, epoch: UInt64, time: UInt64) {
        guard active, sixDoFEnabled, !suspended else { return }
        sequence &+= 1; updateGate()
        transport.send(PortalState(sessionID: sessionID, trackingEpoch: epoch, sequence: sequence, geometryRevision: scene.revision,
                                   sampleTimeNs: time, targetTimeNs: time, trackingValid: valid,
                                   worldFromHead: PortalPose(head), worldFromPortal: PortalPose(scene.root.transform.matrix),
                                   width: Double(scene.width), height: Double(scene.height), ipd: scene.eyeSeparation))
        if !valid { hide(); state = .recovering; status = scene.trackingMessage }
    }
    private func receive(_ value: PortalHostStatus) {
        guard active, sixDoFEnabled, UInt64(value.sessionID) == sessionID, UInt64(value.trackingEpoch) == scene.trackingEpoch,
              UInt64(value.geometryRevision) == scene.revision,
              let accepted = UInt64(value.acceptedSequence), accepted <= sequence,
              accepted >= (lastHostStatus.flatMap { UInt64($0.acceptedSequence) } ?? 0) else { return }
        lastHostStatus = value; sourceStatusAt = Date()
        sourceAvailable = value.trackingValid && value.errorCode == "none" && value.outputMode == "direct_sbs"
        if !sourceAvailable { hide(); state = .recovering; status = "Portal host: \(value.errorCode)" }
    }
    private func hide() { scene.surface.components.set(OpacityComponent(opacity: 0)); moonlight.gamepad.setActive(false) }
    private func fail(_ message: String) {
        guard active else { return }
        active = false; gate.close(); transport.stop(); watchdog?.cancel(); hide()
        state = .failed; status = message; onEnded?(message)
        let failedToken = startToken
        Task {
            guard startToken == failedToken, !active else { return }
            await moonlight.stop()
        }
    }
}
