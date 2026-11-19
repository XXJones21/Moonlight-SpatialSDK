import Foundation
import AVFoundation
import RealityKit

/// Plays the stream's channels through RealityKit so they have a place in the room.
///
/// The stage hangs from the screen's root entity. In that entity's space the screen
/// faces +Z, toward the viewer, and +X is the viewer's right.
///
/// Two arrangements are offered, both fed by `SurroundFeed`:
///
/// - **Bed**: one generator with a multichannel layout on an entity that uses
///   `AmbientAudioComponent`. Apple documents that component as emitting each channel
///   from its own angle around the entity, front toward the entity's -Z. One stream and
///   one clock, so the channels cannot drift apart.
/// - **Speakers**: one mono generator per channel on entities that use
///   `SpatialAudioComponent`, placed around the screen and the seat. These pick up the
///   system's simulation of the real room. The low-frequency channel has no direction,
///   so it plays through `ChannelAudioComponent`, which bypasses spatialization.
///
/// Channel numbers below are the stream's order: FL FR C LFE BL BR SL SR.
@MainActor
final class SurroundStage {
    private let root = Entity()
    private var controllers: [AudioGeneratorController] = []
    private var lowFrequency: AudioGeneratorController?
    private(set) var mode = SoundMode.system
    private(set) var channels = 0
    private var builtWidth: Float = 0
    private var builtSeat = SIMD3<Float>(0, 0, 1.5)

    init() { root.name = "SurroundStage" }

    func attach(to parent: Entity) {
        if root.parent !== parent { parent.addChild(root) }
    }

    /// True while generators are playing and own the stream's audio.
    var playing: Bool { !controllers.isEmpty }

    /// Builds the arrangement for `mode`. A `.system` mode, or a stream with no
    /// channels yet, tears the stage down and returns playback to the audio engine.
    func apply(mode: SoundMode, channels: Int, screenWidth: Float, seat: SIMD3<Float>?, lowFrequencyGain: Double) {
        let seat = seat ?? builtSeat
        let unchanged = mode == self.mode && channels == self.channels && playing
        if unchanged {
            lowFrequency?.gain = lowFrequencyGain
            if mode == .speakers, abs(screenWidth - builtWidth) > 0.02 { placeFronts(width: screenWidth) }
            return
        }
        stop()
        guard mode != .system, channels >= 2 else { return }
        do {
            switch mode {
            case .bed: try buildBed(channels: channels)
            case .speakers: try buildSpeakers(channels: channels, width: screenWidth, seat: seat, lowFrequencyGain: lowFrequencyGain)
            case .system: break
            }
            self.mode = mode; self.channels = channels
            builtWidth = screenWidth; builtSeat = seat
            SurroundFeed.shared().active = true
            PortalDiagnostics.shared().record("Surround stage: \(mode.rawValue), \(channels) channels, \(controllers.count) generators")
        } catch {
            PortalDiagnostics.shared().record("Surround stage failed: \(error.localizedDescription)")
            stop()
        }
    }

    func stop() {
        SurroundFeed.shared().active = false
        for controller in controllers { controller.stop() }
        controllers.removeAll(); lowFrequency = nil
        for child in Array(root.children) { child.removeFromParent() }
        mode = .system; channels = 0
    }

    /// Moves the side and back speakers to stand around a new seat.
    func reseat(_ seat: SIMD3<Float>, screenWidth: Float, lowFrequencyGain: Double) {
        guard mode == .speakers else { builtSeat = seat; return }
        let current = channels
        stop()
        apply(mode: .speakers, channels: current, screenWidth: screenWidth, seat: seat, lowFrequencyGain: lowFrequencyGain)
    }

    // MARK: - Bed

    private func buildBed(channels: Int) throws {
        let layout: AudioChannelLayoutTag
        let map: [Int]
        switch channels {
        case 8:
            // Apple's 7.1 order is L R C LFE Ls Rs Rls Rrs: side pair before rear pair.
            layout = kAudioChannelLayoutTag_AudioUnit_7_1
            map = [0, 1, 2, 3, 6, 7, 4, 5]
        case 6:
            // 5.1 is L R C LFE Ls Rs, which matches the stream's order.
            layout = kAudioChannelLayoutTag_AudioUnit_5_1
            map = [0, 1, 2, 3, 4, 5]
        default:
            layout = kAudioChannelLayoutTag_Stereo
            map = [0, 1]
        }
        let entity = Entity()
        entity.name = "SurroundBed"
        // Identity orientation: the entity's -Z points from the viewer into the screen,
        // which is where the front channels belong.
        entity.ambientAudio = AmbientAudioComponent()
        root.addChild(entity)
        let block = SurroundFeed.shared().block(channelMap: map.map { NSNumber(value: $0) }, lowPass: false)
        let controller = try entity.playAudio(configuration: AudioGeneratorConfiguration(layoutTag: layout), block)
        controllers.append(controller)
    }

    // MARK: - Speakers

    private struct Speaker { let name: String; let channel: Int; let position: SIMD3<Float> }

    private func buildSpeakers(channels: Int, width: Float, seat: SIMD3<Float>, lowFrequencyGain: Double) throws {
        for speaker in layout(channels: channels, width: width, seat: seat) {
            let entity = Entity()
            entity.name = speaker.name
            entity.position = speaker.position
            entity.spatialAudio = SpatialAudioComponent()
            root.addChild(entity)
            let block = SurroundFeed.shared().block(channelMap: [NSNumber(value: speaker.channel)], lowPass: false)
            controllers.append(try entity.playAudio(configuration: AudioGeneratorConfiguration(layoutTag: kAudioChannelLayoutTag_Mono), block))
        }
        guard channels >= 6 else { return }
        let entity = Entity()
        entity.name = "SpeakerLFE"
        entity.channelAudio = ChannelAudioComponent()
        root.addChild(entity)
        let block = SurroundFeed.shared().block(channelMap: [NSNumber(value: 3)], lowPass: true)
        let controller = try entity.playAudio(configuration: AudioGeneratorConfiguration(layoutTag: kAudioChannelLayoutTag_Mono), block)
        controller.gain = lowFrequencyGain
        controllers.append(controller); lowFrequency = controller
    }

    private func layout(channels: Int, width: Float, seat: SIMD3<Float>) -> [Speaker] {
        let half = width / 2
        var speakers = [
            Speaker(name: "SpeakerFL", channel: 0, position: [-half, 0, 0]),
            Speaker(name: "SpeakerFR", channel: 1, position: [half, 0, 0])
        ]
        guard channels >= 6 else { return speakers }
        speakers.append(Speaker(name: "SpeakerC", channel: 2, position: [0, 0, 0]))
        // Surrounds stand on a circle around the seat. The radius follows how far the
        // seat is from the screen, within sensible room limits.
        let radius = min(3, max(1.2, abs(seat.z)))
        func around(_ degreesFromFront: Float, left: Bool) -> SIMD3<Float> {
            let angle = degreesFromFront * .pi / 180
            let x = sin(angle) * radius * (left ? -1 : 1)
            // Front is -Z, so an angle past 90 degrees lands behind the seat at +Z.
            return SIMD3(seat.x + x, seat.y, seat.z - cos(angle) * radius)
        }
        if channels >= 8 {
            speakers.append(Speaker(name: "SpeakerSL", channel: 6, position: around(90, left: true)))
            speakers.append(Speaker(name: "SpeakerSR", channel: 7, position: around(90, left: false)))
            speakers.append(Speaker(name: "SpeakerBL", channel: 4, position: around(150, left: true)))
            speakers.append(Speaker(name: "SpeakerBR", channel: 5, position: around(150, left: false)))
        } else {
            // In 5.1 the last pair are surrounds, placed a little behind the seat.
            speakers.append(Speaker(name: "SpeakerSL", channel: 4, position: around(110, left: true)))
            speakers.append(Speaker(name: "SpeakerSR", channel: 5, position: around(110, left: false)))
        }
        return speakers
    }

    private func placeFronts(width: Float) {
        builtWidth = width
        root.findEntity(named: "SpeakerFL")?.position = [-width / 2, 0, 0]
        root.findEntity(named: "SpeakerFR")?.position = [width / 2, 0, 0]
    }

    // MARK: - Speaker check

    /// The channels of the current stream, in the order the check plays them.
    static func checkOrder(channels: Int) -> [(channel: Int, name: String)] {
        switch channels {
        case 8: return [(0, "Front left"), (2, "Centre"), (1, "Front right"), (7, "Side right"), (5, "Back right"), (4, "Back left"), (6, "Side left"), (3, "Low frequency")]
        case 6: return [(0, "Front left"), (2, "Centre"), (1, "Front right"), (5, "Surround right"), (4, "Surround left"), (3, "Low frequency")]
        default: return [(0, "Left"), (1, "Right")]
        }
    }
}
