#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Hands decoded PCM from the Moonlight audio callback to RealityKit audio generators.
///
/// One writer (the Opus decode path in CoreAudioRenderer) and any number of readers
/// (one per generator render block). Readers never take a lock: each keeps its own
/// cursor and aligns it to a shared anchor by host time, so separate generators that
/// start in different render cycles still read the same sample at the same moment.
///
/// Samples are 48 kHz Float32 in moonlight-common-c order: FL FR C LFE BL BR SL SR.
@interface SurroundFeed : NSObject

+ (instancetype)shared;

/// YES while RealityKit generators own playback. CoreAudioRenderer keeps filling the
/// feed either way, and skips its own AVAudioEngine output while this is set.
@property (atomic) BOOL active;

/// Channels of the stream currently feeding the ring; 0 when no stream is running.
@property (atomic, readonly) int channels;

/// Lip-sync trim: extra audio delay in milliseconds, 0...300.
@property (atomic) int delayMilliseconds;

/// -1 normally. Otherwise the source channel that plays a test tone while all others are silent.
@property (atomic) int testChannel;

/// Render cycles that found no decoded audio where some was expected.
@property (atomic, readonly) uint64_t underruns;

/// Called by the audio renderer when a stream starts (channel count) and stops (0).
- (void)configureWithChannels:(int)channels;

/// Called by the audio renderer for every decoded packet.
- (void)writeInterleaved:(const float *)samples frames:(int)frames channels:(int)channels;

/// Drops the current alignment so every reader lines up again on its next cycle.
- (void)resync;

/// A render block for one generator. `map[i]` is the source channel played on the
/// generator's output channel `i`; -1 plays silence. `lowPass` applies a gentle
/// 120 Hz low-pass, for the low-frequency channel.
- (AVAudioSourceNodeRenderBlock)blockWithChannelMap:(NSArray<NSNumber *> *)map lowPass:(BOOL)lowPass
    NS_SWIFT_NAME(block(channelMap:lowPass:));

@end

NS_ASSUME_NONNULL_END
