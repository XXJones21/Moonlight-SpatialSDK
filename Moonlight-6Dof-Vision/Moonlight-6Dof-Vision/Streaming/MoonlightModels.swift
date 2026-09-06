import Foundation

/// Minimal HTTP DTOs required by the imported Moonlight response parsers.
@objcMembers final class TemporaryHost: NSObject {
    var name = "", uuid = "", mac = "", currentGame = "0"
    var activeAddress: String?, localAddress: String?, externalAddress: String?
    var httpsPort: UInt16 = 47984
    var serverCert: Data?
    var isNvidiaServerSoftware = false
    var pairState = PairState.unknown
    var serverCodecModeSupport: Int32 = 0
}
@objcMembers final class TemporaryApp: NSObject {
    var id: String
    var name: String
    var hdrSupported = false
    var installPath: String?
    @objc(initWithId:name:) init(id: String, name: String) { self.id = id; self.name = name }
}
