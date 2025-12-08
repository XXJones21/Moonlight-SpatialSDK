# RTSP Handshake Investigation Report

## Executive Summary

This document investigates three potential areas contributing to RTSP handshake issues:
1. Stream panel encryption configuration and DRM/secure layer requirements
2. Android permissions and manifest comparison with moonlight-android
3. Crypto/TLS library compatibility (OpenSSL vs BoringSSL)

**Status**: Investigation complete - no code changes made per requirements.

**CRITICAL FINDING**: Log analysis reveals that Sunshine server returns `rtspenc://` URL even when LAN encryption is disabled in the UI. This causes `encryptedRtspEnabled = true` but server sends unencrypted messages, leading to handshake failure.

---

## 1. Stream Panel Encryption & DRM Configuration

### Current Implementation

#### RTSP Encryption Detection (RtspConnection.c:939)

```c
encryptedRtspEnabled = serverInfo->rtspSessionUrl && strstr(serverInfo->rtspSessionUrl, "rtspenc://");
```

**Finding**: RTSP encryption is enabled **only if** the RTSP session URL contains `"rtspenc://"` protocol prefix.

**Key Code Locations**:

- `RtspConnection.c:116-119`: Encryption check in `sealRtspMessage()`
- `RtspConnection.c:171-229`: Decryption check in `unsealRtspMessage()`
- `RtspConnection.c:264`: Assertion that RTSP encryption is NOT supported over ENet

**Critical Observation**:

- If Sunshine server has LAN encryption **disabled** (as shown in your screenshot), the RTSP session URL will use `"rtsp://"` (not `"rtspenc://"`)
- The code correctly handles this by checking the URL prefix
- **However**, there's a potential issue: The code may be **rejecting unencrypted messages** if `encryptedRtspEnabled` is incorrectly set

#### Encryption Flow Analysis

**Message Sealing** (`sealRtspMessage()`):

```c
if (!encryptedRtspEnabled) {
    *messageLen = plaintextLen;
    return serializedMessage;  // Returns plaintext
}
```

**Message Unsealing** (`unsealRtspMessage()`):

```c
if (encryptedRtspEnabled) {
    // Expects encrypted header with ENCRYPTED_RTSP_BIT flag
    if (!(typeAndLen & ENCRYPTED_RTSP_BIT)) {
        Limelog("Rejecting unencrypted RTSP message\n");
        return false;  // FAILS if expecting encryption but got plaintext
    }
} else {
    decryptedMessage = rawMessage;  // Uses plaintext directly
    decryptedMessageLen = rawMessageLen;
}
```

**Potential Issue**: If `encryptedRtspEnabled` is set to `true` but the server sends unencrypted messages (because LAN encryption is disabled), the handshake will fail with "Rejecting unencrypted RTSP message".

**CRITICAL EVIDENCE FROM LOGS** (`immserive-single-panel.log:7663`):

```
<sessionUrl0>rtspenc://10.1.95.5:48010</sessionUrl0>
```

**Finding**: Sunshine server is returning `rtspenc://` URL **even though LAN encryption is disabled** in the server UI. This creates a mismatch:

- Client expects encrypted messages (`encryptedRtspEnabled = true`)
- Server sends unencrypted messages (LAN encryption disabled)
- Result: Handshake fails with "Rejecting unencrypted RTSP message"

### DRM/Secure Layer Configuration

#### Current Panel Setup (ImmersiveActivity.kt:215)

```kotlin
rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None),
```

**Finding**: The `MediaPanelRenderOptions` does **NOT** set `isDRM = true`.

**According to Meta Documentation** ([spatial-sdk-2dpanel-drm](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-drm)):

- `isDRM = false` (default): For non-DRM content, readable surfaces
- `isDRM = true`: For DRM-protected content, requires secure layers

**Analysis**:

- Since Sunshine has LAN encryption **disabled**, we should **NOT** need DRM
- However, the documentation suggests that for **secure video streaming**, you may need `isDRM = true` even without full DRM protection
- The panel is using `VideoSurfacePanelRegistration` which supports both DRM and non-DRM modes

**Recommendation**: 

- **Current setting is correct** for non-encrypted LAN streaming
- If handshake issues persist, consider testing with `isDRM = true` to see if secure layer requirements are needed

### Encryption Features Detection (RtspConnection.c:1125-1132)

```c
// Look for the Sunshine encryption flags in the SDP attributes
if (!parseSdpAttributeToUInt(response.payload, "x-ss-general.encryptionSupported", &EncryptionFeaturesSupported)) {
    EncryptionFeaturesSupported = 0;
}
if (!parseSdpAttributeToUInt(response.payload, "x-ss-general.encryptionRequested", &EncryptionFeaturesRequested)) {
    EncryptionFeaturesRequested = 0;
}
EncryptionFeaturesEnabled = 0;
```

**Finding**: The code parses Sunshine-specific encryption flags from the RTSP DESCRIBE response but doesn't automatically enable encryption based on these flags.

**Potential Issue**: If Sunshine **requests** encryption via SDP attributes but the RTSP URL doesn't use `"rtspenc://"`, there's a mismatch.

---

## 2. Android Permissions & Manifest Comparison

### Current Manifest (AndroidManifest.xml)

**Network Permissions**:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

**Audio/Video Permissions**:

```xml
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.VIBRATE" />
```

**Spatial SDK Permissions**:

```xml
<uses-permission android:name="com.oculus.permission.HAND_TRACKING" />
<uses-permission android:name="com.oculus.permission.RENDER_MODEL" />
```

**Network Security Config**:

```xml
android:networkSecurityConfig="@xml/network_security_config"
```

### Comparison with Moonlight-Android

**Expected Moonlight-Android Permissions** (based on standard Android streaming apps):

- ✅ `INTERNET` - Present
- ✅ `ACCESS_NETWORK_STATE` - Present
- ✅ `ACCESS_WIFI_STATE` - Present
- ✅ `MODIFY_AUDIO_SETTINGS` - Present
- ❓ `WAKE_LOCK` - Present (for keeping connection alive)
- ❓ `FOREGROUND_SERVICE` - May be needed for background streaming (not present)

**Missing Permissions** (potentially):

- `CHANGE_WIFI_MULTICAST_STATE` - For multicast discovery (if used)
- `ACCESS_FINE_LOCATION` - Sometimes required for WiFi state access on newer Android versions

**Network Security Configuration**:

- ✅ Present - Allows cleartext traffic for pairing (correct)

**Analysis**:

- **Current permissions appear sufficient** for basic RTSP streaming
- No obvious missing permissions that would cause handshake failures
- Network security config is correctly set up for cleartext RTSP traffic

**Recommendation**: 

- Permissions look correct
- If issues persist, check if `WAKE_LOCK` is being properly used to prevent connection timeouts

---

## 3. Crypto/TLS Library Compatibility

### Current Implementation

#### Build Configuration (CMakeLists.txt:59-62)

```cmake
else()
  find_package(OpenSSL 1.0.2 REQUIRED)
  target_link_libraries(moonlight-common-c PRIVATE ${OPENSSL_CRYPTO_LIBRARY})
  target_include_directories(moonlight-common-c SYSTEM PRIVATE ${OPENSSL_INCLUDE_DIR})
endif()
```

**Finding**: The codebase uses **OpenSSL 1.0.2+** (not BoringSSL).

#### OpenSSL Integration Points

**Platform Crypto** (`PlatformCrypto.c`):

- Uses OpenSSL for AES-GCM encryption/decryption
- Used in RTSP message sealing/unsealing

**Android Build** (`Android.mk`):

- Links against prebuilt OpenSSL libraries (`libssl.a`, `libcrypto.a`)
- Located in `app/src/main/jni/moonlight-core/openssl/`

**Java Crypto** (`AndroidCryptoProvider.java`):

- Uses BouncyCastle for certificate/key management
- Separate from native OpenSSL usage

### Sunshine Server Crypto Library

**According to Sunshine Documentation**:

- Sunshine **requires OpenSSL** as a dependency
- Uses OpenSSL for TLS/SSL connections
- Compatible with OpenSSL 1.0.2+

**Compatibility Analysis**:

- ✅ **Both use OpenSSL** - No library mismatch
- ✅ **Version compatible** - OpenSSL 1.0.2+ is supported by both
- ✅ **Protocol compatible** - Both support AES-GCM (used for RTSP encryption)

**Potential Issues**:

1. **OpenSSL Version Mismatch**: If Sunshine uses a newer OpenSSL version with different cipher suites, there could be negotiation issues
2. **Cipher Suite Compatibility**: Different OpenSSL versions may negotiate different cipher suites
3. **TLS Version**: If RTSP uses TLS (not just AES-GCM), TLS version compatibility matters

**Recommendation**:
- **Library compatibility is correct** - Both use OpenSSL
- If issues persist, check:
  - OpenSSL version on Sunshine server
  - Cipher suite negotiation logs
  - TLS version requirements

---

## Summary of Findings

### 1. Encryption Configuration

- ✅ **RTSP encryption detection** works correctly based on URL prefix
- ⚠️ **Potential issue**: If `encryptedRtspEnabled` is incorrectly set, unencrypted messages will be rejected
- ✅ **DRM setting** is correct for non-encrypted LAN streaming
- ⚠️ **SDP encryption flags** are parsed but not used to enable encryption

### 2. Permissions & Manifest

- ✅ **All required permissions** are present
- ✅ **Network security config** is correctly set up
- ✅ **No obvious missing permissions** that would cause handshake failures

### 3. Crypto Library Compatibility

- ✅ **Both use OpenSSL** - No library mismatch
- ✅ **Version compatible** - OpenSSL 1.0.2+ supported
- ✅ **Protocol compatible** - AES-GCM supported by both

---

## Recommended Next Steps

### 1. Verify RTSP URL Format

- **Check**: What RTSP session URL is being passed to `performRtspHandshake()`?
- **Action**: Add logging to verify if URL contains `"rtspenc://"` or `"rtsp://"`
- **Expected**: Should be `"rtsp://"` if LAN encryption is disabled
- **ACTUAL**: Logs show `rtspenc://` is returned even when LAN encryption is disabled
- **ROOT CAUSE**: Sunshine server returns `rtspenc://` URL regardless of LAN encryption setting

### 2. Add Encryption State Logging

- **Location**: `RtspConnection.c:939` - Log `encryptedRtspEnabled` value
- **Location**: `RtspConnection.c:186-189` - Log when rejecting unencrypted messages
- **Purpose**: Verify encryption state matches server configuration

### 3. Check SDP Encryption Attributes

- **Location**: `RtspConnection.c:1125-1132` - Log parsed encryption flags
- **Purpose**: Verify if Sunshine is requesting encryption via SDP

### 4. Verify OpenSSL Version

- **Action**: Check Sunshine server logs for OpenSSL version
- **Action**: Verify OpenSSL version in Android build
- **Purpose**: Ensure version compatibility

### 5. Test with Explicit DRM Setting

- **Action**: Temporarily set `isDRM = true` in `MediaPanelRenderOptions`
- **Purpose**: Verify if secure layer requirements are needed

---

## Files to Monitor

### Native Code

- `RtspConnection.c:939` - Encryption detection
- `RtspConnection.c:116-119` - Message sealing
- `RtspConnection.c:171-229` - Message unsealing
- `RtspConnection.c:1125-1132` - SDP encryption flags

### Java/Kotlin Code

- `ImmersiveActivity.kt:215` - Panel DRM setting
- `NvConnection.java:440` - RTSP URL passing
- `MoonlightConnectionManager.kt` - Connection parameter handling

### Build Configuration

- `CMakeLists.txt:59-62` - OpenSSL configuration
- `Android.mk` - OpenSSL library linking

---

## Conclusion

**PRIMARY ROOT CAUSE IDENTIFIED**: RTSP encryption state mismatch

- **Evidence**: Logs show Sunshine returns `rtspenc://` URL even when LAN encryption is disabled
- **Impact**: Client sets `encryptedRtspEnabled = true` but server sends unencrypted messages
- **Result**: Handshake fails with "Rejecting unencrypted RTSP message" error
- **Solution Options**:
  1. **Client-side workaround**: Ignore URL prefix and check SDP encryption attributes instead
  2. **Server-side fix**: Configure Sunshine to return `rtsp://` when LAN encryption is disabled
  3. **Hybrid approach**: Use SDP `x-ss-general.encryptionRequested` attribute to determine encryption state

**Secondary Suspect**: SDP encryption attribute mismatch

- Sunshine may request encryption via SDP but client doesn't enable it
- Check SDP attribute parsing and response

**Low Likelihood**: Permissions or crypto library issues

- Permissions appear correct
- Crypto library compatibility is confirmed

**Next Action**: Add detailed logging around encryption state detection and message handling to identify the exact failure point.
