#import <AVFoundation/AVFoundation.h>
#import "PortalAudioFormat.h"
int main(void) {
    @autoreleasepool {
        for (NSNumber *count in @[@1, @2, @6, @8]) {
            AVAudioFormat *format = PortalAudioFormat(48000, count.unsignedIntValue);
            NSCAssert(format != nil, @"Supported channel count %@ must create a format", count);
            NSCAssert(format.channelCount == count.unsignedIntValue, @"Preserve all channels");
            AVAudioPCMBuffer *buffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:240];
            NSCAssert(buffer.floatChannelData != NULL, @"Must provide writable float planes");
            buffer.frameLength = 240;
            for (unsigned int channel = 0; channel < format.channelCount; ++channel) buffer.floatChannelData[channel][239] = channel;
        }
        AVAudioFormat *oldStereo = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:48000 channels:2];
        AVAudioFormat *newStereo = PortalAudioFormat(48000, 2);
        NSCAssert([oldStereo isEqual:newStereo], @"Explicit stereo layout must match the original format");
        NSCAssert(newStereo.commonFormat == AVAudioPCMFormatFloat32 && !newStereo.interleaved,
                  @"Opus float output requires separate Float32 channel planes");
        NSCAssert(PortalAudioFormat(0, 2) == nil, @"Reject invalid rate");
        NSCAssert(PortalAudioFormat(48000, 3) == nil, @"Reject unsupported channel layout");
        NSLog(@"Audio format tests passed");
    }
}
