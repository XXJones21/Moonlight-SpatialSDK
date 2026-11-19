import Foundation
import RealityKit
import VideoToolbox
import Observation
import OSLog

@MainActor @Observable final class MoonlightSession {
    private let logger = Logger(subsystem: "com.joshuajones.Moonlight-6Dof-Vision", category: "StreamRequest")
    enum State: Equatable { case idle, connecting, streaming, stopping, failed(String) }
    var state = State.idle
    var stage = "Not streaming"
    var statistics = "Not streaming"
    var activeConfiguration: StreamPreferences?
    var negotiatedWidth = 0
    var negotiatedHeight = 0
    var negotiatedHDR: Bool?
    var dynamicRangeDescription: String {
        guard let requested = activeConfiguration else { return "Not streaming" }
        guard let negotiatedHDR else { return requested.hdr ? "HDR requested; awaiting result" : "SDR requested" }
        return negotiatedHDR ? "HDR active" : (requested.hdr ? "HDR unavailable; streaming SDR" : "SDR active")
    }
    var videoTexture: TextureResource?
    let gamepad = PortalGamepad()
    var onTexture: ((TextureResource, Int, Int) -> Void)?
    var onEnded: ((String) -> Void)?
    var onStarted: (() -> Void)?
    var onDecodedBuffer: ((CVImageBuffer) -> Bool)?
    var onPresentedFrame: ((PortalFrameIdentity?) -> Void)?
    var sampleLighting: ((CVPixelBuffer) -> Void)?
    /// Decoder frame pacing for the next stream. Set from settings before `start`.
    var useFramePacing = false
    /// Whether the decoder also renders the small blurred copy of the picture that the
    /// room light projects. Read on the decoder's thread, so it sits behind a lock.
    let ambientSwitch = AmbientSwitch()
    /// Delivers the texture backing that blurred copy, or nil when the stream ends.
    var onAmbientTexture: ((TextureResource?) -> Void)?
    private var receivedPresentedFrame = false
    private var client: MLClient?
    private var decoder: DrawableVideoDecoder?
    private var statsTask: Task<Void, Never>?
    private var token = UUID()
    private var operation = UUID()
    private var stopping: Task<Void, Never>?

    func start(server: SavedServer, preferences: StreamPreferences) async {
        let request = UUID(); operation = request
        await stopCore()
        guard operation == request else { return }
        guard let certificate = PortalKeychain.data(for: server.address) else { state = .failed("Pair this server first"); return }
        let generation = UUID(); token = generation
        receivedPresentedFrame = false
        do {
            let formats = try preferences.videoFormats()
            logger.notice("Starting stream: codec=\(preferences.codec, privacy: .public), formatMask=\(formats), size=\(preferences.encodedWidth)x\(preferences.encodedHeight), fps=\(preferences.fps), HDR=\(preferences.hdr), 6DoF=\(preferences.metadata)")
            PortalDiagnostics.shared().record("Stream request: codec=\(preferences.codec), audio=\(preferences.audio), size=\(preferences.encodedWidth)x\(preferences.encodedHeight), HDR=\(preferences.hdr), 6DoF=\(preferences.metadata)")
            let texture = try StereoPreview.makeTexture(stereo: preferences.metadata)
            // A stand-in until the decoder's ambient queue exists; the same resource
            // then receives that queue's drawables.
            let ambientTexture = try StereoPreview.makeTexture(stereo: false)
            let ambientSwitch = self.ambientSwitch
            let pacing = useFramePacing
            let client = MLClient()
            client.event = { [weak self] name, payload in
                Task { @MainActor in guard let self, self.token == generation else { return }; self.handle(name, payload: payload) }
            }
            let decoder = DrawableVideoDecoder(texture: texture, callbacks: client, aspectRatio: Float(preferences.eyeWidth) / Float(preferences.eyeHeight),
                useFramePacing: pacing, enableHDR: preferences.hdr, enableAmbilightProvider: { ambientSwitch.isOn },
                callbackToRender: { [weak self] queue, ambientQueue, resolution in
                    guard let self, self.token == generation else { return }
                    texture.replace(withDrawables: queue)
                    if let ambientQueue, !preferences.metadata {
                        ambientTexture.replace(withDrawables: ambientQueue)
                        self.onAmbientTexture?(ambientTexture)
                    }
                    let width = resolution?.0 ?? preferences.encodedWidth, height = resolution?.1 ?? preferences.encodedHeight
                    self.negotiatedWidth = width; self.negotiatedHeight = height
                    self.videoTexture = texture; self.onTexture?(texture, width, height)
                })
            decoder.acceptDecodedFrame = preferences.metadata ? onDecodedBuffer : nil
            decoder.didPresentFrame = { [weak self] identity in
                Task { @MainActor in
                    guard let self, self.token == generation else { return }
                    if !self.receivedPresentedFrame {
                        self.receivedPresentedFrame = true
                        PortalDiagnostics.shared().record("First decoded video frame completed GPU copy")
                    }
                    self.onPresentedFrame?(identity)
                }
            }
            decoder.sampleLighting = sampleLighting
            decoder.metadataRows = preferences.metadata ? 16 : 0
            // Ordinary streams get a plain blurred copy for the room light. The 6DoF
            // portal keeps the donor's halo shader and never enables it.
            if !preferences.metadata { decoder.ambilightFragment = "copyFragmentShaderRoomLight" }
            let config = StreamConfiguration()
            config.host = server.address; config.appID = server.appID; config.appName = server.name
            config.width = Int32(preferences.encodedWidth); config.height = Int32(preferences.encodedHeight)
            config.frameRate = Int32(preferences.fps); config.bitRate = Int32(preferences.bitrateKbps ?? preferences.automaticBitrate)
            config.audioConfiguration = preferences.audioConfiguration; config.supportedVideoFormats = formats
            config.colorRange = preferences.fullRange ? 1 : 0; config.colorSpace = preferences.hdr ? 2 : 1
            config.serverCert = certificate; config.gamepadMask = 1; config.useFramePacing = pacing
            self.client = client; self.decoder = decoder; self.activeConfiguration = preferences
            negotiatedHDR = nil
            state = .connecting; stage = "Connecting"
            // Install the chosen material immediately, including when no frame
            // has decoded yet. The same texture later receives drawable updates.
            videoTexture = texture
            onTexture?(texture, preferences.encodedWidth, preferences.eyeHeight)
            client.start(config: config, renderer: decoder)
            statsTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1))
                    guard let self, self.token == generation, !Task.isCancelled else { return }
                    self.statistics = client.statistics() + "\n" + CoreAudioRenderer.currentStats()
                }
            }
        } catch { state = .failed(error.localizedDescription); onEnded?(error.localizedDescription) }
    }

    func stop() async {
        operation = UUID() // Supersede starts that are still awaiting shared teardown.
        await stopCore()
    }

    private func stopCore() async {
        if let stopping { await stopping.value; return }
        guard let client else { state = .idle; return }
        PortalDiagnostics.shared().record("Native stream teardown begin")
        token = UUID(); state = .stopping; gamepad.setActive(false)
        statsTask?.cancel(); statsTask = nil
        let stopTask = Task { @MainActor in
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in client.stop { continuation.resume() } }
            // Every waiter observes full teardown, including Swift-owned resources. Keeping this
            // inside the shared task prevents a reconnect from racing another waiter's cleanup.
            PortalDiagnostics.shared().record("Native stream teardown complete")
            self.client = nil; self.decoder = nil; self.videoTexture = nil
            self.onAmbientTexture?(nil)
            self.activeConfiguration = nil; self.negotiatedWidth = 0; self.negotiatedHeight = 0
            self.negotiatedHDR = nil
            self.statistics = "Not streaming"; self.state = .idle; self.stopping = nil
        }
        stopping = stopTask
        await stopTask.value
    }
    private func handle(_ name: String, payload: [AnyHashable: Any]) {
        if ["stage", "started", "error", "terminated"].contains(name) {
            PortalDiagnostics.shared().record("Native event: \(name), code=\(payload["code"] ?? "none")")
        }
        switch name {
        case "stage":
            stage = payload["message"] as? String ?? "Connecting"
            PortalDiagnostics.shared().record("Connection stage: \(stage)")
        case "started": stage = "Connected"; onStarted?()
        case "video": state = .streaming
        case "hdr":
            let enabled = payload["enabled"] as? Bool ?? false
            negotiatedHDR = enabled; decoder?.setHdrMode(enabled)
        case "rumble": gamepad.rumble(low: (payload["low"] as? NSNumber)?.doubleValue ?? 0, high: (payload["high"] as? NSNumber)?.doubleValue ?? 0)
        case "error", "terminated":
            let message = payload["message"] as? String ?? "Connection ended (\(payload["code"] ?? "unknown"))"
            let failedClient = client
            let failedOperation = operation
            token = UUID() // Reject additional callbacks already queued by this native session.
            statsTask?.cancel(); statsTask = nil
            state = .failed(message); gamepad.setActive(false); onEnded?(message)
            Task { @MainActor [weak self, weak failedClient] in
                guard let self, let failedClient, self.operation == failedOperation, self.client === failedClient else { return }
                // Coordinator-triggered shutdown shares this task; an old failure cannot stop a new client.
                await self.stop()
            }
        default: break
        }
    }
}

/// A flag the decoder reads on its own thread.
final class AmbientSwitch: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isOn: Bool {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }
}
