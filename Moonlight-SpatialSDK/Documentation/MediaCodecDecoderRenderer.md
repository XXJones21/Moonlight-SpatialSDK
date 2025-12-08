# MediaCodecDecoderRenderer – Data Flow and Requirements (Quest 3 build)

This note summarizes how the renderer is wired, what it expects as inputs, and where it currently logs. It is based on `app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java` and recent logs (`logging-java-decoder.log`).

## Lifecycle and Threads

- **Renderer construction**: Decoders are discovered (`findAvcDecoder`, `findHevcDecoder`, `findAv1Decoder`) and capability flags are set (direct submit, reference frame invalidation, slices).
- **setup(format,width,height,fps)** → **initializeDecoder()**: runs on the caller thread (MoonBridge bridgeDrSetup). Selects mime/decoder, configures `MediaFormat`, creates `MediaCodec`, calls `configureAndStartDecoder()`. Logs seen: `setup ENTER...`, `initializeDecoder ENTER...`, `configureAndStartDecoder...`.
- **start()**: spins two threads:
  - Renderer thread: `dequeueOutputBuffer` loop, posts buffers or renders immediately.
  - Choreographer thread (if frame pacing balanced): drains queued output buffers on vsync.
- **submitDecodeUnit(...)**: called from MoonBridge JNI for every decode unit.

## Expected Decode-Unit Path (per code)

1) `submitDecodeUnit` logs the DU (`type/frame/frameType`).
2) CSD handling on IDR: SPS/PPS/VPS batched, `queueNextInputBuffer(... CODEC_CONFIG)`.
3) For every DU: `submitDecodeUnit: calling fetchNextInputBuffer` → `fetchNextInputBuffer` should log begin/dequeued index (and queueInputBuffer count), then `queueNextInputBuffer` should log rendered count downstream.
4) Renderer thread should log `dequeueOutputBuffer: rendered=...`.

## Inputs/Prerequisites

- **Surface**: must be set via `setRenderTarget()` before `setup()`. `renderTarget` is checked in `configureAndStartDecoder`; null throws.
- **Format params**: width/height/fps and `videoFormat` (H264/HEVC/AV1) are passed via `setup()` and stored in `initialWidth/initialHeight/refreshRate/videoFormat`.
- **CSD**: SPS/PPS/VPS are expected on IDR frames; CSD is resubmitted on reconfigure.
- **Frame pacing**: balanced mode uses output queue + Choreographer; other modes render immediately.
- **Prefs-driven options**: color space/range (guarded off for QTI), slices-per-frame, fused IDR, reference-frame invalidation.

## What the recent logs show

- We see `initializeDecoder ENTER ... renderTarget=set` (so decoder configuration is invoked).
- We see `bridgeDrSubmitDecodeUnit` and `submitDecodeUnit` for frames.
- We do **not** see `fetchNextInputBuffer` / `queueInputBuffer` / `dequeueOutputBuffer`, meaning MediaCodec input/output paths are not executing on-device despite setup being called.

## Next minimal verification hooks (if needed)

- Confirm `configureAndStartDecoder` logs appear on-device (renderTarget set, configure called/completed). They are present in past runs; re-verify in the current build.
- If configure logs appear but fetch/queue/dequeue do not, the input thread is not progressing after setup—investigate thread startup (`start()`), and whether `start()` is called after `setup()` in this session.

## Inputs the renderer still needs at runtime

- A valid SurfaceHolder (`setRenderTarget`) before `setup`.
- Correct `videoFormat` flag matching the negotiated stream (H264/H265/AV1).
- Matching width/height/fps (if they change, reconfigure will be skipped only when compatible).
- CSD on IDR frames (SPS/PPS/VPS) to seed the codec.

