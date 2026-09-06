# Portal state protocol v1

Implementation: `PortalCore/include/portal/Protocol.hpp`, `PortalCore/src/Protocol.cpp`.
UDP state is exactly **200 bytes**, little-endian and field encoded, never a native packed struct.
Quaternion components are x,y,z,w; lengths and translations are meters. Float64 is IEEE-754 binary64.

| Bytes | Meaning |
|---|---|
| 0–7 | `P6DV`, uint16 version=1, uint16 length=200 |
| 8–55 | uint64 sessionID, trackingEpoch, sequence, geometryRevision, sampleTimeNs, targetTimeNs |
| 56–63 | uint32 flags (bit 0 trackingValid), uint32 reserved=0 |
| 64–119 | head position XYZ, head quaternion XYZW: seven float64 |
| 120–175 | portal position XYZ, portal quaternion XYZW: seven float64 |
| 176–199 | width, height, eye separation: three float64 |

All identifiers except timestamps must be nonzero. Unknown flag bits and reserved values are rejected.
Transforms/dimensions must be finite, dimensions positive, eye separation in (0,0.2] m, and quaternion squared
norm within 0.001 of one. The same numeric validity applies when trackingValid=false: retain the last finite
calibrated pose while marking invalid. Packet parsing never mutates accepted session state.

`StateSnapshot` field names are `sessionID`, `trackingEpoch`, `sequence`, `geometryRevision`, `sampleTimeNs`,
`targetTimeNs`, `trackingValid`, `worldFromHead`, `worldFromPortal`, `widthMeters`, `heightMeters`, `eyeSeparationMeters`.
`decodeState(span<const byte>)` returns an optional; `encodeState` returns array<byte,200> and throws
invalid_argument for invalid source values.

## Session and tracking reset

The first accepted state establishes a session and epoch. Sequences are strictly increasing **across epochs**
within a session; duplicate/older sequences cannot refresh the local lease. Geometry revision cannot decrease.
Unchanged revision requires exactly unchanged portal transform/dimensions. Eye separation is independently sampled.
The latest state's receive time uses steady_clock; sender timestamps remain correlation values until clocks are synchronized.
The state is stale at 250 ms, when trackingValid=false, or while an epoch reset awaits its first new state.

Epoch changes require a separate **32-byte** reset control datagram:

| Bytes | Meaning |
|---|---|
| 0–7 | `P6DR`, uint16 version=1, uint16 length=32 |
| 8–15 | uint64 sessionID |
| 16–23 | uint64 previousEpoch |
| 24–31 | uint64 nextEpoch |

The session must match the accepted session, previousEpoch must match the current epoch, and nextEpoch must
be greater. An identical retransmission of the most recently accepted reset is idempotent. Delayed earlier
resets cannot roll the epoch backward. Only the pinned client IP **and source port** may request a reset.
The host forwards accepted resets to UEVR, including retransmissions. Send reset at 10 Hz until returned
status acknowledges nextEpoch; keep sequence increasing and then send state in that epoch. Reset immediately
makes the prior state's tracking invalid and does not refresh its lease. A world-origin relocation must
also increment geometryRevision when the portal transform changes.

The host may acknowledge its accepted epoch before UEVR acknowledges the second UDP hop. It therefore retains
an ordered chain of at most 16 pending resets and retransmits the chain in order at 100 ms intervals.
When full, it rejects a new reset before advancing the accepted host epoch; the client retries after capacity
is released. A same-session UEVR status acknowledging a queued epoch removes every chain link through that
epoch. Intermediate epoch acknowledgments only release control capacity; they cannot replace the current
client-facing status or make older imagery valid. Each retry chain precedes a resend of the one latest state
if that state's epoch matches and its original local receive lease remains fresh. Retransmission does not
refresh the receive lease or emit duplicate OpenTrack motion. An identical reset does not invalidate fresh
state already accepted in its target epoch.

A new session is accepted only after the previous accepted receive lease expires (250 ms), and its sessionID
must not be retired. It may establish a new source port on the configured peer IP. Same-session packets from
a different source port are always rejected. Retired session IDs are retained for the host process lifetime,
bounded to 64 replacements; further replacements fail closed until operator restart. Restarting the host is
a new replay boundary; v1 does not provide cryptographic authentication or cross-restart replay persistence.
Changing trackingValid to false alone does not let another session take over early.

## Host routing and status

```
portal_host --listen 4243 --uevr-port 4244 --opentrack-port 4242 --peer 127.0.0.1
```

For device integration replace `--peer` with the measured headset IPv4 address. The host binds 4243 exclusively;
its single nonblocking socket receives state and sends original validated state to UEVR at 127.0.0.1:4244,
OpenTrack at 127.0.0.1:4242, and status to the accepted client endpoint. Source IP is checked before accepting
client state. Each receive drain is bounded at 64 datagrams, followed by stale/status/shutdown work; only the
newest accepted state in that drain is forwarded. No historical motion playback queue exists.
Reset controls are forwarded before following states. Invalid/stale state never emits an OpenTrack pose.
Ctrl+C/Break requests cooperative shutdown; RAII closes the socket before WSACleanup.

UEVR sends bounded newline-terminated JSON from **127.0.0.1:4244** back to the host's sending/bound port 4243.
It must use its bound receive socket for replies. Example:

```json
{"version":1,"sessionID":"1","trackingEpoch":"1","acceptedSequence":"42","geometryRevision":"3","renderFrameID":"91","trackingValid":true,"outputMode":"sbs","errorCode":"none"}
```

All five 64-bit fields are decimal **strings**, even when small. Exactly nine fields are required; reject
duplicate/unknown keys, malformed JSON, escapes, nested values, numeric IDs, noncanonical decimals, payloads
over 1024 bytes, and missing final newline. `outputMode` is an ASCII identifier of 1–32 letters/digits/_/-
(e.g. `sbs`, `unavailable`). Error is `none`, `staleTracking`, `invalidGeometry`, `unsupportedRuntime`, or
`outputUnavailable`. trackingValid=true requires errorCode=none.

Status must match accepted session/epoch/current geometry revision; acceptedSequence cannot exceed the host's
accepted state. Within one session/epoch, acceptedSequence and renderFrameID cannot regress. The host retains
only the newest status and forwards it at 10 Hz and immediately on error/validity/mode/revision/epoch transitions.
A status older than 250 ms becomes outputUnavailable. Local tracking staleness overrides trackingValid/errorCode
to false/staleTracking. Until UEVR reports a frame the host uses frameID="0" and outputUnavailable.
Forwarded status originates from 4243, so Swift uses one UDP connection for sending and receiving.
**Status is diagnostic only; it never establishes the identity or revision of a decoded video frame.**

## OpenTrack inverse and evidence still required

`PortalHost/src/OpenTrackAdapter.cpp` inverts the pinned VRto3D source, not a generic Euler convention:

- `External/VRto3D/vrto3d/src/hmd_device_driver.cpp:977–979` receives six native doubles
  X,Y,Z,Yaw,Pitch,Roll and maps position to (-X/100,-Y/100,Z/100).
- `External/VRto3D/utils/vrmath/vrmath.h:175–191` constructs `Ry(yaw)*Rx(pitch)*Rz(roll)`.
  The caller passes Roll,Pitch,-Yaw to that helper.

Consequently the relay sends (-100*x,-100*y,+100*z) centimeters and decomposes the normalized head quaternion
using pitch=asin(2*(w*x-y*z)), helper-yaw=atan2(2*(x*z+w*y),1-2*(x*x+y*y)), and
roll=atan2(2*(x*y+w*z),1-2*(x*x+z*z)); transmitted Yaw negates helper-yaw. Angles are degrees.
At pitch gimbal lock, choose roll=0 and preserve the represented orientation with the combined yaw.
`--trace-poses` logs original head position/quaternion and transmitted six doubles for the later SteamVR comparison.
The authored adapter test reconstructs the pinned quaternion equations including both pitch poles.
This mathematical test is not a measurement of SteamVR/controller pose composition; that measurement is pending.

## Synthetic tools and deferred checks

```
python tools/visionos-portal/send_pose.py --host 127.0.0.1 --port 4243 --pattern sweep --duration 10
python tools/visionos-portal/send_pose.py --pattern static --csv static.csv
python tools/visionos-portal/send_pose.py --pattern roll --reset-at 3 --csv roll-reset.csv
```

Each run selects a random nonzero uint64 session unless overridden with --session. It logs sequence/timestamps
to CSV and uses one connected UDP socket for status. Synthetic geometry is the 2.4 × 1.35 m math fixture.
`generate_fixture.py` independently uses `struct.pack('<4sHH6QII17d',...)` to create the committed 200-byte
`PortalCore/fixtures/state-v1.bin`. Fixture generation was performed; no tests were executed.

After the Mac handoff, planned commands are:

```
cmake -S PortalCore -B PortalCore/build -G "Visual Studio 17 2022" -A x64
cmake --build PortalCore/build --config Release
ctest --test-dir PortalCore/build -C Release --output-on-failure
cmake -S PortalHost -B PortalHost/build -G "Visual Studio 17 2022" -A x64 -DPORTAL_HOST_BUILD_TESTS=ON
cmake --build PortalHost/build --config Release
ctest --test-dir PortalHost/build -C Release --output-on-failure
python -m unittest discover -s tools/visionos-portal/tests -v
```

Also measure packet loss/reordering, endpoint spoof rejection, reset retransmission, stale status, rapid geometry
changes, host/UEVR restart, retired session replay, and clean socket shutdown with the actual host. All builds,
CTest/unittest runs, runtime measurements, and intentional Release fixture-break evidence remain unverified.
