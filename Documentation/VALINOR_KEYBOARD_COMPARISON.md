# Valinor Keyboard Input Comparison

## Critical Architectural Difference

**Valinor** uses **XML layout with traditional Android Views**, while we use **Compose with SpatialTextField**.

### Valinor's Approach

```kotlin
// PanelRegistration uses XML layout file
PanelRegistration(R.layout.chat_panel) {
    config {
        themeResourceId = R.style.ChatPanelTheme
        includeGlass = true
        layerConfig = LayerConfig()
        enableTransparent = true
        width = 0.8f
        height = 0.6f
    }
    panel {
        // Access traditional Android Views
        val textInput = rootView?.findViewById<android.widget.EditText>(R.id.text_input)
        val sendButton = rootView?.findViewById<android.widget.Button>(R.id.send_button)
        
        sendButton?.setOnClickListener {
            val inputText = textInput?.text?.toString()?.trim()
            // Handle input...
        }
    }
}
```

**XML Layout (chat_panel.xml)**:
```xml
<EditText
    android:id="@+id/text_input"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:layout_weight="1"
    android:hint="Type your message..."
    android:inputType="textCapSentences|textAutoCorrect"
    android:imeOptions="actionSend|flagNoExtractUi"
    android:privateImeOptions="nm,com.google.android.inputmethod.latin.dictation"/>
```

### Our Approach

```kotlin
// PanelRegistration uses Compose
PanelRegistration(R.id.connection_panel) {
    config {
        fractionOfScreen = 0.4f
        height = basePanelHeightMeters * 0.75f
        width = basePanelHeightMeters * 0.6f
        layoutDpi = 240
        layerConfig = LayerConfig()
        enableTransparent = true
        includeGlass = false
        themeResourceId = R.style.PanelAppThemeTransparent
    }
    composePanel { setContent {
        SpatialTextField(
            label = "IP Address",
            placeholder = "192.168.1.100",
            value = host,
            onValueChange = { host = it },
            autoValidate = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }}
}
```

## Root Cause Analysis from keyboard.log

### Event Sequence (Lines 174-765)

1. **Line 174**: `onWindowFocusChanged: hasFocus=false, videoPanelEntity exists=false`
   - Window loses focus **immediately** when text field is tapped
   - This happens **before** keyboard is requested

2. **Line 500**: `UpdateActiveFlow from (none) to OverlayKeyboardFlow`
   - System switches to overlay keyboard flow

3. **Line 504**: `setOverlayInputFocus: inputFocus=true`
   - System calls `setOverlayInputFocus(1)` - **This is the trigger**

4. **Line 514**: `setOverlayInputFocus(1) : Vwr(1)`
   - Overlay input focus is set for viewer mode

5. **Line 579**: `START u0 {flg=0x10000000 cmp=com.oculus.vrshell/.FocusPlaceholderActivity}`
   - **FocusPlaceholderActivity is launched** - This should NOT happen when `videoPanelEntity` is null

6. **Line 582**: Keyboard created with **zero bounds**:
   ```
   bounds = Extent3f { width = 0.0, height = 0.0, depth = 0.0 }
   surfaceSize = 1x1
   ```
   - Keyboard is invisible but "physically there" (movable)

7. **Line 749**: `onWindowFocusChanged: hasFocus=false` again
   - Focus recovery attempts fail because `FocusPlaceholderActivity` already launched

8. **Line 765**: Focus changes to `FocusPlaceholderActivity`:
   ```
   Focused window changed to RunningWindowInfo{ activity: com.oculus.vrshell.FocusPlaceholderActivity }
   ```

## Why Valinor Works

1. **Traditional Android View System**: `EditText` in XML layout uses standard Android input method framework
2. **No Compose Overhead**: No Compose recomposition cycles that might trigger focus loss
3. **Direct View Access**: `rootView?.findViewById()` gives direct access to the EditText widget
4. **Standard IME Flow**: Traditional Android IME (Input Method Editor) flow, not overlay input focus

## Why Our Approach Fails

1. **Compose + SpatialTextField**: Uses Spatial SDK's Compose integration which may trigger overlay input focus
2. **Window Focus Loss**: Window loses focus immediately when `SpatialTextField` is tapped (line 174)
3. **setOverlayInputFocus Triggered**: System calls `setOverlayInputFocus(1)` which launches `FocusPlaceholderActivity`
4. **FocusPlaceholderActivity with Zero Bounds**: When `videoPanelEntity` is null, `FocusPlaceholderActivity` creates keyboard with invalid dimensions
5. **Focus Recovery Fails**: By the time we try to regain focus, `FocusPlaceholderActivity` is already launched

## Solutions

### Option 1: Use XML Layout with EditText (Like Valinor)

**Pros:**
- Proven to work (Valinor example)
- Standard Android IME flow
- No Compose recomposition issues
- Direct widget access

**Cons:**
- Need to rewrite UI in XML
- Lose Compose benefits (state management, recomposition)
- More boilerplate code

### Option 2: Use Separate ComponentActivity (Like geo_voyage)

**Pros:**
- Panel runs in separate activity lifecycle
- Keyboard events handled by PanelActivity
- No window focus conflicts with ImmersiveActivity

**Cons:**
- More complex architecture
- Need to manage activity lifecycle
- State sharing between activities

### Option 3: Prevent setOverlayInputFocus When videoPanelEntity is Null

**Pros:**
- Keep current Compose architecture
- Minimal changes

**Cons:**
- May not be possible - `setOverlayInputFocus` is system-level call
- Need to find way to prevent it or handle it differently

### Option 4: Use PanelRegistration with panel block (Not composePanel)

**Pros:**
- Similar to Valinor but can use Compose via `setContent` in panel block
- Might avoid overlay input focus trigger

**Cons:**
- Need to verify if this works with Compose
- May still have same issues

## Recommended Solution

**Option 1 (XML Layout)** is the most reliable because:
1. Valinor proves it works
2. Standard Android IME flow doesn't trigger `setOverlayInputFocus`
3. No Compose recomposition cycles
4. Direct widget access for focus management

However, if we want to keep Compose, **Option 2 (Separate ComponentActivity)** is the next best choice based on geo_voyage analysis.

## Implementation Plan for Option 1

1. Create `res/layout/connection_panel.xml` with EditText widgets
2. Change `PanelRegistration` to use `R.layout.connection_panel`
3. Use `panel { }` block to access views via `rootView?.findViewById()`
4. Remove Compose code from connection panel
5. Keep Compose for other panels if needed




