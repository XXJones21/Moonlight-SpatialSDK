import GameController
import CoreHaptics
import Observation

@MainActor @Observable final class PortalGamepad {
    var name = "No device"
    private var controller: GCController?
    private var active = false
    private var observers: [NSObjectProtocol] = []
    private var hapticEngine: CHHapticEngine?
    init() {
        for notification in [Notification.Name.GCControllerDidConnect, .GCControllerDidDisconnect] {
            observers.append(NotificationCenter.default.addObserver(forName: notification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            })
        }
        refresh()
    }
    private func refresh() {
        let next = GCController.controllers().first { $0.extendedGamepad != nil }
        guard next !== controller else { return }
        release(); controller?.extendedGamepad?.valueChangedHandler = nil
        controller = next; name = next?.vendorName ?? "No device"
        hapticEngine = next?.haptics?.createEngine(withLocality: .default)
        controller?.extendedGamepad?.valueChangedHandler = { [weak self] _, _ in Task { @MainActor in self?.send() } }
        send()
    }
    func setActive(_ value: Bool) { if !value { release() }; active = value; if value { send() } }
    func release() { if active { LiSendMultiControllerEvent(0, 1, 0, 0, 0, 0, 0, 0, 0) } }
    private func send() {
        guard active, let pad = controller?.extendedGamepad else { return }
        var buttons: Int32 = 0
        let mapping: [(GCControllerButtonInput?, Int32)] = [
            (pad.dpad.up,1),(pad.dpad.down,2),(pad.dpad.left,4),(pad.dpad.right,8),
            (pad.buttonMenu,0x10),(pad.buttonOptions,0x20),(pad.leftThumbstickButton,0x40),(pad.rightThumbstickButton,0x80),
            (pad.leftShoulder,0x100),(pad.rightShoulder,0x200),(pad.buttonA,0x1000),(pad.buttonB,0x2000),(pad.buttonX,0x4000),(pad.buttonY,0x8000)]
        for (button, mask) in mapping where button?.isPressed == true { buttons |= mask }
        func axis(_ value: Float) -> Int16 { Int16((min(1,max(-1,value))*32767).rounded()) }
        LiSendMultiControllerEvent(0, 1, buttons, UInt8(min(255,max(0,pad.leftTrigger.value*255))), UInt8(min(255,max(0,pad.rightTrigger.value*255))),
            axis(pad.leftThumbstick.xAxis.value), axis(pad.leftThumbstick.yAxis.value), axis(pad.rightThumbstick.xAxis.value), axis(pad.rightThumbstick.yAxis.value))
    }
    func rumble(low: Double, high: Double) {
        guard active, let engine = hapticEngine else { return }
        do {
            try engine.start()
            let intensity = Float(min(1,max(low,high)/65535))
            let event = CHHapticEvent(eventType: .hapticContinuous, parameters: [.init(parameterID: .hapticIntensity, value: intensity)], relativeTime: 0, duration: 0.1)
            let player = try engine.makePlayer(with: CHHapticPattern(events: [event], parameters: []))
            try player.start(atTime: CHHapticTimeImmediate)
        } catch { /* Controllers without compatible haptics still forward input. */ }
    }
}
