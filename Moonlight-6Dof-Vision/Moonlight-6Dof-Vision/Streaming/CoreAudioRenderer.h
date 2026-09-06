#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <simd/simd.h>
#include "Limelight.h"
@interface CoreAudioRenderer : NSObject
- (instancetype)initWithConfig:(const OPUS_MULTISTREAM_CONFIGURATION *)config;
- (void)start;
- (void)stop;
- (void *)getAudioBuffer:(int *)size;
- (BOOL)submitAudio:(int)bytesWritten opusBytes:(int)opusBytes decodeStartTime:(CFTimeInterval)decodeStartTime;
- (NSString *)getAudioStatsString;
+ (void)updatePortal:(simd_float4x4)portal head:(simd_float4x4)head spatial:(BOOL)spatial;
@end
