import Foundation
import Observation

/// Build-time switches.
enum AppFeatures {
    /// The 6DoF game portal ships as its own target. This target leaves it out unless
    /// `PORTAL_6DOF` is set in the target's Swift compilation conditions.
    #if PORTAL_6DOF
    static let sixDoF = true
    #else
    static let sixDoF = false
    #endif
}

/// How the stream's audio is placed in the room while effects are on in the theater.
enum SoundMode: String, CaseIterable, Codable, Identifiable {
    /// The channels go to the system, which places them around the screen's scene.
    case system
    /// One multichannel source on the screen: every channel from its own direction.
    case bed
    /// One source per channel, positioned around the screen and the seat.
    case speakers
    var id: String { rawValue }
    var label: String {
        switch self {
        case .system: return "System"
        case .bed: return "Surround bed"
        case .speakers: return "Placed speakers"
        }
    }
    var detail: String {
        switch self {
        case .system: return "The system places the channels. This is the original behavior."
        case .bed: return "Each channel comes from its own direction around the screen. No room acoustics."
        case .speakers: return "Virtual speakers stand around the screen and your seat, with your room's acoustics."
        }
    }
}

/// A saved screen arrangement.
struct ScreenPreset: Codable, Equatable {
    var width: Float
    var distance: Float
    var heightOffset: Float
    var curve: Float
    var tiltDegrees: Float
    static let standard = ScreenPreset(width: 0.7 * 16 / 9, distance: 1, heightOffset: -0.1, curve: 0, tiltDegrees: 0)
}

/// Every option the cinematic work adds, persisted as one record.
///
/// Each feature has its own switch so a problem found on the headset can be turned off
/// without losing the rest. Decoding tolerates missing keys, so adding an option later
/// does not reset the ones already saved.
@MainActor @Observable final class CinemaSettings {
    struct Values: Codable, Equatable {
        // Picture
        var framePacing = true
        var refreshRateHint = true
        // Light
        var roomLight = true
        var roomLightStrength: Float = 1
        var roomLightReach: Float = 4
        var roomLightFlipVertical = false
        var biasLight = true
        var biasLightStrength: Float = 1
        var roomTint = false
        var roomMesh = true
        // Sound
        var soundMode = SoundMode.bed
        var lowFrequencyGain: Double = 0
        var lipSyncMilliseconds: Double = 0
        // Screen
        var curve: Float = 0
        var tiltDegrees: Float = 0
        var presets: [ScreenPreset?] = [nil, nil, nil]
        var lastPlacement: ScreenPreset?
        var restorePlacement = true
        // Session
        var startInWindow = false
        var resumeOnLaunch = false
        var autoReconnect = true
        var showStatistics = false
        var keyboardAndMouse = false

        init() {}

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            func value<T: Decodable>(_ key: CodingKeys, _ fallback: T) -> T {
                (try? c.decodeIfPresent(T.self, forKey: key)) ?? fallback
            }
            let defaults = Values()
            framePacing = value(.framePacing, defaults.framePacing)
            refreshRateHint = value(.refreshRateHint, defaults.refreshRateHint)
            roomLight = value(.roomLight, defaults.roomLight)
            roomLightStrength = value(.roomLightStrength, defaults.roomLightStrength)
            roomLightReach = value(.roomLightReach, defaults.roomLightReach)
            roomLightFlipVertical = value(.roomLightFlipVertical, defaults.roomLightFlipVertical)
            biasLight = value(.biasLight, defaults.biasLight)
            biasLightStrength = value(.biasLightStrength, defaults.biasLightStrength)
            roomTint = value(.roomTint, defaults.roomTint)
            roomMesh = value(.roomMesh, defaults.roomMesh)
            soundMode = value(.soundMode, defaults.soundMode)
            lowFrequencyGain = value(.lowFrequencyGain, defaults.lowFrequencyGain)
            lipSyncMilliseconds = value(.lipSyncMilliseconds, defaults.lipSyncMilliseconds)
            curve = value(.curve, defaults.curve)
            tiltDegrees = value(.tiltDegrees, defaults.tiltDegrees)
            let saved: [ScreenPreset?] = value(.presets, defaults.presets)
            presets = saved.count == 3 ? saved : defaults.presets
            lastPlacement = (try? c.decodeIfPresent(ScreenPreset.self, forKey: .lastPlacement)) ?? nil
            restorePlacement = value(.restorePlacement, defaults.restorePlacement)
            startInWindow = value(.startInWindow, defaults.startInWindow)
            resumeOnLaunch = value(.resumeOnLaunch, defaults.resumeOnLaunch)
            autoReconnect = value(.autoReconnect, defaults.autoReconnect)
            showStatistics = value(.showStatistics, defaults.showStatistics)
            keyboardAndMouse = value(.keyboardAndMouse, defaults.keyboardAndMouse)
        }
    }

    private static let key = "cinema.settings"

    var values: Values {
        didSet {
            guard values != oldValue else { return }
            UserDefaults.standard.set(try? JSONEncoder().encode(values), forKey: Self.key)
            onChange?()
        }
    }

    /// Called after any option changes, so the app can re-apply light, sound and screen.
    var onChange: (() -> Void)?

    init() {
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let stored = try? JSONDecoder().decode(Values.self, from: data) {
            values = stored
        } else {
            values = Values()
        }
    }
}
