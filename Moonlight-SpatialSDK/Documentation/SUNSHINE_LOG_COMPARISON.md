# Sunshine Server Log Comparison: Quest vs macOS Client

## Executive Summary

**CRITICAL FINDING**: The Quest client **DOES successfully connect** to the control stream (ENet connection works!), but the **process terminates immediately** after connection. The macOS client connects and continues normally.

---

## Key Comparison

### Control Stream Connection

#### Quest Client (FAILING) - `sunshine.log:10221-10225`

```
[2025-12-07 12:09:23.888]: Debug: Initialized new control stream session by connect data match [v2]
[2025-12-07 12:09:23.888]: Debug: Control local address [10.1.95.5]
[2025-12-07 12:09:23.888]: Debug: Control peer address [10.1.95.13:46020]
[2025-12-07 12:09:23.888]: Info: CLIENT CONNECTED
[2025-12-07 12:09:23.888]: Info: Process terminated ← CRITICAL: Process terminates immediately!
```

**Timeline**:
- RTSP PLAY completes: `12:09:23.875`
- Control stream connects: `12:09:23.888` (13ms later)
- **Process terminates**: `12:09:23.888` (same timestamp!)

**Analysis**: 
- ✅ ENet connection **succeeds** (CLIENT CONNECTED)
- ❌ Process terminates **immediately** after connection
- No control packets received after connection

#### macOS Client (WORKING) - `macOS.log:2494-2503`

```
[2025-12-07 12:17:36.430]: Debug: Initialized new control stream session by connect data match [v2]
[2025-12-07 12:17:36.430]: Debug: Control local address [10.1.95.5]
[2025-12-07 12:17:36.430]: Debug: Control peer address [10.1.95.13:60158]
[2025-12-07 12:17:36.430]: Info: CLIENT CONNECTED
[2025-12-07 12:17:36.431]: Verbose: type [IDX_ENCRYPTED]
[2025-12-07 12:17:36.431]: Debug: type [IDX_REQUEST_IDR_FRAME]
[2025-12-07 12:17:36.431]: Verbose: type [IDX_ENCRYPTED]
[2025-12-07 12:17:36.431]: Debug: type [IDX_START_B]
[2025-12-07 12:17:36.432]: Verbose: type [IDX_ENCRYPTED]
[2025-12-07 12:17:36.432]: Verbose: type [IDX_PERIODIC_PING]
```

**Timeline**:
- RTSP PLAY completes: `12:17:36.422`
- Control stream connects: `12:17:36.430` (8ms later)
- **Control packets received**: `12:17:36.431` (1ms after connection)
- Continues normally with video/audio streams

**Analysis**:
- ✅ ENet connection succeeds (CLIENT CONNECTED)
- ✅ Control packets received immediately after connection
- ✅ Process continues normally

---

## RTSP Handshake Comparison

### Control Stream SETUP Response

#### Quest Client - `sunshine.log:10065-10080`

```
RTSP/1.0 200 OK

CSeq: 5

Session: DEADBEEFCAFE;timeout = 90

Transport: server_port=47999

X-SS-Connect-Data: 2910671779
```

**ControlConnectData**: `2910671779` (0xAD7E5CE3)

#### macOS Client - `macOS.log:2336-2351`

```
RTSP/1.0 200 OK

CSeq: 5

Session: DEADBEEFCAFE;timeout = 90

Transport: server_port=47999

X-SS-Connect-Data: 54360317
```

**ControlConnectData**: `54360317` (0x033E0F3D)

**Analysis**: Both clients receive different `X-SS-Connect-Data` values, which is expected (session-specific).

---

## RTSP ANNOUNCE Comparison

### Quest Client - `sunshine.log:10086-10119`

**Key Parameters**:
```
a=x-nv-video[0].clientViewportWd:2560 
a=x-nv-video[0].clientViewportHt:1440 
a=x-nv-video[0].packetSize:1024 
a=x-ml-video.configuredBitrateKbps:40000 
a=x-nv-clientSupportHevc:0 
a=x-nv-vqos[0].bitStreamFormat:0 
a=x-nv-video[0].dynamicRangeMode:0 
```

**Notable**:
- `packetSize: 1024`
- `clientSupportHevc: 0` (H.264 only)
- `bitStreamFormat: 0`
- `dynamicRangeMode: 0` (SDR)

### macOS Client - `macOS.log:2357-2439`

**Key Parameters**:
```
a=x-nv-video[0].clientViewportWd:2560 
a=x-nv-video[0].clientViewportHt:1600 
a=x-nv-video[0].packetSize:1392 
a=x-ml-video.configuredBitrateKbps:44000 
a=x-nv-clientSupportHevc:1 
a=x-nv-vqos[0].bitStreamFormat:1 
a=x-nv-video[0].dynamicRangeMode:1 
```

**Notable**:
- `packetSize: 1392`
- `clientSupportHevc: 1` (HEVC supported)
- `bitStreamFormat: 1`
- `dynamicRangeMode: 1` (HDR)

**Differences**:
1. **Packet Size**: Quest uses 1024, macOS uses 1392
2. **HEVC Support**: Quest = 0, macOS = 1
3. **Bitstream Format**: Quest = 0, macOS = 1
4. **Dynamic Range**: Quest = 0 (SDR), macOS = 1 (HDR)
5. **Viewport Height**: Quest = 1440, macOS = 1600
6. **Bitrate**: Quest = 40000, macOS = 44000

---

## Timeline Comparison

### Quest Client Timeline

```
12:09:23.691: RTSP SETUP control stream (CSeq 5)
12:09:23.691: Server responds: port=47999, X-SS-Connect-Data=2910671779
12:09:23.697: RTSP ANNOUNCE (CSeq 6)
12:09:23.697: Server: "New streaming session started"
12:09:23.697: Server: "Expecting incoming session connections from 10.1.95.13"
12:09:23.875: RTSP PLAY (CSeq 7)
12:09:23.875: Server responds: 200 OK
12:09:23.888: Control stream connects (13ms after PLAY)
12:09:23.888: CLIENT CONNECTED
12:09:23.888: Process terminated ← IMMEDIATE TERMINATION
```

**Duration from PLAY to termination**: 13ms

### macOS Client Timeline

```
12:17:36.238: RTSP SETUP control stream (CSeq 5)
12:17:36.238: Server responds: port=47999, X-SS-Connect-Data=54360317
12:17:36.245: RTSP ANNOUNCE (CSeq 6)
12:17:36.246: Server: "New streaming session started"
12:17:36.246: Server: "Expecting incoming session connections from 10.1.95.13"
12:17:36.422: RTSP PLAY (CSeq 7)
12:17:36.422: Server responds: 200 OK
12:17:36.430: Control stream connects (8ms after PLAY)
12:17:36.430: CLIENT CONNECTED
12:17:36.431: Control packets received (IDX_REQUEST_IDR_FRAME, IDX_START_B)
12:17:36.433: Video stream starts receiving packets
12:17:36.750: Audio stream starts receiving packets
```

**Duration from PLAY to first control packet**: 9ms

---

## Root Cause Analysis

### What We Know

1. ✅ **ENet connection succeeds** - Both clients connect successfully
2. ✅ **Control stream established** - Server logs "CLIENT CONNECTED" for both
3. ❌ **Quest client terminates immediately** - Process ends right after connection
4. ✅ **macOS client continues** - Receives control packets and starts streaming

### Hypothesis: Client-Side Termination

**Evidence**:
- Server logs show "CLIENT CONNECTED" for Quest client
- Server logs show "Process terminated" immediately after
- No control packets received from Quest client after connection
- macOS client sends control packets immediately after connection

**Possible Causes**:

1. **Client detects connection failure and terminates**
   - Client may be checking for something that fails
   - May be expecting immediate response that doesn't come
   - May have timeout that triggers immediately

2. **Client-side error after connection**
   - Something fails in the client after ENet connection succeeds
   - May be related to control stream initialization
   - May be related to video/audio stream setup

3. **Missing control packets**
   - Client may expect to send control packets immediately
   - If sending fails, client may terminate
   - macOS client sends `IDX_REQUEST_IDR_FRAME` and `IDX_START_B` immediately

4. **Video stream setup failure**
   - Client may be waiting for video stream to start
   - If video stream doesn't start, client terminates
   - macOS client receives video packets at `12:17:36.433`

---

## Key Differences Summary

| Aspect | Quest Client | macOS Client | Impact |
|--------|-------------|--------------|--------|
| **ENet Connection** | ✅ Succeeds | ✅ Succeeds | None - both work |
| **Control Stream** | ✅ Connects | ✅ Connects | None - both work |
| **After Connection** | ❌ Terminates immediately | ✅ Sends control packets | **CRITICAL** |
| **Control Packets** | ❌ None sent | ✅ IDX_REQUEST_IDR_FRAME, IDX_START_B | **CRITICAL** |
| **Video Stream** | ❌ Never starts | ✅ Starts at 12:17:36.433 | **CRITICAL** |
| **Packet Size** | 1024 | 1392 | May affect performance |
| **HEVC Support** | 0 (H.264 only) | 1 (HEVC) | May affect codec selection |
| **HDR Support** | 0 (SDR) | 1 (HDR) | May affect encoding |

---

## Conclusion

**The ENet connection is NOT the problem!** 

The Quest client successfully connects to the control stream, but then **terminates immediately** without sending any control packets. The macOS client, in contrast, sends control packets immediately after connection and continues normally.

**Root Cause**: The issue is **client-side termination** after successful ENet connection, not the connection itself. The client likely:
1. Connects successfully
2. Fails to send initial control packets (IDX_REQUEST_IDR_FRAME, IDX_START_B)
3. Detects failure and terminates
4. Server logs "Process terminated"

**Next Steps**:
1. Check Quest client logs for errors after "CLIENT CONNECTED"
2. Verify control packet sending code after ENet connection
3. Compare control stream initialization between Quest and macOS clients
4. Check for timeouts or error conditions that trigger termination

---

## References

- **Quest Client Logs**: `sunshine.log:10221-10225`
- **macOS Client Logs**: `macOS.log:2494-2503`
- **Quest RTSP SETUP**: `sunshine.log:10065-10080`
- **macOS RTSP SETUP**: `macOS.log:2336-2351`

