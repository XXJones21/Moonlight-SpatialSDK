import Foundation
import RealityKit
import UIKit

/// The lights that make the screen belong in the room.
///
/// Two lights hang from the screen's root entity, so they follow it when it moves:
///
/// - **Room light**: a white spot light just in front of the screen, pointing at the
///   viewer's side of the room. It projects a small, blurred copy of the live picture,
///   so the colours that fall on the floor, walls and desk are the picture's own.
/// - **Bias light**: a point light behind the screen, coloured from the picture's
///   average, that washes the wall behind.
///
/// Both carry `SurroundingsLight`, the visionOS 27 component that lets a RealityKit
/// light illuminate real surfaces instead of only virtual objects. Apple documents the
/// cost as proportional to the lit area per light, so reach is a setting and both
/// lights step down as the headset warms.
@MainActor
final class RoomLightRig {
    private let spot = Entity()
    private let bias = Entity()
    private var ambientTexture: TextureResource?
    private var projectedTexture: TextureResource?
    private var thermalScale: Float = 1
    private var thermalObserver: NSObjectProtocol?
    private var spotLit = false
    private var biasLit = false

    /// Lumens at strength 1. Starting points to tune on the headset.
    private static let roomLumens: Float = 9000
    private static let biasLumens: Float = 2500

    /// Called when the thermal state changes, so the owner re-applies the lights.
    var onThermalChange: (() -> Void)?

    init() {
        spot.name = "RoomLightSpot"
        bias.name = "RoomLightBias"
        thermalObserver = NotificationCenter.default.addObserver(
            forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.thermalChanged() }
        }
        readThermalState()
    }

    func attach(to parent: Entity) {
        if spot.parent !== parent { parent.addChild(spot) }
        if bias.parent !== parent { parent.addChild(bias) }
    }

    /// The blurred copy of the picture that the decoder renders alongside the screen.
    func setAmbientTexture(_ texture: TextureResource?) {
        ambientTexture = texture
        if texture == nil { projectedTexture = nil }
    }

    /// Applies the current options. Cheap enough to call whenever the average colour updates.
    func update(enabled: Bool, values: CinemaSettings.Values, average: SIMD3<Float>) {
        updateSpot(on: enabled && values.roomLight && thermalScale > 0, values: values)
        updateBias(on: enabled && values.biasLight && thermalScale > 0, values: values, average: average)
    }

    private func updateSpot(on: Bool, values: CinemaSettings.Values) {
        guard on, let texture = ambientTexture else {
            if spotLit {
                spot.components.remove(SpotLightComponent.ProjectiveTexture.self)
                spot.components.remove(SpotLightComponent.SurroundingsLight.self)
                spot.components.remove(SpotLightComponent.self)
                spotLit = false; projectedTexture = nil
            }
            return
        }
        // A spot light shines along its own -Z. The screen faces +Z, toward the viewer,
        // so the light is turned half a turn about X: that keeps the picture's left on
        // the room's left. It also swaps top and bottom, which the option below undoes
        // if the headset shows the floor taking the sky's colour.
        spot.position = [0, 0, 0.05]
        spot.orientation = simd_quatf(angle: .pi, axis: [1, 0, 0])
        spot.scale = [1, values.roomLightFlipVertical ? -1 : 1, 1]

        let intensity = Self.roomLumens * max(0, values.roomLightStrength) * thermalScale
        let reach = max(1, values.roomLightReach) * thermalScale
        // The light stays white: a projected texture is combined with the light's own
        // colour, so white passes the picture's colours through unchanged.
        spot.components.set(SpotLightComponent(
            color: .white,
            intensity: intensity,
            innerAngleInDegrees: 35,
            outerAngleInDegrees: 70,
            attenuationRadius: reach,
            attenuationFalloffExponent: 2))
        if !spotLit {
            spot.components.set(SpotLightComponent.SurroundingsLight())
            spotLit = true
        }
        if projectedTexture !== texture {
            spot.components.set(SpotLightComponent.ProjectiveTexture(texture: texture))
            projectedTexture = texture
        }
    }

    private func updateBias(on: Bool, values: CinemaSettings.Values, average: SIMD3<Float>) {
        guard on else {
            if biasLit {
                bias.components.remove(PointLightComponent.SurroundingsLight.self)
                bias.components.remove(PointLightComponent.self)
                biasLit = false
            }
            return
        }
        bias.position = [0, 0, -0.25]
        // Brightness goes into the intensity and hue into the colour, so a dark scene
        // dims the wall instead of turning it grey.
        let peak = max(average.x, average.y, average.z, 0.001)
        let hue = average / peak
        let color = UIColor(red: CGFloat(hue.x), green: CGFloat(hue.y), blue: CGFloat(hue.z), alpha: 1)
        let intensity = Self.biasLumens * max(0, values.biasLightStrength) * min(1, peak) * thermalScale
        bias.components.set(PointLightComponent(color: color, intensity: intensity, attenuationRadius: 2.5 * thermalScale))
        if !biasLit {
            bias.components.set(PointLightComponent.SurroundingsLight())
            biasLit = true
        }
    }

    private func readThermalState() {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal, .fair: thermalScale = 1
        case .serious: thermalScale = 0.5
        case .critical: thermalScale = 0
        @unknown default: thermalScale = 1
        }
    }

    private func thermalChanged() {
        let before = thermalScale
        readThermalState()
        guard before != thermalScale else { return }
        PortalDiagnostics.shared().record("Room light: thermal scale \(before) -> \(thermalScale)")
        onThermalChange?()
    }
}
