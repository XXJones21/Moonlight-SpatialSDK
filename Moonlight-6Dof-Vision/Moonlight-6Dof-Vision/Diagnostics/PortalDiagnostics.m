#import "PortalDiagnostics.h"
#import <MetricKit/MetricKit.h>
#import <os/log.h>

@interface PortalDiagnostics () <MXMetricManagerSubscriber>
@end
@implementation PortalDiagnostics {
    NSURL *_directory;
    NSLock *_lock;
    BOOL _started;
}
+ (instancetype)shared {
    static PortalDiagnostics *instance;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSURL *support = [NSFileManager.defaultManager URLsForDirectory:NSApplicationSupportDirectory inDomains:NSUserDomainMask].firstObject;
        instance = [[self alloc] initWithDirectory:[support URLByAppendingPathComponent:@"Diagnostics" isDirectory:YES]];
    });
    return instance;
}
- (instancetype)initWithDirectory:(NSURL *)directory {
    if ((self = [super init])) { _directory = directory; _lock = [NSLock new]; }
    return self;
}
- (void)start {
    [_lock lock]; BOOL started = _started; _started = YES; [_lock unlock];
    if (started) return;
    [self record:[NSString stringWithFormat:@"App launch %@; version %@ (%@); %@; pid %d", NSUUID.UUID.UUIDString,
        [NSBundle.mainBundle objectForInfoDictionaryKey:@"CFBundleShortVersionString"] ?: @"?",
        [NSBundle.mainBundle objectForInfoDictionaryKey:@"CFBundleVersion"] ?: @"?",
        NSProcessInfo.processInfo.operatingSystemVersionString, NSProcessInfo.processInfo.processIdentifier]];
    [MXMetricManager.sharedManager addSubscriber:self];
    [self didReceiveDiagnosticPayloads:MXMetricManager.sharedManager.pastDiagnosticPayloads];
}
- (void)record:(NSString *)event {
    // Callers supply lifecycle messages, never HTTP URLs, keys, or packet data.
    if (event.length > 4096) event = [event substringToIndex:4096];
    os_log_with_type(OS_LOG_DEFAULT, OS_LOG_TYPE_DEFAULT, "Portal lifecycle: %{public}@", event);
    NSData *line = [[NSString stringWithFormat:@"%@ %@\n", NSDate.date, event] dataUsingEncoding:NSUTF8StringEncoding];
    [_lock lock];
    @try {
        NSFileManager *fm = NSFileManager.defaultManager;
        [fm createDirectoryAtURL:_directory withIntermediateDirectories:YES attributes:nil error:nil];
        NSURL *current = [_directory URLByAppendingPathComponent:@"lifecycle.log"];
        unsigned long long size = [[fm attributesOfItemAtPath:current.path error:nil] fileSize];
        if (size + line.length > 256 * 1024) {
            [fm removeItemAtURL:[_directory URLByAppendingPathComponent:@"lifecycle.3.log"] error:nil];
            for (int i=2; i>=0; --i) {
                NSString *from = i == 0 ? @"lifecycle.log" : [NSString stringWithFormat:@"lifecycle.%d.log", i];
                [fm moveItemAtURL:[_directory URLByAppendingPathComponent:from] toURL:[_directory URLByAppendingPathComponent:[NSString stringWithFormat:@"lifecycle.%d.log", i+1]] error:nil];
            }
        }
        if (![fm fileExistsAtPath:current.path]) [fm createFileAtPath:current.path contents:nil attributes:nil];
        NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:current.path];
        [handle seekToEndOfFile]; [handle writeData:line]; [handle closeFile];
    } @catch (NSException *exception) {
        os_log_error(OS_LOG_DEFAULT, "Diagnostics write failed: %{public}@", exception.name);
    } @finally { [_lock unlock]; }
}
- (void)didReceiveDiagnosticPayloads:(NSArray<MXDiagnosticPayload *> *)payloads {
    for (MXDiagnosticPayload *payload in payloads) {
        NSData *data = payload.JSONRepresentation;
        if (data.length > 2 * 1024 * 1024) { [self record:@"MetricKit payload exceeds 2 MiB retention limit"]; continue; }
        [_lock lock];
        NSFileManager *fm = NSFileManager.defaultManager;
        [fm createDirectoryAtURL:_directory withIntermediateDirectories:YES attributes:nil error:nil];
        NSString *name = [NSString stringWithFormat:@"system-%.3f-%.3f.json", payload.timeStampBegin.timeIntervalSince1970, payload.timeStampEnd.timeIntervalSince1970];
        [data writeToURL:[_directory URLByAppendingPathComponent:name] options:NSDataWritingAtomic error:nil];
        NSArray<NSString *> *files = [[[fm contentsOfDirectoryAtPath:_directory.path error:nil] filteredArrayUsingPredicate:[NSPredicate predicateWithFormat:@"SELF BEGINSWITH 'system-' AND SELF ENDSWITH '.json'"]] sortedArrayUsingSelector:@selector(compare:)];
        for (NSUInteger i=0; i+5<files.count; ++i) [fm removeItemAtURL:[_directory URLByAppendingPathComponent:files[i]] error:nil];
        [_lock unlock];
    }
}
- (NSURL *)exportReport:(NSError **)error {
    [_lock lock];
    NSMutableString *report = [NSMutableString stringWithString:@"Moonlight diagnostics\nLifecycle breadcrumbs and available MetricKit reports.\nSystem reports may arrive after relaunch; absence does not mean no crash occurred.\n\n"];
    NSArray<NSString *> *files = [[NSFileManager.defaultManager contentsOfDirectoryAtPath:_directory.path error:nil] sortedArrayUsingSelector:@selector(compare:)];
    for (NSString *file in files) {
        if (![file hasPrefix:@"lifecycle"] && ![file hasPrefix:@"system-"]) continue;
        NSString *text = [NSString stringWithContentsOfURL:[_directory URLByAppendingPathComponent:file] encoding:NSUTF8StringEncoding error:nil];
        if (text) [report appendFormat:@"--- %@ ---\n%@\n", file, text];
    }
    // Stable snapshot prevents unbounded exports. Source logs remain in Application Support.
    NSURL *url = [_directory URLByAppendingPathComponent:@"Moonlight-diagnostics.txt"];
    [NSFileManager.defaultManager createDirectoryAtURL:_directory withIntermediateDirectories:YES attributes:nil error:nil];
    BOOL ok = [report writeToURL:url atomically:YES encoding:NSUTF8StringEncoding error:error];
    [_lock unlock];
    return ok ? url : nil;
}
@end
