import RealityKit
import Foundation

/// Shared handle/bar geometry only. Each concrete panel owns a separate hierarchy.
@MainActor
class MoonlightPanelEntity: Entity {
    let surface = ModelEntity()
    let shelf = Entity()
    let backdrop = ModelEntity()
    let glow = ModelEntity()
    let light = PointLight()
    private(set) var corners: [ModelEntity] = []

    required init() {
        super.init()
        let width: Float = 0.7 * 16 / 9, height: Float = 0.7
        name = "MoonlightPanel"; surface.name = "PortalSurface"
        addChild(surface); addChild(shelf)
        backdrop.name = "PortalBackdrop"
        backdrop.model = ModelComponent(mesh: Self.mesh(width: width + 0.012, height: height + 0.012), materials: [UnlitMaterial(color: .darkGray)])
        backdrop.position.z = -0.008
        backdrop.components.set(InputTargetComponent())
        backdrop.components.set(HoverEffectComponent())
        addChild(backdrop)
        glow.model = ModelComponent(mesh: Self.mesh(width: width + 0.08, height: height + 0.08), materials: [UnlitMaterial(color: .black)])
        glow.position.z = -0.012; glow.isEnabled = false; addChild(glow)
        light.position = [0, 0, 0.1]; light.light.attenuationRadius = 3; light.isEnabled = false; addChild(light)
        surface.components.set(InputTargetComponent())
        surface.components.set(HoverEffectComponent())
        for index in 0..<4 {
            let corner = ModelEntity(mesh: .generateSphere(radius: 0.025), materials: [UnlitMaterial(color: .white)])
            corner.name = "PortalCorner\(index)"
            corner.components.set(InputTargetComponent())
            corner.components.set(HoverEffectComponent())
            corner.components.set(CollisionComponent(shapes: [.generateBox(size: SIMD3(repeating: 0.10))]))
            addChild(corner); corners.append(corner)
        }
        surface.model = ModelComponent(mesh: Self.mesh(width: width, height: height), materials: [UnlitMaterial(color: .darkGray)])
        updateGeometry(width: width, height: height, aspect: 16 / 9)
    }

    func updateGeometry(width: Float, height: Float, aspect: Float) {
        surface.model?.mesh = Self.mesh(width: width, height: height)
        surface.components.set(CollisionComponent(shapes: [.generateBox(size: [width, height, 0.015])]))
        backdrop.model?.mesh = Self.mesh(width: width + 0.012, height: height + 0.012)
        backdrop.components.set(CollisionComponent(shapes: [.generateBox(size: [width + 0.012, height + 0.012, 0.015])]))
        glow.model?.mesh = Self.mesh(width: width + 0.08, height: height + 0.08)
        for (index, corner) in corners.enumerated() {
            corner.scale = SIMD3(repeating: width / (0.7 * aspect))
            // Retain a 10 cm physical hit target when the visible handle shrinks.
            corner.components.set(CollisionComponent(shapes: [.generateBox(size: SIMD3(repeating: max(0.10, 0.10 / corner.scale.x)))]))
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
