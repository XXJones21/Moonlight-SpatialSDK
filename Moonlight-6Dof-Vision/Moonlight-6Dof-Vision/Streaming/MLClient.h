#import <Foundation/Foundation.h>
#import "AnyVideoDecoderRenderer.h"
@class StreamConfiguration;
NS_ASSUME_NONNULL_BEGIN
typedef void (^MLEvent)(NSString *name, NSDictionary *payload);
@interface MLClient : NSObject <ConnectionCallbacks>
@property(nonatomic,copy) MLEvent event;
- (void)inspect:(NSString *)address certificate:(nullable NSData *)certificate NS_SWIFT_NAME(inspect(address:certificate:));
- (void)pair:(NSString *)address certificate:(nullable NSData *)certificate NS_SWIFT_NAME(pair(address:certificate:));
- (void)apps:(NSString *)address certificate:(NSData *)certificate NS_SWIFT_NAME(apps(address:certificate:));
- (void)start:(StreamConfiguration *)config renderer:(id<AnyVideoDecoderRenderer>)renderer NS_SWIFT_NAME(start(config:renderer:));
- (void)stop:(void (^)(void))completion NS_SWIFT_NAME(stop(completion:));
- (NSString *)statistics;
+ (void)resetIdentity;
@end
NS_ASSUME_NONNULL_END
