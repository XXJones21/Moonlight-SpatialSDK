#import "SurroundFeed.h"
#import <stdatomic.h>
#import <mach/mach_time.h>
#import <objc/runtime.h>

// Ring geometry. Capacity is a power of two so wrapping is a mask, and is long enough
// (about 1.36 s) to hold the base lead plus the largest lip-sync trim with room to spare.
#define SF_CAPACITY 65536
#define SF_MASK (SF_CAPACITY - 1)
#define SF_MAX_CHANNELS 8
#define SF_SAMPLE_RATE 48000

// Audio the readers keep in hand before any lip-sync trim. Network audio arrives in
// packets with jitter; 40 ms absorbs that without an audible delay against the picture.
#define SF_BASE_LEAD_FRAMES 1920

static float *gRing;                       // SF_CAPACITY * SF_MAX_CHANNELS, interleaved
static _Atomic int64_t gWrite;             // total frames written since configure
static _Atomic int gChannels;
static _Atomic int gDelayMs;
static _Atomic int gTestChannel = -1;
static _Atomic uint64_t gUnderruns;

// Alignment. `gEpoch` changes whenever readers must line up again. The first reader to
// notice claims the anchor, records "this host time corresponds to this ring frame",
// and publishes it; every reader then derives its cursor from that one pair.
static _Atomic uint64_t gEpoch = 1;
static _Atomic uint64_t gAnchorClaim;
static _Atomic uint64_t gAnchorEpoch;
static _Atomic uint64_t gAnchorHost;
static _Atomic int64_t gAnchorFrame;

static mach_timebase_info_data_t gTimebase;

typedef struct {
    uint64_t epoch;
    int64_t cursor;
    int starved;
    int outputs;
    int map[SF_MAX_CHANNELS];
    float lowPassState[SF_MAX_CHANNELS];
    bool lowPass;
    double tonePhase;
} SFReader;

static inline int64_t SFHostTicksToFrames(uint64_t ticks) {
    // ticks -> nanoseconds -> frames. Done in double: the spans are well under an hour.
    double nanos = (double)ticks * (double)gTimebase.numer / (double)gTimebase.denom;
    return (int64_t)llround(nanos * (double)SF_SAMPLE_RATE / 1e9);
}

static inline int64_t SFLeadFrames(void) {
    int delay = atomic_load_explicit(&gDelayMs, memory_order_relaxed);
    if (delay < 0) delay = 0;
    if (delay > 300) delay = 300;
    return SF_BASE_LEAD_FRAMES + (int64_t)delay * SF_SAMPLE_RATE / 1000;
}

/// Bumps the epoch once, even if several readers ask in the same cycle.
static inline void SFRequestResync(uint64_t seenEpoch) {
    uint64_t expected = seenEpoch;
    atomic_compare_exchange_strong(&gEpoch, &expected, seenEpoch + 1);
}

/// Returns false when another reader is publishing the anchor right now; the caller
/// plays silence for this cycle and picks the anchor up on the next one.
static inline bool SFEnsureAnchor(uint64_t epoch, const AudioTimeStamp *timestamp) {
    if (atomic_load(&gAnchorEpoch) == epoch) return true;
    uint64_t claimed = atomic_load(&gAnchorClaim);
    if (claimed == epoch) return false;
    if (!atomic_compare_exchange_strong(&gAnchorClaim, &claimed, epoch)) return false;
    bool hostValid = (timestamp->mFlags & kAudioTimeStampHostTimeValid) != 0;
    atomic_store(&gAnchorHost, hostValid ? timestamp->mHostTime : mach_absolute_time());
    atomic_store(&gAnchorFrame, atomic_load(&gWrite) - SFLeadFrames());
    atomic_store(&gAnchorEpoch, epoch);
    return true;
}

static OSStatus SFRender(SFReader *reader, BOOL *isSilence, const AudioTimeStamp *timestamp,
                         AVAudioFrameCount frameCount, AudioBufferList *outputData) {
    const int64_t frames = (int64_t)frameCount;
    const uint64_t epoch = atomic_load(&gEpoch);
    bool silent = !SFEnsureAnchor(epoch, timestamp);

    if (!silent && reader->epoch != epoch) {
        // Line this reader up with the shared anchor. Host time is common to every
        // render thread, so generators that start in different cycles still agree.
        int64_t elapsed = 0;
        uint64_t anchorHost = atomic_load(&gAnchorHost);
        if ((timestamp->mFlags & kAudioTimeStampHostTimeValid) && timestamp->mHostTime >= anchorHost) {
            elapsed = SFHostTicksToFrames(timestamp->mHostTime - anchorHost);
        }
        reader->cursor = atomic_load(&gAnchorFrame) + elapsed;
        reader->epoch = epoch;
        reader->starved = 0;
    }

    const int64_t written = atomic_load(&gWrite);
    const int64_t oldest = written - SF_CAPACITY;
    const int channels = atomic_load_explicit(&gChannels, memory_order_relaxed);
    const int testChannel = atomic_load_explicit(&gTestChannel, memory_order_relaxed);

    int output = 0;
    for (UInt32 b = 0; b < outputData->mNumberBuffers; ++b) {
        AudioBuffer *buffer = &outputData->mBuffers[b];
        float *destination = (float *)buffer->mData;
        const int stride = buffer->mNumberChannels > 0 ? (int)buffer->mNumberChannels : 1;
        if (!destination) { output += stride; continue; }
        for (int c = 0; c < stride; ++c, ++output) {
            const int source = output < reader->outputs ? reader->map[output] : -1;
            if (silent || source < 0 || source >= channels) {
                for (int64_t i = 0; i < frames; ++i) destination[i * stride + c] = 0;
                continue;
            }
            if (testChannel >= 0) {
                // Speaker check: one source channel plays a tone, the rest stay quiet.
                double phase = reader->tonePhase;
                const double step = 2.0 * M_PI * 660.0 / (double)SF_SAMPLE_RATE;
                for (int64_t i = 0; i < frames; ++i) {
                    destination[i * stride + c] = source == testChannel ? (float)(sin(phase) * 0.2) : 0;
                    phase += step;
                }
                continue;
            }
            float state = reader->lowPassState[output];
            for (int64_t i = 0; i < frames; ++i) {
                const int64_t frame = reader->cursor + i;
                float sample = 0;
                if (frame >= 0 && frame >= oldest && frame < written) {
                    sample = gRing[(frame & SF_MASK) * SF_MAX_CHANNELS + source];
                }
                if (reader->lowPass) { state += 0.0156f * (sample - state); sample = state; }
                destination[i * stride + c] = sample;
            }
            reader->lowPassState[output] = state;
        }
    }
    if (testChannel >= 0) {
        reader->tonePhase = fmod(reader->tonePhase + 2.0 * M_PI * 660.0 / (double)SF_SAMPLE_RATE * (double)frames, 2.0 * M_PI);
    }
    if (isSilence) *isSilence = silent;
    if (silent) return noErr;

    // The cursor always advances at the render rate, so every reader stays in step
    // whether or not audio was available. Late packets are heard as a short gap, and
    // the lead recovers on its own once the writer catches up.
    reader->cursor += frames;
    const int64_t lead = written - reader->cursor;
    const int64_t target = SFLeadFrames();
    if (channels > 0 && lead < 0) atomic_fetch_add_explicit(&gUnderruns, 1, memory_order_relaxed);
    if (lead < 240) reader->starved += 1; else reader->starved = 0;
    // Sender and headset clocks drift apart slowly. Line up again when the lead has
    // been nearly empty for about half a second, or has grown well past its target.
    if (channels > 0 && (reader->starved > 50 || lead > target + 4800)) SFRequestResync(epoch);
    return noErr;
}

@implementation SurroundFeed {
    BOOL _active;
}

+ (instancetype)shared {
    static SurroundFeed *feed;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        mach_timebase_info(&gTimebase);
        gRing = calloc((size_t)SF_CAPACITY * SF_MAX_CHANNELS, sizeof(float));
        feed = [SurroundFeed new];
    });
    return feed;
}

- (BOOL)active { @synchronized (self) { return _active; } }
- (void)setActive:(BOOL)active {
    @synchronized (self) { if (_active == active) return; _active = active; }
    [self resync];
}

- (int)channels { return atomic_load(&gChannels); }
- (uint64_t)underruns { return atomic_load(&gUnderruns); }

- (int)delayMilliseconds { return atomic_load(&gDelayMs); }
- (void)setDelayMilliseconds:(int)delayMilliseconds {
    int clamped = MAX(0, MIN(300, delayMilliseconds));
    if (atomic_exchange(&gDelayMs, clamped) != clamped) [self resync];
}

- (int)testChannel { return atomic_load(&gTestChannel); }
- (void)setTestChannel:(int)testChannel { atomic_store(&gTestChannel, testChannel); }

- (void)configureWithChannels:(int)channels {
    atomic_store(&gChannels, MAX(0, MIN(SF_MAX_CHANNELS, channels)));
    atomic_store(&gUnderruns, 0);
    [self resync];
}

- (void)writeInterleaved:(const float *)samples frames:(int)frames channels:(int)channels {
    if (!gRing || !samples || frames <= 0 || channels <= 0 || channels > SF_MAX_CHANNELS) return;
    if (frames > SF_CAPACITY) { samples += (size_t)(frames - SF_CAPACITY) * channels; frames = SF_CAPACITY; }
    const int64_t start = atomic_load(&gWrite);
    for (int i = 0; i < frames; ++i) {
        float *slot = &gRing[((start + i) & SF_MASK) * SF_MAX_CHANNELS];
        const float *frame = &samples[(size_t)i * channels];
        for (int c = 0; c < channels; ++c) slot[c] = frame[c];
        for (int c = channels; c < SF_MAX_CHANNELS; ++c) slot[c] = 0;
    }
    // Published last: readers never look past the write position.
    atomic_store(&gWrite, start + frames);
}

- (void)resync { atomic_fetch_add(&gEpoch, 1); }

- (AVAudioSourceNodeRenderBlock)blockWithChannelMap:(NSArray<NSNumber *> *)map lowPass:(BOOL)lowPass {
    // The reader lives in an NSMutableData tied to the block's lifetime, so the render
    // thread only ever touches a raw pointer and never retains or releases an object.
    NSMutableData *storage = [NSMutableData dataWithLength:sizeof(SFReader)];
    SFReader *reader = storage.mutableBytes;
    reader->outputs = (int)MIN((NSUInteger)SF_MAX_CHANNELS, map.count);
    for (int i = 0; i < SF_MAX_CHANNELS; ++i) reader->map[i] = i < reader->outputs ? map[i].intValue : -1;
    reader->lowPass = lowPass;
    AVAudioSourceNodeRenderBlock block = ^OSStatus(BOOL *isSilence, const AudioTimeStamp *timestamp,
                                                   AVAudioFrameCount frameCount, AudioBufferList *outputData) {
        return SFRender(reader, isSilence, timestamp, frameCount, outputData);
    };
    block = [block copy];
    static char storageKey;
    objc_setAssociatedObject(block, &storageKey, storage, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    return block;
}

@end
