import RealityKit

/// Associates stream-owned AVAudioEngine playback with the panel's immersive
/// UIScene. PCM decoding remains in CoreAudioRenderer; Settings owns neither.
struct PanelAudioComponent: Component {
    let sceneIdentifier: String
}
