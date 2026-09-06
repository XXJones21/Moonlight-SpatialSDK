import Foundation

@main
struct AnnexBTests {
    static func main() {
        let fixtures: [([UInt8], [UInt8]?)] = [
            ([0,0,0,1,0x65,0xaa], [0,0,0,2,0x65,0xaa]),
            ([0,0,1,0x26,1,0xab,0,0,0,1,0x02,1,0xcd],
             [0,0,0,3,0x26,1,0xab,0,0,0,3,0x02,1,0xcd]),
            ([0,0,1,0x65,0,0,3,1,0,0,1,0x41], [0,0,0,5,0x65,0,0,3,1,0,0,0,1,0x41]),
            ([], nil), ([0,0], nil), ([0,0,1], nil), ([9,0,0,1,0x65], nil),
            ([0,0,1,0,0,1,0x65], nil)
        ]
        for (input, expected) in fixtures {
            let result = input.withUnsafeBufferPointer { VideoAnnexB.lengthPrefixed($0) }
            precondition(result.map { Array($0) } == expected, "Incorrect NAL conversion for \(input)")
        }
        print("Annex B multi-NAL, mixed prefix, emulation-prevention and malformed input checks passed")
    }
}
