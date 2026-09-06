import SwiftUI
import UIKit

/// Resolve the real UIScene identifier from the immersive view, never Settings.
struct ImmersiveAudioSceneReader: UIViewRepresentable {
    let onScene: (String) -> Void
    func makeUIView(context: Context) -> SceneView {
        let view = SceneView()
        view.isUserInteractionEnabled = false
        view.onScene = onScene
        return view
    }
    func updateUIView(_ view: SceneView, context: Context) { view.onScene = onScene; view.reportScene() }
    final class SceneView: UIView {
        var onScene: ((String) -> Void)?
        private var reportedIdentifier: String?
        override func didMoveToWindow() { super.didMoveToWindow(); reportScene() }
        func reportScene() {
            guard let scene = window?.windowScene else { return }
            let id = scene.session.persistentIdentifier
            guard id != reportedIdentifier else { return }
            // Avoid mutating observable scene state during SwiftUI view updates.
            DispatchQueue.main.async { [weak self] in
                guard let self, let current = self.window?.windowScene,
                      current.session.persistentIdentifier == id,
                      self.reportedIdentifier != id, let onScene = self.onScene else { return }
                onScene(id)
                self.reportedIdentifier = id
                PortalDiagnostics.shared().record("Audio scene resolved: \(current.session.role.rawValue)")
            }
        }
    }
}
