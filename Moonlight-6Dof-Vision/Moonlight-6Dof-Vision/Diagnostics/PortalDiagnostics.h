#import <Foundation/Foundation.h>
NS_ASSUME_NONNULL_BEGIN
/// Local, bounded breadcrumbs and system diagnostic payloads. No signal handlers.
@interface PortalDiagnostics : NSObject
+ (instancetype)shared;
- (void)start;
- (void)record:(NSString *)event;
- (nullable NSURL *)exportReport:(NSError **)error;
// Separate directory allows exercising retention/export without touching app logs.
- (instancetype)initWithDirectory:(NSURL *)directory;
@end
NS_ASSUME_NONNULL_END
