import SwiftUI

struct ConnectionView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openImmersiveSpace) private var openImmersiveSpace
    @State private var diagnosticURL: URL?
    @State private var diagnosticError: String?
    @State private var showServer = false
    @State private var showOptions = false
    @State private var showConfiguration = false
    @State private var showEffects = false
    @State private var showCalibration = false

    var body: some View {
        @Bindable var connection = app.connection
        ScrollView {
            VStack(spacing: 12) {
                Label("Moonlight Connection", systemImage: "moon.stars.fill").font(.title2).frame(maxWidth: .infinity)
                Divider().opacity(0.3)
                HStack(alignment: .top, spacing: 10) {
                    Button { if connection.paired { launch() } else { showServer = true } } label: {
                        VStack(spacing: 8) {
                            Image(systemName: "desktopcomputer").font(.title)
                            Text(connection.selected?.name ?? "Ready to connect").lineLimit(2)
                            if let server = connection.selected { Text(server.address).font(.caption).foregroundStyle(.secondary) }
                            if connection.paired { Text("Connect").fontWeight(.semibold) }
                        }.frame(maxWidth: .infinity, minHeight: 110)
                    }.buttonStyle(.bordered).disabled(app.changingStreamMode || connection.busy || connection.hasPendingStreamSettings || app.session.state == .connecting || app.session.state == .stopping)
                    Button { showServer = true } label: {
                        VStack(spacing: 8) { Image(systemName: "plus").font(.title); Text(connection.paired ? "Pair New Server" : "Connect to PC") }
                            .frame(maxWidth: .infinity, minHeight: 110)
                    }.buttonStyle(.bordered).disabled(connection.busy)
                }
                if connection.servers.count > 1 {
                    Picker("Server", selection: Binding(get: { connection.selected?.id ?? "" }, set: { id in if let server = connection.servers.first(where: { $0.id == id }) { connection.select(server) } })) {
                        ForEach(connection.servers) { Text($0.name).tag($0.id) }
                    }.disabled(connection.busy)
                }
                StreamModeToggle()
                Text(app.sixDoFEnabled ? "Selected mode: 6DoF game portal" : "Selected mode: Basic Moonlight desktop")
                    .font(.caption).foregroundStyle(.secondary)
                Text(connection.status).frame(maxWidth: .infinity, alignment: .leading).foregroundStyle(.secondary)
                HStack(alignment: .top, spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        if connection.loadingApplications { ProgressView("Loading applications…") }
                        else if !connection.applications.isEmpty {
                            Picker("Application", selection: $connection.appID) {
                                if !connection.applications.contains(where: { $0.id == connection.appID }) { Text("App ID \(connection.appID)").tag(connection.appID) }
                                ForEach(connection.applications) { Text($0.name).tag($0.id) }
                            }
                        } else {
                            if let error = connection.appListError { Text("App list: \(error)").foregroundStyle(.orange) }
                            TextField("App ID", text: $connection.appID).textFieldStyle(.roundedBorder)
                        }
                        Button("Options") { showOptions = true }
                    }.frame(maxWidth: .infinity)
                    VStack(alignment: .leading, spacing: 6) {
                        Label(app.session.gamepad.name, systemImage: "gamecontroller")
                        if let config = app.session.activeConfiguration {
                            Text(config.metadata ? "Active mode: 6DoF" : "Active mode: Basic Moonlight")
                            Text("Requested codec: \(StreamCapabilities.label(config.codec))")
                            Text("\(config.metadata ? "Per eye" : "Desktop"): \(app.session.negotiatedWidth / (config.metadata ? 2 : 1)) × \(app.session.negotiatedHeight)")
                            Text("\(config.metadata ? "6DoF SBS" : "Stream"): \(config.encodedWidth) × \(config.encodedHeight)").foregroundStyle(.secondary)
                            Text("FPS: \(config.fps) requested")
                            Text("Audio: \(config.audio)")
                            Text(app.session.dynamicRangeDescription)
                        } else { Text("Not streaming") }
                    }.font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                }
                if showConfiguration { StreamConfigurationView(saved: connection.preferences, apply: connection.apply) }
                if app.session.state != .idle {
                    Text(app.session.stage).foregroundStyle(.secondary)
                    Text(app.coordinator.status).font(.caption).foregroundStyle(.secondary)
                    DisclosureGroup("Connection Details") { Text(app.session.statistics).font(.caption.monospaced()).textSelection(.enabled) }
                    Button("Disconnect") { app.disconnect() }
                }
                if let message = app.message { Text(message).foregroundStyle(.orange).textSelection(.enabled) }
                Divider().opacity(0.3)
                HStack {
                    Button("Prepare Diagnostics") {
                        do { diagnosticURL = try PortalDiagnostics.shared().exportReport(); diagnosticError = nil }
                        catch { diagnosticURL = nil; diagnosticError = error.localizedDescription }
                    }
                    if let diagnosticURL { ShareLink("Share Diagnostics", item: diagnosticURL) }
                }
                if let diagnosticError { Text(diagnosticError).foregroundStyle(.orange) }
                Button("Launch Immersive Mode (No Connection)") { Task { await openPortal() } }.disabled(app.immersiveSpaceState == .inTransition)
            }.padding(16)
        }.frame(minWidth: 680, minHeight: 450).glassBackgroundEffect(in: .rect(cornerRadius: 16))
        .sheet(isPresented: $showServer) { ServerPairingSheet().streamControllerEvents() }
        .sheet(isPresented: $connection.showPIN) { PINSheet(pin: connection.pin ?? "").streamControllerEvents() }
        .sheet(isPresented: $showOptions) {
            VStack(spacing: 12) {
                Button { showConfiguration.toggle(); showOptions = false } label: {
                    VStack(alignment: .leading) { Text(showConfiguration ? "Hide Stream Configuration" : "Configure Stream"); Text("Device Capabilities").font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading)
                }
                Button(role: .destructive) { Task { await app.coordinator.stop(); connection.resetPairing(); showOptions = false } } label: {
                    VStack(alignment: .leading) { Text("Reset Client Pairing"); Text("Clear cert & UID").font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading)
                }.disabled(connection.busy)
                Button { showOptions = false; showEffects = true } label: {
                    VStack(alignment: .leading) { Text("Immersive Options"); Text("Enable immersive features").font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading)
                }
                if app.sixDoFEnabled {
                    DisclosureGroup("Advanced") { Button("Portal Calibration") { showOptions = false; showCalibration = true } }
                }
                Button("Cancel", role: .cancel) { showOptions = false }
            }.buttonStyle(.bordered).padding(24).streamControllerEvents()
        }
        .sheet(isPresented: $showEffects) { ImmersiveOptionsView().streamControllerEvents() }
        .onChange(of: app.sixDoFEnabled) { _, enabled in if !enabled { showCalibration = false } }
        .sheet(isPresented: $showCalibration) { PortalCalibrationView().streamControllerEvents() }
        .task { if connection.paired { connection.loadApplications() } }
    }
    private func launch() {
        guard let server = app.connection.launchServer else { return }
        app.connection.saveSelection()
        Task { if await openPortal() { await app.start(server: server) } }
    }
    @discardableResult private func openPortal() async -> Bool {
        if app.immersiveSpaceState == .open { return true }
        guard app.immersiveSpaceState == .closed else { return false }
        app.immersiveSpaceState = .inTransition
        switch await openImmersiveSpace(id: app.immersiveSpaceID) {
        case .opened: app.immersiveSpaceState = .open; return true
        case .userCancelled: app.immersiveSpaceState = .closed; return false
        case .error: app.message = "The immersive space could not open"; app.immersiveSpaceState = .closed; return false
        @unknown default: app.immersiveSpaceState = .closed; return false
        }
    }
}

struct ServerPairingSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        @Bindable var connection = app.connection
        VStack(spacing: 16) {
            Text("Connect to Server").font(.title2)
            TextField("IP Address", text: $connection.host).textInputAutocapitalization(.never).autocorrectionDisabled()
            TextField("Port", text: $connection.port).keyboardType(.numberPad)
            HStack { Button("Cancel", role: .cancel) { dismiss() }; Button("Connect") { connection.connectToServer(); dismiss() } }
        }.textFieldStyle(.roundedBorder).padding(24).frame(width: 420)
    }
}
struct PINSheet: View {
    let pin: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(spacing: 20) { Text("Enter this PIN on your server").font(.title2); Text(pin).font(.system(size: 44, weight: .bold, design: .monospaced)); Button("OK") { dismiss() } }.padding(32)
    }
}
