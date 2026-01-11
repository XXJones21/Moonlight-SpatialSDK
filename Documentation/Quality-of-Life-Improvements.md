# Quest 3 App - Top 10 Quality of Life Improvements

## Overview

This document outlines the top 10 quality of life improvements identified for the Moonlight-SpatialSDK Quest 3 application, focusing on both user experience (UX) and infrastructure improvements. These recommendations are based on comprehensive review of the documentation, codebase, and current implementation patterns.

---

## 1. Multiple Connection Management & Favorites System

### Current State
- Only one saved connection (`saved_host`, `saved_port`, `saved_appId` in SharedPreferences)
- No connection history or favorites
- Users must manually re-enter connection details each time

### Proposed Improvement
**UX Impact**: High - Reduces friction for users with multiple PCs or frequent connections

**Infrastructure Impact**: Medium - Requires new data model and UI components

**Implementation**:
- Create `ConnectionProfile` data class with: host, port, appId, displayName, lastConnected, favorite flag
- Replace single SharedPreferences with Room database or JSON file storage
- Add connection list UI in PancakeActivity with:
  - Quick connect buttons for favorites
  - Connection history (last 10 connections)
  - Edit/delete actions
  - Server name detection and display
- Add "Quick Connect" button in ImmersiveActivity ButtonShelf for recent connections

**Files to Modify**:
- `PancakeActivity.kt` - Add connection list UI
- Create `ConnectionProfile.kt` - Data model
- Create `ConnectionRepository.kt` - Data persistence layer
- `ImmersiveActivity.kt` - Add quick reconnect UI

---

## 2. Enhanced Connection Status & Quality Feedback in VR

### Current State
- Connection status exists in `MoonlightConnectionManager` but only logged
- Connection quality updates (`CONN_STATUS_OKAY`, `CONN_STATUS_POOR`) not displayed in VR
- No visual indicators for connection health during streaming

### Proposed Improvement
**UX Impact**: High - Users need real-time feedback on connection quality

**Infrastructure Impact**: Low - Leverage existing status callbacks

**Implementation**:
- Add connection quality indicator to ButtonShelf (signal strength icon)
- Display connection status overlay in VR (non-intrusive, auto-hide after 3 seconds)
- Show connection metrics: latency, frame loss percentage, bitrate
- Color-coded status: Green (Good), Yellow (Fair), Red (Poor)
- Add haptic feedback on connection quality degradation

**Files to Modify**:
- `ImmersiveActivity.kt` - Add status display UI
- `ButtonShelfCompose.kt` - Add connection quality indicator
- `MoonlightConnectionManager.kt` - Expose detailed connection metrics

---

## 3. Automatic Connection Recovery & Retry Logic

### Current State
- Sleep/wake recovery exists but no automatic retry on connection failure
- Users must manually reconnect after network interruptions
- No exponential backoff or retry limits

### Proposed Improvement
**UX Impact**: High - Seamless experience during network hiccups

**Infrastructure Impact**: Medium - Requires connection state machine

**Implementation**:
- Add automatic retry logic with exponential backoff (1s, 2s, 4s, 8s, max 30s)
- Retry up to 3 times before showing error dialog
- Detect network state changes and auto-reconnect when network restored
- Show "Reconnecting..." status during retry attempts
- Add user preference: "Auto-reconnect on failure" (enabled by default)

**Files to Modify**:
- `MoonlightConnectionManager.kt` - Add retry logic
- `ImmersiveActivity.kt` - Handle retry UI states
- Create `ConnectionStateMachine.kt` - Manage connection lifecycle

---

## 4. Unified Settings Management System

### Current State
- Settings scattered across multiple SharedPreferences:
  - `connection_prefs` - Connection details
  - `immersive_settings` - Immersive features
  - `PreferenceManager.getDefaultSharedPreferences()` - Stream settings
  - `GlPreferences` - GPU renderer info
- No centralized settings UI
- Settings accessed from different places (PancakeActivity, ImmersiveActivity)

### Proposed Improvement
**UX Impact**: Medium - Easier to find and manage all settings

**Infrastructure Impact**: High - Requires refactoring settings architecture

**Implementation**:
- Create unified `AppSettings` data class with all settings
- Implement single source of truth for settings (Room database or single SharedPreferences file)
- Create `SettingsRepository` for settings persistence
- Add comprehensive Settings panel accessible from both activities
- Group settings logically: Connection, Streaming, VR Features, Advanced
- Add settings export/import functionality

**Files to Modify**:
- Create `AppSettings.kt` - Unified settings model
- Create `SettingsRepository.kt` - Settings persistence
- `PancakeActivity.kt` - Refactor to use unified settings
- `ImmersiveActivity.kt` - Refactor to use unified settings
- Create `SettingsPanel.kt` - Unified settings UI

---

## 5. Connection Discovery & Auto-Detection

### Current State
- Manual IP address entry required
- No network scanning or server discovery
- Users must know PC IP address

### Proposed Improvement
**UX Impact**: High - Eliminates need to manually enter IP addresses

**Infrastructure Impact**: Medium - Requires network scanning implementation

**Implementation**:
- Add mDNS/Bonjour discovery for Moonlight servers on local network
- Scan common IP ranges (192.168.x.x, 10.x.x.x) for Moonlight servers
- Display discovered servers in connection list with server names
- Add "Scan Network" button in PancakeActivity
- Cache discovered servers for quick access
- Show server status (online/offline) in connection list

**Files to Modify**:
- Create `ServerDiscovery.kt` - Network scanning logic
- `PancakeActivity.kt` - Add discovery UI
- `ConnectionProfile.kt` - Add discovery metadata

---

## 6. Stream Configuration Presets

### Current State
- Users manually configure resolution, FPS, bitrate, codec each time
- No presets for common use cases (Performance, Quality, Balanced)
- Settings not saved per connection

### Proposed Improvement
**UX Impact**: Medium - Faster configuration for common scenarios

**Infrastructure Impact**: Low - Add preset definitions

**Implementation**:
- Create preset system: "Performance" (720p@120fps), "Balanced" (1080p@90fps), "Quality" (1440p@90fps), "Ultra" (4K@60fps)
- Add preset selector in connection configuration
- Save preset per connection profile
- Allow custom presets (user-defined configurations)
- Show estimated bandwidth requirements for each preset

**Files to Modify**:
- Create `StreamPreset.kt` - Preset definitions
- `PancakeActivity.kt` - Add preset selector UI
- `ConnectionProfile.kt` - Store preset preference

---

## 7. Smart Panel Positioning & Memory

### Current State
- Panel positioned at fixed distance (1.0m) in front of user
- No memory of previous positions
- No support for multiple panel layouts

### Proposed Improvement
**UX Impact**: Medium - More personalized and convenient panel placement

**Infrastructure Impact**: Low - Add position persistence

**Implementation**:
- Save panel position, rotation, and scale per session
- Restore last panel position on app launch
- Add preset positions: "Center", "Left", "Right", "Above", "Below"
- Support multiple saved positions (switch between layouts)
- Add "Reset to Default" option
- Remember position per connection profile

**Files to Modify**:
- `PanelPositioningSystem.kt` - Add position persistence
- `ImmersiveActivity.kt` - Save/restore panel state
- `ButtonShelfCompose.kt` - Add position preset buttons

---

## 8. Enhanced Error Messages & Troubleshooting

### Current State
- Generic error messages ("Connection terminated (error: $errorCode)")
- No actionable guidance for users
- Errors logged but not user-friendly

### Proposed Improvement
**UX Impact**: High - Users can self-diagnose and fix issues

**Infrastructure Impact**: Low - Add error message mapping

**Implementation**:
- Create error code to user-friendly message mapping
- Add troubleshooting tips for common errors:
  - Network unreachable → "Check PC is on same network"
  - Pairing failed → "Verify PIN entered correctly on PC"
  - Decoder error → "Try different codec (H264/HEVC)"
- Show error details in expandable section
- Add "Retry" button on error dialogs
- Link to documentation/wiki for detailed troubleshooting

**Files to Modify**:
- Create `ErrorMessages.kt` - Error message mapping
- `MoonlightConnectionManager.kt` - Return detailed error info
- `PancakeActivity.kt` - Display user-friendly errors
- `ImmersiveActivity.kt` - Show error dialogs with actions

---

## 9. Connection State Simplification & State Machine

### Current State
- Multiple boolean flags: `isPaired`, `isSurfaceReady`, `isConnected`, `shouldForwardInputs`
- Complex state synchronization logic
- Potential race conditions with async operations

### Proposed Improvement
**UX Impact**: Low - Internal improvement, but reduces bugs

**Infrastructure Impact**: High - Improves code maintainability and reliability

**Implementation**:
- Implement proper state machine for connection lifecycle:
  - `DISCONNECTED` → `PAIRING` → `PAIRED` → `CONNECTING` → `CONNECTED` → `STREAMING`
- Use sealed class for connection state
- Single source of truth for connection state
- State transitions are explicit and logged
- Eliminate race conditions with proper state management
- Add state transition callbacks for UI updates

**Files to Modify**:
- Create `ConnectionState.kt` - State machine definition
- `MoonlightConnectionManager.kt` - Refactor to use state machine
- `ImmersiveActivity.kt` - Update to use state machine
- Remove redundant boolean flags

---

## 10. Performance Monitoring & Diagnostics

### Current State
- Limited performance metrics available
- No built-in diagnostics or performance monitoring
- Difficult to debug performance issues

### Proposed Improvement
**UX Impact**: Medium - Helps users optimize their setup

**Infrastructure Impact**: Medium - Add metrics collection and display

**Implementation**:
- Add performance overlay (optional, toggleable):
  - FPS counter
  - Frame decode time
  - Network latency
  - Bitrate (current vs target)
  - Frame loss percentage
- Add diagnostics panel accessible from settings:
  - Connection quality history graph
  - Performance statistics (min/max/avg FPS, latency)
  - Network test tool (ping, bandwidth test)
  - Decoder capabilities display
- Export diagnostics report for troubleshooting
- Add "Performance Mode" toggle (reduces overhead for monitoring)

**Files to Modify**:
- Create `PerformanceMonitor.kt` - Metrics collection
- Create `DiagnosticsPanel.kt` - Diagnostics UI
- `ImmersiveActivity.kt` - Add performance overlay
- `MoonlightPanelRenderer.kt` - Expose decode metrics

---

## Priority Ranking

### High Priority (Immediate Impact)
1. **Multiple Connection Management** - Most requested feature, high user value
2. **Enhanced Connection Status in VR** - Critical for user awareness
3. **Automatic Connection Recovery** - Improves reliability significantly

### Medium Priority (Significant Improvement)
4. **Unified Settings Management** - Improves maintainability and UX
5. **Connection Discovery** - Reduces setup friction
6. **Enhanced Error Messages** - Improves user experience during failures

### Lower Priority (Nice to Have)
7. **Stream Configuration Presets** - Convenience feature
8. **Smart Panel Positioning** - Quality of life improvement
9. **Connection State Simplification** - Internal improvement
10. **Performance Monitoring** - Advanced user feature

---

## Implementation Considerations

### Dependencies
- Room database (for connection profiles and settings) - Add to `build.gradle.kts`
- Network scanning library (for server discovery) - Consider mDNS library
- State machine library (optional) - Can implement with sealed classes

### Breaking Changes
- Settings migration: Need migration path from old SharedPreferences to new unified system
- Connection data migration: Migrate single connection to connection profiles

### Testing Requirements
- Connection state machine: Unit tests for all state transitions
- Network discovery: Integration tests with mock servers
- Settings migration: Test migration from old to new format

### Documentation Updates
- Update `Quest 3 App Overview.md` with new features
- Add user guide for connection management
- Document settings structure and migration

---

## Related Documentation

- **Quest 3 App Overview.md** - Current app architecture
- **POST_MORTEM.md** - Known issues and resolutions
- **Quest 3 App Pipeline.md** - Pipeline analysis

---

*Document created: Based on comprehensive codebase review and documentation analysis*
