import SwiftUI
import RealityKit
import ARKit
import QuartzCore

@MainActor @Observable
final class PortalSceneController {
    let root = Entity()
    let surface = ModelEntity()
    let shelf = Entity()
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
    var deviceToHead = SIMD3<Float>.zero
    var eyeSeparation: Double = 0.064
    var onSample: ((simd_float4x4, Bool, UInt64, UInt64) -> Void)?
    var onEpoch: ((UInt64, UInt64) -> Void)?
    var onGeometryChanged: (() -> Void)?
    private var session: ARKitSession?
    private var provider: WorldTrackingProvider?
    private var events: Task<Void, Never>?
    private var corners: [ModelEntity] = []
    private var lastHead: simd_float4x4?
    private var shelfUntil: TimeInterval = .infinity
    private var cornersUntil: TimeInterval = .infinity
    private var dragStart: Transform?
    private var sizeStart: SIMD2<Float>?
    private var yawStart: simd_quatf?
    private var baseAspect: Float = 16 / 9
    private var stereoMaterial: ShaderGraphMaterial?

    init() {
        root.name = "PortalRoot"; surface.name = "PortalSurface"
        root.addChild(surface); root.addChild(shelf)
        surface.components.set(InputTargetComponent())
        surface.components.set(HoverEffectComponent())
        for index in 0..<4 {
            let corner = ModelEntity(mesh: .generateSphere(radius: 0.025), materials: [UnlitMaterial(color: .white)])
            corner.name = "PortalCorner\(index)"
            corner.components.set(InputTargetComponent())
            corner.components.set(HoverEffectComponent())
            corner.components.set(CollisionComponent(shapes: [.generateBox(size: SIMD3(repeating: 0.10))]))
            root.addChild(corner); corners.append(corner)
        }
        surface.model = ModelComponent(mesh: Self.mesh(width: width, height: height), materials: [UnlitMaterial(color: .darkGray)])
        updateGeometry()
    }

    func install(texture: TextureResource) async throws {
        var material = try await ShaderGraphMaterial(named: "/Root/SBSMaterial", from: "SBSMaterial")
        try material.setParameter(name: "texture", value: .textureResource(texture))
        stereoMaterial = material
        surface.model?.materials = [material]
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
            shelfVisible = now < shelfUntil
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
        else if needsRecenter {
            lastHead = head
            trackingMessage = "Tracking recovered. Recenter the portal to resume."
            onSample?(head, false, trackingEpoch, UInt64(now * 1_000_000_000))
            return
        }
        if !trackingValid { revealControls(); trackingMessage = "Tracking active" }
        trackingValid = true; lastHead = head
        onSample?(head, true, trackingEpoch, UInt64(now * 1_000_000_000))
    }

    private func loseTracking(_ reason: String) {
        if trackingValid {
            let previous = trackingEpoch; trackingEpoch &+= 1
            needsRecenter = placed
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
    func resetSize() { height = 0.7; width = min(10, max(0.5, height * baseAspect)); height = width / baseAspect; changed() }
    func revealControls() { shelfUntil = CACurrentMediaTime() + 3; cornersUntil = CACurrentMediaTime() + 1.5; shelfVisible = true; cornersVisible = true }
    func keepShelfVisible() { shelfUntil = CACurrentMediaTime() + 3; shelfVisible = true }

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
        surface.model?.mesh = Self.mesh(width: width, height: height)
        surface.components.set(CollisionComponent(shapes: [.generateBox(size: [width, height, 0.015])]))
        for (index, corner) in corners.enumerated() {
            corner.position = [index % 2 == 0 ? -width / 2 : width / 2, index < 2 ? -height / 2 : height / 2, 0.015]
        }
        shelf.position = [0, -height / 2 - 0.06, 0.015]
    }

    private static func mesh(width: Float, height: Float) -> MeshResource {
        let radius = min(0.025, height * 0.05)
        var positions: [SIMD3<Float>] = [[0, 0, 0]]
        var uv: [SIMD2<Float>] = [[0.5, 0.5]]
        for quadrant in 0..<4 {
            let cx = quadrant == 0 || quadrant == 3 ? width / 2 - radius : -width / 2 + radius
            let cy = quadrant < 2 ? height / 2 - radius : -height / 2 + radius
            for step in 0...8 {
                let angle = Float(quadrant) * .pi / 2 + Float(step) * .pi / 16
                let x = cx + radius * cos(angle), y = cy + radius * sin(angle)
                positions.append([x, y, 0]); uv.append([x / width + 0.5, y / height + 0.5])
            }
        }
        var indices: [UInt32] = []
        for index in 1..<positions.count { indices += [0, UInt32(index), UInt32(index == positions.count - 1 ? 1 : index + 1)] }
        var descriptor = MeshDescriptor(name: "PortalRoundedPlane")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(Array(repeating: [0, 0, 1], count: positions.count))
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uv)
        descriptor.primitives = .triangles(indices)
        // Descriptor is generated from bounded dimensions and constant topology.
        return (try? MeshResource.generate(from: [descriptor])) ?? .generatePlane(width: width, height: height)
    }
}
