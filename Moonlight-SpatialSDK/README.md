# Moonlight SpatialSDK

A Meta Quest 3 application that brings Moonlight game streaming to virtual and mixed reality. Stream your PC games to your Quest 3 headset with low-latency video and audio, featuring a fully immersive VR experience with passthrough support.

## Overview

Moonlight SpatialSDK is a port of the Moonlight game streaming client to Meta's Spatial SDK platform. It enables streaming from NVIDIA GameStream, Sunshine, or other Moonlight-compatible servers directly to your Quest 3 headset, providing an immersive gaming experience in VR with passthrough support.

**Key Features:**

- Stream PC games to Quest 3 with low latency
- Fully immersive VR experience with in-VR connection UI
- Passthrough mode for mixed reality gaming
- Secure PIN pairing system
- Native video decoder with hardware acceleration
- Full audio support
- Bluetooth controller input passthrough (Xbox, DualShock 4, and compatible gamepads)
- Configurable stream settings (resolution, FPS, bitrate, codec)
- Video panel scaling (0.5x to 10.0x) with corner-based controls
- Automatic video stream recovery after sleep/wake cycles

## Architecture

The application uses Meta Spatial SDK to provide a fully immersive VR experience for game streaming:

### Main Components

- **ImmersiveActivity** (`ImmersiveActivity.kt`): Main VR activity for connection UI, pairing, video streaming with passthrough, and input handling
- **MoonlightConnectionManager** (`MoonlightConnectionManager.kt`): Connection lifecycle, pairing, stream management, and controller input passthrough
- **MoonlightPanelRenderer** (`MoonlightPanelRenderer.kt`): Bridges Spatial panel Surface to Moonlight native decoder
- **LegacySurfaceHolderAdapter** (`LegacySurfaceHolderAdapter.kt`): Adapter for Moonlight's SurfaceHolder interface
- **ControllerHandler** (Moonlight core): Handles gamepad detection, input mapping, and forwarding to the streaming server

### Connection Flow

1. **Launch and Connection**:
   - App launches directly into immersive VR mode
   - Connection panel appears in VR for server configuration
   - User enters server host/port and app ID
   - Checks pairing status

2. **Pairing and Streaming**:
   - If not paired: Generates PIN, displays to user in VR, pairs with server
   - Registers video panel for streaming
   - Enables passthrough for mixed reality
   - Connects to server and starts streaming
   - Displays game stream on VR panel
   - Connection panel is automatically hidden when streaming starts

## Requirements

### Hardware

- Meta Quest 3 headset
- PC with NVIDIA GPU (for GameStream) or Sunshine server
- Local network connection (recommended) or internet connection
- Bluetooth gamepad (optional): Xbox Wireless Controller, DualShock 4, or compatible gamepad for input passthrough

### Software

- Android Studio (latest version)
- Meta Spatial SDK (included via Gradle)
- Meta Spatial Editor (for scene editing)
- Java Development Kit (JDK) 17 or later

### Server Requirements

- NVIDIA GameStream (GeForce Experience) or
- [Sunshine](https://github.com/LizardByte/Sunshine) game streaming server

## Setup

### Prerequisites

1. **Install Meta Spatial Editor**:
   - Download from [Meta Developer Portal](https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-editor-overview)
   - Required for editing scenes (`app/scenes/Main.metaspatial`)

2. **Configure Android Studio**:
   - Install Android SDK and build tools
   - Configure Quest 3 for development (enable Developer Mode)

3. **Set Up Server**:
   - Install and configure Sunshine or enable NVIDIA GameStream
   - Note your server's IP address and port (default: 47989)

### Building the Project

1. **Clone the repository**:

   ```bash
   git clone <repository-url>
   cd Moonlight-SpatialSDK/Moonlight-SpatialSDK
   ```

2. **Open in Android Studio**:

   - Open the `Moonlight-SpatialSDK` directory
   - Sync Gradle files
   - Wait for dependencies to download

3. **Build and Install**:

   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

   Or use Android Studio's Run/Debug configuration to build and install directly to your Quest 3.

### Configuration

#### Network Security Configuration

The app includes network security configuration to allow cleartext HTTP traffic for initial pairing (required by Moonlight protocol). This is configured in:

- `app/src/main/res/xml/network_security_config.xml`
- Referenced in `AndroidManifest.xml`

**Note**: After pairing, all connections use HTTPS with certificate pinning for security.

## Usage

### First-Time Connection

1. **Launch the app** on your Quest 3 (app launches directly into immersive VR mode)
2. **Enter server details** in the VR connection panel:
   - Host: Your PC's IP address (e.g., `192.168.1.100`)
   - Port: Server port (default: `47989`)
   - App ID: `0` for desktop, or specific app ID
3. **Pair with server**:
   - Tap "Connect" in the VR connection panel
   - If not paired, the app will generate a PIN and display it in VR
   - Enter the displayed PIN on your server (Sunshine/GFE pairing dialog)
   - Wait for pairing to complete
4. **Start streaming**:
   - After pairing, tap "Connect" again in the VR connection panel
   - The connection panel will disappear and streaming will begin
   - The game stream appears on a floating panel in VR

### Streaming

- **Video Panel**: The game stream appears on a floating panel in VR
- **Passthrough**: Real-world view is visible through the headset
- **Panel Scaling**: 
  - Hover over the video panel to reveal corner handles
  - Grab a corner handle with the trigger and drag to scale the panel
  - Scale range: 0.5x to 10.0x (proportional scaling)
  - Position and rotation remain locked during scaling
- **Controls**: 
  - **Bluetooth Gamepads**: Connect an Xbox Wireless Controller, DualShock 4, or compatible gamepad directly to your Quest 3 via Bluetooth. Input is automatically forwarded to the streaming server when connected.
  - **Quest Controllers**: Use Quest controllers for VR navigation (controller input passthrough is not supported for Quest controllers)
- **Settings**: Stream settings (resolution, FPS, bitrate, codec) are configured via Moonlight preferences

### Controller Input Passthrough

The app supports Bluetooth gamepad input passthrough, allowing you to use Xbox, DualShock 4, or compatible controllers connected directly to your Quest 3 headset:

1. **Pair your controller** with the Quest 3 via Bluetooth (Quest Settings → Controllers → Pair New Controller)
2. **Connect to your streaming server** using the app
3. **Controller input is automatically forwarded** to the server once the stream is active
4. **ControllerHandler initializes automatically** when the video panel becomes visible

**Supported Controllers:**
- Xbox Wireless Controller (Xbox One, Xbox Series X/S)
- DualShock 4 (PlayStation 4)
- Other Android-compatible gamepads with standard gamepad input sources

**Note**: Quest controllers are used for VR navigation and UI interaction, but their input is not forwarded to the streaming server. Use a Bluetooth gamepad for game control.

### Pairing Notes

**Important**: The PIN is generated by the client (Quest 3), not the server. The app displays a PIN that you must enter on your server's pairing dialog. This is the reverse of what many users expect.

## Project Structure

```text
Moonlight-SpatialSDK/
├── app/
│   ├── src/main/
│   │   ├── java/              # Kotlin/Java source code
│   │   │   ├── ImmersiveActivity.kt
│   │   │   ├── MoonlightConnectionManager.kt
│   │   │   └── ...
│   │   ├── jni/               # Native Moonlight code
│   │   ├── res/               # Resources
│   │   └── AndroidManifest.xml
│   ├── scenes/                # Meta Spatial Editor scenes
│   └── build.gradle.kts
├── Documentation/             # Project documentation
└── README.md
```

## Key Features

### Immersive Architecture

- **Fully VR Experience**: Connection UI, pairing, and streaming all occur in immersive VR mode
- **In-VR Connection Panel**: Connection setup and pairing happen directly in VR
- **Passthrough Support**: Mixed reality gaming with real-world visibility

### Video Rendering

- Native hardware-accelerated video decoder (AMediaCodec)
- Direct-to-surface rendering for optimal performance
- Support for H.264, HEVC, and AV1 codecs
- Configurable resolution, FPS, and bitrate

### Connection Management

- Background thread execution (prevents ANR)
- Automatic pairing with PIN generation
- Connection status callbacks
- Graceful error handling
- Dynamic ControllerHandler initialization when stream is ready

### Passthrough Support

- Mixed reality gaming experience
- Real-world visibility while gaming
- Configurable lighting and environment

### Video Panel Scaling

- Corner-based scaling system for resizing the video panel
- Hover over panel to reveal corner handles
- Grab corner with trigger and drag to scale
- Scale range: 0.5x to 10.0x
- Proportional scaling maintains aspect ratio
- Position and rotation locked during scaling

### Sleep/Wake Recovery

- Automatic video stream recovery after device sleep/wake cycles
- Addresses color space initialization issues (known Spatial SDK limitation)
- Re-establishes video stream if it dies during long sleep periods
- Seamless recovery without manual reconnection
- See `Documentation/POST_MORTEM.md` for technical details

## Development

### Key Technologies

- **Kotlin**: Primary development language
- **Meta Spatial SDK**: VR framework
- **Moonlight Core**: Game streaming protocol (native C code)
- **Android MediaCodec**: Hardware video decoding
- **Jetpack Compose**: UI framework (for in-VR connection panel)

### Building from Source

The project uses Gradle for build management. Key build files:

- `build.gradle.kts`: Root build configuration
- `app/build.gradle.kts`: App module configuration
- `gradle/libs.versions.toml`: Dependency versions

### Debugging

- Enable USB debugging on Quest 3
- Use `adb logcat` to view logs
- Check `Documentation/POST_MORTEM.md` for known issues and solutions

## Known Limitations

- No MRUK features (anchoring, wall detection) yet
- Video surface color space initialization issue (affects PremiumMediaSample too)
  - Colors may be incorrect on first frame after surface creation
  - **Fixed**: Automatic recovery via sleep/wake cycle handling
  - Sleep/wake cycle triggers both color space fix and video stream recovery
  - See `Documentation/POST_MORTEM.md` for details

## Future Enhancements

- **Phase 1**: MRUK integration (anchoring, wall detection)
- **Phase 2**: Advanced features (scaling, interaction systems)
- **Phase 3**: Enhanced UI and configuration options

## Documentation

- [Quest 3 App Pipeline](Documentation/Quest%203%20App%20Pipeline.md): Comprehensive architecture documentation
- [POST_MORTEM](Documentation/POST_MORTEM.md): Issue investigation and resolution report
- [Native Video Path Plan](Documentation/Native_Video_Path_Plan.md): Video rendering architecture
- [Native Rendering Overview](Documentation/Native_Rendering_Overview.md): Rendering system details

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## License

This project is based on:

- **Moonlight**: [Moonlight Game Streaming](https://github.com/moonlight-stream/moonlight-android) (GPLv3)
- **Meta Spatial SDK**: Meta Platform Technologies SDK license

See individual component licenses for details.

## Acknowledgments

- [Moonlight Project](https://moonlight-stream.org/): Game streaming protocol
- [Sunshine](https://github.com/LizardByte/Sunshine): Open-source game streaming server
- Meta Spatial SDK team - VR framework and tools

## Support

For issues, questions, or contributions:

- Check the [Documentation](Documentation/) folder for detailed guides
- Review [POST_MORTEM.md](Documentation/POST_MORTEM.md) for known issues
- Open an issue on GitHub for bugs or feature requests

---

**Note**: This project is in active development. Some features may be incomplete or subject to change.
