# Keyboard Vanishing Issue - Detailed Log Analysis

## Overview

The system keyboard briefly appears and then becomes invisible/non-interactive, while still blocking interaction with ConnectionPanels. This document provides a comprehensive breakdown of the keyboard lifecycle events from the log analysis.

## Timeline of Events

### Phase 1: Initial Keyboard Closure (21:25:07.003 - 21:25:07.280)

#### Trigger Event

- **21:25:07.003**: `PinchObserver` detects a pinch gesture (Hand: 1, Finger: 1)
- This gesture triggers the keyboard dismissal sequence

#### Keyboard Closure Sequence

- **21:25:07.006** (7x repeated): `SystemKeyboardController::DeferCloseKeyboard` called with:
  - `isKeyboardActive: true`
  - `panelHost: true` 
  - `VWHost: true`
  
  **Analysis**: Multiple rapid calls suggest a race condition or retry logic attempting to close the keyboard.

- **21:25:07.007**: `SystemKeyboardController::Closing keyboard`
  - `hasTransitioningKeyboardActivation: 0`
  - `isExiting: 1`
  - **Critical**: `No focused panel entity; skipping save` - indicates focus loss before proper cleanup

#### Panel Destruction

- **21:25:07.007**: `PanelAppHost destructor` for panel ID **437**
  - Component: `com.oculus.vrshell/com.oculus.panelapp.keyboardv2.KeyboardPanelService`
  - **Warning**: `PanelAppHost entity destroyed externally without ExitNextFrame - marking as SystemClosure`
  
  **Analysis**: The panel is being destroyed without proper cleanup, indicating an abnormal termination.

- **21:25:07.007**: Panel state transition: `foreground → shutdown`
- **21:25:07.008**: `CppPanelService::onDestroy()` called
- **21:25:07.013**: Panel lifecycle reaches `DESTROYED` state
- **21:25:07.013**: `AndroidPanelApp::Destroyed`

#### Volumetric Window Cleanup Issues

- **21:25:07.007**: `removeVolumetricWindow` called for panel ID 437
  - Token: `953c796f-3e25-4008-b21e-7d09555c8cba`
  - Window properties show: `visible: true, active: true, drawn: true` at time of removal
  
- **21:25:07.019**: Second volumetric window removed (IME window)
  - Token: `1a437130-a60a-40c8-ad50-f5dfce2f1c77`
  - Type: 4 (IME window)
  - **Critical**: Window still marked as `visible: true, active: true` when removed

- **21:25:07.023**: **Warning**: `Could not close panel app 953c796f-3e25-4008-b21e-7d09555c8cba`
  
  **Analysis**: The system failed to properly close the panel app, leaving it in a zombie state.

- **21:25:07.023**: `DeferCloseKeyboard` called again with:
  - `isKeyboardActive: false`
  - `panelHost: false`
  - `VWHost: true` ← **Still true despite panel being destroyed**

- **21:25:07.030**: IME window close request received
  - Window bounds: `width: 0.0, height: 0.0, depth: 0.0` (already zeroed out)
  - But still marked as `visible: true, active: true`

#### Input Connection Issues

- **21:25:07.025**: `KeyboardInputMethodService::onFinishInput`
- **21:25:07.026**: `KeyboardInputMethodService::unregister keyboard callback`
- **21:25:07.046-047**: Multiple warnings:
  - `RemoteInputConnectionImpl::performPrivateCommand on inactive InputConnection`
  - `RemoteInputConnectionImpl::performEditorAction on inactive InputConnection`
  
  **Analysis**: The app is attempting to interact with an InputConnection that has already been deactivated, indicating a timing/synchronization issue.

#### Display and Resource Cleanup

- **21:25:07.019**: Display device removed (virtual display for keyboard)
- **21:25:07.025**: Logical display removed: Display 1341
- **21:25:07.026**: `VolumetricContainerPlacement released`
- **21:25:07.026**: Flow state change: `OverlayKeyboardFlow → (none)`
- **21:25:07.033**: Interaction state change: `InputFocus → None`

### Phase 2: Keyboard Re-opening Attempt (21:25:08.304 - 21:25:08.365)

#### Service Binding

- **21:25:08.304**: `ActivityManager::bindService` for `KeyboardInputMethodService`
- **21:25:08.322**: `KeyboardInputMethodService::onStartInput`
- **21:25:08.322**: `ImeTracker::onRequestShow` 
  - Origin: `ORIGIN_CLIENT_SHOW_SOFT_INPUT`
  - Reason: `SHOW_SOFT_INPUT`
  - Session ID: `47aad225`

#### Panel Positioning Failures

- **21:25:08.317, 326, 337, 348, 360**: Multiple `PanelPositioningSystem::Failed to position panel after 60 attempts`
  
  **Analysis**: The panel positioning system is completely failing to position the keyboard panel. This is a critical issue that prevents proper keyboard display.

#### IME Show Request

- **21:25:08.322**: `InputMethodManager::showSoftInput()` called
  - View: `AndroidComposeView{2bf55ed}`
  - Package: `com.example.moonlight_spatialsdk`

- **21:25:08.333**: `KeyboardInputMethodService::onShowInputRequested` for package `com.example.moonlight_spatialsdk`

#### Critical Failure
- **21:25:08.347**: **IME Tracker Failure**: `onFailed at PHASE_WM_SHOW_IME_RUNNER`
  - Session ID: `a942abf2`
  
  **Analysis**: The WindowManager phase of showing the IME failed. This is a system-level failure preventing keyboard display.

#### Reset/Retry Loop
- **21:25:08.364-365**: Multiple rapid calls:
  - `ShellApp::Resetting system keyboard from IMS`
  - `ShellApp::Opening system keyboard from IMS`
  
  **Analysis**: The system is stuck in a reset/retry loop, attempting to recover from the failed keyboard show operation.

### Phase 3: Keyboard Activation and Panel Creation (21:25:08.462 - 21:25:08.463)

#### Activation Sequence
- **21:25:08.462**: `SurfaceTypingController::OnSystemKeyboardActivated`
- **21:25:08.462**: `SystemKeyboardController::ActivateAndLaunchKeyboard - Opening Keyboard with PanelAppHost`

#### Panel App Launch Info
- **21:25:08.462**: `PanelAppLaunchInfo` created
  - Component: `com.oculus.vrshell/com.oculus.panelapp.keyboardv2.KeyboardPanelService`
  - Package: `com.oculus.vrshell`
  - Service: `com.oculus.panelapp.keyboardv2.KeyboardPanelService`
  - Multi-layer support: Enabled (`shouldAllowLayerSupport: 1`)
  - Is PanelSDK component: `true`

#### Panel Host Creation
- **21:25:08.462**: `PanelAppHost constructor` for panel ID **439**
  - **New panel ID**: Different from the destroyed panel (437)
  - SPC (Single Process Mode): `false`
  - Created layers: `#main[0]`

### Phase 4: Panel Service Launch (21:25:08.558 - 21:25:08.560)

#### Launch Intent
- **21:25:08.558**: `PanelAppHost::Calling sendPanelLaunchIntent` for panel 439
- **21:25:08.559**: `ShellApplication::sendApkPanelLaunchIntent`
  - Package: `com.oculus.vrshell`
  - Service: `com.oculus.panelapp.keyboardv2.KeyboardPanelService`

#### Service Binding
- **21:25:08.559**: `bindService` Intent created
- **21:25:08.560**: `ActivityManager::bindService` called
  - Connection: `BinderProxy@6b99be4`
  - Flags: `0x1` (standard binding)

### Phase 5: Keyboard Initialization and Visibility Issues (21:25:08.689 - 21:25:08.696)

#### Keyboard Service Registration
- **21:25:08.689-691**: Keyboard subscriptions activated:
  - `DictationPartialResponseMessage`
  - `DictationStateMessage`
  - `DictationFinalResponseMessage`
  - `DictationMicVolumeMessage`
  - `OnTypeaheadSuggestionMessage`

- **21:25:08.691**: `KeyboardInputMethodService::register keyboard callback`

#### Dictionary Loading
- **21:25:08.692**: Dictionary loading for locale `en_US` → normalized to `en`
- **21:25:08.692**: Dictionary found: `dictionary/main_en.dict`
- **21:25:08.692**: Dictionary info: `main:en, version: 54, date: 1414726273`

#### Critical Visibility Issue
- **21:25:08.696**: `PanelRenderLayer::ResetSurface` called with:
  - Size: `0 x 0 x 0`
  - Shape: `hidden`
  - **OS Panel Resize Notification**: `width 0, height 0`

- **21:25:08.696**: **Critical**: `PanelAppHost::Setting panel layer visibility of layer "#main" to invisible`
  
  **Analysis**: The keyboard panel is explicitly set to invisible immediately after creation. This is the root cause of the "vanishing" behavior.

- **21:25:08.696**: `PanelAppHost::Resize layer #main` to:
  - Size: `780 x 413 x 0`
  - Shape: `landscape_cylinder`
  - Stereo: `mono`
  - `isStereoSizeCorrected: false`

- **21:25:08.696**: `PanelRenderLayer::ResetSurface` again with proper size:
  - Size: `780 x 413 x 0`
  - Shape: `landscape_cylinder`
  - **OS Panel Resize Notification**: `width 780, height 413`

- **21:25:08.696**: Display ID 1343 assigned to layer `#main`

### Phase 6: Keyboard Becomes Visible (21:25:08.700 - 21:25:08.799)

#### Layer Show Action
- **21:25:08.700**: `AndroidPanelApp::Processing next frame action "layerShow" for layer "#main"`

#### Display Device Activation
- **21:25:08.702**: Display device state changed to `ON`
  - Display: `com.oculus.android_panel_app.AndroidPanelLayer-com.oculus.vrshell-#main`
  - Size: `780 x 413`
  - Mode ID: 1645
  - Frame rate: 60.0 Hz

- **21:25:08.704**: Layerstack set to 1343 for virtual display

#### Visibility Change
- **21:25:08.733**: **Critical**: `PanelAppHost::Setting panel layer visibility of layer "#main" to visible`
  
  **Analysis**: The keyboard becomes visible here, but this happens after a significant delay and may be too late or may conflict with other state.

#### First Frame
- **21:25:08.736**: `PanelAppAnalytics::First frame latency: 0.273 seconds` for panel 439
- **21:25:08.736**: Swapchain created:
  - Size: `780 x 413`
  - Swap chain length: 3
  - Mips: 1
  - Memory allocation: 1365 KB per buffer (3 buffers = ~4 MB total)

#### IME Shown Confirmation
- **21:25:08.798**: `ImeTracker::onShown` for session `47aad225`
  
  **Analysis**: The IME system confirms the keyboard is shown, but user interaction may still be blocked due to the earlier visibility issues.

## Root Cause Analysis

### Primary Issues

1. **Panel Positioning System Failure**
   - Continuous "Failed to position panel after 60 attempts" warnings
   - Prevents proper keyboard placement in 3D space
   - May cause the keyboard to be positioned outside the user's view or at incorrect coordinates

2. **Premature Visibility Setting to Invisible**
   - The keyboard layer is explicitly set to invisible immediately after creation (21:25:08.696)
   - This happens before the keyboard is properly initialized
   - The visibility is later set to visible (21:25:08.733), but there's a ~37ms gap

3. **IME Window Manager Failure**
   - `onFailed at PHASE_WM_SHOW_IME_RUNNER` indicates system-level failure
   - The WindowManager cannot properly show the IME window
   - This causes the reset/retry loop

4. **Zombie Panel State**
   - Previous keyboard panel (437) is not properly cleaned up
   - `Could not close panel app` warning indicates lingering state
   - `VWHost: true` persists even after panel destruction
   - This may cause conflicts with the new panel (439)

5. **Input Connection Timing Issues**
   - Multiple "inactive InputConnection" warnings
   - The app attempts to interact with the keyboard before it's fully ready
   - Input events are lost or ignored

### Secondary Issues

1. **Multiple Rapid State Changes**
   - Keyboard is opened and closed multiple times rapidly
   - Reset/retry loops suggest unstable state management
   - Race conditions between different system components

2. **Display Device Management**
   - Virtual displays are created and destroyed rapidly
   - Display IDs change (1341 → 1343)
   - May cause rendering issues

3. **Focus Management**
   - Focus transitions from `InputFocus → None` during keyboard closure
   - No clear focus restoration when keyboard reopens
   - May prevent proper input handling

## Impact on User Experience

1. **Keyboard Appears Briefly Then Vanishes**
   - Keyboard panel is created and becomes visible
   - But positioning failures and visibility timing issues cause it to disappear
   - User sees a brief flash of the keyboard

2. **Interaction Blocking**
   - Keyboard volumetric window remains in the system (VWHost: true)
   - Blocks interaction with ConnectionPanels behind it
   - But keyboard itself is not visible or interactive

3. **Input Loss**
   - InputConnection is inactive when app tries to use it
   - User input is lost or ignored
   - Keyboard cannot receive or process user interactions

## Recommendations

### Immediate Fixes

1. **Fix Panel Positioning**
   - Investigate why `PanelPositioningSystem` fails after 60 attempts
   - Check if there are coordinate/transform issues
   - Verify panel positioning requirements are met before keyboard activation

2. **Fix Visibility Timing**
   - Ensure keyboard layer visibility is set to visible immediately upon creation
   - Remove the premature "invisible" setting
   - Add proper initialization sequence before visibility changes

3. **Fix IME Window Manager Integration**
   - Investigate `PHASE_WM_SHOW_IME_RUNNER` failure
   - Ensure all WindowManager requirements are met
   - Add proper error handling and recovery

4. **Fix Panel Cleanup**
   - Ensure previous keyboard panels are fully cleaned up before creating new ones
   - Fix the "Could not close panel app" issue
   - Clear VWHost state properly

### Long-term Improvements

1. **State Management**
   - Implement proper state machine for keyboard lifecycle
   - Add state validation before transitions
   - Prevent rapid open/close cycles

2. **Error Recovery**
   - Add retry logic with exponential backoff
   - Implement fallback mechanisms
   - Better error reporting and logging

3. **Synchronization**
   - Ensure InputConnection is ready before use
   - Add proper synchronization between components
   - Prevent race conditions

4. **Focus Management**
   - Proper focus restoration when keyboard opens
   - Clear focus state when keyboard closes
   - Handle focus transitions gracefully

## Technical Details

### Panel IDs
- **Panel 437**: Original keyboard panel (destroyed)
- **Panel 439**: New keyboard panel (created but has issues)

### Display IDs
- **Display 1341**: Original keyboard virtual display (removed)
- **Display 1343**: New keyboard virtual display (created)

### Volumetric Window Tokens
- **Token 1**: `953c796f-3e25-4008-b21e-7d09555c8cba` (Panel 437 - not properly closed)
- **Token 2**: `1a437130-a60a-40c8-ad50-f5dfce2f1c77` (IME window - removed)

### Timing
- **Keyboard closure**: ~280ms (21:25:07.003 - 21:25:07.280)
- **Keyboard re-opening attempt**: ~60ms (21:25:08.304 - 21:25:08.365)
- **Keyboard activation**: ~1ms (21:25:08.462 - 21:25:08.463)
- **Panel launch**: ~100ms (21:25:08.558 - 21:25:08.560)
- **Initialization**: ~7ms (21:25:08.689 - 21:25:08.696)
- **First frame**: ~100ms (21:25:08.700 - 21:25:08.799)
- **Total time from closure to visible**: ~1.8 seconds

## Conclusion

The keyboard vanishing issue is caused by multiple system-level failures:
1. Panel positioning system completely failing
2. Premature visibility setting to invisible
3. IME WindowManager phase failure
4. Incomplete cleanup of previous keyboard instances
5. Input connection timing issues

These issues compound to create a state where the keyboard is technically "present" in the system (blocking interactions) but not visible or interactive to the user. Fixing the panel positioning system and visibility timing should resolve the primary symptoms, while proper cleanup and state management will prevent recurrence.
