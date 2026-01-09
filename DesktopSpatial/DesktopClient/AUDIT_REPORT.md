# DesktopClient & Quest 3 App - Complete Audit Report

**Date:** January 8, 2026  
**Status:** CRITICAL ISSUES IDENTIFIED  
**Exit Code:** -805306369 (Windows exception)

---

## Executive Summary

**Primary Issues:**

1. **DesktopClient crashes** with exit code -805306369 after depth processing completes
2. **Sunshine NOT capturing from virtual display** - Falls back to DISPLAY1 (2560x1440) instead of DISPLAY15 (5120x1440)
3. **Quest 3 app correctly requests 5120x1440** but receives 2560x1440 stream from Sunshine

**Root Causes:**

- OpenGL rendering crash in `virtual_display.py::render_frame()` - likely invalid OpenGL context or texture upload failure
- Window not capturable by DXGI Desktop Duplication (WS_EX_TOOLWINDOW flag issue - PARTIALLY FIXED)
- Sunshine fails to find DISPLAY15 device for capture, falls back to DISPLAY1

---

## DesktopClient Audit

### 1. Main Loop (`main.py`)

**Status:** ✅ LOGICALLY CORRECT

**Flow:**

1. Capture frame (DXGI) → 2560x1440
2. Process depth (DA3) → depth map
3. Generate SBS → 5120x1440
4. Render to virtual display → OpenGL window on DISPLAY15

**Issues Found:**

- ✅ Exception handling around SBS generation and rendering
- ✅ CUDA synchronization before rendering
- ⚠️ **NO error handling for OpenGL context loss** - if OpenGL context becomes invalid, crash is silent until Python exits

**Crash Point Analysis:**

- Crash occurs AFTER depth processing completes (logs show depth stats)
- Crash occurs BEFORE or DURING rendering (exit code suggests OpenGL/Windows API failure)
- Exit code -805306369 = 0xCFFFFFFF (not standard Windows exception, likely OpenGL driver crash)

---

### 2. Virtual Display Renderer (`render/virtual_display.py`)

**Status:** ❌ CRITICAL ISSUES

#### Issue #1: Window Creation Flags (PARTIALLY FIXED)

**Location:** Line 290-291

**Problem:**
- **ORIGINAL:** `WS_EX_TOPMOST | WS_EX_TOOLWINDOW` - Tool windows are NOT capturable by DXGI Desktop Duplication
- **CURRENT:** `WS_EX_TOPMOST | WS_EX_NOACTIVATE` - Better, but may still have issues

**Impact:**

- DXGI Desktop Duplication API skips tool windows
- Sunshine cannot capture from DISPLAY15 window
- Falls back to DISPLAY1

**Fix Applied:**

- Removed `WS_EX_TOOLWINDOW`
- Added `WS_EX_NOACTIVATE` to prevent focus stealing

**Remaining Risk:**

- Window may still not be fully capturable if it's not "active" enough
- DXGI requires window to be visible, not minimized, and have actual content

#### Issue #2: OpenGL Context Management

**Location:** Lines 552-590 (`render_frame()`)

**Problems:**

1. **No context validation before use** - `wglMakeCurrent()` may fail silently
2. **No OpenGL error checking** - `glGetError()` never called
3. **Texture upload without validation** - `glTexImage2D()` can fail if:
   - Context is lost
   - Texture ID is invalid
   - Frame data is malformed
   - Memory allocation fails

**Crash Scenario:**

```
1. Depth processing completes successfully
2. SBS frame generated (5120x1440 tensor)
3. Frame converted to numpy (CPU)
4. wglMakeCurrent() called - MAY FAIL if context lost
5. glTexImage2D() called with 5120x1440 frame
6. OpenGL driver crashes (invalid context or memory issue)
7. Python process terminated with -805306369
```

**Missing Error Handling:**

- No `glGetError()` after OpenGL calls
- No validation that `wglMakeCurrent()` succeeded
- No check that texture ID is valid
- No validation that frame data is contiguous/valid

#### Issue #3: Window Visibility for DXGI Capture

**Location:** Lines 300-341

**Problems:**
1. Window shown with `SW_SHOWMAXIMIZED` but may not be on correct display
2. `SetForegroundWindow()` may fail (caught but ignored)
3. Window position set AFTER creation - may cause DXGI to miss it
4. **No verification that window is actually visible to DXGI APIs**

**DXGI Capture Requirements:**
- Window must be on target display
- Window must be visible (not minimized)
- Window must have content (not blank)
- Window must be "real" application window (not tool window) ✅ FIXED

**Missing:**
- No check that window is actually on DISPLAY15
- No verification that DXGI can see the window
- No test render to ensure content is visible

---

### 3. Depth Processor (`depth/depth_processor.py`)

**Status:** ✅ MOSTLY CORRECT

**Issues Found:**
- ✅ CUDA synchronization before/after inference
- ✅ Error handling for CUDA OOM
- ✅ Depth map validation
- ✅ Depth enhancement (gamma correction)
- ⚠️ **No validation that depth map is valid before passing to SBS generator**

**Potential Issue:**
- If depth map has invalid values (NaN, Inf), SBS generation may crash
- No check for tensor validity before SBS generation

---

### 4. SBS Generator (`sbs/stereo_generator.py`)

**Status:** ✅ LOGICALLY CORRECT

**Issues Found:**
- ✅ CUDA synchronization before tensor value access
- ✅ Depth map normalization
- ✅ Parallax calculation
- ✅ Grid sampling for eye views
- ⚠️ **No validation that input tensors are valid** (no NaN/Inf checks)

**Potential Issue:**
- If depth map contains invalid values, `grid_sample()` may crash
- No bounds checking on coordinate shifts

---

### 5. DXGI Capture (`capture/dxgi_capture.py`)

**Status:** ✅ CORRECT

**Issues Found:**
- ✅ BGR to RGB conversion with `.copy()` (fixes negative stride)
- ✅ Resize if needed
- ✅ GPU tensor conversion
- No issues identified

---

## Quest 3 App Audit

### 1. Stream Configuration (`MoonlightConnectionManager.kt`)

**Status:** ✅ CORRECT

**Code Analysis:**
```kotlin
// Line 250: Correctly doubles width for stereoscopic mode
val streamWidth = if (prefs.stereoscopicModeEnabled) prefs.width * 2 else prefs.width
val streamHeight = prefs.height

// Line 259: Correctly sets resolution
.setResolution(streamWidth, streamHeight)
```

**Verification:**
- ✅ If `prefs.width = 2560` and `stereoscopicModeEnabled = true` → `streamWidth = 5120` ✅
- ✅ If `prefs.width = 1280` and `stereoscopicModeEnabled = true` → `streamWidth = 2560` ⚠️ **THIS IS THE ISSUE**

**CRITICAL FINDING:**
- Quest 3 app requests `5120x1440` ONLY if `prefs.width = 2560`
- If user has `prefs.width = 1280`, app requests `2560x1440` (correct for 1280x720 per eye)
- **User's preference may be set to 1280x720, causing 2560x1440 request**

**Sunshine Log Evidence:**
- Line 344: `mode -- 5120x1440x60` - Quest 3 IS requesting 5120x1440 ✅
- But Sunshine captures 2560x1440 - **Sunshine is ignoring the request and using DISPLAY1**

---

### 2. Panel Registration (`ImmersiveActivity.kt`)

**Status:** ✅ CORRECT

**Code Analysis:**
```kotlin
// Line 1317-1318: Correctly calculates expected dimensions
val expectedWidth = prefs.width * 2
val expectedHeight = prefs.height

// Line 1373-1374: Correctly sets PixelDisplayOptions
val displayWidth = prefs.width * 2
val displayHeight = prefs.height

// Line 1383: Correctly sets StereoMode.LeftRight
stereoMode = StereoMode.LeftRight
```

**Verification:**
- ✅ Panel expects 5120x1440 when `prefs.width = 2560`
- ✅ StereoMode.LeftRight is set correctly
- ✅ Panel visibility is set immediately (shows black screen while connecting)

**No Issues Found**

---

## Root Cause Analysis

### Issue #1: DesktopClient Crash (-805306369)

**Most Likely Cause:** OpenGL context loss or invalid texture upload

**Evidence:**
1. Crash occurs AFTER depth processing (logs show depth stats)
2. Crash occurs DURING or BEFORE rendering completes
3. Exit code suggests OpenGL driver crash
4. No OpenGL error checking in `render_frame()`

**Failure Points:**
1. `wglMakeCurrent()` fails silently - context may be lost
2. `glTexImage2D()` with 5120x1440 frame may exceed driver limits
3. OpenGL context may be invalid if window loses focus or display changes
4. Texture ID may be invalid if OpenGL context was recreated

**Fix Required:**
- Add `glGetError()` after every OpenGL call
- Validate `wglMakeCurrent()` return value
- Check OpenGL context validity before rendering
- Add error recovery (recreate context if lost)

---

### Issue #2: Sunshine Not Capturing from DISPLAY15

**Root Cause:** Window not capturable by DXGI Desktop Duplication

**Evidence:**
1. Sunshine logs: `Failed to find device for \\.\DISPLAY15!`
2. Sunshine falls back to DISPLAY1 (2560x1440)
3. Works with Apple Vision Pro (different timing/client)
4. Window created with `WS_EX_TOOLWINDOW` (FIXED, but may need more)

**Why It Works with Apple Vision Pro:**
- Different connection timing
- Apple Vision Pro may use different capture method
- Window may be in different state when Apple Vision Pro connects

**Why It Fails with Quest 3:**
- Quest 3 connects while DesktopClient is initializing
- Window may not be fully rendered/visible when Sunshine tries to capture
- DXGI Desktop Duplication requires window to be "active" and have content

**Additional Issues:**
1. Window created with `WS_EX_NOACTIVATE` - may prevent DXGI from seeing it as "active"
2. Window position set AFTER creation - may cause timing issues
3. No verification that window is actually on DISPLAY15
4. No test render to ensure content is visible to DXGI

**Fix Required:**
- Ensure window is fully rendered BEFORE allowing connections
- Add window visibility verification
- Consider removing `WS_EX_NOACTIVATE` if it prevents capture
- Add delay/readiness check before allowing connections

---

### Issue #3: Stream Continues After DesktopClient Crash

**Root Cause:** Sunshine is NOT using virtual display

**Evidence:**
- User reports: "closing the desktopclient after it crashes and I still see the desktop just fine in quest 3"
- This PROVES Sunshine is capturing from DISPLAY1 (physical monitor), not DISPLAY15 (virtual display)

**Why:**
- Sunshine failed to find DISPLAY15 for capture
- Sunshine fell back to DISPLAY1
- DISPLAY1 continues to show desktop even when DesktopClient crashes
- Stream continues because Sunshine is capturing physical monitor, not virtual display

**This is NOT a DesktopClient issue - this is a Sunshine configuration/capture issue**

---

## Critical Fixes Required

### Fix #1: OpenGL Error Handling in `render_frame()`

**File:** `render/virtual_display.py`  
**Location:** Lines 552-590

**Changes:**
1. Add `glGetError()` after every OpenGL call
2. Validate `wglMakeCurrent()` return value
3. Check OpenGL context validity
4. Add error recovery (recreate context if lost)

### Fix #2: Window Readiness for DXGI Capture

**File:** `render/virtual_display.py`  
**Location:** Lines 76-138 (initialize method)

**Changes:**
1. Ensure window is fully rendered BEFORE returning from `initialize()`
2. Add test render and buffer swap to ensure content is visible
3. Add delay/verification that window is ready for capture
4. Consider removing `WS_EX_NOACTIVATE` if it prevents capture

### Fix #3: Window Position Verification

**File:** `render/virtual_display.py`  
**Location:** Lines 266-350 (`_create_window`)

**Changes:**
1. Verify window is actually on DISPLAY15 after creation
2. Add check that window position matches virtual display position
3. Log window position and virtual display position for verification

---

## Quest 3 App - No Issues Found

**Status:** ✅ CORRECT

**Verification:**
- ✅ Stream width correctly doubled when `stereoscopicModeEnabled = true`
- ✅ Panel correctly configured with `StereoMode.LeftRight`
- ✅ `PixelDisplayOptions` correctly set to `width * 2`
- ✅ Panel visibility set immediately (shows black screen while connecting)

**The Quest 3 app is working correctly. The issue is entirely on the DesktopClient/Sunshine side.**

---

## Summary of Issues

### DesktopClient

1. **CRITICAL:** OpenGL rendering crash - no error handling
2. **CRITICAL:** Window not capturable by DXGI (partially fixed)
3. **HIGH:** No OpenGL context validation
4. **HIGH:** No window readiness verification
5. **MEDIUM:** No tensor validation before SBS generation

### Quest 3 App

**NO ISSUES FOUND** - App is correctly requesting 5120x1440 and configuring panel for SBS

### Sunshine Integration

1. **CRITICAL:** Sunshine fails to capture from DISPLAY15
2. **CRITICAL:** Falls back to DISPLAY1 without user notification
3. **HIGH:** Window may not be ready when Sunshine tries to capture

---

## Recommended Fixes (Priority Order)

### Priority 1: Fix OpenGL Crash

1. Add `glGetError()` after every OpenGL call in `render_frame()`
2. Validate `wglMakeCurrent()` return value
3. Add OpenGL context validation before rendering
4. Add error recovery (recreate context if lost)

### Priority 2: Ensure Window is Capturable

1. Remove `WS_EX_NOACTIVATE` if it prevents capture (test both)
2. Add test render and buffer swap in `initialize()` to ensure content is visible
3. Add window position verification
4. Add delay/readiness check before allowing connections

### Priority 3: Add Comprehensive Error Handling

1. Add tensor validation (NaN/Inf checks)
2. Add OpenGL context state checking
3. Add window visibility verification
4. Add DXGI capture readiness verification

---

## Files Requiring Changes

1. `render/virtual_display.py` - OpenGL error handling, window readiness
2. `sbs/stereo_generator.py` - Tensor validation (optional)
3. `depth/depth_processor.py` - Tensor validation (optional)
4. `main.py` - Add readiness check before starting loop (optional)

---

## Testing Requirements

1. **Verify OpenGL crash is fixed** - Run DesktopClient and check for crash
2. **Verify Sunshine captures from DISPLAY15** - Close DesktopClient, stream should STOP
3. **Verify window is visible to DXGI** - Use dxgi-info.exe to verify window is on DISPLAY15
4. **Verify Quest 3 receives 5120x1440** - Check logs for negotiated resolution

---

## Conclusion

**DesktopClient has critical OpenGL error handling issues causing crashes.**  
**Window is not fully capturable by DXGI Desktop Duplication.**  
**Quest 3 app is working correctly - no changes needed.**

The primary issue is that **Sunshine cannot capture from the virtual display window**, causing it to fall back to the physical monitor. This is why the stream continues even after DesktopClient crashes.
