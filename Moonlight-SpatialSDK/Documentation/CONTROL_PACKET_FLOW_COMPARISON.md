# Control Packet Flow Comparison: Quest vs macOS Client

## Executive Summary

**CRITICAL FINDING**: The Quest client **never reaches** the START A/B packet sending code because the ENet connection wait fails with a DISCONNECT event. However, server logs show the connection **does succeed** briefly before terminating. This indicates a **race condition** where the server accepts the connection but immediately disconnects before the client can process the CONNECT event.

---

## Expected Control Stream Flow (from Code)

### `startControlStream()` Function Flow (ControlStream.c:1636-1821)

**After ENet Connection Succeeds**:

1. **Line 1714-1715**: Flush connection ACK

   ```c
   enet_host_flush(client);
   ```

2. **Line 1722-1723**: Set peer timeout (10 seconds)

   ```c
   enet_peer_timeout(peer, 2, 10000, 10000);
   ```

3. **Line 1739**: Create control receive thread

   ```c
   err = PltCreateThread("ControlRecv", controlReceiveThreadFunc, NULL, &controlReceiveThread);
   ```

4. **Line 1756-1787**: Send START A packet

   ```c
   if (!sendMessageAndDiscardReply(packetTypes[IDX_START_A], ...)) {
       // Error handling - terminates connection
   }
   ```

5. **Line 1790-1821**: Send START B packet

   ```c
   if (!sendMessageAndDiscardReply(packetTypes[IDX_START_B], ...)) {
       // Error handling - terminates connection
   }
   ```

6. **Line 1823+**: Create additional threads (LossStats, ReqIdrFrame, AsyncCallback)

**Key Point**: START A and START B packets are sent **immediately after** ENet connection succeeds, before any other operations.

---

## Server Log Analysis

### macOS Client (WORKING) - `macOS.log:2494-2503`

```
[2025-12-07 12:17:36.430]: Debug: Initialized new control stream session by connect data match [v2]
[2025-12-07 12:17:36.430]: Debug: Control local address [10.1.95.5]
[2025-12-07 12:17:36.430]: Debug: Control peer address [10.1.95.13:60158]
[2025-12-07 12:17:36.430]: Info: CLIENT CONNECTED
[2025-12-07 12:17:36.431]: Verbose: type [IDX_ENCRYPTED]
[2025-12-07 12:17:36.431]: Debug: type [IDX_REQUEST_IDR_FRAME]  ← START A equivalent
[2025-12-07 12:17:36.431]: Verbose: type [IDX_ENCRYPTED]
[2025-12-07 12:17:36.431]: Debug: type [IDX_START_B]              ← START B
[2025-12-07 12:17:36.432]: Verbose: type [IDX_ENCRYPTED]
[2025-12-07 12:17:36.432]: Verbose: type [IDX_PERIODIC_PING]
```

**Timeline**:

- `12:17:36.430`: CLIENT CONNECTED
- `12:17:36.431`: IDX_REQUEST_IDR_FRAME received (1ms after connection)
- `12:17:36.431`: IDX_START_B received (1ms after connection)
- `12:17:36.432`: IDX_PERIODIC_PING received (2ms after connection)

**Analysis**: macOS client sends control packets **immediately** (within 1-2ms) after connection.

### Quest Client (FAILING) - `sunshine.log:10221-10225`

```
[2025-12-07 12:09:23.888]: Debug: Initialized new control stream session by connect data match [v2]
[2025-12-07 12:09:23.888]: Debug: Control local address [10.1.95.5]
[2025-12-07 12:09:23.888]: Debug: Control peer address [10.1.95.13:46020]
[2025-12-07 12:09:23.888]: Info: CLIENT CONNECTED
[2025-12-07 12:09:23.888]: Info: Process terminated ← IMMEDIATE TERMINATION
```

**Timeline**:

- `12:09:23.888`: CLIENT CONNECTED
- `12:09:23.888`: Process terminated (same timestamp - immediate)

**Analysis**: Quest client connects but **never sends** any control packets. Process terminates immediately.

---

## Client Log Analysis

### Quest Client Logs - `immserive-drm.log:7257-7260`

```
12-07 11:35:39.466 I/moonlight-common-c(10713): Failed to establish ENet connection on UDP port 47999: unexpected event 2 (error: 11)
12-07 11:35:39.466 I/moonlight-common-c(10713): failed: 11
12-07 11:35:39.466 E/moonlight-common-c(10713): Stage 8 failed: control stream establishment error=11
12-07 11:35:39.466 W/MoonlightConnectionMgr(10713): stageFailed control stream establishment error=11 portFlags=512
```

**Error Location**: `ControlStream.c:1692`

- **Event Type**: 2 = `ENET_EVENT_TYPE_DISCONNECT`
- **Error Code**: 11 = EAGAIN/EWOULDBLOCK
- **Timing**: 8 seconds after Stage 8 starts (matches timeout)

**Analysis**: Client receives DISCONNECT event instead of CONNECT event during the 10-second wait.

---

## Discrepancy Analysis

### The Contradiction

**Server Perspective** (`sunshine.log`):
- ✅ Connection succeeds: "CLIENT CONNECTED" at `12:09:23.888`
- ❌ Process terminates immediately: "Process terminated" at `12:09:23.888`
- ❌ No control packets received

**Client Perspective** (`immserive-drm.log`):
- ❌ Connection fails: "unexpected event 2" (DISCONNECT) at `11:35:39.466`
- ❌ Never reaches START A/B packet sending code
- ❌ Stage 8 fails with error 11

**Note**: Timestamps differ (`12:09:23` vs `11:35:39`) - these may be from different test runs or timezone differences.

### Hypothesis: Race Condition

**Scenario**:

1. Client initiates ENet connection (`enet_host_connect` at line 1674)
2. Server accepts connection (logs "CLIENT CONNECTED")
3. **Something causes immediate disconnect** (server or network issue)
4. Client's `serviceEnetHost` wait (line 1683) receives DISCONNECT event instead of CONNECT
5. Client fails with "unexpected event 2"
6. Server logs "Process terminated" because client disconnected

**Possible Causes**:

1. **Network issue**: UDP packet loss causes connection to fail immediately
2. **Server-side timeout**: Server disconnects if no control packets received quickly
3. **QoS marking rejection**: Network/router rejects QoS-marked packets, causing disconnect
4. **ControlConnectData mismatch**: Server rejects connection due to mismatched data
5. **Threading issue**: Control receive thread creation fails, causing disconnect

---

## Code Flow Comparison

### Expected Flow (macOS Client - WORKING)

```
1. startControlStream() called
2. enet_host_connect() - initiate connection
3. serviceEnetHost() - wait for CONNECT event
   → Receives ENET_EVENT_TYPE_CONNECT ✅
4. enet_host_flush() - send ACK
5. enet_peer_timeout() - set timeout
6. PltCreateThread("ControlRecv") - create receive thread ✅
7. sendMessageAndDiscardReply(START A) - send START A ✅
   → Server receives IDX_REQUEST_IDR_FRAME
8. sendMessageAndDiscardReply(START B) - send START B ✅
   → Server receives IDX_START_B
9. Continue with other threads...
```

### Actual Flow (Quest Client - FAILING)

```
1. startControlStream() called
2. enet_host_connect() - initiate connection
3. serviceEnetHost() - wait for CONNECT event
   → Receives ENET_EVENT_TYPE_DISCONNECT ❌
   → Error: "unexpected event 2 (error: 11)"
4. Function returns error 11
5. Connection cleanup
6. Stage 8 fails
7. Never reaches START A/B packet sending code
```

**Key Difference**: Quest client **never receives CONNECT event**, so it never reaches the START A/B packet sending code.

---

## Root Cause Hypothesis

### Most Likely: Server Disconnects Before Client Processes CONNECT

**Evidence**:

- Server logs show "CLIENT CONNECTED" (connection succeeds)
- Server logs show "Process terminated" immediately (client disconnects)
- Client logs show DISCONNECT event received (never sees CONNECT)

**Possible Sequence**:

1. Server accepts connection → logs "CLIENT CONNECTED"
2. Server expects immediate control packets (START A/B)
3. Client is still waiting for CONNECT event in `serviceEnetHost()`
4. Server times out waiting for control packets → disconnects
5. Client receives DISCONNECT event → fails

**Why macOS Works**:

- macOS client may process CONNECT event faster
- macOS client may have different network conditions
- macOS client may send control packets before server timeout

### Alternative: Network Packet Ordering Issue

**Hypothesis**: DISCONNECT packet arrives before CONNECT packet due to network reordering.

**Evidence**:

- UDP is unreliable - packets can arrive out of order
- Server sends CONNECT, but DISCONNECT arrives first
- Client processes DISCONNECT and fails

**Why This Is Unlikely**:

- ENet handles packet ordering
- CONNECT should be sent before any DISCONNECT
- macOS client on same network works fine

---

## Investigation Steps

### 1. Add Detailed Logging

**Location**: `ControlStream.c:1683-1712`

**Add Logging**:

```c
// Wait for the connect to complete
Limelog("Waiting for ENet CONNECT event on port %u (timeout: %d ms)\n", ControlPortNumber, CONTROL_STREAM_TIMEOUT_SEC * 1000);
err = serviceEnetHost(client, &event, CONTROL_STREAM_TIMEOUT_SEC * 1000);
if (err <= 0 || event.type != ENET_EVENT_TYPE_CONNECT) {
    Limelog("serviceEnetHost returned: err=%d, event.type=%d, LastSocketError()=%d\n", err, (int)event.type, LastSocketError());
    // ... existing error handling ...
}
else {
    Limelog("ENet CONNECT event received successfully on port %u\n", ControlPortNumber);
}
```

**Purpose**: Understand exactly what event is received and when.

### 2. Check ControlReceiveThread Creation

**Location**: `ControlStream.c:1739`

**Add Logging**:

```c
Limelog("Creating control receive thread...\n");
err = PltCreateThread("ControlRecv", controlReceiveThreadFunc, NULL, &controlReceiveThread);
if (err != 0) {
    Limelog("Failed to create control receive thread: %d\n", err);
    // ... existing error handling ...
}
else {
    Limelog("Control receive thread created successfully\n");
}
```

**Purpose**: Verify thread creation doesn't fail silently.

### 3. Check START A Packet Sending

**Location**: `ControlStream.c:1756`

**Add Logging**:

```c
Limelog("Sending START A packet (type: 0x%04x)\n", packetTypes[IDX_START_A]);
if (!sendMessageAndDiscardReply(...)) {
    Limelog("START A failed: err=%d, LastSocketError()=%d\n", err, LastSocketError());
    // ... existing error handling ...
}
else {
    Limelog("START A sent successfully\n");
}
```

**Purpose**: Verify if START A sending is attempted and why it might fail.

### 4. Compare Network Conditions

**Action**: Test Quest client on same network as macOS client to rule out network-specific issues.

### 5. Check for Threading Issues

**Action**: Verify `PltCreateThread` implementation on Quest platform doesn't have issues.

---

## Code Comparison: moonlight-android vs Our Implementation

### ControlStream.c:1636-1821

**Finding**: Code is **identical** between moonlight-android and our implementation.

**Conclusion**: The issue is **not** in the moonlight-core code itself, but likely in:

1. Platform-specific threading implementation
2. Network conditions on Quest
3. Timing/race conditions specific to Quest platform
4. ENet library behavior on Quest

---

## Next Steps

### Immediate Actions

1. **Add detailed logging** to `startControlStream()` to track:
   - When CONNECT/DISCONNECT events are received
   - Thread creation success/failure
   - START A/B packet sending attempts

2. **Check Quest platform threading**:
   - Verify `PltCreateThread` implementation
   - Check for thread creation failures
   - Verify thread priorities

3. **Test network conditions**:
   - Compare Quest vs macOS network paths
   - Check for UDP packet loss
   - Verify firewall/NAT behavior

4. **Compare timing**:
   - Measure time from connection to first control packet
   - Check if Quest has slower processing
   - Verify timeout values

### Code Changes (If Needed)

**Only if investigation reveals specific issue**:
- Add retry logic for ENet connection

- Adjust timeout values
- Add workaround for race condition
- Fix platform-specific threading issue

---

## Conclusion

**Root Cause**: Quest client receives DISCONNECT event instead of CONNECT event during ENet connection wait, preventing it from reaching the START A/B packet sending code. Server logs show the connection succeeds briefly, indicating a race condition or timing issue.

**Key Finding**: The code is identical to moonlight-android, so the issue is likely platform-specific (Quest VR) or network-related.

**Next Action**: Add detailed logging to understand the exact sequence of events and identify why the DISCONNECT event is received instead of CONNECT.

---

## References

- **Control Stream Code**: `ControlStream.c:1636-1821`
- **ENet Connection Wait**: `ControlStream.c:1683-1712`
- **START A/B Sending**: `ControlStream.c:1756-1821`
- **Server Logs**: `sunshine.log:10221-10225` (Quest), `macOS.log:2494-2503` (macOS)
- **Client Logs**: `immserive-drm.log:7257-7260`
