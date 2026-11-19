import SwiftUI
import UIKit
import AVKit
import CoreMedia

/// Tells the system which refresh rate the stream would like.
///
/// `AVDisplayCriteria(refreshRate:formatDescription:)` exists for apps that render
/// their own video instead of playing an asset, and the window's display manager takes
/// it as a hint. Apple lists both for visionOS but describes the behavior for Apple TV,
/// so whether the headset follows the hint is one of the things to check on device.
/// It is a preference only: the system may ignore it, and clearing it costs nothing.
struct DisplayCriteriaHint: UIViewRepresentable {
    /// Frames per second to ask for, or nil to clear the hint.
    let framesPerSecond: Int?
    let width: Int
    let height: Int

    func makeUIView(context: Context) -> HintView {
        let view = HintView()
        view.isUserInteractionEnabled = false
        view.apply(framesPerSecond: framesPerSecond, width: width, height: height)
        return view
    }

    func updateUIView(_ view: HintView, context: Context) {
        view.apply(framesPerSecond: framesPerSecond, width: width, height: height)
    }

    static func dismantleUIView(_ view: HintView, coordinator: ()) {
        view.apply(framesPerSecond: nil, width: 0, height: 0)
    }

    final class HintView: UIView {
        private var framesPerSecond: Int?
        private var size = (width: 0, height: 0)
        private var applied: String?

        func apply(framesPerSecond: Int?, width: Int, height: Int) {
            self.framesPerSecond = framesPerSecond; size = (width, height)
            push()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            applied = nil
            push()
        }

        private func push() {
            guard let window else { return }
            let key = framesPerSecond.map { "\($0)@\(size.width)x\(size.height)" } ?? "none"
            guard key != applied else { return }
            applied = key
            guard let framesPerSecond, framesPerSecond > 0, size.width > 0, size.height > 0 else {
                window.avDisplayManager.preferredDisplayCriteria = nil
                return
            }
            var description: CMFormatDescription?
            let status = CMVideoFormatDescriptionCreate(
                allocator: kCFAllocatorDefault, codecType: kCMVideoCodecType_HEVC,
                width: Int32(size.width), height: Int32(size.height),
                extensions: nil, formatDescriptionOut: &description)
            guard status == noErr, let description else { return }
            window.avDisplayManager.preferredDisplayCriteria = AVDisplayCriteria(refreshRate: Float(framesPerSecond), formatDescription: description)
            PortalDiagnostics.shared().record("Display hint: asked for \(framesPerSecond) Hz")
        }
    }
}

extension View {
    /// Applies the refresh-rate hint for the running stream, when the option is on.
    func streamDisplayHint(_ app: AppModel) -> some View {
        let config = app.session.activeConfiguration
        let wanted = app.settings.values.refreshRateHint && app.hasSession ? config?.fps : nil
        return background(DisplayCriteriaHint(framesPerSecond: wanted, width: config?.encodedWidth ?? 0, height: config?.encodedHeight ?? 0)
            .frame(width: 0, height: 0))
    }
}
