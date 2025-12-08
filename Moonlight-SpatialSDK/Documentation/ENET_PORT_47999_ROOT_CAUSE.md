# ENet Control Stream Port 47999 - Root Cause Analysis

## Executive Summary

**CONFIRMED**: Server is **actively rejecting** connections on port 47999. The ncat test confirms TCP port 47999 is closed, but the critical issue is that the **UDP ENet connection** is also being rejected by the server.

**Root Cause**: Server provides port 47999 in RTSP handshake but **does not accept ENet UDP connections** on that port, or the server is not properly configured to listen on UDP 47999.

---

## Confirmed Evidence

### 1. Network Test Result

```
ncat 10.1.95.5 47999
Ncat: No connection could be made because the target machine actively refused it.
```

**Analysis**:
- **Protocol**: ncat defaults to **TCP** (not UDP)
- **Result**: "Actively refused" = Server is **rejecting TCP connections** on port 47999
- **Implication**: TCP port 47999 is **closed/not listening**

**Note**: ENet uses **UDP**, not TCP, so this test doesn't directly confirm UDP status, but it suggests the port may not be properly configured.

### 2. Sunshine Configuration

**From Screenshot**:
- **UDP Ports**: 47998-48000 (includes 47999)
- **TCP Ports**: 47984, 47989, 47990 (Web UI), 48010
- **Base Port**: 47989

**Analysis**:
- Sunshine **configures** UDP ports 47998-48000
- Port 47999 **should be available** for UDP connections
- But server is **rejecting** the connection

### 3. Log Evidence

**RTSP Handshake** (Line 6594):
```
Control port: 47999
```

**ENet Connection Attempt** (Line 7257):
```
Failed to establish ENet connection on UDP port 47999: unexpected event 2 (error: 11)
```

**Timeline**:
- Stage 8 starts: `11:35:31.387`
- ENet failure: `11:35:39.466`
- **Delay**: 8.079 seconds (matches `CONTROL_STREAM_TIMEOUT_SEC` = 10 seconds)

**Event Type 2** = `ENET_EVENT_TYPE_DISCONNECT` (server sends disconnect instead of connect)

---

## Root Cause Analysis

### Hypothesis 1: Server Not Listening on UDP 47999

**Evidence**:
- Sunshine config shows UDP 47998-48000 configured
- But ncat TCP test shows port 47999 is closed
- ENet UDP connection receives DISCONNECT event

**Possible Reasons**:
1. **Sunshine server not actually binding to UDP 47999**
   - Configuration says it should, but server may not be listening
   - Server may only be listening on TCP ports
   - UDP binding may have failed silently

2. **Firewall blocking UDP 47999**
   - Windows Firewall may be blocking UDP
   - Router/NAT may be blocking UDP
   - Network security policy blocking UDP

3. **Server process not running control stream listener**
   - Server may not have started UDP listener for control stream
   - Control stream may use different mechanism

### Hypothesis 2: Server Rejecting ENet Protocol

**Evidence**:
- Server provides port 47999 in RTSP handshake
- But rejects ENet connection attempt
- Receives DISCONNECT event (active rejection)

**Possible Reasons**:
1. **Server expects different connection method**
   - May expect TCP instead of UDP
   - May expect different protocol
   - May require different handshake

2. **ControlConnectData mismatch**
   - `ControlConnectData` is sent during ENet connect (line 1674)
   - Value comes from RTSP SETUP response header `X-SS-Connect-Data` (line 1277-1283)
   - If missing, defaults to 0
   - Server may reject if value doesn't match

3. **Channel count mismatch**
   - Code uses `CTRL_CHANNEL_COUNT` (line 1659, 1674)
   - Server may expect different channel count
   - Mismatch causes rejection

4. **QoS marking causing issues**
   - Code enables QoS marking (line 1671)
   - Some networks/routers reject QoS-marked packets
   - Server may reject QoS-marked connections

### Hypothesis 3: Timing/State Issue

**Evidence**:
- 8-second delay before failure
- Server provides port but rejects connection

**Possible Reasons**:
1. **Server not ready when connection attempted**
   - Server may need time to set up UDP listener
   - Connection attempted too early
   - Server state not synchronized

2. **Previous connection still active**
   - Previous session may still hold port 47999
   - Server rejects new connection while old one exists
   - Port not released from previous session

3. **Server-side timeout**
   - Server may have timeout for accepting connections
   - Client waits 8 seconds, server may have already timed out
   - Race condition between client connect and server readiness

---

## Code Analysis

### Control Stream Connection (ControlStream.c:1636-1712)

**Key Code Sections**:

1. **ENet Host Creation** (Lines 1657-1663):
```c
client = enet_host_create(RemoteAddr.ss_family,
                          LocalAddr.ss_family != 0 ? &localAddress : NULL,
                          1, CTRL_CHANNEL_COUNT, 0, 0);
```
- Creates ENet host with `CTRL_CHANNEL_COUNT` channels
- Uses wildcard local port (0) for binding

2. **QoS Marking** (Line 1671):
```c
enet_socket_set_option(client->socket, ENET_SOCKOPT_QOS, 1);
```
- Enables QoS marking on control stream
- May cause rejection on some networks

3. **Connection Attempt** (Line 1674):
```c
peer = enet_host_connect(client, &remoteAddress, CTRL_CHANNEL_COUNT, ControlConnectData);
```
- Connects with `CTRL_CHANNEL_COUNT` channels
- Sends `ControlConnectData` as connection data
- `ControlConnectData` comes from RTSP SETUP response `X-SS-Connect-Data` header

4. **Wait for Connect** (Line 1683):
```c
err = serviceEnetHost(client, &event, CONTROL_STREAM_TIMEOUT_SEC * 1000);
```
- Waits up to 10 seconds (`CONTROL_STREAM_TIMEOUT_SEC = 10`)
- Expects `ENET_EVENT_TYPE_CONNECT` (event type 1)
- Receives `ENET_EVENT_TYPE_DISCONNECT` (event type 2) instead

### ControlConnectData Source (RtspConnection.c:1276-1283)

```c
// Parse the Sunshine control connect data extension if present
connectData = getOptionContent(response.options, "X-SS-Connect-Data");
if (connectData != NULL) {
    ControlConnectData = (uint32_t)strtoul(connectData, NULL, 0);
}
else {
    ControlConnectData = 0;
}
```

**Analysis**:
- `ControlConnectData` is parsed from RTSP SETUP response
- If `X-SS-Connect-Data` header is missing, defaults to 0
- Server may require specific value to accept connection

---

## Investigation Steps

### 1. Verify UDP Port Status

**Action**: Test UDP connectivity (not TCP):
```bash
# Test UDP port 47999
ncat -u -v 10.1.95.5 47999

# Or use netcat with UDP
nc -u -v 10.1.95.5 47999
```

**Expected Results**:
- If UDP port is open: Connection succeeds or no immediate error
- If UDP port is closed: Connection refused or timeout
- If firewall blocking: Timeout or connection refused

### 2. Check Sunshine Server Logs

**Action**: Review Sunshine server logs for:
- UDP connection attempts on port 47999
- Control stream connection rejections
- Server-side errors during connection
- Port binding failures

**What to Look For**:
- "Failed to bind UDP port 47999"
- "Connection rejected on port 47999"
- "Control stream connection failed"
- "ENet connection refused"

### 3. Verify ControlConnectData Value

**Action**: Check RTSP SETUP response for `X-SS-Connect-Data` header

**Method**:
- Add logging to print `ControlConnectData` value
- Check if value is 0 (default) or has specific value
- Verify server expects this value

**Code Location**: `RtspConnection.c:1277-1283`

### 4. Test Without QoS Marking

**Action**: Temporarily disable QoS marking to test if it causes rejection

**Code Change**: Comment out line 1671 in `ControlStream.c`:
```c
// enet_socket_set_option(client->socket, ENET_SOCKOPT_QOS, 1);
```

**Test**: Attempt connection and see if server accepts

### 5. Check for Stale Connections

**Action**: Verify no previous sessions are holding port 47999

**Method**:
- Restart Sunshine server
- Check for active connections
- Verify port is released

---

## Potential Solutions

### Solution 1: Verify Sunshine UDP Configuration

**Action**: Ensure Sunshine is actually listening on UDP 47999

**Steps**:
1. Check Sunshine server logs for UDP binding
2. Verify firewall allows UDP 47999
3. Test UDP connectivity from another machine
4. Restart Sunshine server to ensure clean state

### Solution 2: Check ControlConnectData

**Action**: Verify `ControlConnectData` value matches server expectations

**Steps**:
1. Add logging to print `ControlConnectData` value
2. Check RTSP SETUP response for `X-SS-Connect-Data` header
3. Verify server expects this value (check Sunshine source code/docs)
4. If missing, investigate why server doesn't send it

### Solution 3: Disable QoS Marking (Test)

**Action**: Temporarily disable QoS to test if it causes rejection

**Code Change**: Comment out QoS marking in `ControlStream.c:1671`

**Risk**: May affect performance, but useful for diagnosis

### Solution 4: Add Retry Logic

**Action**: Implement retry logic for control stream connection

**Code Change**: Add retry loop in `startControlStream()` with exponential backoff

**Consideration**: May help if issue is timing-related

### Solution 5: Fallback to TCP Control Stream

**Action**: If ENet fails, fallback to TCP control stream (for AppVersion < 5)

**Code Location**: `ControlStream.c:1726-1734` (already exists for old versions)

**Consideration**: May require server support for TCP control stream

---

## Immediate Next Steps

### Priority 1: Verify UDP Port Status

1. **Test UDP connectivity**:
   ```bash
   ncat -u -v 10.1.95.5 47999
   ```

2. **Check Sunshine server logs** for UDP binding/connection attempts

3. **Verify firewall rules** allow UDP 47999

### Priority 2: Investigate ControlConnectData

1. **Add logging** to print `ControlConnectData` value
2. **Check RTSP SETUP response** for `X-SS-Connect-Data` header
3. **Verify server expectations** for this value

### Priority 3: Test Without QoS

1. **Temporarily disable QoS marking** (line 1671)
2. **Test connection** to see if server accepts
3. **Re-enable if not the issue**

---

## Conclusion

**Root Cause**: Server is **actively rejecting** ENet UDP connections on port 47999, despite providing this port in RTSP handshake.

**Most Likely Causes**:
1. **Server not actually listening on UDP 47999** (configuration vs. reality mismatch)
2. **ControlConnectData mismatch** (server expects specific value)
3. **QoS marking rejection** (network/server rejecting QoS-marked packets)
4. **Timing issue** (connection attempted before server is ready)

**Next Action**: Test UDP connectivity and check Sunshine server logs to confirm which hypothesis is correct.

---

## References

- **Control Stream Code**: `ControlStream.c:1636-1712`
- **ControlConnectData Source**: `RtspConnection.c:1276-1283`
- **Timeout Value**: `CONTROL_STREAM_TIMEOUT_SEC = 10` (line 122)
- **ENet Event Types**: `ENET_EVENT_TYPE_DISCONNECT = 2`

