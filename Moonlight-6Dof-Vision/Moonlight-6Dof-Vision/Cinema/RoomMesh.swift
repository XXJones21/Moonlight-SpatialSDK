import Foundation
import ARKit
import RealityKit

/// The real room as geometry the renderer can use, so the screen's light has surfaces
/// to land on.
///
/// ARKit's `SceneReconstructionProvider` streams a mesh of the actual walls, floor and
/// furniture. Every anchor becomes a `ModelEntity` wearing `OcclusionMaterial`: invisible,
/// so passthrough shows through it, but present, so a light that reaches surroundings
/// has something to fall on.
///
/// This follows the approach proven in Hearth's visionOS client. Two side effects come
/// with it. Real furniture now hides the virtual screen where it stands in front of it,
/// and a screen pushed into a wall is clipped by that wall. Both are correct, and both
/// can look like a fault the first time, so the mesh has its own switch in settings.
///
/// Needs `NSWorldSensingUsageDescription` in Info.plist; without it ARKit stops the app
/// when it asks for authorization.
@MainActor
final class RoomMesh {
    /// Everything reconstructed hangs here. It lives in world space, beside the screen.
    let root = Entity()

    private var session: ARKitSession?
    private var updates: Task<Void, Never>?
    private var pieces: [UUID: ModelEntity] = [:]
    private(set) var running = false

    init() { root.name = "RoomMesh" }

    /// Starts reconstruction. A provider can be run only once, so each start makes a
    /// fresh session and provider.
    func start() {
        guard !running else { return }
        guard SceneReconstructionProvider.isSupported else {
            PortalDiagnostics.shared().record("Room mesh: scene reconstruction is unsupported on this device")
            return
        }
        running = true
        let session = ARKitSession()
        let provider = SceneReconstructionProvider()
        self.session = session
        updates = Task { [weak self] in
            do {
                try await session.run([provider])
            } catch {
                PortalDiagnostics.shared().record("Room mesh: session failed: \(error.localizedDescription)")
                self?.stop()
                return
            }
            for await update in provider.anchorUpdates {
                guard let self, !Task.isCancelled else { return }
                await self.handle(update)
            }
        }
    }

    func stop() {
        updates?.cancel(); updates = nil
        session?.stop(); session = nil
        for piece in pieces.values { piece.removeFromParent() }
        pieces.removeAll()
        running = false
    }

    private func handle(_ update: AnchorUpdate<MeshAnchor>) async {
        let anchor = update.anchor
        switch update.event {
        case .added, .updated:
            guard let mesh = try? await MeshResource(from: anchor) else { return }
            guard running else { return }
            if let existing = pieces[anchor.id] {
                existing.model?.mesh = mesh
                existing.transform = Transform(matrix: anchor.originFromAnchorTransform)
            } else {
                let piece = ModelEntity(mesh: mesh, materials: [OcclusionMaterial()])
                piece.name = "RoomMeshPiece"
                piece.transform = Transform(matrix: anchor.originFromAnchorTransform)
                pieces[anchor.id] = piece
                root.addChild(piece)
                if pieces.count == 1 { PortalDiagnostics.shared().record("Room mesh: first surface arrived") }
            }
        case .removed:
            pieces[anchor.id]?.removeFromParent()
            pieces[anchor.id] = nil
        }
    }
}
