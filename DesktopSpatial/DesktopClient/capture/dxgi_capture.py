"""
DXGI Desktop Duplication capture module.

Captures desktop output at specified resolution using DXGI Desktop Duplication API.
Target: 2560x1440 @ 60 FPS with 1-3ms latency per frame.
"""

import logging
import time
import numpy as np
import torch
from typing import Optional, Tuple, Callable
import dxcam

logger = logging.getLogger(__name__)


class DXGICapture:
    """
    Desktop capture using DXGI Desktop Duplication API via dxcam library.
    
    Captures frames from the primary monitor or specified display and converts
    them to GPU textures (PyTorch tensors) for efficient processing.
    """
    
    def __init__(
        self,
        width: int = 2560,
        height: int = 1440,
        target_fps: int = 60,
        monitor_idx: int = 0
    ):
        """
        Initialize DXGI capture.
        
        Args:
            width: Capture width in pixels
            height: Capture height in pixels
            target_fps: Target frame rate (default: 60)
            monitor_idx: Monitor index to capture (0 = primary)
        """
        self.width = width
        self.height = height
        self.target_fps = target_fps
        self.monitor_idx = monitor_idx
        self.camera = None
        self.is_capturing = False
        self.frame_count = 0
        self.last_frame_time = 0
        self.fps = 0.0
        
        # Frame timing for FPS calculation
        self.frame_times = []
        self.max_frame_time_samples = 60
        
    def initialize(self) -> bool:
        """
        Initialize DXGI capture.
        
        Returns:
            True if initialization successful, False otherwise
        """
        try:
            # Create camera instance for specified monitor
            self.camera = dxcam.create(output_idx=self.monitor_idx, output_color="RGB")
            
            # Set capture region (full screen or specified resolution)
            # Note: dxcam captures at native resolution, we'll resize if needed
            logger.info(f"Initialized DXGI capture for monitor {self.monitor_idx}")
            logger.info(f"Target resolution: {self.width}x{self.height} @ {self.target_fps} FPS")
            
            return True
        except Exception as e:
            logger.error(f"Failed to initialize DXGI capture: {e}")
            return False
    
    def start(self) -> bool:
        """
        Start capturing frames.
        
        Returns:
            True if started successfully, False otherwise
        """
        if self.camera is None:
            if not self.initialize():
                return False
        
        try:
            self.camera.start(target_fps=self.target_fps, video_mode=True)
            self.is_capturing = True
            self.frame_count = 0
            self.last_frame_time = time.time()
            logger.info("Started DXGI capture")
            return True
        except Exception as e:
            logger.error(f"Failed to start capture: {e}")
            return False
    
    def stop(self):
        """Stop capturing frames."""
        if self.camera is not None:
            try:
                self.camera.stop()
            except Exception:
                pass
        self.is_capturing = False
        logger.info("Stopped DXGI capture")
    
    def get_frame(self) -> Optional[torch.Tensor]:
        """
        Capture a single frame and return as GPU tensor.
        
        Returns:
            Frame as torch.Tensor on GPU (RGB, shape: [H, W, 3]) or None if error
        """
        if not self.is_capturing or self.camera is None:
            return None
        
        try:
            # Capture frame (dxcam returns numpy array)
            frame = self.camera.get_latest_frame()
            
            if frame is None:
                return None
            
            # Convert to torch tensor
            # dxcam returns BGR format, convert to RGB
            # Use .copy() to create contiguous array (fixes negative stride issue)
            frame_rgb = frame[:, :, ::-1].copy()  # BGR to RGB
            
            # Resize if needed (dxcam captures at native resolution)
            if frame_rgb.shape[0] != self.height or frame_rgb.shape[1] != self.width:
                import cv2
                frame_rgb = cv2.resize(frame_rgb, (self.width, self.height), interpolation=cv2.INTER_LINEAR)
            
            # Convert to torch tensor and move to GPU
            frame_tensor = torch.from_numpy(frame_rgb).float() / 255.0  # Normalize to [0, 1]
            frame_tensor = frame_tensor.cuda()  # Move to GPU
            
            # Update FPS calculation
            current_time = time.time()
            if self.last_frame_time > 0:
                frame_time = current_time - self.last_frame_time
                self.frame_times.append(frame_time)
                if len(self.frame_times) > self.max_frame_time_samples:
                    self.frame_times.pop(0)
                
                if len(self.frame_times) > 0:
                    avg_frame_time = sum(self.frame_times) / len(self.frame_times)
                    self.fps = 1.0 / avg_frame_time if avg_frame_time > 0 else 0.0
            
            self.last_frame_time = current_time
            self.frame_count += 1
            
            return frame_tensor
            
        except Exception as e:
            logger.error(f"Error capturing frame: {e}")
            return None
    
    def get_fps(self) -> float:
        """
        Get current capture FPS.
        
        Returns:
            Current FPS
        """
        return self.fps
    
    def get_frame_count(self) -> int:
        """
        Get total frames captured.
        
        Returns:
            Frame count
        """
        return self.frame_count
    
    def cleanup(self):
        """Clean up resources."""
        self.stop()
        self.camera = None
        logger.info("Cleaned up DXGI capture")
