import Foundation

@main
struct StreamPreferencesTests {
    static func main() throws {
        let defaults = StreamPreferences()
        precondition(!defaults.metadata, "New streams must default to ordinary Moonlight")
        let h264 = StreamPreferences(codec: "h264", hdr: false, metadata: false)
        let h264Formats = try h264.videoFormats()
        precondition(h264Formats == 0x0001, "Explicit H.264 must not advertise HEVC or AV1")
        let restoredH264 = try JSONDecoder().decode(StreamPreferences.self, from: JSONEncoder().encode(h264))
        let restoredFormats = try restoredH264.videoFormats()
        precondition(restoredFormats == 0x0001, "Saved H.264 selection must survive persistence")
        for size in [[1280, 720], [2560, 1440]] {
            var stream = StreamPreferences(eyeWidth: size[0], eyeHeight: size[1], metadata: false)
            precondition(stream.encodedWidth == size[0] && stream.encodedHeight == size[1],
                         "Desktop streaming must request the selected resolution without SBS or metadata padding")
            stream.metadata = true
            precondition(stream.encodedWidth == size[0] * 2 && stream.encodedHeight == size[1] + 16,
                         "6DoF must retain both eye images and the identity strips")
            let restored = try JSONDecoder().decode(StreamPreferences.self, from: JSONEncoder().encode(stream))
            precondition(restored == stream, "Settings must preserve the selected stream mode")
            stream.metadata = false
            precondition(stream.encodedWidth == size[0] && stream.encodedHeight == size[1],
                         "Switching back must restore ordinary desktop dimensions")
        }
        precondition(StreamPreferences.bench.encodedWidth == 3840 && StreamPreferences.bench.encodedHeight == 1096)
        print("StreamPreferences mode/resolution checks passed")
    }
}
