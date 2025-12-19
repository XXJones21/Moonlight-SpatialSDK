# Keyboard Dismissal and Fatal Crash Analysis - CORRECTED

## Root Cause: Immediate Focus Loss on Dialog Open

### ACTUAL Event Flow (Keyboard Disappears Before Typing):

#### Step-by-Step Lifecycle Breakdown:

1. **User Taps "Connect to PC" Button** (Line 282-288)
   - `onClick` executes: `dialogHost = host`, `dialogPort = port`, `showPairingDialog = true`
   - State change triggers recomposition of `ConnectionPanelImmersive`

2. **Dialog Box Renders** (Line 550-630)
   - `if (showPairingDialog)` condition becomes true
   - Box with `fillMaxSize()` and `background()` is created
   - Column with dialog content is created
   - SpatialTextField is created with `value = dialogHost`, `onValueChange = { dialogHost = it }`

3. **User Taps SpatialTextField**
   - Keyboard appears (virtual keyboard on Quest)
   - Focus is established on the text field
   - **KEYBOARD IS VISIBLE FOR < 1 SECOND**

4. **IMMEDIATE FOCUS LOSS** (CRITICAL POINT - BEFORE ANY TYPING)
   - Something causes focus loss immediately after keyboard appears
   - Keyboard dismisses before user can type
   - User cannot enter any input

5. **Rapid Tapping Causes Fatal Crash**
   - User taps field again → keyboard appears
   - Keyboard immediately disappears again
   - This cycle repeats rapidly
   - Multiple rapid focus gain/loss cycles cause race conditions
   - Input system gets into inconsistent state
   - Fatal crash occurs when system tries to access disposed/recomposed components

### Key Issues Identified:

#### Issue 1: Nested fillMaxSize() Containers
```kotlin
// Line 183-190: Parent Column with fillMaxSize()
SpatialTheme(colorScheme = getPanelTheme()) {
    Column(
        modifier = Modifier
            .fillMaxSize()  // PARENT CONTAINER
            .clip(SpatialTheme.shapes.large)
            .background(brush = LocalColorScheme.current.panel)
            .verticalScroll(scrollState)
            .padding(16.dp),
    ) {
        // ... content ...
        
        // Line 550-558: Dialog Box with fillMaxSize() INSIDE parent Column
        if (showPairingDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()  // CHILD CONTAINER - NESTED fillMaxSize()
                    .background(...),
            ) {
```

**Problem**: Nested `fillMaxSize()` containers. Parent Column has `fillMaxSize()`, and dialog Box also has `fillMaxSize()`. This creates layout conflicts and can cause focus issues in VR.

#### Issue 2: Box Covers Entire Panel When Dialog Opens
```kotlin
// Line 551-558: Box covers entire panel when dialog appears
Box(
    modifier = Modifier
        .fillMaxSize()  // Covers entire parent Column which also has fillMaxSize()
        .background(...),
    contentAlignment = Alignment.Center
) {
```

**Problem**: When `showPairingDialog` becomes true, the Box is created and covers the entire panel. This happens DURING the recomposition that occurs when the dialog opens. The Box creation might be interfering with focus establishment.

#### Issue 3: Parent Column Recomposition on Dialog Open
```kotlin
// Line 183-190: Parent Column recomposes when showPairingDialog changes
Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scrollState)  // ScrollState might be affected
        .padding(16.dp),
) {
    // ... all content including dialog ...
    if (showPairingDialog) {  // Conditionally rendered dialog
```

**Problem**: When `showPairingDialog` becomes true, the entire parent Column recomposes. The `verticalScroll(scrollState)` modifier on the parent might be interfering with focus when the dialog Box is added.

#### Issue 4: No Key Modifier for Dialog Box
```kotlin
// Dialog Box has no key() modifier
if (showPairingDialog) {
    Box(...) {  // No key = unstable identity
```

**Problem**: Without `key()`, Compose cannot maintain stable identity. When the dialog first appears, Compose might be recreating the Box in a way that causes focus loss.

#### Issue 5: LaunchedEffect May Trigger on Dialog Open
```kotlin
// Line 107-121: LaunchedEffect watches host and port
LaunchedEffect(host, port) {
    if (host.isNotBlank() && port.isNotBlank()) {
        // Network calls that might cause recomposition
        pairingHelper.fetchServerName(host, portInt) { name ->
            serverName = name  // State change
        }
        pairingHelper.checkPairing(host, portInt) { isPairedResult, error ->
            isPaired = isPairedResult  // State change
        }
    }
}
```

**Problem**: When dialog opens, `dialogHost = host` is set (line 285). If `host` changes, LaunchedEffect triggers. The async callbacks update state (`serverName`, `isPaired`), causing additional recompositions that might interfere with focus.

### What Happens When User Taps Text Field:

1. **User taps SpatialTextField** → Focus request
2. **Keyboard appears** → Focus gained, keyboard visible
3. **IMMEDIATELY**: Something causes focus loss
   - Possible causes:
     - Parent Column with `fillMaxSize()` recomposing
     - Dialog Box with `fillMaxSize()` interfering
     - LaunchedEffect triggering and causing state changes
     - Nested `fillMaxSize()` layout conflict
     - ScrollState on parent Column interfering
4. **Keyboard dismisses** → Before any typing can occur

### Comparison with UISetSample:

UISetSample does NOT have:
- Nested `fillMaxSize()` containers
- Custom dialog Box overlays
- Parent Column with `fillMaxSize()` containing dialog
- `verticalScroll` on parent container with dialog

UISetSample uses:
- `SpatialBasicDialog` and `SpatialIconDialog` - proper SDK dialog components
- Stable panel structure without nested fillMaxSize()
- Text fields in stable layouts (not in custom dialog overlays)

### Why This Causes Fatal Crashes:

1. **Rapid Focus Gain/Loss**: User taps → keyboard appears → immediately disappears → user taps again → repeat
2. **Race Conditions**: Focus system tries to establish focus while components are being recomposed
3. **Nested Layout Conflicts**: Two `fillMaxSize()` containers (parent Column + dialog Box) create layout measurement conflicts
4. **ScrollState Interference**: `verticalScroll(scrollState)` on parent might be interfering with focus
5. **State Update Cascade**: LaunchedEffect triggers → async callbacks → state updates → recompositions → focus loss

### Critical Code Locations:

1. **ConnectionPanelImmersive.kt:183-190**: Parent Column with `fillMaxSize()` and `verticalScroll`
2. **ConnectionPanelImmersive.kt:550-630**: Dialog Box with nested `fillMaxSize()`
3. **ConnectionPanelImmersive.kt:107-121**: LaunchedEffect that triggers on host/port changes
4. **ConnectionPanelImmersive.kt:285**: `dialogHost = host` assignment that might trigger LaunchedEffect

### Summary:

The keyboard disappears IMMEDIATELY (before typing) because:
1. Nested `fillMaxSize()` containers (parent Column + dialog Box) create layout conflicts
2. Parent Column with `verticalScroll` recomposes when dialog opens
3. Dialog Box creation interferes with focus establishment
4. LaunchedEffect may trigger async operations that cause additional recompositions
5. No stable identity for dialog Box (no `key()` modifier)

Fatal crashes occur because:
1. Rapid focus gain/loss cycles create race conditions
2. Input system gets into inconsistent state
3. Components are accessed while being disposed/recomposed
4. Nested layout measurement conflicts cause system instability

**Root Cause**: Nested `fillMaxSize()` containers + parent Column recomposition + unstable dialog Box identity causing immediate focus loss when keyboard appears.
