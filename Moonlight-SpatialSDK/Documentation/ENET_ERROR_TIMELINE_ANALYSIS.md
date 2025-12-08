# ENet Control Stream Error - Timeline Analysis

## Executive Summary

Analysis of log lines 6517-7257 leading up to the ENet control stream connection failure. The error occurs **8 seconds** after Stage 8 (control stream establishment) begins, suggesting a timeout or server-side rejection rather than an immediate failure.

---

## Critical Timeline

### Connection Initialization (Lines 6517-6533)

```
6517: HTTP launch request successful
6518: RTSP URL received: rtspenc://10.1.95.5:48010
6522: MoonBridge setupBridge called
6532: RTSP URL confirmed: rtspenc://10.1.95.5:48010
```

**Status**: ✅ Connection parameters received successfully

---

### RTSP Handshake (Lines 6559-6616)

```
6559: Starting RTSP handshake...
6560: Stage 4 starting: RTSP handshake
6574: NegotiatedVideoFormat set to VIDEO_FORMAT_H264 (1)
6575: Audio port: 48000
6576: ⚠️ ProxyLayerLogic warning (Type 9, ID -1)
6577: Video port: 47998
6594: Control port: 47999 ← This is the port that will fail
6616: Stage 4 complete: RTSP handshake
```

**Status**: ✅ RTSP handshake **completed successfully**

- Video format: H264
- Audio port: 48000
- Video port: 47998
- **Control port: 47999** (provided by server)

**Key Finding**: RTSP handshake succeeds, server provides control port 47999

---

### Stream Initialization Stages (Lines 6619-6636)

```
6619: Initializing control stream...
6620: Stage 5 starting: control stream initialization
6622: Stage 5 complete: control stream initialization
6625: Initializing video stream...
6626: Stage 6 starting: video stream initialization
6628: Stage 6 complete: video stream initialization
6631: Initializing input stream...
6632: Stage 7 starting: input stream initialization
6634: Stage 7 complete: input stream initialization
```

**Status**: ✅ All initialization stages complete successfully

---

### Control Stream Establishment - START (Lines 6637-6642)

```
6637: Starting control stream...
6638: Stage 8 starting: control stream establishment ← CRITICAL MOMENT
6639: stageStarting control stream establishment
6641: ⚠️ ProxyLayerLogic warning (Type 9, ID -1)
6642: ⚠️ ProxyLayerLogic warning (Type 9, ID -1)
```

**Status**: ⚠️ Stage 8 begins, ProxyLayerLogic warnings appear immediately

**Timing**: Stage 8 starts at `11:35:31.387`

---

### Gap Period (Lines 6643-7256)

**Duration**: ~8 seconds (from `11:35:31.387` to `11:35:39.466`)

**Notable Events During Gap**:

1. **Line 6724**: `E/VrDeviceManagerService: Permission denied: process 3469 (uid 10053) doesn't have the required permission: com.oculus.permission.SET_THREAD_POLICY`
   - **Process**: 3469 (VR Shell, not our app)
   - **Process**: Our app is 10713
   - **Analysis**: System-level permission error, **NOT related to our connection**

2. **Line 6747**: `W/PackageManager: Verification with id 94 not found. It may be invalid or overridden by integrity verification`
   - **Process**: 1971 (system_server)
   - **Analysis**: Package verification warning, **NOT related to our connection**

3. **Multiple ProxyLayerLogic warnings** (Lines 6576, 6641, 6642, 6595, 6601, 6615, etc.)
   - **Pattern**: `Proxy layer (Type 9, ID -1, content 0) was ignored because its content layer could not be found`
   - **Source**: Process 2832 (CompositorServer)
   - **Analysis**: Meta Spatial SDK compositor warnings, **likely unrelated to ENet**

---

### Control Stream Establishment - FAILURE (Line 7257)

```
7257: Failed to establish ENet connection on UDP port 47999: unexpected event 2 (error: 11)
7258: failed: 11
7259: Stage 8 failed: control stream establishment error=11
```

**Status**: ❌ ENet connection fails after ~8 second wait

**Timing**: Failure occurs at `11:35:39.466` (8.079 seconds after Stage 8 start)

---

## Analysis of Specific Lines

### Line 6576: ProxyLayerLogic Warning

```
12-07 11:35:31.350 W/ProxyLayerLogic( 2832): Proxy layer (Type 9, ID -1, content 0) was ignored because its content layer could not be found
```

**Context**:
- Occurs during RTSP handshake (between video port and control port announcements)
- Process 2832 = CompositorServer (Meta Spatial SDK system component)
- Type 9, ID -1 = System-level proxy layer

**Analysis**:

- **NOT related to our video panel** (our panel uses Type 1, has valid ID)
- System compositor warning about missing content layer
- Common in Meta Spatial SDK when system layers are being cleaned up/recreated
- **Unlikely cause of ENet failure** - occurs 8 seconds before failure

**Conclusion**: System-level compositor warning, unrelated to Moonlight connection

---

### Line 6724: VrDeviceManagerService Permission Error

```
12-07 11:35:32.375 E/VrDeviceManagerService( 1166): Permission denied: process 3469 (uid 10053) doesn't have the required permission: com.oculus.permission.SET_THREAD_POLICY
```

**Context**:
- Process 3469 = VR Shell (com.oculus.vrshell)
- Process 10713 = Our app (com.example.moonlight_spatialsdk)
- Error occurs ~1 second after Stage 8 starts

**Analysis**:
- **Different process** - VR Shell, not our app
- System-level permission issue for VR Shell
- **NOT related to our connection** - our app doesn't need this permission
- Occurs during system transitions (immersive mode changes)

**Conclusion**: VR Shell permission error, completely unrelated to Moonlight ENet connection

---

### Line 6747: PackageManager Verification Warning

```
12-07 11:35:32.841 W/PackageManager( 1971): Verification with id 94 not found. It may be invalid or overridden by integrity verification
```

**Context**:
- Process 1971 = system_server
- Generic package verification warning

**Analysis**:
- System-level package verification issue
- **NOT related to our connection**
- Common Android system warning

**Conclusion**: System package manager warning, unrelated to ENet connection

---

## Key Findings

### 1. RTSP Handshake Success

**Evidence**:

- ✅ RTSP handshake completes (Line 6616)
- ✅ Server provides control port 47999 (Line 6594)
- ✅ All stream initialization stages complete (Lines 6622, 6628, 6634)

**Conclusion**: Server knows about the connection and provides correct port

### 2. 8-Second Delay Before Failure

**Timeline**:

- Stage 8 starts: `11:35:31.387`
- ENet failure: `11:35:39.466`
- **Delay**: 8.079 seconds

**Analysis**:

- This matches `CONTROL_STREAM_TIMEOUT_SEC` timeout period
- Code waits for CONNECT event but receives DISCONNECT instead
- Suggests server **actively rejects** the connection, not a network timeout

### 3. ProxyLayerLogic Warnings

**Pattern**:

- Multiple warnings throughout the log
- Always: `Type 9, ID -1, content 0`
- Source: CompositorServer (process 2832)
- Timing: Appear during system transitions and RTSP handshake

**Analysis**:

- **System-level compositor warnings**
- Not related to our video panel
- Common during VR mode transitions
- **Unlikely cause** of ENet failure (timing doesn't match)

### 4. System Errors (Lines 6724, 6747)

**Analysis**:

- **Different processes** (VR Shell, system_server)
- **Not our app** (process 10713)
- System-level issues unrelated to Moonlight

**Conclusion**: These are red herrings - system noise, not connection issues

---

## Root Cause Hypothesis

### Most Likely: Server-Side Rejection

**Evidence**:

1. RTSP handshake succeeds (server knows about connection)
2. Server provides control port 47999
3. 8-second delay suggests timeout waiting for CONNECT event
4. Receives DISCONNECT event instead of CONNECT
5. Error 11 (EAGAIN) suggests socket operation would block

**Possible Reasons**:

1. **Server doesn't support ENet** for control stream on this port
2. **Server security policy** blocking the connection
3. **Server already has connection** from previous attempt
4. **Network/firewall** blocking UDP port 47999
5. **Protocol mismatch** - server expects different connection parameters

### Unlikely: Client-Side Issues

**Evidence Against**:

- ✅ RTSP handshake succeeds
- ✅ All initialization stages complete
- ✅ No client-side errors before Stage 8
- ✅ System warnings are unrelated (different processes)

---

## ProxyLayerLogic Warnings - Detailed Analysis

### What They Are

**Source**: Meta Spatial SDK CompositorServer (process 2832)  
**Type**: System-level compositor warnings  
**Pattern**: `Proxy layer (Type 9, ID -1, content 0) was ignored because its content layer could not be found`

### When They Occur

1. **During RTSP handshake** (Line 6576, 6595, 6601, 6615)
2. **Right after Stage 8 starts** (Lines 6641, 6642)
3. **Throughout system transitions** (multiple occurrences)

### Why They're Unrelated

1. **Different Process**: CompositorServer (2832) vs Our App (10713)
2. **System Component**: Meta Spatial SDK internal compositor
3. **Type 9, ID -1**: System proxy layer, not our panel
4. **Timing**: Occur throughout, not correlated with ENet failure
5. **Common Pattern**: These warnings are normal during VR transitions

### Our Panel Configuration

**Our Panel**:

- Uses `VideoSurfacePanelRegistration`
- Has valid panel ID (`R.id.ui_example`)
- Type: Media panel (not Type 9)
- **No ProxyLayerLogic errors for our panel**

**Conclusion**: ProxyLayerLogic warnings are system noise, not related to our connection

---

## Recommendations

### 1. Focus on Server-Side Investigation

**Action**: Check Sunshine server logs for:

- Control stream connection attempts on port 47999
- Rejection reasons
- Server-side errors during connection

### 2. Verify Network Connectivity

**Action**: Test UDP port 47999 connectivity:

```bash
# Test UDP connectivity
nc -u -v 10.1.95.5 47999

# Check firewall rules
# Verify NAT/VPN isn't blocking
```

### 3. Check for Stale Connections

**Action**: Verify no previous connections are holding port 47999:
- Check server for active connections
- Restart Sunshine server if needed
- Verify no other Moonlight clients connected

### 4. Ignore System Warnings

**Action**: Filter out system-level warnings:
- ProxyLayerLogic (system compositor)
- VrDeviceManagerService (VR Shell)
- PackageManager (system server)

These are **not related** to the ENet connection failure.

---

## Conclusion

### Timeline Summary

1. **11:35:31.318**: RTSP handshake starts
2. **11:35:31.341-6594**: RTSP handshake completes, ports negotiated
3. **11:35:31.387**: Stage 8 starts (control stream establishment)
4. **11:35:31.350-6642**: ProxyLayerLogic warnings (system noise)
5. **11:35:32.375**: VR Shell permission error (unrelated)
6. **11:35:32.841**: PackageManager warning (unrelated)
7. **11:35:39.466**: ENet connection fails (8 seconds later)

### Root Cause

**Most Likely**: Server-side rejection of ENet control stream connection
- Server provides port but rejects connection
- 8-second delay suggests timeout waiting for CONNECT
- Receives DISCONNECT instead, indicating active rejection

**Unlikely**: Client-side issues
- RTSP handshake succeeds
- All initialization stages complete
- System warnings are unrelated (different processes)

### Next Steps

1. **Check Sunshine server logs** for control stream connection attempts
2. **Test UDP connectivity** to port 47999
3. **Verify server configuration** for ENet control stream support
4. **Ignore system warnings** (ProxyLayerLogic, VrDeviceManagerService, PackageManager)

---

## Appendix: Process IDs Reference

- **10713**: Our app (`com.example.moonlight_spatialsdk`)
- **2832**: CompositorServer (Meta Spatial SDK)
- **3469**: VR Shell (`com.oculus.vrshell`)
- **1971**: system_server (Android system)
- **1166**: VrDeviceManagerService (VR system service)

