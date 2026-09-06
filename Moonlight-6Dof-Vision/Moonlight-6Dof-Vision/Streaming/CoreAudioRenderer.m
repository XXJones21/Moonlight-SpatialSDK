#import "CoreAudioRenderer.h"
#import <os/lock.h>
#import <AudioToolbox/AudioToolbox.h>
#import "PortalAudioFormat.h"
#import "../Diagnostics/PortalDiagnostics.h"

/// Opus callback -> bounded PCM buffers -> AVAudioEngine. All engine mutations use one queue.
@implementation CoreAudioRenderer {
    AVAudioEngine *_engine;
    AVAudioPlayerNode *_player;
    AVAudioEnvironmentNode *_environment;
    AVAudioFormat *_format;
    NSMutableData *_decodeBuffer;
    dispatch_queue_t _queue;
    os_unfair_lock _lock;
    NSUInteger _queued;
    uint64_t _generation;
    uint64_t _receivedBuffers, _queueDrops, _decodeErrors;
    float _pcmPeak;
    BOOL _running, _spatial, _interrupted;
    BOOL _wantsPlayback; // Access only on the audio queue.
    NSString *_sceneIdentifier;
    id _interruptionObserver, _routeObserver;
    int _channels, _sampleRate;
}
static __weak CoreAudioRenderer *current;
static NSString *currentSceneIdentifier;
- (instancetype)initWithConfig:(const OPUS_MULTISTREAM_CONFIGURATION *)config {
    if (!(self=[super init])) return nil;
    _channels=config->channelCount; _sampleRate=config->sampleRate;
    if(_channels<1 || _channels>8 || _sampleRate<=0) return nil;
    _lock=OS_UNFAIR_LOCK_INIT;
    [PortalDiagnostics.shared record:[NSString stringWithFormat:@"Audio init: channels=%d rate=%d", _channels, _sampleRate]];
    _decodeBuffer=[NSMutableData dataWithLength:5760*_channels*sizeof(float)];
    _queue=dispatch_queue_create("portal.audio",DISPATCH_QUEUE_SERIAL);
    _engine=[AVAudioEngine new]; _player=[AVAudioPlayerNode new]; _environment=[AVAudioEnvironmentNode new];
    [_engine attachNode:_player]; [_engine attachNode:_environment];
    @synchronized(CoreAudioRenderer.class) { current=self; _sceneIdentifier=[currentSceneIdentifier copy]; }
    __weak CoreAudioRenderer *weakSelf=self;
    _interruptionObserver=[NSNotificationCenter.defaultCenter addObserverForName:AVAudioSessionInterruptionNotification object:nil queue:nil usingBlock:^(NSNotification *note) {
        CoreAudioRenderer *strongSelf=weakSelf; if(!strongSelf) return;
        BOOL began=[note.userInfo[AVAudioSessionInterruptionTypeKey] unsignedIntegerValue]==AVAudioSessionInterruptionTypeBegan;
        dispatch_async(strongSelf->_queue, ^{
            os_unfair_lock_lock(&strongSelf->_lock);
            strongSelf->_interrupted=began; ++strongSelf->_generation; strongSelf->_queued=0;
            BOOL running=strongSelf->_wantsPlayback;
            os_unfair_lock_unlock(&strongSelf->_lock);
            [PortalDiagnostics.shared record:began ? @"Audio interruption began" : @"Audio interruption ended"];
            if(began) { [strongSelf->_player stop]; [strongSelf->_engine pause]; }
            else if(running) { [strongSelf activateAndConfigure]; }
        });
    }];
    _routeObserver=[NSNotificationCenter.defaultCenter addObserverForName:AVAudioSessionRouteChangeNotification object:nil queue:nil usingBlock:^(NSNotification *note) {
        CoreAudioRenderer *strongSelf=weakSelf; if(!strongSelf) return;
        dispatch_async(strongSelf->_queue, ^{
            os_unfair_lock_lock(&strongSelf->_lock);
            BOOL running=strongSelf->_running && !strongSelf->_interrupted;
            ++strongSelf->_generation; strongSelf->_queued=0;
            os_unfair_lock_unlock(&strongSelf->_lock);
            [PortalDiagnostics.shared record:@"Audio route changed"];
            if(running) [strongSelf configureGraph];
        });
    }];
    return self;
}
- (void)dealloc {
    if(_interruptionObserver) [NSNotificationCenter.defaultCenter removeObserver:_interruptionObserver];
    if(_routeObserver) [NSNotificationCenter.defaultCenter removeObserver:_routeObserver];
}
- (void)configureGraph {
    os_unfair_lock_lock(&_lock); _running=NO; ++_generation; _queued=0; os_unfair_lock_unlock(&_lock);
    [PortalDiagnostics.shared record:[NSString stringWithFormat:@"Audio graph begin: channels=%d spatial=%d", _channels, _spatial]];
    [_player stop]; [_engine stop]; [_engine disconnectNodeOutput:_player]; [_engine disconnectNodeOutput:_environment];
    _format=PortalAudioFormat(_sampleRate, _spatial ? 1 : _channels);
    if (!_format) { [PortalDiagnostics.shared record:@"Audio graph rejected: invalid PCM format"]; return; }
    if(_spatial) {
        _player.renderingAlgorithm=AVAudio3DMixingRenderingAlgorithmHRTF;
        [_engine connect:_player to:_environment format:_format];
        [_engine connect:_environment to:_engine.mainMixerNode format:nil];
        _environment.distanceAttenuationParameters.distanceAttenuationModel=AVAudioEnvironmentDistanceAttenuationModelInverse;
        _environment.distanceAttenuationParameters.referenceDistance=1;
    } else { [_engine connect:_player to:_engine.mainMixerNode format:_format]; }
    _engine.outputNode.intendedSpatialExperience = [[CAHeadTrackedSpatialAudio alloc]
        initWithSoundStageSize:CASoundStageSizeAutomatic
        anchoringStrategy:[[CASceneAnchoringStrategy alloc] initWithSceneIdentifier:_sceneIdentifier]];
    NSError *error=nil;
    if(![_engine startAndReturnError:&error]) {
        os_unfair_lock_lock(&_lock); _running=NO; ++_generation; _queued=0; os_unfair_lock_unlock(&_lock);
        [PortalDiagnostics.shared record:[NSString stringWithFormat:@"Audio engine failed: %@ (%ld)", error.domain, (long)error.code]]; return;
    }
    [_player play];
    os_unfair_lock_lock(&_lock); _running=YES; os_unfair_lock_unlock(&_lock);
    [PortalDiagnostics.shared record:@"Audio graph ready; queue completion=consumed"];
}
- (void)activateAndConfigure {
    NSError *error=nil;
    AVAudioSession *session=AVAudioSession.sharedInstance;
    if (!_sceneIdentifier.length) {
        [PortalDiagnostics.shared record:@"Audio waiting for immersive panel scene association"];
        return;
    }
    NSDictionary *spatialOptions = @{
        AVAudioSessionSpatialExperienceOptionAnchoringStrategy: @(AVAudioSessionAnchoringStrategyScene),
        AVAudioSessionSpatialExperienceOptionSceneIdentifier: _sceneIdentifier
    };
    if (![session setIntendedSpatialExperience:AVAudioSessionSpatialExperienceHeadTracked options:spatialOptions error:&error]) {
        [PortalDiagnostics.shared record:[NSString stringWithFormat:@"Audio scene association failed: %@ (%ld)", error.domain, (long)error.code]];
        return;
    }
    [PortalDiagnostics.shared record:@"Audio associated with immersive panel scene"];
    if (![session setCategory:AVAudioSessionCategoryPlayback mode:AVAudioSessionModeDefault options:AVAudioSessionCategoryOptionMixWithOthers error:&error] ||
        ![session setActive:YES error:&error]) {
        os_unfair_lock_lock(&_lock); _running=NO; ++_generation; _queued=0; os_unfair_lock_unlock(&_lock);
        [PortalDiagnostics.shared record:[NSString stringWithFormat:@"Audio session activation failed: %@ (%ld)", error.domain, (long)error.code]];
        return;
    }
    [self configureGraph];
}
- (void)start {
    [PortalDiagnostics.shared record:@"Audio start requested"];
    dispatch_sync(_queue, ^{ self->_wantsPlayback=YES; [self activateAndConfigure]; });
}
- (void)stop {
    [PortalDiagnostics.shared record:[NSString stringWithFormat:@"Audio stop requested; %@", [self getAudioStatsString]]];
    os_unfair_lock_lock(&_lock); _running=NO; ++_generation; _queued=0; os_unfair_lock_unlock(&_lock);
    dispatch_sync(_queue, ^{
        self->_wantsPlayback=NO;
        // A graph rebuild already on this queue may have completed after the
        // early invalidation above. Keep teardown final for queued observers.
        os_unfair_lock_lock(&self->_lock); self->_running=NO; ++self->_generation; self->_queued=0; os_unfair_lock_unlock(&self->_lock);
        [self->_player stop]; [self->_engine stop];
    });
}
- (void *)getAudioBuffer:(int *)size { *size=(int)_decodeBuffer.length; return _decodeBuffer.mutableBytes; }
- (BOOL)submitAudio:(int)bytesWritten opusBytes:(int)opusBytes decodeStartTime:(CFTimeInterval)decodeStartTime {
    if(bytesWritten<=0 || bytesWritten>_decodeBuffer.length || bytesWritten%(_channels*sizeof(float))) return NO;
    os_unfair_lock_lock(&_lock);
    ++_receivedBuffers;
    if(!_running || _interrupted) { os_unfair_lock_unlock(&_lock); return NO; }
    if (_queued>=6) { ++_queueDrops; os_unfair_lock_unlock(&_lock); return NO; }
    uint64_t generation=_generation; ++_queued;
    os_unfair_lock_unlock(&_lock);
    NSData *pcm=[NSData dataWithBytes:_decodeBuffer.bytes length:bytesWritten];
    float peak = 0;
    const float *samples = pcm.bytes;
    for (NSUInteger i=0; i<pcm.length/sizeof(float); ++i) peak = fmaxf(peak, fabsf(samples[i]));
    os_unfair_lock_lock(&_lock); _pcmPeak = fmaxf(_pcmPeak, peak); os_unfair_lock_unlock(&_lock);
    dispatch_async(_queue, ^{
        os_unfair_lock_lock(&self->_lock);
        BOOL accepted=self->_running && !self->_interrupted && generation==self->_generation;
        os_unfair_lock_unlock(&self->_lock);
        if(!accepted) return;
        if (!self->_format) {
            os_unfair_lock_lock(&self->_lock); self->_running=NO; ++self->_generation; self->_queued=0; os_unfair_lock_unlock(&self->_lock);
            [PortalDiagnostics.shared record:@"Audio submission rejected: missing PCM format"]; return;
        }
        AVAudioFrameCount frames=(AVAudioFrameCount)(pcm.length/(self->_channels*sizeof(float)));
        AVAudioPCMBuffer *buffer=[[AVAudioPCMBuffer alloc] initWithPCMFormat:self->_format frameCapacity:frames];
        if (!buffer || !buffer.floatChannelData) {
            os_unfair_lock_lock(&self->_lock); if (self->_queued) --self->_queued; os_unfair_lock_unlock(&self->_lock);
            [PortalDiagnostics.shared record:@"Audio buffer allocation failed"]; return;
        }
        buffer.frameLength=frames;
        const float *source=pcm.bytes;
        for(AVAudioFrameCount frame=0;frame<frames;++frame) {
            if(self->_spatial) {
                float mixed=0;
                for(int channel=0;channel<self->_channels;++channel) mixed+=source[frame*self->_channels+channel];
                buffer.floatChannelData[0][frame]=mixed/self->_channels;
            } else {
                for(int channel=0;channel<self->_channels;++channel) buffer.floatChannelData[channel][frame]=source[frame*self->_channels+channel];
            }
        }
        // Slots represent data waiting for consumption, not device output latency.
        // Waiting for PlayedBack can exhaust six short buffers while the device
        // still has already-consumed audio in flight, dropping incoming packets.
        [self->_player scheduleBuffer:buffer completionCallbackType:AVAudioPlayerNodeCompletionDataConsumed completionHandler:^(AVAudioPlayerNodeCompletionCallbackType type) {
            os_unfair_lock_lock(&self->_lock);
            if(generation==self->_generation && self->_queued) --self->_queued;
            os_unfair_lock_unlock(&self->_lock);
        }];
    });
    return YES;
}
- (void)recordDecodeFailure:(int)code {
    os_unfair_lock_lock(&_lock); BOOL first = _decodeErrors++ == 0; os_unfair_lock_unlock(&_lock);
    if (first) [PortalDiagnostics.shared record:[NSString stringWithFormat:@"Opus decode failed: %d", code]];
}
- (NSString *)getAudioStatsString {
    os_unfair_lock_lock(&_lock);
    NSString *stats = [NSString stringWithFormat:@"Audio: %d ch, %d Hz; queued %lu/6; buffers %llu; queue drops %llu; decode errors %llu; PCM peak %.3f",
        _channels, _sampleRate, (unsigned long)_queued, (unsigned long long)_receivedBuffers,
        (unsigned long long)_queueDrops, (unsigned long long)_decodeErrors, _pcmPeak];
    os_unfair_lock_unlock(&_lock);
    return stats;
}
+ (void)setSceneIdentifier:(NSString *)identifier {
    CoreAudioRenderer *renderer;
    @synchronized(self) { currentSceneIdentifier=[identifier copy]; renderer=current; }
    if (!renderer) return;
    dispatch_async(renderer->_queue, ^{
        if ([renderer->_sceneIdentifier isEqualToString:identifier]) return;
        renderer->_sceneIdentifier=[identifier copy];
        if (renderer->_wantsPlayback && !renderer->_interrupted) [renderer activateAndConfigure];
    });
}
+ (NSString *)currentStats {
    CoreAudioRenderer *renderer;
    @synchronized(self) { renderer=current; }
    return renderer ? [renderer getAudioStatsString] : @"Audio: inactive";
}
+ (void)updatePortal:(simd_float4x4)portal head:(simd_float4x4)head spatial:(BOOL)spatial {
    CoreAudioRenderer *renderer;
    @synchronized(self) { renderer=current; }
    if(!renderer) return;
    dispatch_async(renderer->_queue, ^{
        if(renderer->_spatial!=spatial) {
            renderer->_spatial=spatial;
            os_unfair_lock_lock(&renderer->_lock); ++renderer->_generation; renderer->_queued=0; BOOL running=renderer->_running; os_unfair_lock_unlock(&renderer->_lock);
            if(running) [renderer configureGraph];
        }
        simd_float4 p=portal.columns[3], h=head.columns[3];
        renderer->_player.position=AVAudioMake3DPoint(p.x,p.y,p.z);
        renderer->_environment.listenerPosition=AVAudioMake3DPoint(h.x,h.y,h.z);
        simd_float4 forward=-head.columns[2], up=head.columns[1];
        AVAudio3DVectorOrientation orientation={{forward.x,forward.y,forward.z},{up.x,up.y,up.z}};
        renderer->_environment.listenerVectorOrientation=orientation;
    });
}
@end
