# Closed-loop source integration

`PortalSessionCoordinator` owns the pose sender and shared video identity gate.
ARKit publishes complete snapshots with head, portal transform, physical size,
eye separation, epoch and geometry revision. Recenter sends an ordered explicit
reset; transport retains reset controls until a matching host acknowledgement.
Status parsing mirrors the restricted PortalCore grammar and rejects duplicate
keys, malformed IDs, unexpected fields and unmatched state.

Decoded frames must carry matching P6FM tags in both eye strips, a valid CRC,
the active session/epoch/revision and the exact capture dimensions. The client
requests readable NV12/P010/BGRA layouts, inspects only metadata sample positions,
and crops the 16-row strip in Metal before presenting the stereo material.
No UDP acknowledgement is used as video frame identity.

Visibility requires current tracking, fresh source availability, a newer matching
decoded frame after GPU completion, and installation of the current connection's
texture. Manipulation changes revision and hides prior imagery immediately.
Tracking loss, missing frames and host errors suppress image and gamepad input.
Recovery after ARKit interruption requires explicit Recenter.

Start/stop operations use generations before asynchronous teardown. Native
callbacks retain their original client; old material loads cannot replace a new
connection's texture or report failure against it. Shared teardown includes
native shutdown and Swift resource cleanup before any waiter reconnects.
Settings window dismissal does not own that teardown.

**Deferred:** These are source behaviors, not measured guarantees. Run the
protocol fixtures, reset/reordering cases, post-codec frame checks, resource
race scenarios and headset lifecycle tests on the Mac/PC bench before judging
correctness or latency. GPU completion is not proof of headset scanout time.
