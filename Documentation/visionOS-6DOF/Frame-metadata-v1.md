# Source-frame metadata v1

Status: authored for the PC direct output and Swift receiver; decoding after video encoding has not been validated.

For eye content W by H, source output is **2W by (H + 16)**. Left eye occupies x=[0,W), right eye x=[W,2W); scene content y=[0,H). The same tag occupies the bottom 16 rows of each eye. Metadata is outside the scene content and must be cropped from the visible material, never squeezed into H. The content aspect is W/H, not W/(H+16).

| Byte offset | Size | Value |
|---|---|---|
| 0 | 4 | ASCII P6FM |
| 4 | 1 | Version 1 |
| 5 | 1 | Bit 0 = valid; all other bits zero |
| 6 | 2 | Reserved zero |
| 8 | 8 | sessionID |
| 16 | 8 | trackingEpoch |
| 24 | 8 | geometryRevision |
| 32 | 8 | renderFrameID |
| 40 | 4 | IEEE CRC32 over bytes 0 through 39 |

All multibyte integers are unsigned little endian. IEEE CRC uses reflected polynomial 0xedb88320, initial 0xffffffff, final XOR 0xffffffff. The tag is 44 bytes = 352 bits. Serialize each byte most significant bit first. First row carries bits 0..175; second carries 176..351. Each row is eight pixels high, with cell width floor(W/176). Zero is opaque black, one opaque white. Remaining pixels to the right of the 176 cells are black. W must be at least 176; supported source sizes are even.

For bit b, sample each eye at x=floor((b mod 176 + .5) * cellWidth), y=H+4+8*floor(b/176). Decode after cropping each eye region. The right-eye x coordinate adds W. Color conversion/encoding can change exact black and white levels, so the receiver thresholds luma, verifies CRC and requires both tags to be identical. Unknown version/flags, bad CRC, mismatched eye tags, invalid flag, wrong session/epoch/revision, repeated/expired render IDs all keep the portal hidden.

The renderer obtains the tag from the frame paired with the copied source texture. It never stamps the receiver's newest state onto an older image. UDP status is telemetry; it is not proof that a video frame has a given geometry.

Default: W=1280,H=720 => 2560x736 capture at requested 60 fps. Bench: W=1920,H=1080 => 3840x1096 capture at requested 60 fps. A 3840x1080 display is only the unmodified masked-VR baseline; it cannot carry 1080 content rows plus this strip without changing dimensions or cropping content.

Portable helper: PortalCore/include/portal/FrameMetadata.hpp. No image readback is required: CPU-generated strip pixels are uploaded and copied on the GPU. Optional capture diagnostics deliberately generate a synthetic source image on the CPU, then use the same output GPU path; this mode is not suitable for performance measurements.
