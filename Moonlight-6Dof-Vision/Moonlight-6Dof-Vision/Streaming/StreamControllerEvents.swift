import SwiftUI
import GameController

/// Prevent visionOS from also translating forwarded gamepad input into UI pinches.
struct StreamControllerEvents: ViewModifier {
    @Environment(AppModel.self) private var app
    func body(content: Content) -> some View {
        content.handlesGameControllerEvents(
            matching: app.session.activeConfiguration == nil ? [] : .gamepad,
            withOptions: .receivesEventsInView(false))
    }
}
extension View {
    func streamControllerEvents() -> some View { modifier(StreamControllerEvents()) }
}
