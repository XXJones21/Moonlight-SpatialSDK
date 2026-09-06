import SwiftUI
import RealityKit
import ARKit
import QuartzCore

@MainActor @Observable
final class PortalSceneController {
    private let panelHost = MoonlightPanelHost()
    var root: Entity { panelHost.root }
    var surface: ModelEntity { panelHost.panel.surface }
    var shelf: Entity { panelHost.panel.shelf }
    var backdrop: ModelEntity { panelHost.panel.backdrop }
    private var glow: ModelEntity { panelHost.panel.glow }
    private var light: PointLight { panelHost.panel.light }
    var width: Float = 0.7 * 16 / 9
    var height: Float = 0.7
    var revision: UInt64 = 1
    var trackingEpoch: UInt64 = 1
    var trackingValid = false
    var trackingMessage = "Waiting for tracking"
    var placed = false
    var needsRecenter = false
    var shelfVisible = true
    var cornersVisible = true
    var manipulating = false
    private var shelfHovered = false
    var deviceToHead = SIMD3<Float>.zero
    var eyeSeparation: Double = 0.064
    var onSample: ((simd_float4x4, Bool, UInt64, UInt64) -> Void)?
    var onHeadSample: ((simd_float4x4) -> Void)?
    var onEpoch: ((UInt64, UInt64) -> Void)?
    var onGeometryChanged: (() -> Void)?
    private var session: ARKitSession?
    private var provider: WorldTrackingProvider?
    private var events: Task<Void, Never>?
    private var corners: [ModelEntity] { panelHost.panel.corners }
    private var lastHead: simd_float4x4?
    private var shelfUntil: TimeInterval = .infinity
    private var cornersUntil: TimeInterval = .infinity
    private var dragStart: Transform?
    private var sizeStart: SIMD2<Float>?
    private var yawStart: simd_quatf?
    private var lightingColor = SIMD3<Float>(repeating: 0.5)
    private var lightingEnabled = false
    private var baseAspect: Float = 16 / 9

    /// Replace the complete panel at stream start, preserving world placement and controls.
    func spawnPanel(sixDoF: Bool) {
        dragStart = nil; sizeStart = nil; yawStart = nil; manipulating = false
        panelHost.spawn(sixDoF: sixDoF)
        if !sixDoF { needsRecenter = false }
        updateGeometry(); revealControls()
        setLightingColor(lightingColor, enabled: lightingEnabled)
    }

    func bindAudioScene(_ identifier: String?) {
        panelHost.bindAudioScene(identifier)
        CoreAudioRenderer.setSceneIdentifier(identifier)
    }

    func preparePreviewPanel(sixDoF: Bool) {
        if panelHost.sixDoF != sixDoF { spawnPanel(sixDoF: sixDoF) }
    }

    func install(texture: TextureResource, isCurrent: @escaping @MainActor () -> Bool = { true }) async throws {
        try await panelHost.install(texture: texture, isCurrent: isCurrent)
    }

    func startTracking() async {
        guard session == nil else { return }
        guard WorldTrackingProvider.isSupported else { trackingMessage = "World tracking is unavailable. Use a Vision Pro for tracking."; return }
        let session = ARKitSession(), provider = WorldTrackingProvider()
        self.session = session; self.provider = provider
        events = Task { [weak self] in
            for await event in session.events {
                guard let self, !Task.isCancelled else { return }
                if case .dataProviderStateChanged(_, let state, let error) = event, state != .running {
                    self.loseTracking(error?.localizedDescription ?? "Tracking paused")
                }
            }
        }
        do { try await session.run([provider]) }
        catch { trackingMessage = error.localizedDescription; stopTracking() }
    }

    func stopTracking() {
        loseTracking("Tracking stopped")
        events?.cancel(); events = nil
        session?.stop(); session = nil; provider = nil
    }

    /// Called from RealityKit SceneEvents.Update; one sample also drives the audio listener.
    func tick() {
        let now = CACurrentMediaTime()
        if !manipulating {
            shelfVisible = shelfHovered || now < shelfUntil
            cornersVisible = now < cornersUntil
        }
        shelf.isEnabled = shelfVisible && !manipulating
        for corner in corners { corner.isEnabled = cornersVisible && !manipulating || sizeStart != nil }
        guard let provider, provider.state == .running,
              let anchor = provider.queryDeviceAnchor(atTimestamp: now), anchor.isTracked else {
            loseTracking("Tracking unavailable")
            if let lastHead { onSample?(lastHead, false, trackingEpoch, UInt64(now * 1_000_000_000)) }
            return
        }
        var calibration = matrix_identity_float4x4
        calibration.columns.3 = SIMD4(deviceToHead, 1)
        let head = anchor.originFromAnchorTransform * calibration
        if !placed { place(inFrontOf: head) }
        else if needsRecenter && panelHost.sixDoF {
            lastHead = head
            trackingMessage = "Tracking recovered. Recenter the portal to resume."
            onSample?(head, false, trackingEpoch, UInt64(now * 1_000_000_000))
            return
        }
        if !trackingValid { revealControls(); trackingMessage = "Tracking active" }
        trackingValid = true; lastHead = head
        onHeadSample?(head)
        onSample?(head, true, trackingEpoch, UInt64(now * 1_000_000_000))
    }

    private func loseTracking(_ reason: String) {
        if trackingValid {
            let previous = trackingEpoch; trackingEpoch &+= 1
            needsRecenter = placed && panelHost.sixDoF
            onEpoch?(previous, trackingEpoch)
        }
        trackingValid = false; trackingMessage = reason
    }

    func recenter() {
        guard let lastHead, provider?.state == .running, provider?.queryDeviceAnchor(atTimestamp: CACurrentMediaTime())?.isTracked == true else { return }
        needsRecenter = false
        let previous = trackingEpoch; trackingEpoch &+= 1
        onEpoch?(previous, trackingEpoch)
        place(inFrontOf: lastHead)
    }
    private func place(inFrontOf head: simd_float4x4) {
        var back = SIMD3(head.columns.2.x, 0, head.columns.2.z)
        if simd_length(back) <= 0.01 { back = SIMD3(-head.columns.0.z, 0, head.columns.0.x) }
        guard simd_length(back) > 0.01 else { return }
        let direction = simd_normalize(back)
        root.position = SIMD3(head.columns.3.x, head.columns.3.y - 0.1, head.columns.3.z) - direction
        root.orientation = simd_quatf(angle: atan2(direction.x, direction.z), axis: [0, 1, 0])
        placed = true; changed(); revealControls()
    }
    func setAspect(_ aspect: Float) {
        guard aspect.isFinite, aspect > 0, aspect != baseAspect else { return }
        baseAspect = aspect; resetSize()
    }
    func calibrationChanged() { changed() }
    func setLightingColor(_ rgb: SIMD3<Float>, enabled: Bool) {
        lightingColor = rgb; lightingEnabled = enabled
        let color = UIColor(red: CGFloat(rgb.x), green: CGFloat(rgb.y), blue: CGFloat(rgb.z), alpha: 1)
        glow.model?.materials = [UnlitMaterial(color: color)]
        glow.components.set(OpacityComponent(opacity: 0.25))
        glow.isEnabled = enabled; light.isEnabled = enabled
        light.light.color = color; light.light.intensity = 50
    }
    func resetSize() { height = 0.7; width = min(10, max(0.5, height * baseAspect)); height = width / baseAspect; changed() }
    func revealControls() { shelfUntil = CACurrentMediaTime() + 3; cornersUntil = CACurrentMediaTime() + 1.5; shelfVisible = true; cornersVisible = true }
    func keepShelfVisible() { shelfUntil = CACurrentMediaTime() + 3; shelfVisible = true }
    func shelfHover(_ active: Bool) { shelfHovered = active; keepShelfVisible() }

    func drag(_ value: EntityTargetValue<DragGesture.Value>) {
        guard yawStart == nil else { return }
        if dragStart == nil { dragStart = root.transform; sizeStart = value.entity.name.hasPrefix("PortalCorner") ? SIMD2(width, height) : nil }
        manipulating = true
        guard let start = dragStart else { return }
        if let initial = sizeStart {
            let point = value.convert(value.location3D, from: .local, to: root)
            let initialDiagonal = simd_length(initial) / 2
            let ratio = simd_length(SIMD2(point.x, point.y)) / initialDiagonal
            width = min(10, max(0.5, initial.x * ratio)); height = width / baseAspect
            changed()
        } else {
            let point = value.convert(value.location3D, from: .local, to: .scene)
            let origin = value.convert(value.startLocation3D, from: .local, to: .scene)
            root.position = start.translation + SIMD3(Float(point.x - origin.x), Float(point.y - origin.y), Float(point.z - origin.z))
            changed()
        }
    }
    func endDrag() { dragStart = nil; sizeStart = nil; manipulating = false; revealControls() }
    func rotate(_ value: EntityTargetValue<RotateGesture3D.Value>) {
        guard dragStart == nil else { return }
        if yawStart == nil { yawStart = root.orientation }
        manipulating = true
        let sign: Float = value.rotation.axis.y >= 0 ? 1 : -1
        root.orientation = simd_quatf(angle: Float(value.rotation.angle.radians) * sign, axis: [0, 1, 0]) * yawStart!
        changed()
    }
    func endRotate() { yawStart = nil; manipulating = false; revealControls() }

    private func changed() { revision &+= 1; updateGeometry(); onGeometryChanged?() }
    private func updateGeometry() {
        panelHost.panel.updateGeometry(width: width, height: height, aspect: baseAspect)
    }
}
