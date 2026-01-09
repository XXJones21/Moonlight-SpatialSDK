# Desktop Stereoscopic Client

Desktop-side processing application for PC-side stereoscopic 3D streaming to Quest 3.

## Overview

This application processes desktop output to create side-by-side (SBS) stereoscopic frames:

1. **Desktop Capture**: Captures desktop at 2560x1440 using DXGI Desktop Duplication
2. **Depth Estimation**: Performs real-time depth estimation using Depth Anything 3 (DA3)
3. **SBS Generation**: Creates 5120x1440 side-by-side stereoscopic frames
4. **Virtual Display Rendering**: Renders processed frames to virtual display
5. **Sunshine Integration**: Streams via Sunshine to Quest 3 using Moonlight

## Requirements

- Windows 10/11
- NVIDIA RTX 3060+ (RTX 3070+ recommended for DA3-Base)
- Virtual Display Driver installed and configured at 5120x1440
- Sunshine server configured
- Python 3.10+
- CUDA-capable GPU with 8GB+ VRAM
- 30+ Mbps network connection (50+ Mbps recommended)

## Installation

**Important**: DA3 requires Python <=3.13. If you have Python 3.14, use Python 3.12 or 3.13 for this project.

1. Install Python dependencies (use Python 3.12 or 3.13):
```bash
py -3.12 -m pip install -r requirements.txt
```

2. Install Depth Anything 3 (optional, for real depth estimation):
   ```bash
   git clone https://github.com/ByteDance-Seed/Depth-Anything-3
   ```
   
   **Note**: The launcher scripts (`run.bat`/`run.ps1`) will automatically detect and install DA3 if the `Depth-Anything-3` directory exists. If DA3 is not installed, the application will use a placeholder model (for testing only).

3. Configure virtual display:
   - Install Virtual Display Driver
   - Configure resolution to 5120x1440
   - Note the display identifier (e.g., `\\.\DISPLAY2`)

4. Configure Sunshine:
   - Locate `sunshine.conf` (typically at `C:\Program Files\Sunshine\config\sunshine.conf` on Windows)
   - Set `output_name` in `sunshine.conf` to virtual display identifier (e.g., `output_name = \\.\DISPLAY2`)
   - Add 5120x1440 to resolutions list if needed
   - Restart Sunshine for changes to take effect

## Usage

1. Start the desktop client using the launcher script:
```bash
# Windows Batch file
run.bat

# Or PowerShell script
.\run.ps1
```

   Or manually with Python 3.13:
```bash
py -3.13 main.py
```

   **Note**: Always use Python 3.13 to ensure DA3 compatibility.

2. The application will:
   - Detect GPU and select appropriate DA3 model (Base for RTX 3070+, Small for RTX 3060)
   - Capture desktop at 2560x1440
   - Process depth and generate SBS frames
   - Render to virtual display at 5120x1440

3. Connect from Quest 3:
   - Enable "PC-Side Stereoscopic Mode" in app settings
   - Connect to Sunshine server
   - Stream will be received as 5120x1440 SBS and split to left/right eyes

## Configuration

Edit `config.yaml` or create one with:

```yaml
# Capture settings
capture_width: 2560
capture_height: 1440
capture_fps: 60

# Virtual display settings
virtual_display_width: 5120
virtual_display_height: 1440
virtual_display_fps: 60

# Depth estimation
depth_model_variant: null  # null = auto-detect
depth_auto_detect: true

# SBS generation
parallax_strength: 1.0
convergence: 0.5

# Performance
performance_target_fps: 60.0
performance_fallback_threshold: 45.0

# Sunshine
sunshine_output_name: null  # null = auto-detect
```

## GPU Model Selection

The application automatically selects the appropriate DA3 model:

- **RTX 3060 and below**: DA3-Small (25-35 FPS @ 1440p)
- **RTX 3070+**: DA3-Base (30-40+ FPS @ 1440p)
- **RTX 4080/4090**: DA3-Base (40-50+ FPS @ 1440p, optimal)

Manual override available in configuration.

## Performance

Expected performance on RTX 4080:
- Capture: 60 FPS
- Depth (DA3-Base): 40-50+ FPS
- SBS Generation: 60+ FPS
- Rendering: 60 FPS
- End-to-end latency: 29-70ms (network dependent)

## Troubleshooting

1. **Virtual display not detected**:
   - Verify virtual display driver is installed
   - Check display resolution is 5120x1440
   - Run `dxgi-info.exe` to list displays

2. **Low FPS**:
   - Check GPU utilization
   - Try DA3-Small instead of DA3-Base
   - Reduce capture resolution

3. **Sunshine streaming issues**:
   - Verify `output_name` in sunshine.conf points to virtual display
   - Check network bandwidth (30-50 Mbps required)
   - Ensure codec supports 5120x1440 (HEVC recommended)

## Implementation Status

- ✅ **DA3 Model Loading**: Implemented with automatic model selection and fallback
- ✅ **Virtual Display Rendering**: Implemented using OpenGL fullscreen window
- ✅ **Desktop Capture**: DXGI Desktop Duplication
- ✅ **SBS Generation**: GPU-accelerated parallax
- ✅ **Sunshine Integration**: Virtual display detection and configuration

All core components are implemented and ready for testing. See `IMPLEMENTATION_STATUS.md` for details.
