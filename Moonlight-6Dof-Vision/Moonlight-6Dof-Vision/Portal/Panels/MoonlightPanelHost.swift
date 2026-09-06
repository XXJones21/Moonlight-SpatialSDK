import Foundation
import RealityKit

/// A stable world transform with exactly one mounted panel. Recovery attachments
/// stay on this root; the interactive control bar moves to the new panel's shelf.
@MainActor
final class MoonlightPanelHost {
    let root = Entity()
    private(set) var panel: MoonlightPanelEntity = BasicMoonlightPanel()
    var sixDoF: Bool { panel is SixDoFPanel }
    private var installation = UUID()

    init() {
        root.name = "MoonlightPanelHost"
        root.addChild(panel)
    }

    func spawn(sixDoF: Bool) {
        installation = UUID()
        let old = panel
        let next: MoonlightPanelEntity = sixDoF ? SixDoFPanel() : BasicMoonlightPanel()
        for attachment in Array(old.shelf.children) { next.shelf.addChild(attachment) }
        old.removeFromParent()
        panel = next
        root.addChild(next)
        PortalDiagnostics.shared().record("Spawned \(next.name); detached \(old.name)")
    }

    func install(texture: TextureResource, isCurrent: @escaping @MainActor () -> Bool) async throws {
        let target = panel, token = UUID()
        installation = token
        let current = { self.panel === target && self.installation == token && isCurrent() }
        guard current() else { return }
        if let basic = target as? BasicMoonlightPanel { basic.apply(texture: texture) }
        else if let stereo = target as? SixDoFPanel {
            try await stereo.apply(texture: texture, isCurrent: current)
        }
    }
}
