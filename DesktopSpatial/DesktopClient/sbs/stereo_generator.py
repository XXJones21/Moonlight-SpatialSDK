"""
Side-by-side (SBS) stereoscopic frame generation.

Generates SBS frames from RGB input and depth maps using GPU-accelerated parallax shader.
Output: 5120x1440 SBS frame (2560x1440 per eye).
"""

import logging
import torch
import torch.nn.functional as F
from typing import Optional, Tuple

logger = logging.getLogger(__name__)


class StereoGenerator:
    """
    GPU-based SBS stereoscopic frame generator.
    
    Uses horizontal parallax based on depth values to create left/right eye views.
    Combines into single 5120x1440 side-by-side frame.
    """
    
    def __init__(
        self,
        input_width: int = 2560,
        input_height: int = 1440,
        parallax_strength: float = 1.0,
        convergence: float = 0.5
    ):
        """
        Initialize stereo generator.
        
        Args:
            input_width: Input frame width (per eye)
            input_height: Input frame height
            parallax_strength: Parallax strength multiplier (0.0 = no parallax, 1.0 = full)
            convergence: Convergence point (0.0 = near, 1.0 = far, 0.5 = middle)
        """
        self.input_width = input_width
        self.input_height = input_height
        self.output_width = input_width * 2  # SBS: double width
        self.output_height = input_height
        self.parallax_strength = parallax_strength
        self.convergence = convergence
        
        self.device = torch.device("cuda:0") if torch.cuda.is_available() else torch.device("cpu")
        logger.info(f"Stereo generator initialized: {self.input_width}x{self.input_height} -> {self.output_width}x{self.output_height}")
        logger.info(f"Parallax strength: {self.parallax_strength}, Convergence: {self.convergence}")
    
    def generate_sbs(
        self,
        frame: torch.Tensor,
        depth_map: torch.Tensor
    ) -> Optional[torch.Tensor]:
        """
        Generate side-by-side stereoscopic frame from RGB frame and depth map.
        
        Args:
            frame: RGB frame as torch.Tensor on GPU (shape: [H, W, 3] or [3, H, W])
            depth_map: Depth map as torch.Tensor on GPU (shape: [H, W] or [1, H, W])
            
        Returns:
            SBS frame as torch.Tensor on GPU (shape: [H, 2*W, 3]) or None if error
        """
        try:
            # Ensure tensors are on GPU
            if frame.device != self.device:
                frame = frame.to(self.device)
            if depth_map.device != self.device:
                depth_map = depth_map.to(self.device)
            
            # Normalize frame format to [H, W, 3]
            if frame.dim() == 4:
                frame = frame.squeeze(0)  # Remove batch dimension
            if frame.dim() == 3 and frame.shape[0] == 3:
                # [C, H, W] -> [H, W, C]
                frame = frame.permute(1, 2, 0)
            
            # Normalize depth map format to [H, W]
            if depth_map.dim() == 4:
                depth_map = depth_map.squeeze(0)
            if depth_map.dim() == 3:
                depth_map = depth_map.squeeze(0)  # Remove channel dimension
            
            # Validate depth map for invalid values
            if torch.isnan(depth_map).any():
                logger.error("Depth map contains NaN values")
                return None
            if torch.isinf(depth_map).any():
                logger.error("Depth map contains Inf values")
                return None
            
            # Ensure depth is normalized to [0, 1]
            depth_min = depth_map.min()
            depth_max = depth_map.max()
            depth_mean = depth_map.mean()
            depth_std = depth_map.std()
            
            # Log depth map statistics before normalization
            logger.debug(f"Depth map stats (pre-normalize): min={depth_min:.3f}, max={depth_max:.3f}, "
                        f"mean={depth_mean:.3f}, std={depth_std:.3f}")
            
            if depth_max > depth_min:
                depth_map = (depth_map - depth_min) / (depth_max - depth_min)
            else:
                logger.warning("Depth map has no variation (min == max), parallax will be zero")
            
            # Generate left and right eye views
            left_eye = self._generate_eye_view(frame, depth_map, is_left=True)
            right_eye = self._generate_eye_view(frame, depth_map, is_left=False)
            
            # Synchronize CUDA before accessing tensor values
            if torch.cuda.is_available():
                torch.cuda.synchronize()
            
            # Log eye view statistics to verify parallax is working
            left_min, left_max = left_eye.min().item(), left_eye.max().item()
            right_min, right_max = right_eye.min().item(), right_eye.max().item()
            logger.debug(f"Left eye range: [{left_min:.3f}, {left_max:.3f}]")
            logger.debug(f"Right eye range: [{right_min:.3f}, {right_max:.3f}]")
            
            # Combine into SBS frame
            sbs_frame = torch.cat([left_eye, right_eye], dim=1)  # Concatenate horizontally
            
            # Synchronize CUDA after SBS generation
            if torch.cuda.is_available():
                torch.cuda.synchronize()
            
            # Verify SBS frame dimensions
            expected_shape = (self.input_height, self.output_width, 3)
            if sbs_frame.shape != expected_shape:
                logger.warning(f"SBS frame shape mismatch: expected {expected_shape}, got {sbs_frame.shape}")
            else:
                logger.debug(f"SBS frame generated successfully: shape={sbs_frame.shape}, dtype={sbs_frame.dtype}")
            
            return sbs_frame
            
        except Exception as e:
            logger.error(f"Error generating SBS frame: {e}", exc_info=True)
            return None
    
    def _generate_eye_view(
        self,
        frame: torch.Tensor,
        depth_map: torch.Tensor,
        is_left: bool
    ) -> torch.Tensor:
        """
        Generate single eye view with horizontal parallax.
        
        Args:
            frame: RGB frame [H, W, 3]
            depth_map: Depth map [H, W]
            is_left: True for left eye, False for right eye
            
        Returns:
            Eye view frame [H, W, 3]
        """
        H, W = frame.shape[:2]
        
        # Create coordinate grid
        y_coords, x_coords = torch.meshgrid(
            torch.arange(H, device=self.device, dtype=torch.float32),
            torch.arange(W, device=self.device, dtype=torch.float32),
            indexing='ij'
        )
        
        # Calculate parallax offset
        # Depth values: 0 = near (more parallax), 1 = far (less parallax)
        # Adjust for convergence point
        adjusted_depth = depth_map - self.convergence  # Center around convergence
        
        # Calculate horizontal shift
        # Positive depth (far) shifts right for left eye, left for right eye
        # Negative depth (near) shifts left for left eye, right for right eye
        max_shift = self.input_width * 0.05 * self.parallax_strength  # Max 5% of width
        
        if is_left:
            # Left eye: far objects shift right, near objects shift left
            shift = -adjusted_depth * max_shift
        else:
            # Right eye: far objects shift left, near objects shift right
            shift = adjusted_depth * max_shift
        
        # Apply shift to x coordinates
        x_coords_shifted = x_coords + shift
        
        # Clamp to valid range
        x_coords_shifted = torch.clamp(x_coords_shifted, 0, W - 1)
        
        # Sample frame at shifted coordinates using bilinear interpolation
        # Reshape for grid_sample: [1, 3, H, W] format
        frame_batch = frame.permute(2, 0, 1).unsqueeze(0)  # [1, 3, H, W]
        
        # Normalize coordinates to [-1, 1] for grid_sample
        x_norm = (x_coords_shifted / (W - 1)) * 2.0 - 1.0
        y_norm = (y_coords / (H - 1)) * 2.0 - 1.0
        
        # Create grid tensor [1, H, W, 2]
        grid = torch.stack([x_norm, y_norm], dim=-1).unsqueeze(0)
        
        # Sample with bilinear interpolation
        sampled = F.grid_sample(
            frame_batch,
            grid,
            mode='bilinear',
            padding_mode='border',
            align_corners=True
        )
        
        # Convert back to [H, W, 3]
        eye_view = sampled.squeeze(0).permute(1, 2, 0)
        
        return eye_view
    
    def set_parallax_strength(self, strength: float):
        """
        Update parallax strength.
        
        Args:
            strength: New parallax strength (0.0 to 1.0)
        """
        self.parallax_strength = max(0.0, min(1.0, strength))
        logger.info(f"Parallax strength updated: {self.parallax_strength}")
    
    def set_convergence(self, convergence: float):
        """
        Update convergence point.
        
        Args:
            convergence: New convergence point (0.0 = near, 1.0 = far)
        """
        self.convergence = max(0.0, min(1.0, convergence))
        logger.info(f"Convergence updated: {self.convergence}")
