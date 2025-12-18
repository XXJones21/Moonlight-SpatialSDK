# Window Focus Loss Root Cause Analysis

## Problem Statement

When `SpatialTextField` in `ConnectionDialog` is tapped, the window loses focus immediately (before keyboard is requested), causing the system to trigger `setOverlayInputFocus(1)` which launches `FocusPlaceholderActivity` with zero-bounds keyboard.

## Evidence from keyboard.log

**Sequence of events:**
1. Line 163: `PanelInputListener(24931): locking controller` - Controller locks when text field is tapped
2. Line 164: `Focused display #1268 does not have a focused window` - System tries to focus displayId 1268 (panel's virtual display)
3. Line 170: `Focused window changed to RunningWindowInfo{ windowType: 2037, displayId: 1268, activity: null}` - Panel window gains focus
4. Line 174: `onWindowFocusChanged: hasFocus=false` - Main activity window loses focus
5. Line 404: `ImeTracker: onFailed at PHASE_WM_SHOW_IME_RUNNER` - IME show fails because window has no focus
6. Line 500: `UpdateActiveFlow from (none) to OverlayKeyboardFlow` - System switches to overlay keyboard flow
7. Line 504: `setOverlayInputFocus: inputFocus=true` - System calls `setOverlayInputFocus(1)`
8. Line 579: `START FocusPlaceholderActivity` - FocusPlaceholderActivity launches
9. Line 582: Keyboard created with zero bounds: `bounds = Extent3f { width = 0.0, height = 0.0, depth = 0.0 }`

## Key Differences: Our Implementation vs UISetSample

### UISetSample (Works)
- Uses `PanelScaffold` wrapper (simple Column with fillMaxSize at root)
- `SpatialTextField` directly in panel content (no dialog overlays)
- No nested `fillMaxSize()` containers
- No custom dialog overlays

### Our Implementation (Fails)
- Custom `ConnectionDialog` with nested `Box` containers
- Outer Box: `Modifier.fillMaxSize()` (line 869)
- Inner Box: `Modifier.fillMaxSize()` for background (line 875)
- `SpatialTextField` inside nested structure
- Dialog rendered conditionally (`if (show)`)

## Hypothesis

The nested `fillMaxSize()` boxes in the dialog overlay structure may be causing the panel window (displayId 1268) to request focus when `SpatialTextField` is tapped, which causes the main activity window (displayId 0) to lose focus.

**Why this might happen:**
- Nested `fillMaxSize()` containers create layout measurement conflicts
- When `SpatialTextField` requests focus, the system may think the panel window needs focus to handle input
- The panel window (windowType 2037, displayId 1268) gains focus, causing main window to lose focus
- This happens BEFORE keyboard is requested, so IME show fails
- System falls back to overlay input focus, which triggers `FocusPlaceholderActivity`

## What We Don't Know

1. **Exact mechanism**: Why does the panel window try to gain focus when `SpatialTextField` in a dialog overlay is tapped?
2. **Why UISetSample works**: UISetSample doesn't have text fields in dialogs - they're directly in panel content. Does this mean dialogs with text fields are fundamentally incompatible?
3. **WindowType 2037**: What is this window type? It's Quest-specific (volumetric/panel window), but why does it try to gain focus?

## Proposed Test

Remove nested `fillMaxSize()` boxes from dialog and use simpler structure:
- Remove outer `Box` with `fillMaxSize()`
- Remove inner `Box` with `fillMaxSize()` for background
- Use `Column` directly with padding/background instead of nested boxes
- Test if this prevents panel window from requesting focus

## Current State

- `onWindowFocusChanged` override is trying to recover focus reactively, but it's too late - `FocusPlaceholderActivity` already launches
- Focus recovery strategies fail because the panel window has already gained focus
- Need to prevent the initial focus loss, not recover from it



