#import "MLClient.h"
#import "Moonlight-Swift.h"
#import "HttpManager.h"
#import "HttpRequest.h"
#import "HttpResponse.h"
#import "ServerInfoResponse.h"
#import "AppListResponse.h"
#import "PairManager.h"
#import "CryptoManager.h"
#import "StreamManager.h"

@interface MLClient () <PairCallback>
@property NSOperationQueue *operations;
@property StreamManager *stream;
@end
@implementation MLClient
- (instancetype)init {
    if ((self=[super init])) { _operations=[NSOperationQueue new]; _operations.maxConcurrentOperationCount=1; }
    return self;
}
- (void)emit:(NSString *)name payload:(NSDictionary *)payload {
    dispatch_async(dispatch_get_main_queue(), ^{ if(self.event) self.event(name,payload); });
}
- (BOOL)prepareIdentity {
    @try { [CryptoManager generateKeyPairUsingSSL]; }
    @catch(NSException *exception) { [self emit:@"error" payload:@{@"message":exception.reason?:@"Client identity storage failed"}]; return NO; }
    if(![CryptoManager readCertFromFile] || ![CryptoManager readKeyFromFile] || ![CryptoManager readP12FromFile]) {
        [self emit:@"error" payload:@{@"message":@"Client identity is unavailable"}]; return NO;
    }
    return YES;
}
- (void)inspect:(NSString *)address certificate:(NSData *)certificate {
    [_operations addOperationWithBlock:^{
        if(![self prepareIdentity]) return;
        HttpManager *http=[[HttpManager alloc] initWithAddress:address httpsPort:0 serverCert:certificate];
        ServerInfoResponse *response=[ServerInfoResponse new];
        [http executeRequestSynchronously:[HttpRequest requestForResponse:response withUrlRequest:[http newServerInfoRequest:false]]];
        if(!response.isStatusOk) { [self emit:@"error" payload:@{@"message":response.statusMessage?:@"Host query failed"}]; return; }
        [self emit:@"host" payload:@{@"name":[response getStringTag:@"hostname"]?:address,
            @"paired":@([[response getStringTag:@"PairStatus"] isEqualToString:@"1"] && certificate!=nil),
            @"httpsPort":@([[response getStringTag:@"HttpsPort"] intValue]?:47984),
            @"codecs":@([[response getStringTag:@"ServerCodecModeSupport"] intValue])}];
    }];
}
- (void)pair:(NSString *)address certificate:(NSData *)certificate {
    [_operations addOperationWithBlock:^{
        if(![self prepareIdentity]) return;
        HttpManager *http=[[HttpManager alloc] initWithAddress:address httpsPort:0 serverCert:certificate];
        PairManager *pair=[[PairManager alloc] initWithManager:http clientCert:[CryptoManager readCertFromFile] callback:self];
        [pair main];
    }];
}
- (void)apps:(NSString *)address certificate:(NSData *)certificate {
    [_operations addOperationWithBlock:^{
        HttpManager *http=[[HttpManager alloc] initWithAddress:address httpsPort:0 serverCert:certificate];
        AppListResponse *response=[AppListResponse new];
        [http executeRequestSynchronously:[HttpRequest requestForResponse:response withUrlRequest:[http newAppListRequest]]];
        if(!response.isStatusOk) { [self emit:@"appsError" payload:@{@"message":response.statusMessage?:@"Application list failed"}]; return; }
        NSMutableArray *apps=[NSMutableArray new];
        for(TemporaryApp *app in response.getAppList) [apps addObject:@{@"id":app.id,@"name":app.name}];
        [self emit:@"apps" payload:@{@"apps":apps}];
    }];
}
- (void)start:(StreamConfiguration *)config renderer:(id<AnyVideoDecoderRenderer>)renderer {
    _stream=[[StreamManager alloc] initWithConfig:config rendererProvider:^{return renderer;} connectionCallbacks:self];
    [_operations addOperation:_stream];
}
- (void)stop:(void (^)(void))completion {
    [_operations cancelAllOperations];
    if(_stream) { [_stream stopStreamWithCompletion:completion]; } else { completion(); }
}
- (NSString *)statistics { return [_stream getStatsOverlayText]?:@"Not streaming"; }
+ (void)resetIdentity { [CryptoManager resetIdentity]; }
- (void)startPairing:(NSString *)PIN { [self emit:@"pin" payload:@{@"pin":PIN}]; }
- (void)pairSuccessful:(NSData *)serverCert { [self emit:@"paired" payload:@{@"certificate":serverCert}]; }
- (void)pairFailed:(NSString *)message { [self emit:@"error" payload:@{@"message":message?:@"Pairing failed"}]; }
- (void)alreadyPaired { [self emit:@"alreadyPaired" payload:@{}]; }
- (void)connectionStarted { [self emit:@"started" payload:@{}]; }
- (void)connectionTerminated:(int)errorCode { [self emit:@"terminated" payload:@{@"code":@(errorCode)}]; }
- (void)stageStarting:(const char *)stageName { [self emit:@"stage" payload:@{@"message":stageName?@(stageName):@"Connecting"}]; }
- (void)stageComplete:(const char *)stageName { }
- (void)stageFailed:(const char *)stageName withError:(int)errorCode portTestFlags:(int)portTestFlags {
    [self emit:@"error" payload:@{@"message":[NSString stringWithFormat:@"%s failed (%d), ports: %d",stageName?:"Connection",errorCode,portTestFlags]}];
}
- (void)launchFailed:(NSString *)message { [self emit:@"error" payload:@{@"message":message?:@"Launch failed"}]; }
- (void)connectionStatusUpdate:(int)status { [self emit:@"quality" payload:@{@"status":@(status)}]; }
- (void)setHdrMode:(bool)enabled { [self emit:@"hdr" payload:@{@"enabled":@(enabled)}]; }
- (void)videoContentShown { [self emit:@"video" payload:@{}]; }
- (void)rumble:(unsigned short)number lowFreqMotor:(unsigned short)low highFreqMotor:(unsigned short)high { [self emit:@"rumble" payload:@{@"low":@(low),@"high":@(high)}]; }
- (void)rumbleTriggers:(uint16_t)number leftTrigger:(uint16_t)left rightTrigger:(uint16_t)right { }
- (void)setMotionEventState:(uint16_t)number motionType:(uint8_t)motion reportRateHz:(uint16_t)rate { }
- (void)setControllerLed:(uint16_t)number r:(uint8_t)r g:(uint8_t)g b:(uint8_t)b { }
@end
