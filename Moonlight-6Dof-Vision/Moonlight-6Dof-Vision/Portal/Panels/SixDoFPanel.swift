import RealityKit

/// The 6DoF panel alone owns the side-by-side eye shader.
@MainActor
final class SixDoFPanel: MoonlightPanelEntity {
    required init() {
        super.init()
        name = "SixDoFPanel"
    }

    func apply(texture: TextureResource, isCurrent: @MainActor () -> Bool) async throws {
        var material = try await ShaderGraphMaterial(named: "/Root/SBSMaterial", from: "SBSMaterial")
        try material.setParameter(name: "texture", value: .textureResource(texture))
        guard isCurrent() else { return }
        surface.model?.materials = [material]
        PortalDiagnostics.shared().record("SixDoFPanel: left/right eye shader installed")
    }
}
