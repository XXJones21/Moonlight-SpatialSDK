import Foundation
import GameController

/// Forwards a connected keyboard and mouse to the host while a stream is running.
///
/// Off by default: it is the one input path that has not been tried on the headset.
/// Keys arrive from GameController as USB HID usages and leave as Windows virtual-key
/// codes, which is what the Moonlight protocol carries.
@MainActor
final class StreamKeyboardMouse {
    private var active = false
    private var observers: [NSObjectProtocol] = []
    private var modifiers: Int8 = 0
    private var held = Set<Int16>()

    init() {
        let names: [Notification.Name] = [.GCKeyboardDidConnect, .GCKeyboardDidDisconnect, .GCMouseDidConnect, .GCMouseDidDisconnect]
        for name in names {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.bind() }
            })
        }
    }

    /// Turns forwarding on or off. Turning it off releases every key still held, so
    /// nothing stays stuck on the host.
    func setActive(_ value: Bool) {
        guard value != active else { return }
        active = value
        if value { bind() } else { unbind(); releaseAll() }
    }

    private func bind() {
        guard active else { return }
        GCKeyboard.coalesced?.keyboardInput?.keyChangedHandler = { [weak self] _, _, keyCode, pressed in
            let usage = keyCode.rawValue
            Task { @MainActor in self?.key(usage: usage, pressed: pressed) }
        }
        for mouse in GCMouse.mice() {
            guard let input = mouse.mouseInput else { continue }
            input.mouseMovedHandler = { [weak self] _, deltaX, deltaY in
                Task { @MainActor in self?.move(deltaX, deltaY) }
            }
            input.leftButton.pressedChangedHandler = { [weak self] _, _, pressed in
                Task { @MainActor in self?.button(BUTTON_LEFT, pressed) }
            }
            input.rightButton?.pressedChangedHandler = { [weak self] _, _, pressed in
                Task { @MainActor in self?.button(BUTTON_RIGHT, pressed) }
            }
            input.middleButton?.pressedChangedHandler = { [weak self] _, _, pressed in
                Task { @MainActor in self?.button(BUTTON_MIDDLE, pressed) }
            }
            input.scroll.valueChangedHandler = { [weak self] _, _, vertical in
                Task { @MainActor in self?.scroll(vertical) }
            }
        }
    }

    private func unbind() {
        GCKeyboard.coalesced?.keyboardInput?.keyChangedHandler = nil
        for mouse in GCMouse.mice() {
            guard let input = mouse.mouseInput else { continue }
            input.mouseMovedHandler = nil
            input.leftButton.pressedChangedHandler = nil
            input.rightButton?.pressedChangedHandler = nil
            input.middleButton?.pressedChangedHandler = nil
            input.scroll.valueChangedHandler = nil
        }
    }

    private func releaseAll() {
        for code in held { LiSendKeyboardEvent(code, CChar(KEY_ACTION_UP), 0) }
        held.removeAll(); modifiers = 0
        for button in [BUTTON_LEFT, BUTTON_RIGHT, BUTTON_MIDDLE] { LiSendMouseButtonEvent(CChar(BUTTON_ACTION_RELEASE), button) }
    }

    private func key(usage: Int, pressed: Bool) {
        guard active, let virtualKey = Self.virtualKey(forUsage: usage) else { return }
        if let bit = Self.modifierBit(forUsage: usage) {
            if pressed { modifiers |= bit } else { modifiers &= ~bit }
        }
        // The protocol expects the high bit set on every key code.
        let code = Int16(bitPattern: 0x8000 | virtualKey)
        if pressed { held.insert(code) } else { held.remove(code) }
        LiSendKeyboardEvent(code, CChar(pressed ? KEY_ACTION_DOWN : KEY_ACTION_UP), modifiers)
    }

    private func move(_ deltaX: Float, _ deltaY: Float) {
        guard active else { return }
        // GameController reports up as positive; the host expects down as positive.
        let x = Int16(max(-32767, min(32767, deltaX.rounded())))
        let y = Int16(max(-32767, min(32767, (-deltaY).rounded())))
        if x != 0 || y != 0 { LiSendMouseMoveEvent(x, y) }
    }

    private func button(_ button: Int32, _ pressed: Bool) {
        guard active else { return }
        LiSendMouseButtonEvent(CChar(pressed ? BUTTON_ACTION_PRESS : BUTTON_ACTION_RELEASE), button)
    }

    private func scroll(_ vertical: Float) {
        guard active, vertical != 0 else { return }
        // One wheel notch is 120 units in the protocol.
        LiSendHighResScrollEvent(Int16(max(-32767, min(32767, (vertical * 120).rounded()))))
    }

    // MARK: - HID usage to Windows virtual key

    private static func modifierBit(forUsage usage: Int) -> Int8? {
        switch usage {
        case 224, 228: return Int8(MODIFIER_CTRL)
        case 225, 229: return Int8(MODIFIER_SHIFT)
        case 226, 230: return Int8(MODIFIER_ALT)
        case 227, 231: return Int8(MODIFIER_META)
        default: return nil
        }
    }

    private static func virtualKey(forUsage usage: Int) -> UInt16? {
        switch usage {
        case 4...29: return UInt16(0x41 + usage - 4)        // A-Z
        case 30...38: return UInt16(0x31 + usage - 30)      // 1-9
        case 39: return 0x30                                // 0
        case 58...69: return UInt16(0x70 + usage - 58)      // F1-F12
        case 89...97: return UInt16(0x61 + usage - 89)      // keypad 1-9
        default: return table[usage]
        }
    }

    private static let table: [Int: UInt16] = [
        40: 0x0D, 41: 0x1B, 42: 0x08, 43: 0x09, 44: 0x20,   // Return, Escape, Backspace, Tab, Space
        45: 0xBD, 46: 0xBB, 47: 0xDB, 48: 0xDD, 49: 0xDC,   // - = [ ] \
        51: 0xBA, 52: 0xDE, 53: 0xC0, 54: 0xBC, 55: 0xBE, 56: 0xBF, // ; ' ` , . /
        57: 0x14,                                           // Caps Lock
        70: 0x2C, 71: 0x91, 72: 0x13,                       // Print Screen, Scroll Lock, Pause
        73: 0x2D, 74: 0x24, 75: 0x21, 76: 0x2E, 77: 0x23, 78: 0x22, // Insert, Home, Page Up, Delete, End, Page Down
        79: 0x27, 80: 0x25, 81: 0x28, 82: 0x26,             // Right, Left, Down, Up
        83: 0x90, 84: 0x6F, 85: 0x6A, 86: 0x6D, 87: 0x6B, 88: 0x0D, // Num Lock, keypad / * - + Enter
        98: 0x60, 99: 0x6E,                                 // keypad 0, keypad .
        224: 0xA2, 225: 0xA0, 226: 0xA4, 227: 0x5B,         // left Ctrl, Shift, Alt, Windows
        228: 0xA3, 229: 0xA1, 230: 0xA5, 231: 0x5C          // right Ctrl, Shift, Alt, Windows
    ]
}
