import RealityKit

/// Ordinary Moonlight: one plane and the full video texture, identical in both eyes.
/// No stereo shader, eye UV selection, frame metadata, or portal host dependency.
@MainActor
final class BasicMoonlightPanel: MoonlightPanelEntity {
    required init() {
        super.init()
        name = "BasicMoonlightPanel"
    }

    func apply(texture: TextureResource) {
        var material = UnlitMaterial()
        material.color = .init(tint: .white, texture: .init(texture))
        surface.model?.materials = [material]
        PortalDiagnostics.shared().record("BasicMoonlightPanel: full video texture installed")
    }
}
