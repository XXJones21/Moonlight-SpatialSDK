# ENet Control Stream Connection Error Analysis

## Error Summary

**Location**: `immserive-drm.log:7257-7259`  
**Error**: `Failed to establish ENet connection on UDP port 47999: unexpected event 2 (error: 11)`  
**Stage**: Stage 8 - Control Stream Establishment  
**Return Code**: 11 (EAGAIN/EWOULDBLOCK)

---

## Error Details

### Log Entry
```
12-07 11:35:39.466 I/moonlight-common-c(10713): Failed to establish ENet connection on UDP port 47999: unexpected event 2 (error: 11)
12-07 11:35:39.466 I/moonlight-common-c(10713): failed: 11
12-07 11:35:39.466 E/moonlight-common-c(10713): Stage 8 failed: control stream establishment error=11
12-07 11:35:39.466 W/MoonlightConnectionMgr(10713): stageFailed control stream establishment error=11 portFlags=512
```

### Code Location

**File**: `ControlStream.c:1683-1692`

```c
// Wait for the connect to complete
err = serviceEnetHost(client, &event, CONTROL_STREAM_TIMEOUT_SEC * 1000);
if (err <= 0 || event.type != ENET_EVENT_TYPE_CONNECT) {
    if (err < 0) {
        Limelog("Failed to establish ENet connection on UDP port %u: error %d\n", ControlPortNumber, LastSocketFail());
    }
    else if (err == 0) {
        Limelog("Failed to establish ENet connection on UDP port %u: timed out\n", ControlPortNumber);
    }
    else {
        Limelog("Failed to establish ENet connection on UDP port %u: unexpected event %d (error: %d)\n", ControlPortNumber, (int)event.type, LastSocketError());
    }
    // ... cleanup and return error ...
}
```

---

## ENet Event Types

From `enet.h:404-430`:

```c
typedef enum _ENetEventType
{
   ENET_EVENT_TYPE_NONE       = 0,  // No event occurred
   ENET_EVENT_TYPE_CONNECT    = 1,  // Connection completed
   ENET_EVENT_TYPE_DISCONNECT = 2,  // Peer disconnected
   ENET_EVENT_TYPE_RECEIVE    = 3   // Packet received
} ENetEventType;
```

**Event Type 2** = `ENET_EVENT_TYPE_DISCONNECT`

---

## Analysis

### What Happened

1. **Client Action**: Client attempts to establish ENet connection on UDP port 47999 (control stream)
2. **Expected Event**: `ENET_EVENT_TYPE_CONNECT` (event type 1)
3. **Actual Event**: `ENET_EVENT_TYPE_DISCONNECT` (event type 2)
4. **Error Code**: 11 (EAGAIN/EWOULDBLOCK)

### Key Observations

1. **Server Immediately Disconnects**: The server sends a DISCONNECT event instead of CONNECT, indicating the connection was rejected or failed immediately.

2. **Error Code 11 (EAGAIN)**: This is unusual - EAGAIN typically means "would block", but we received a DISCONNECT event, which suggests:
   - The connection attempt was processed
   - The server actively rejected it
   - The error code might be from a different socket operation

3. **Stage 8 Failure**: This occurs after RTSP handshake (Stage 7), meaning:
   - RTSP handshake likely succeeded (or at least progressed far enough)
   - Server provided control port number (47999)
   - Control stream establishment is where it fails

4. **Port 47999**: This is the standard Moonlight control stream port. The server provided this port in the RTSP handshake.

---

## Possible Root Causes

### 1. Server Rejection
**Hypothesis**: Server actively rejects the ENet connection attempt.

**Evidence**:
- DISCONNECT event received immediately
- No timeout (connection processed quickly)
- Server provided port 47999, so it knows about the connection

**Possible Reasons**:
- Server doesn't support ENet for control stream
- Server configuration mismatch
- Server security policy blocking connection
- Server already has too many connections

### 2. Network/Firewall Issue
**Hypothesis**: UDP port 47999 is blocked or unreachable.

**Evidence**:
- DISCONNECT event suggests connection attempt was processed
- Error 11 might indicate network-level blocking

**Possible Reasons**:
- Firewall blocking UDP port 47999
- NAT traversal issues
- Network routing problems
- VPN/proxy interference

### 3. Race Condition
**Hypothesis**: Connection established and immediately disconnected.

**Evidence**:
- DISCONNECT event received
- Error 11 might be from a subsequent operation

**Possible Reasons**:
- Server timeout during connection
- Server detects invalid client state
- Server cleanup of stale connections

### 4. Protocol Mismatch
**Hypothesis**: Server expects different connection protocol or parameters.

**Evidence**:
- RTSP handshake succeeded (server provided port)
- Control stream connection fails immediately

**Possible Reasons**:
- Server expects different ENet version
- Server expects different connection parameters
- Server expects different authentication

---

## Comparison with Previous Logs

### What to Check in Previous Logs

1. **RTSP Handshake Success**: Verify RTSP handshake completed successfully
2. **Control Port Number**: Confirm server provided port 47999
3. **Previous Control Stream Attempts**: Check if this is a recurring issue
4. **Network Configuration**: Verify network setup hasn't changed

### Expected vs Actual

**Expected Flow**:
1. RTSP handshake completes
2. Server provides control port (47999)
3. Client establishes ENet connection
4. Server sends CONNECT event
5. Control stream ready

**Actual Flow**:
1. RTSP handshake completes (assumed)
2. Server provides control port (47999)
3. Client attempts ENet connection
4. Server sends DISCONNECT event immediately
5. Connection fails

---

## Investigation Steps

### 1. Check RTSP Handshake Logs
**Action**: Search for RTSP handshake completion logs before line 7257.

**What to Look For**:
- RTSP DESCRIBE response
- Control port number in SDP
- RTSP handshake success messages

### 2. Verify Server Configuration
**Action**: Check Sunshine server logs for control stream connection attempts.

**What to Look For**:
- Connection attempts on port 47999
- Rejection reasons
- Server-side errors

### 3. Network Diagnostics
**Action**: Test UDP connectivity to port 47999.

**Commands**:
```bash
# Test UDP port connectivity
nc -u -v <server_ip> 47999

# Check firewall rules
# (platform-specific)
```

### 4. Compare with Working Logs
**Action**: Compare with logs from successful connections (if available).

**What to Compare**:
- RTSP handshake differences
- Control port numbers
- Timing of events
- Network conditions

---

## Code Investigation

### ControlStream.c:1636-1712

The `startControlStream()` function:
1. Creates ENet host
2. Connects to server on control port
3. Waits for CONNECT event
4. Fails if DISCONNECT or other event received

**Key Code Section**:
```c
// Connect to the host
peer = enet_host_connect(client, &remoteAddress, CTRL_CHANNEL_COUNT, ControlConnectData);

// Wait for the connect to complete
err = serviceEnetHost(client, &event, CONTROL_STREAM_TIMEOUT_SEC * 1000);
if (err <= 0 || event.type != ENET_EVENT_TYPE_CONNECT) {
    // Error handling - this is where we are
}
```

### Possible Issues

1. **ControlConnectData**: Check what data is sent during connection
2. **CTRL_CHANNEL_COUNT**: Verify channel count matches server expectations
3. **Timeout**: Check if timeout is too short
4. **QoS Settings**: Verify QoS marking doesn't cause issues

---

## Recommendations

### Immediate Actions

1. **Check RTSP Handshake**: Verify RTSP handshake completed successfully before this error
2. **Server Logs**: Check Sunshine server logs for connection rejection reasons
3. **Network Test**: Verify UDP port 47999 is reachable

### Code Changes (If Needed)

1. **Better Error Logging**: Add more context about why DISCONNECT was received
2. **Retry Logic**: Consider retrying control stream connection
3. **Fallback**: Consider fallback to non-ENet control stream if available

### Configuration Checks

1. **Sunshine Settings**: Verify control stream settings in Sunshine
2. **Firewall Rules**: Ensure UDP port 47999 is open
3. **Network Configuration**: Check for NAT/VPN issues

---

## Related Issues

This error occurs at **Stage 8** (Control Stream Establishment), which is after:
- Stage 1: Platform initialization
- Stage 2: HTTP connection
- Stage 3: RTSP handshake (Stage 7 in some versions)

If RTSP handshake succeeded but control stream fails, this suggests:
- Server configuration issue
- Network issue specific to control stream
- Protocol compatibility issue

---

## Next Steps

1. **Verify RTSP Handshake**: Check logs before line 7257 for RTSP handshake completion
2. **Server Investigation**: Check Sunshine server logs for control stream connection attempts
3. **Network Test**: Verify UDP connectivity to port 47999
4. **Compare Logs**: Compare with previous successful connection logs (if available)

---

## Conclusion

The server is **immediately rejecting** the ENet control stream connection by sending a DISCONNECT event. This is unusual and suggests either:
- Server-side configuration issue
- Network/firewall blocking
- Protocol mismatch

The error occurs after RTSP handshake, indicating the server knows about the connection but rejects the control stream specifically.

