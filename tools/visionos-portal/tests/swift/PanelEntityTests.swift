import Foundation
import RealityKit
import CoreGraphics

// The host test exercises real RealityKit hierarchies without connecting a stream.
enum PortalDiagnostics {
    static func shared() -> Self { .logger }
    case logger
    func record(_ message: String) {}
}
@main struct PanelEntityTests {
    @MainActor static func main() async throws {
        let host = MoonlightPanelHost()
        host.bindAudioScene("immersive-test-scene")
        let first = host.panel
        precondition(first is BasicMoonlightPanel)
        precondition(host.root.children.count == 1)
        let controls = Entity()
        first.shelf.addChild(controls)
        host.root.position = [1, 2, -1]
        host.spawn(sixDoF: true)
        let stereo = host.panel
        precondition(stereo is SixDoFPanel)
        precondition(stereo.components[PanelAudioComponent.self]?.sceneIdentifier == "immersive-test-scene")
        precondition(first.parent == nil)
        precondition(stereo.surface !== first.surface)
        precondition(controls.parent === stereo.shelf)
        host.spawn(sixDoF: false)
        let basic = host.panel
        precondition(basic is BasicMoonlightPanel)
        precondition(basic.components[PanelAudioComponent.self]?.sceneIdentifier == "immersive-test-scene")
        precondition(stereo.parent == nil)
        precondition(basic.surface !== stereo.surface)
        precondition(basic.corners.count == 4 && stereo.corners.count == 4)
        precondition(controls.parent === basic.shelf)
        precondition(host.root.children.count == 1 && host.root.position == [1, 2, -1])
        precondition(basic.surface.model!.materials.first is UnlitMaterial)
        let context = CGContext(data: nil, width: 16, height: 8, bitsPerComponent: 8,
                                bytesPerRow: 64, space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        let texture = try TextureResource.generate(from: context.makeImage()!, options: .init(semantic: .color))
        try await host.install(texture: texture, isCurrent: { false })
        precondition((basic.surface.model!.materials[0] as! UnlitMaterial).color.texture == nil,
                     "A stale callback must not bind a texture")
        try await host.install(texture: texture, isCurrent: { true })
        let material = basic.surface.model!.materials[0] as! UnlitMaterial
        precondition(material.color.texture?.resource === texture, "Bind the entire original resource")
        let old = basic
        host.spawn(sixDoF: false)
        precondition(host.panel !== old, "Each stream needs a fresh panel hierarchy")
        precondition(old.parent == nil && controls.parent === host.panel.shelf)
        host.bindAudioScene(nil)
        host.spawn(sixDoF: false)
        precondition(host.panel.components[PanelAudioComponent.self] == nil, "Closed scene must not survive restart")
        print("Panel isolation, replacement, controls and placement checks passed")
    }
}
