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
        // Keep panel gestures available without a gaze-hover tint over video.
        surface.components.set(InputTargetComponent())
        backdrop.components.set(InputTargetComponent())
        backdrop.position.z = -0.008
        addChild(backdrop)
        glow.model = ModelComponent(mesh: Self.mesh(width: width + 0.08, height: height + 0.08), materials: [UnlitMaterial(color: .black)])
        glow.position.z = -0.012; glow.isEnabled = false; addChild(glow)
        light.position = [0, 0, 0.1]; light.light.attenuationRadius = 3; light.isEnabled = false; addChild(light)
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

    /// `curve` runs from 0 (flat) to 1 (the screen spans a 100 degree arc around the viewer).
    func updateGeometry(width: Float, height: Float, aspect: Float, curve: Float = 0) {
        let arc = Self.arc(for: curve)
        surface.model?.mesh = arc > 0 ? Self.curvedMesh(width: width, height: height, arc: arc) : Self.mesh(width: width, height: height)
        // How far the left and right edges come forward of the centre when curved.
        let edgeDepth: Float = arc > 0 ? (width / arc) * (1 - cos(arc / 2)) : 0
        let edgeX: Float = arc > 0 ? (width / arc) * sin(arc / 2) : width / 2
        surface.components.set(CollisionComponent(shapes: [.generateBox(size: [width, height, 0.015])]))
        backdrop.model?.mesh = Self.mesh(width: width + 0.012, height: height + 0.012)
        backdrop.components.set(CollisionComponent(shapes: [.generateBox(size: [width + 0.012, height + 0.012, 0.015])]))
        glow.model?.mesh = Self.mesh(width: width + 0.08, height: height + 0.08)
        for (index, corner) in corners.enumerated() {
            corner.scale = SIMD3(repeating: width / (0.7 * aspect))
            // Retain a 10 cm physical hit target when the visible handle shrinks.
            corner.components.set(CollisionComponent(shapes: [.generateBox(size: SIMD3(repeating: max(0.10, 0.10 / corner.scale.x)))]))
            corner.position = [index % 2 == 0 ? -edgeX : edgeX, index < 2 ? -height / 2 : height / 2, 0.015 + edgeDepth]
        }
        shelf.position = [0, -height / 2 - 0.06, 0.015]
    }

    /// The angle, in radians, that a curved screen spans around the viewer.
    private static func arc(for curve: Float) -> Float {
        let clamped = min(1, max(0, curve))
        return clamped < 0.02 ? 0 : clamped * (100 * .pi / 180)
    }

    /// A screen bent around a vertical axis in front of it. Arc length equals `width`,
    /// so curving the screen does not stretch the picture. The centre stays where the
    /// flat screen was and the edges come toward the viewer.
    private static func curvedMesh(width: Float, height: Float, arc: Float) -> MeshResource {
        let columns = 48
        let radius = width / arc
        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        var uv: [SIMD2<Float>] = []
        for column in 0...columns {
            let u = Float(column) / Float(columns)
            let angle = (u - 0.5) * arc
            let x = radius * sin(angle), z = radius * (1 - cos(angle))
            for row in 0...1 {
                positions.append([x, row == 0 ? -height / 2 : height / 2, z])
                normals.append([-sin(angle), 0, cos(angle)])
                uv.append([u, Float(row)])
            }
        }
        var indices: [UInt32] = []
        for column in 0..<columns {
            let a = UInt32(column * 2), b = a + 2, c = a + 3, d = a + 1
            indices += [a, b, c, a, c, d]
        }
        var descriptor = MeshDescriptor(name: "PortalCurvedPlane")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uv)
        descriptor.primitives = .triangles(indices)
        return (try? MeshResource.generate(from: [descriptor])) ?? mesh(width: width, height: height)
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
