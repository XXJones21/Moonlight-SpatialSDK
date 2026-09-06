import Foundation
import Network

/// A serial queue owns the socket. A pending state is replaced, never queued as motion history.
final class PortalTransport: @unchecked Sendable {
    private let queue = DispatchQueue(label: "portal.pose", qos: .userInteractive)
    private var connection: NWConnection?
    private var pending: Data?
    private var sending = false
    private var generation: UInt64 = 0
    private var resets: [Data] = []
    var onStatus: (@Sendable (PortalHostStatus) -> Void)?
    var onError: (@Sendable (String) -> Void)?

    func connect(host: String, port: UInt16 = 4243) {
        queue.async { [self] in
            generation &+= 1
            connection?.cancel(); pending = nil; resets = []; sending = false
            guard let port = NWEndpoint.Port(rawValue: port) else { return }
            let socket = NWConnection(host: NWEndpoint.Host(host), port: port, using: .udp)
            let token = generation
            connection = socket
            socket.stateUpdateHandler = { [weak self] state in
                guard let self, self.generation == token else { return }
                switch state {
                case .ready: self.receive(socket, token: token); self.flush()
                case .failed(let error): self.onError?(error.localizedDescription)
                default: break
                }
            }
            socket.start(queue: queue)
        }
    }
    func send(_ state: PortalState) {
        guard let bytes = state.encode() else { onError?("Invalid portal state"); return }
        queue.async { [self] in pending = bytes; flush() }
    }
    func requestReset(session: UInt64, previous: UInt64, next: UInt64) {
        guard let bytes = PortalState.reset(session: session, previous: previous, next: next) else { onError?("Invalid tracking reset"); return }
        queue.async { [self] in
            guard resets.count < 64 else { onError?("Too many unacknowledged tracking resets"); return }
            resets.append(bytes)
        }
    }
    func stop() {
        queue.async { [self] in generation &+= 1; connection?.cancel(); connection = nil; pending = nil; resets = []; sending = false }
    }
    private func flush() {
        guard !sending, let socket = connection, socket.state == .ready, let data = pending else { return }
        pending = nil; sending = true
        let token = generation
        for reset in resets { socket.send(content: reset, completion: .idempotent) }
        socket.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let self, self.generation == token else { return }
            self.sending = false
            if let error { self.onError?(error.localizedDescription) }
            self.flush()
        })
    }
    private func receive(_ socket: NWConnection, token: UInt64) {
        socket.receiveMessage { [weak self] data, _, _, error in
            guard let self, self.generation == token else { return }
            if let data, data.count <= 1024,
               let status = try? JSONDecoder().decode(PortalHostStatus.self, from: data), status.version == 1 {
                if let epoch = UInt64(status.trackingEpoch), let session = UInt64(status.sessionID) {
                    self.resets.removeAll { packet in
                        packet.integerLE(at: 8, as: UInt64.self) == session && (packet.integerLE(at: 24, as: UInt64.self) ?? .max) <= epoch
                    }
                }
                self.onStatus?(status)
            }
            if let error { self.onError?(error.localizedDescription) } else { self.receive(socket, token: token) }
        }
    }
}
