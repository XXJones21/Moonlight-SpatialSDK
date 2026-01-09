"""
Main entry point for Desktop Stereoscopic Client.

Processes desktop capture → depth estimation → SBS generation → virtual display rendering.
"""

import logging
import sys
import signal
import time
from pathlib import Path

# Add current directory to path for imports
sys.path.insert(0, str(Path(__file__).parent))

# Setup logging
import colorlog

handler = colorlog.StreamHandler()
handler.setFormatter(colorlog.ColoredFormatter(
    '%(log_color)s%(levelname)s:%(name)s:%(message)s'
))
logger = logging.getLogger()
logger.addHandler(handler)
logger.setLevel(logging.INFO)

# Import modules
from capture import DXGICapture
from depth import DepthProcessor, GPUDetector
from sbs import StereoGenerator
from render import VirtualDisplayRenderer
from config import Config
import torch

# Global state for cleanup
capture = None
depth_processor = None
stereo_generator = None
renderer = None
running = True


def signal_handler(sig, frame):
    """Handle shutdown signals."""
    global running
    logger.info("Shutdown signal received")
    running = False


def main():
    """Main application loop."""
    global capture, depth_processor, stereo_generator, renderer, running
    
    # Setup signal handlers
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    
    logger.info("Desktop Stereoscopic Client starting...")
    
    # Load configuration
    config = Config.load()
    logger.setLevel(getattr(logging, config.log_level))
    
    # Initialize components
    logger.info("Initializing components...")
    
    # 1. Desktop capture
    logger.info(f"Capturing from monitor {config.capture_monitor} (0 = primary, 1 = secondary, etc.)")
    capture = DXGICapture(
        width=config.capture_width,
        height=config.capture_height,
        target_fps=config.capture_fps,
        monitor_idx=config.capture_monitor
    )
    if not capture.initialize():
        logger.error("Failed to initialize desktop capture")
        return 1
    if not capture.start():
        logger.error("Failed to start desktop capture")
        return 1
    
    # 2. GPU detection
    gpu_detector = GPUDetector()
    if not gpu_detector.detect_gpu():
        logger.warning("GPU detection failed, continuing with defaults")
    else:
        gpu_info = gpu_detector.get_gpu_info()
        logger.info(f"GPU Information: {gpu_info}")
    
    # 3. Depth processor
    depth_processor = DepthProcessor(
        width=config.depth_width,
        height=config.depth_height,
        model_variant=config.get_depth_model_variant(),
        auto_detect=config.depth_auto_detect
    )
    if not depth_processor.initialize():
        logger.error("Failed to initialize depth processor")
        return 1
    
    # 4. Stereo generator
    stereo_generator = StereoGenerator(
        input_width=config.capture_width,
        input_height=config.capture_height,
        parallax_strength=config.parallax_strength,
        convergence=config.convergence
    )
    
    # 5. Virtual display renderer
    renderer = VirtualDisplayRenderer(
        width=config.virtual_display_width,
        height=config.virtual_display_height,
        target_fps=config.virtual_display_fps
    )
    if not renderer.initialize():
        logger.error("Failed to initialize virtual display renderer")
        return 1
    if not renderer.start():
        logger.error("Failed to start virtual display renderer")
        return 1
    
    # Ensure window is ready for capture by rendering a test frame
    logger.info("Rendering test frame to ensure window is ready for DXGI capture...")
    try:
        import torch
        test_frame = torch.zeros((renderer.height, renderer.width, 3), dtype=torch.float32)
        if renderer.render_frame(test_frame):
            logger.info("Test frame rendered successfully - window is ready for capture")
        else:
            logger.warning("Test frame render failed - window may not be ready for capture")
    except Exception as e:
        logger.warning(f"Test frame render failed: {e}")
    
    # Small delay to ensure window is fully ready
    time.sleep(0.5)
    
    logger.info("All components initialized, starting processing loop...")
    logger.info("=" * 80)
    logger.info("VERIFICATION: To confirm Sunshine is using the virtual display:")
    logger.info("  1. Close this DesktopClient (the stream should STOP if Sunshine is configured correctly)")
    logger.info("  2. If stream continues, Sunshine is NOT using the virtual display")
    logger.info("  3. Check sunshine.conf: output_name should be set to virtual display ID (e.g., \\\\.\\DISPLAY15)")
    logger.info("=" * 80)
    
    # Main processing loop
    frame_count = 0
    last_stats_time = time.time()
    
    try:
        while running:
            loop_start = time.time()
            
            # 1. Capture frame
            frame = capture.get_frame()
            if frame is None:
                time.sleep(0.001)  # Small delay if no frame
                continue
            
            # 2. Process depth
            depth_map = depth_processor.process_frame(frame)
            if depth_map is None:
                logger.warning("Failed to process depth, skipping frame")
                continue
            
            # 3. Generate SBS
            try:
                sbs_frame = stereo_generator.generate_sbs(frame, depth_map)
                if sbs_frame is None:
                    logger.warning("Failed to generate SBS, skipping frame")
                    continue
                
                # Log successful SBS generation (every 60 frames to avoid spam)
                if frame_count % 60 == 0:
                    logger.debug(f"Successfully generated SBS frame {frame_count}: shape={sbs_frame.shape}")
            except Exception as e:
                logger.error(f"Error generating SBS frame: {e}", exc_info=True)
                continue
            
            # 4. Render to virtual display
            try:
                # Synchronize CUDA before rendering to catch any pending errors
                if torch.cuda.is_available():
                    torch.cuda.synchronize()
                
                if not renderer.render_frame(sbs_frame):
                    logger.warning("Failed to render frame")
            except Exception as e:
                logger.error(f"Error rendering frame to virtual display: {e}", exc_info=True)
                continue
            
            frame_count += 1
            
            # Print stats every second
            current_time = time.time()
            if current_time - last_stats_time >= 1.0:
                capture_fps = capture.get_fps()
                depth_fps = depth_processor.get_fps()
                render_fps = renderer.get_fps()
                
                logger.info(
                    f"Stats - Capture: {capture_fps:.1f} FPS, "
                    f"Depth: {depth_fps:.1f} FPS, "
                    f"Render: {render_fps:.1f} FPS, "
                    f"Total frames: {frame_count}"
                )
                
                last_stats_time = current_time
            
            # Frame pacing
            loop_time = time.time() - loop_start
            target_time = 1.0 / config.capture_fps
            if loop_time < target_time:
                time.sleep(target_time - loop_time)
    
    except KeyboardInterrupt:
        logger.info("Interrupted by user")
    except Exception as e:
        logger.error(f"Error in main loop: {e}", exc_info=True)
    finally:
        # Cleanup
        logger.info("Shutting down...")
        if capture:
            capture.cleanup()
        if depth_processor:
            depth_processor.cleanup()
        if renderer:
            renderer.cleanup()
        logger.info("Shutdown complete")
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
