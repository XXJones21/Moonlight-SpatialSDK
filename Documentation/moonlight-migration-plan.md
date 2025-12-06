# Moonlight Android → Spatial SDK Migration Plan

## Overview

This plan migrates the Moonlight Android streaming runtime into the Spatial SDK project, enabling game streaming on Meta Quest devices. The migration preserves Moonlight's core functionality while adapting it to Spatial SDK's panel-based rendering system.

### Resolved Issues & Decisions

The following issues were raised during plan validation and have been resolved:

1. **Submodule Handling (Option B)**: Copy `moonlight-common-c` submodule contents directly into the project (flattened, not as git submodules). This simplifies the build process and avoids submodule initialization complexity.

2. **Audio Renderer for Phase 1**: Include `AndroidAudioRenderer` for Phase 1 as a 1-to-1 solution. Spatial audio integration is deferred to a later phase. This ensures compatibility with Moonlight's existing audio pipeline.

3. **Utility Class**: `LimeLog.java` is explicitly included in Phase 1 Java source migration. This logging utility is used throughout Moonlight and must be migrated.

4. **NDK Version**: Use NDK version `29.0.14206865` from the Valinor project (confirmed working configuration for Quest 3/Pro).

## Migration Strategy

### Phase 1: Java Source Migration

Copy core Java packages from `D:\Tools\Github-repos\moonlight-android\app\src\main\java\com\limelight\` into the Spatial SDK project.

**Source Location:** `D:\Tools\Github-repos\moonlight-android\app\src\main\java\com\limelight\`

**Target Location:** `Moonlight-SpatialSDK/app/src/main/java/com/limelight/`

**Required Packages:**

1. **LimeLog.java** - Logging utility (root package `com.limelight`, used throughout Moonlight codebase)
2. **binding/video/** - MediaCodecDecoderRenderer, MediaCodecHelper, VideoDecoderRenderer interface
3. **binding/audio/** - AndroidAudioRenderer (REQUIRED for Phase 1 - 1-to-1 solution, standard Android audio, not spatial audio)
4. **preferences/** - PreferenceConfiguration, GlPreferences
5. **nvstream/** - NvConnection, StreamConfiguration, NvConnectionListener, MoonBridge JNI wrapper
6. **nvstream/http/** - NvHTTP, ComputerDetails, NvApp, PairingManager
7. **nvstream/av/video/** - VideoDecoderRenderer interface
8. **nvstream/av/audio/** - AudioRenderer interface
9. **nvstream/input/** - ControllerPacket, KeyboardPacket, MouseButtonPacket
10. **nvstream/jni/** - MoonBridge.java (JNI bridge)
11. **utils/** - Helper classes (Dialog, UiHelper, etc.)

**Implementation Steps:**

- Copy entire com.limelight package tree to Spatial SDK project
- Update package imports if namespace conflicts occur
- Preserve Java source files (no Kotlin conversion yet)

### Phase 2: Native Code (JNI/NDK) migration

Copy JNI sources and configure NDK build for Quest 3/Pro (arm64-v8a).

**Source Location:** `D:\Tools\Github-repos\moonlight-android\app\src\main\jni\`

**Target Location:** `Moonlight-SpatialSDK/app/src/main/jni/`

**Required Components:**

`moonlight-core/` - Core C++ streaming library
`evdev_reader/` - Input device reader (may not be needed for VR)
Native build files: `Android.mk`, `Application.mk`
Submodules: `moonlight-common-c`, `openssl`, `libopus`

**Gradle Configuration:**

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        // NDK version confirmed from Valinor project (working configuration for Quest 3/Pro)
        ndkVersion = "29.0.14206865"
        ndk {
            abiFilters += listOf("arm64-v8a") // Quest 3/Pro only
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}
```

**Implementation Steps:**

1. Copy `jni/` directory structure from moonlight-android to Spatial SDK project
2. **Submodule Handling (Option B - CONFIRMED)**: Copy submodule contents directly (flattened, not git submodules):
   - Clone or copy `moonlight-common-c/` directory contents into `jni/moonlight-core/moonlight-common-c/`
   - Do NOT use git submodules - copy the actual source files
   - `openssl/` and `libopus/` are already present as pre-built static libraries (`.a` files) in the moonlight-android repo for arm64-v8a
3. Update `Android.mk` to match Spatial SDK project paths
4. Update `Application.mk`: Change `APP_PLATFORM := android-21` to `APP_PLATFORM := android-34` (HorizonOS requirement)
5. Configure Gradle for NDK build (see Gradle Configuration section above)
6. Test native library compilation for arm64-v8a only

### Phase 3: Surface Integration (Verify/Complete)

Verify the existing `LegacySurfaceHolderAdapter` correctly bridges Spatial SDK Surface to Moonlight's `SurfaceHolder` interface.

**Current Implementation:** `Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/LegacySurfaceHolderAdapter.kt`

**Verification:**

- Ensure adapter properly implements SurfaceHolder interface
- Verify `MediaCodecDecoderRenderer.setRenderTarget()` accepts the adapter
- Test surface lifecycle (create/destroy) matches Moonlight expectations

**Code Pattern:**

```kotlin
// Already implemented in LegacySurfaceHolderAdapter.kt
class LegacySurfaceHolderAdapter(private val surface: Surface) : SurfaceHolder {
    override fun getSurface(): Surface = surface
    // ... other SurfaceHolder methods
}
```

### Phase 4: Connection Initialization

Recreate Moonlight's connection flow from `Game.java` adapted for Spatial SDK lifecycle.

**Source Reference:** `D:\Tools\Github-repos\moonlight-android\app\src\main\java\com\limelight\Game.java (lines ~2500-2600)`

**New File:** `Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/MoonlightConnectionManager.kt`

**Implementation:**

```kotlin
class MoonlightConnectionManager(
    private val context: Context,
    private val activity: Activity,
    private val decoderRenderer: MediaCodecDecoderRenderer,
    private val audioRenderer: AndroidAudioRenderer  // Required for NvConnection.start()
) : NvConnectionListener {
    private var connection: NvConnection? = null

    fun startStream(
        host: String,
        port: Int,
        appId: Int,
        uniqueId: String,
        prefs: PreferenceConfiguration
    ) {
        val computerDetails = ComputerDetails.AddressTuple(host, port)
        val streamConfig = StreamConfiguration().apply {
            setApp(NvApp("Moonlight", appId, false))
            setWidth(prefs.width)
            setHeight(prefs.height)
            setFps(prefs.fps)
            // ... other config
        }
        
        connection = NvConnection(
            context,
            computerDetails,
            0, // httpsPort
            uniqueId,
            streamConfig,
            LimelightCryptoProvider(),
            null // serverCert
        )
        
        // NvConnection.start() requires both audio and video renderers
        connection?.start(audioRenderer, decoderRenderer, this)
    }
    
    override fun stageStarting(stageName: String) { }
    override fun stageComplete(stageName: String) { }
    override fun stageFailed(stageName: String, portFlags: Int, errorCode: Int) { }
    override fun connectionStarted() { }
    override fun connectionTerminated(errorCode: Int) { }
    override fun connectionStatusUpdate(connectionStatus: Int) { }
    override fun displayMessage(message: String) { }
    override fun displayTransientMessage(message: String) { }
    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) { }
    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) { }
    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray) { }
    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) { }
    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) { }
}
```

**Integration in ImmersiveActivity:**

- Create `MoonlightConnectionManager` instance
- Initialize `MediaCodecDecoderRenderer` with proper parameters
- Wire decoder to panel surface via `MoonlightPanelRenderer`
- Start connection when panel surface is ready

### Phase 5: Permissions & Dependencies

Add required Android permissions and external dependencies.

**AndroidManifest.xml Updates:**

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.VIBRATE" />
```

**build.gradle.kts Dependencies:**

```kotlin
dependencies {
    // Moonlight dependencies
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jmdns:jmdns:3.5.9")
}
```

**Implementation Steps:**

- Update `AndroidManifest.xml` with permissions
- Add Moonlight dependencies to `app/build.gradle.kts`
- Sync Gradle and verify no dependency conflicts

### Phase 6: Input & Audio System Mapping

Map Spatial SDK controller input to Moonlight's input packet format.

**Input Mapping:**

- Create `MoonlightInputHandler.kt` to translate Spatial SDK `Controller` events to Moonlight input packets
- Map controller buttons to `ControllerPacket`
- Map hand tracking/pointer to mouse/keyboard events if needed

**Audio (Phase 1 - CONFIRMED):**

- **Use `AndroidAudioRenderer`** for Phase 1 (1-to-1 solution with standard Android audio)
- This is required for `NvConnection.start()` which expects both audio and video renderers
- Spatial audio integration is deferred to a later phase (as discussed in feasibility report)

**New File:** Moonlight-SpatialSDK/app/src/main/java/com/example/moonlight_spatialsdk/MoonlightInputHandler.kt

**Implementation Pattern:**

```kotlin
class MoonlightInputHandler(
    private val connection: NvConnection
) {
    fun handleControllerInput(controller: Controller) {
        val buttonState = mapButtons(controller.buttonState)
        val leftStick = mapStick(controller.leftStick)
        val rightStick = mapStick(controller.rightStick)

        val packet = ControllerPacket(
            buttonFlags = buttonState,
            leftStickX = leftStick.x,
            leftStickY = leftStick.y,
            rightStickX = rightStick.x,
            rightStickY = rightStick.y,
            // ... triggers, etc.
        )
        
        connection.sendControllerInput(packet)
    }
    
    private fun mapButtons(spatialButtons: Int): Int {
        // Map Spatial SDK ButtonBits to Moonlight ControllerPacket flags
        // ButtonA -> A, ButtonB -> B, etc.
    }
}

## File Structure After Migration

Moonlight-SpatialSDK/
├── app/
│   ├── src/main/
│   │   ├── java/
│   │   │   ├── com/example/moonlight_spatialsdk/
│   │   │   │   ├── ImmersiveActivity.kt
│   │   │   │   ├── MoonlightPanelRenderer.kt
│   │   │   │   ├── MoonlightConnectionManager.kt (NEW)
│   │   │   │   ├── MoonlightInputHandler.kt (NEW)
│   │   │   │   └── LegacySurfaceHolderAdapter.kt
│   │   │   └── com/limelight/ (COPIED)
│   │   │       ├── binding/
│   │   │       ├── nvstream/
│   │   │       ├── preferences/
│   │   │       └── utils/
│   │   ├── jni/ (COPIED)
│   │   │   ├── Android.mk
│   │   │   ├── Application.mk
│   │   │   └── moonlight-core/
│   │   └── AndroidManifest.xml (UPDATED)
│   └── build.gradle.kts (UPDATED)

### Testing Strategy

1. Build Verification: Ensure project compiles with all Moonlight dependencies
2. Native Library: Verify JNI libraries build and link correctly
3. Surface Integration: Test panel surface creation and decoder attachment
4/ Connection: Test connection initialization (may need mock server for Phase 1)
5. Input: Verify controller input mapping works correctly

### Risks & Mitigations
| Risk | Mitigation |
|------|------------|
| Package namespace conflicts | Use separate package or rename if needed |
| NDK build complexity | Start with minimal native code, add incrementally |
| Dependency conflicts | Test each dependency addition separately |
| Surface lifecycle issues | Match Moonlight expected lifecycle exactly |
| Missing native submodules | Copy submodule contents directly (Option B - confirmed) |

### Success Criteria
✅ Project compiles with Moonlight Java sources
✅ Native libraries build successfully for arm64-v8a
✅ Panel surface correctly attached to MediaCodecDecoderRenderer
✅ Connection manager initializes without errors
✅ Basic input mapping functional (controller → Moonlight packets)
