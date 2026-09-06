import Foundation
import RealityKit
import VideoToolbox
import Observation

@MainActor @Observable final class MoonlightSession {
    enum State: Equatable { case idle, connecting, streaming, stopping, failed(String) }
    var state = State.idle
    var stage = "Not streaming"
    var statistics = "Not streaming"
    var activeConfiguration: StreamPreferences?
    var negotiatedWidth = 0, negotiatedHeight = 0
    var videoTexture: TextureResource?
    let gamepad = PortalGamepad()
    var onTexture: ((TextureResource, Int, Int) -> Void)?
    var onEnded: ((String) -> Void)?
    var onStarted: (() -> Void)?
    var onDecodedBuffer: ((CVImageBuffer) -> Bool)?
    var onPresentedFrame: ((PortalFrameIdentity) -> Void)?
    var sampleLighting: ((CVPixelBuffer) -> Void)?
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
        do {
            let formats = try preferences.videoFormats()
            let texture = try StereoPreview.makeTexture()
            let client = MLClient()
            client.event = { [weak self] name, payload in
                Task { @MainActor in guard let self, self.token == generation else { return }; self.handle(name, payload: payload) }
            }
            let decoder = DrawableVideoDecoder(texture: texture, callbacks: client, aspectRatio: Float(preferences.eyeWidth) / Float(preferences.eyeHeight),
                useFramePacing: false, enableHDR: preferences.hdr, enableAmbilightProvider: { false },
                callbackToRender: { [weak self] queue, _, resolution in
                    guard let self, self.token == generation else { return }
                    texture.replace(withDrawables: queue)
                    let width = resolution?.0 ?? preferences.encodedWidth, height = resolution?.1 ?? preferences.encodedHeight
                    self.negotiatedWidth = width; self.negotiatedHeight = height
                    self.videoTexture = texture; self.onTexture?(texture, width, height)
                })
            decoder.acceptDecodedFrame = onDecodedBuffer
            decoder.didPresentFrame = onPresentedFrame
            decoder.sampleLighting = sampleLighting
            decoder.metadataRows = preferences.metadata ? 16 : 0
            let config = StreamConfiguration()
            config.host = server.address; config.appID = server.appID; config.appName = server.name
            config.width = Int32(preferences.encodedWidth); config.height = Int32(preferences.encodedHeight)
            config.frameRate = Int32(preferences.fps); config.bitRate = Int32(preferences.bitrateKbps ?? preferences.automaticBitrate)
            config.audioConfiguration = preferences.audioConfiguration; config.supportedVideoFormats = formats
            config.colorRange = preferences.fullRange ? 1 : 0; config.colorSpace = preferences.hdr ? 2 : 1
            config.serverCert = certificate; config.gamepadMask = 1; config.useFramePacing = false
            self.client = client; self.decoder = decoder; self.activeConfiguration = preferences
            state = .connecting; stage = "Connecting"
            client.start(config: config, renderer: decoder)
            statsTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1))
                    guard let self, self.token == generation, !Task.isCancelled else { return }
                    self.statistics = client.statistics()
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
        token = UUID(); state = .stopping; gamepad.setActive(false)
        statsTask?.cancel(); statsTask = nil
        let stopTask = Task { @MainActor in
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in client.stop { continuation.resume() } }
            // Every waiter observes full teardown, including Swift-owned resources. Keeping this
            // inside the shared task prevents a reconnect from racing another waiter's cleanup.
            self.client = nil; self.decoder = nil; self.videoTexture = nil
            self.activeConfiguration = nil; self.negotiatedWidth = 0; self.negotiatedHeight = 0
            self.statistics = "Not streaming"; self.state = .idle; self.stopping = nil
        }
        stopping = stopTask
        await stopTask.value
    }
    private func handle(_ name: String, payload: [AnyHashable: Any]) {
        switch name {
        case "stage": stage = payload["message"] as? String ?? "Connecting"
        case "started": stage = "Connected"; onStarted?()
        case "video": state = .streaming
        case "hdr": decoder?.setHdrMode(payload["enabled"] as? Bool ?? false)
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
