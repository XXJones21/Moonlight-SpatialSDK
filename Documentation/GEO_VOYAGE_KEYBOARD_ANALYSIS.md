# Geo Voyage Keyboard Handling Analysis

## Key Architectural Difference

**geo_voyage** uses a **separate ComponentActivity** (`PanelActivity`) for the panel, while we use `composePanel` directly in `ImmersiveActivity`.

### geo_voyage Structure:
1. `MainActivity` (ImmersiveActivity) - VR scene
2. `PanelActivity` (ComponentActivity) - Separate activity for panel UI
3. PanelRegistration uses `activityClass = PanelActivity::class.java`

### Our Structure:
1. `ImmersiveActivity` - VR scene + panel via `composePanel`
2. PanelRegistration uses `composePanel { setContent { ... } }`

## Critical Differences

### 1. Activity Separation
- **geo_voyage**: Panel runs in separate ComponentActivity
  - Keyboard events handled by PanelActivity
  - No dispatchKeyEvent override in PanelActivity
  - Panel has its own activity lifecycle
  
- **Our app**: Panel runs in ImmersiveActivity via composePanel
  - Keyboard events handled by ImmersiveActivity
  - We removed dispatchKeyEvent, but events still go through ImmersiveActivity

### 2. Panel Configuration
**geo_voyage**:
```kotlin
PanelRegistration(R.integer.panel_id) {
  activityClass = PanelActivity::class.java
  config {
    width = 0.642f
    height = 0.6f
    layoutWidthInDp = 642f
    layoutHeightInDp = 600f
    includeGlass = false
    themeResourceId = R.style.PanelAppThemeTransparent
    layerBlendType = PanelShapeLayerBlendType.MASKED
    enableLayerFeatheredEdge = true
    forceSceneTexture = true
    layerConfig = LayerConfig()
    panelShader = SceneMaterial.HOLE_PUNCH_PANEL_SHADER
    alphaMode = AlphaMode.HOLE_PUNCH
  }
}
```

**Our app**:
```kotlin
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
  composePanel { setContent { ... } }
}
```

### 3. SpatialTextField Usage
**geo_voyage** (SettingsTextFieldListItem.kt):
```kotlin
SpatialTextField(
    label = label,
    value = textFieldValue.value,
    placeholder = placeholder,
    onValueChange = { textFieldValue.value = it },
    onSubmit = { onChanged(it) },
    singleLine = true,
    autoCorrectEnabled = false,
    autoValidate = false,
    capitalization = KeyboardCapitalization.None,
    keyboardType = KeyboardType.Uri,
    modifier = Modifier.fillMaxWidth(),
)
```

**Our app**:
```kotlin
SpatialTextField(
    label = "IP Address",
    placeholder = "192.168.1.100",
    value = dialogHost,
    onValueChange = { dialogHost = it },
    autoValidate = false,
    modifier = Modifier.fillMaxWidth(),
)
```

### 4. PanelActivity Setup
**geo_voyage** PanelActivity:
- Extends `ComponentActivity`
- Calls `enableEdgeToEdge()` in onCreate
- Uses `setContent { ... }` directly
- No dispatchKeyEvent override
- No special keyboard handling

### 5. Layout Structure
**geo_voyage** SettingsScreen:
- Uses `verticalScroll` on nested Column (not root)
- Simple Box/Surface components
- No nested fillMaxSize() conflicts

## Root Cause Hypothesis

The keyboard dismissal is likely caused by:

1. **Activity Context**: Using `composePanel` in ImmersiveActivity means keyboard events are processed in the VR activity context, which may have different input handling than a regular ComponentActivity.

2. **Panel Configuration**: Our panel uses `enableTransparent = true` and different layer settings than geo_voyage. The `forceSceneTexture = true` and `alphaMode = AlphaMode.HOLE_PUNCH` in geo_voyage might affect input handling.

3. **Compose Context**: The composePanel might not have proper focus management when embedded in ImmersiveActivity vs. running in a separate ComponentActivity.

## Potential Solutions

1. **Match Panel Configuration**: Try matching geo_voyage's panel config exactly:
   - Use `forceSceneTexture = true`
   - Use `alphaMode = AlphaMode.HOLE_PUNCH`
   - Use `layerBlendType = PanelShapeLayerBlendType.MASKED`
   - Remove `enableTransparent`

2. **Check Activity Lifecycle**: Ensure the panel's Compose context is properly initialized and not being recreated.

3. **Focus Management**: Add explicit focus management to SpatialTextField using FocusRequester.

4. **Panel Registration Timing**: Ensure panel is fully registered before showing text fields.
