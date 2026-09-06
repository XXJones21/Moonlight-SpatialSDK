#import "PortalDiagnostics.h"
int main(void) {
    @autoreleasepool {
        NSURL *directory = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString]];
        PortalDiagnostics *store = [[PortalDiagnostics alloc] initWithDirectory:directory];
        [store record:@"first launch: audio start"];
        store = [[PortalDiagnostics alloc] initWithDirectory:directory];
        NSError *error = nil;
        NSString *text = [NSString stringWithContentsOfURL:[store exportReport:&error] encoding:NSUTF8StringEncoding error:&error];
        NSCAssert([text containsString:@"first launch: audio start"], @"Breadcrumb must survive relaunch");
        NSString *large = [@"x" stringByPaddingToLength:4096 withString:@"x" startingAtIndex:0];
        for (int i=0; i<300; ++i) [store record:large];
        [store record:@"last event"];
        text = [NSString stringWithContentsOfURL:[store exportReport:&error] encoding:NSUTF8StringEncoding error:&error];
        NSCAssert([text containsString:@"last event"], @"Rotation must retain newest events");
        NSCAssert(text.length < 1100000, @"Breadcrumb storage must stay bounded");
        NSCAssert(![text containsString:@"first launch: audio start"], @"Old logs must rotate out");
        [[NSFileManager defaultManager] removeItemAtURL:directory error:nil];
        NSLog(@"Diagnostics tests passed");
    }
}
