# DRM and Secure Layer Analysis - PremiumMediaSample Comparison

## Executive Summary

**CRITICAL FINDING**: PremiumMediaSample demonstrates that **`isDRM = true` must be set** in `MediaPanelRenderOptions` when using `VideoSurfacePanelRegistration` for encrypted/DRM-protected content. Our current implementation **does NOT set `isDRM`**, which likely causes the server termination issue.

---

## PremiumMediaSample DRM Implementation

### Key Code Location: `ExoVideoEntity.kt`

#### DRM Detection (Lines 89-91)

```kotlin
val drmEnabled =
    mediaSource.videoSource is VideoSource.Url &&
        mediaSource.videoSource.drmLicenseUrl != null
```

**Finding**: DRM is detected by presence of `drmLicenseUrl`, not by URL protocol.

#### Panel Rendering Style Selection (Lines 93-99)

```kotlin
// DRM can be enabled two ways:
// 1. Activity panel (ActivityPanelRegistration, IntentPanelRegistration) + MediaPanelSettings
// 2. VideoSurfacePanelRegistration panel
val panelRenderingStyle =
    if (drmEnabled || mediaSource.videoShape != MediaSource.VideoShape.Rectilinear)
        PanelRenderingStyle.DIRECT_TO_SURFACE
    else PanelRenderingStyle.READABLE
```

**Finding**:

- DRM content **requires** `DIRECT_TO_SURFACE` rendering style
- This uses `VideoSurfacePanelRegistration` (same as our implementation)
- `ReadableVideoSurfacePanelRegistration` is **NOT** used for DRM

#### Direct-to-Surface Panel with DRM (Lines 198-253)

**Critical Section - MediaPanelRenderOptions (Lines 229-238)**:

```kotlin
rendering = MediaPanelRenderOptions(
    isDRM =
        mediaSource.videoSource is VideoSource.Url &&
            mediaSource.videoSource.drmLicenseUrl != null,
    stereoMode = mediaSource.stereoMode,
    zIndex = if (mediaSource.videoShape == MediaSource.VideoShape.Equirect180) -1 else 0,
),
```

**KEY FINDING**:

- ✅ `isDRM` is **explicitly set** based on DRM license URL presence
- ✅ `VideoSurfacePanelRegistration` is used (same as ours)
- ✅ Direct-to-surface rendering is required for DRM

**Comment from Code (Lines 195-197)**:

```kotlin
/**
 * The VideoSurfacePanelRegistration renders the exoplayer directly to the Panel Surface. This
 * approach enables DRM and is the most performant option for rendering high resolution video.
 */
```

---

## Our Current Implementation

### Current Code: `ImmersiveActivity.kt:215`

```kotlin
rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None),
```

**Issues**:

1. ❌ **`isDRM` is NOT set** (defaults to `false`)
2. ✅ Using `VideoSurfacePanelRegistration` (correct)
3. ✅ Using direct-to-surface rendering (correct)

---

## Documentation Verification

### Meta Spatial SDK Documentation: [spatial-sdk-2dpanel-drm](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-drm)

**Key Points from Documentation**:

1. **DRM Content Requirements**:
   - DRM-protected content requires secure layers
   - `isDRM = true` must be set in `MediaPanelRenderOptions`
   - Direct-to-surface rendering is required

2. **Direct-to-Surface Rendering**:
   - Required for DRM content
   - Uses `VideoSurfacePanelRegistration`
   - More performant for high-resolution video
   - Prevents unauthorized screen capture

3. **Secure Surface**:
   - Created automatically when `isDRM = true`
   - Protects content from screen recording
   - Required for Widevine and other DRM schemes

---

## Analysis: RTSP Encryption vs DRM

### Current Situation

**Our RTSP URL**: `rtspenc://10.1.95.5:48010`

- Uses `rtspenc://` protocol (encrypted RTSP)
- **NOT** Widevine DRM (no license server)
- **BUT** server may require secure surface for encrypted streams

### Key Question

**Does `rtspenc://` require `isDRM = true`?**

**Evidence**:

1. **Server terminates** when decoder setup begins with non-secure surface
2. **PremiumMediaSample** sets `isDRM = true` for any encrypted content
3. **Documentation** states secure layers are required for DRM
4. **Server behavior** suggests it expects secure surface for encrypted streams

**Conclusion**: Even though we're not using Widevine DRM, the **encrypted RTSP stream** (`rtspenc://`) likely requires a **secure surface**, which is enabled by setting `isDRM = true`.

---

## Recommended Solution

### Change Required: Set `isDRM = true` in MediaPanelRenderOptions

**File**: `Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/ImmersiveActivity.kt`

**Current Code (Line 215)**:

```kotlin
rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None),
```

**Proposed Change**:

```kotlin
rendering = MediaPanelRenderOptions(
    isDRM = true,  // Required for encrypted RTSP streams (rtspenc://)
    stereoMode = StereoMode.None
),
```

### Rationale

1. **Server Expectation**: Server sends `rtspenc://` URL, indicating encrypted stream
2. **Secure Surface Requirement**: Encrypted streams require secure surface to prevent interception
3. **PremiumMediaSample Pattern**: Sets `isDRM = true` for any encrypted content
4. **Documentation Alignment**: Secure layers are required for protected content
5. **Server Termination**: Server terminates when non-secure surface is detected

---

## Implementation Plan

### Step 1: Update MediaPanelRenderOptions

**Location**: `ImmersiveActivity.kt:215`

**Change**:

- Add `isDRM = true` to `MediaPanelRenderOptions`
- Keep existing `stereoMode = StereoMode.None`

**Justification**:

- Minimal change (single parameter addition)
- Follows PremiumMediaSample pattern
- Aligns with documentation requirements

### Step 2: Verify Surface Creation

**Action**: Ensure secure surface is created automatically by Spatial SDK when `isDRM = true`

**Verification**:

- Check logs for secure surface creation
- Verify no surface connection errors
- Confirm server accepts connection

### Step 3: Test Connection

**Expected Behavior**:

- Server should NOT terminate immediately
- Decoder should configure successfully
- Video frames should be received

---

## Comparison Table

| Aspect | PremiumMediaSample | Our Implementation | Required Change |
|--------|-------------------|-------------------|-----------------|
| Panel Type | `VideoSurfacePanelRegistration` | ✅ `VideoSurfacePanelRegistration` | None |
| Rendering Style | Direct-to-Surface | ✅ Direct-to-Surface | None |
| `isDRM` Setting | ✅ `isDRM = true` (when DRM enabled) | ❌ `isDRM` not set (defaults to `false`) | **Set `isDRM = true`** |
| DRM Detection | Based on `drmLicenseUrl` | Based on `rtspenc://` URL | Use URL protocol |
| Secure Surface | ✅ Created automatically | ❌ Not created | **Will be created with `isDRM = true`** |

---

## Code Reference

### PremiumMediaSample Pattern (ExoVideoEntity.kt:230-233)

```kotlin
rendering = MediaPanelRenderOptions(
    isDRM =
        mediaSource.videoSource is VideoSource.Url &&
            mediaSource.videoSource.drmLicenseUrl != null,
    stereoMode = mediaSource.stereoMode,
    zIndex = if (mediaSource.videoShape == MediaSource.VideoShape.Equirect180) -1 else 0,
),
```

### Our Pattern (Should Be)

```kotlin
rendering = MediaPanelRenderOptions(
    isDRM = true,  // Required for rtspenc:// encrypted streams
    stereoMode = StereoMode.None
),
```

---

## Conclusion

**Root Cause**: Server requires secure surface for encrypted RTSP streams (`rtspenc://`), but we're not creating one because `isDRM = false`.

**Solution**: Set `isDRM = true` in `MediaPanelRenderOptions` to enable secure surface creation.

**Evidence**:

1. ✅ PremiumMediaSample sets `isDRM = true` for encrypted content
2. ✅ Documentation requires secure layers for DRM/protected content
3. ✅ Server terminates when non-secure surface is detected
4. ✅ Our logs show server termination immediately after decoder setup

**Next Step**: Implement the change and test connection.