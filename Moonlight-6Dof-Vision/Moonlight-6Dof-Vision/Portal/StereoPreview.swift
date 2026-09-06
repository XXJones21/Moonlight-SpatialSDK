import UIKit
import RealityKit

enum StereoPreview {
    @MainActor static func makeTexture() throws -> TextureResource {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1024, height: 288)).image { context in
            for eye in 0..<2 {
                let left = CGFloat(eye * 512)
                (eye == 0 ? UIColor.systemIndigo : UIColor.systemTeal).setFill()
                context.fill(CGRect(x: left, y: 0, width: 512, height: 288))
                for row in 0..<8 { for column in 0..<16 where (row + column) % 2 == 0 {
                    UIColor.white.withAlphaComponent(0.12).setFill()
                    context.fill(CGRect(x: left + CGFloat(column * 32), y: CGFloat(row * 36), width: 32, height: 36))
                } }
                let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 32), .foregroundColor: UIColor.white]
                (eye == 0 ? "LEFT EYE" : "RIGHT EYE").draw(at: CGPoint(x: left + 150, y: 115), withAttributes: attributes)
                for (index, point) in [CGPoint(x: 8, y: 8), CGPoint(x: 476, y: 8), CGPoint(x: 8, y: 246), CGPoint(x: 476, y: 246)].enumerated() {
                    "\(index + 1)".draw(at: CGPoint(x: left + point.x, y: point.y), withAttributes: attributes)
                }
            }
        }
        guard let cgImage = image.cgImage else { throw CocoaError(.coderInvalidValue) }
        return try TextureResource.generate(from: cgImage, options: .init(semantic: .color))
    }
}
