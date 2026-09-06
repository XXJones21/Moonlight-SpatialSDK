#import <AVFoundation/AVFoundation.h>
static inline AVAudioFormat *PortalAudioFormat(double sampleRate, AVAudioChannelCount channels) {
    if (!isfinite(sampleRate) || sampleRate <= 0) return nil;
    AudioChannelLayoutTag tag;
    // moonlight-common-c produces FL FR C LFE RL RR SL SR (WAVE order).
    switch (channels) {
        case 1: tag = kAudioChannelLayoutTag_Mono; break;
        case 2: tag = kAudioChannelLayoutTag_Stereo; break;
        case 6: tag = kAudioChannelLayoutTag_WAVE_5_1_A; break;
        case 8: tag = kAudioChannelLayoutTag_WAVE_7_1; break;
        default: return nil;
    }
    AVAudioChannelLayout *layout = [[AVAudioChannelLayout alloc] initWithLayoutTag:tag];
    return [[AVAudioFormat alloc] initStandardFormatWithSampleRate:sampleRate channelLayout:layout];
}
