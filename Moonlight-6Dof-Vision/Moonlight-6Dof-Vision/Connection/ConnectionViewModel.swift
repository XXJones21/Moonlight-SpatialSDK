import Foundation
import Observation

@MainActor @Observable final class ConnectionViewModel {
    struct AppChoice: Identifiable { let id: String; let name: String }
    var servers: [SavedServer] = []
    var selected: SavedServer?
    var applications: [AppChoice] = []
    var appID = "0"
    var host = ""
    var port = "47989"
    var status = "Ready to connect"
    var busy = false
    var loadingApplications = false
    var appListError: String?
    var pin: String?
    var showPIN = false
    var preferences = StreamPreferences.load()
    private var request = UUID()
    private var client: MLClient?
    init() {
        if let data = UserDefaults.standard.data(forKey: "portal.servers"), let stored = try? JSONDecoder().decode([SavedServer].self, from: data) { servers = stored }
        let savedID = UserDefaults.standard.string(forKey: "portal.selectedServer")
        if let first = servers.first(where: { $0.id == savedID }) ?? servers.first { selected = first; host = first.host; port = String(first.port); appID = first.appID }
    }
    var paired: Bool { selected.map { PortalKeychain.data(for: $0.address) != nil } ?? false }
    var launchServer: SavedServer? { guard var selected, paired else { return nil }; selected.appID = appID; return selected }
    func select(_ server: SavedServer) {
        guard !busy else { return }
        request = UUID(); selected = server; host = server.host; port = String(server.port); appID = server.appID
        UserDefaults.standard.set(server.id, forKey: "portal.selectedServer")
        applications = []; appListError = nil; loadApplications()
    }
    func connectToServer() {
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanHost.isEmpty, !cleanHost.contains("/"), let portNumber = UInt16(port), portNumber > 0 else { status = "Enter an IP address or hostname and a port from 1 to 65535"; return }
        guard !busy else { return }
        let server = SavedServer(host: cleanHost, port: portNumber, name: cleanHost)
        let generation = UUID(); request = generation
        busy = true; status = "Checking server…"; applications = []; appListError = nil
        let client = MLClient(); self.client = client
        client.event = { [weak self] name, payload in
            Task { @MainActor in
                guard let self, self.request == generation else { return }
                switch name {
                case "host":
                    var found = server; found.name = payload["name"] as? String ?? server.host
                    self.selected = found
                    if payload["paired"] as? Bool == true {
                        self.finishPairing(found)
                    } else { self.status = "Waiting for PIN confirmation…"; client.pair(address: server.address, certificate: PortalKeychain.data(for: server.address)) }
                case "pin": self.pin = payload["pin"] as? String; self.showPIN = true
                case "paired":
                    guard let certificate = payload["certificate"] as? Data, let found = self.selected else { return }
                    do { try PortalKeychain.save(certificate, for: found.address); self.finishPairing(found) }
                    catch { self.busy = false; self.status = error.localizedDescription }
                case "alreadyPaired":
                    if let found = self.selected, PortalKeychain.data(for: found.address) != nil { self.finishPairing(found) }
                    else { self.busy = false; self.status = "The PC reports an existing pairing, but this client has no certificate. Remove that client pairing on the PC and try again." }
                case "error": self.busy = false; self.showPIN = false; self.status = payload["message"] as? String ?? "Connection failed"
                default: break
                }
            }
        }
        client.inspect(address: server.address, certificate: PortalKeychain.data(for: server.address))
    }
    private func finishPairing(_ server: SavedServer) {
        UserDefaults.standard.set(server.id, forKey: "portal.selectedServer")
        busy = false; showPIN = false; status = "Paired! Click Connect to continue"
        if let index = servers.firstIndex(where: { $0.id == server.id }) { servers[index] = server } else { servers.append(server) }
        saveServers(); loadApplications()
    }
    func saveSelection() {
        guard let selected, let index = servers.firstIndex(where: { $0.id == selected.id }) else { return }
        servers[index].appID = appID; saveServers()
    }
    private func saveServers() { UserDefaults.standard.set(try? JSONEncoder().encode(servers), forKey: "portal.servers") }
    func apply(_ draft: StreamPreferences) { preferences = draft; draft.save() }
    func resetPairing() {
        guard !busy else { return }
        request = UUID(); PortalKeychain.reset(); MLClient.resetIdentity()
        applications = []; loadingApplications = false; appListError = nil; status = "Client pairing reset. Connect to pair again."
    }
    func loadApplications() {
        guard let selected, let certificate = PortalKeychain.data(for: selected.address) else { return }
        let generation = UUID(); request = generation; loadingApplications = true
        let client = MLClient(); self.client = client
        client.event = { [weak self] name, payload in Task { @MainActor in
            guard let self, self.request == generation else { return }
            self.loadingApplications = false
            if name == "apps", let apps = payload["apps"] as? [[String: String]] {
                self.applications = apps.compactMap { guard let id = $0["id"], let name = $0["name"] else { return nil }; return AppChoice(id: id, name: name) }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                if !self.applications.contains(where: { $0.id == self.appID }), let first = self.applications.first { self.appID = first.id }
                self.appListError = nil
            } else { self.appListError = payload["message"] as? String ?? "Application list failed" }
        } }
        client.apps(address: selected.address, certificate: certificate)
    }
}
