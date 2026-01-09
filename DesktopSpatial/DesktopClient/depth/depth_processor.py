"""
Depth estimation processor using Depth Anything 3 (DA3) Streaming API.

Supports automatic GPU detection and model selection.
"""

import logging
import torch
import torch.nn.functional as F
import numpy as np
from typing import Optional, Tuple
from pathlib import Path

from .gpu_detector import GPUDetector, DA3ModelVariant

logger = logging.getLogger(__name__)


class DepthProcessor:
    """
    Real-time depth estimation using Depth Anything 3.
    
    Automatically selects appropriate model variant based on GPU capabilities.
    Uses StreamingInference API for optimized real-time video processing.
    """
    
    def __init__(
        self,
        width: int = 2560,
        height: int = 1440,
        model_variant: Optional[DA3ModelVariant] = None,
        auto_detect: bool = True
    ):
        """
        Initialize depth processor.
        
        Args:
            width: Input frame width
            height: Input frame height
            model_variant: Force specific model variant (None = auto-detect)
            auto_detect: Enable automatic GPU detection and model selection
        """
        self.width = width
        self.height = height
        self.auto_detect = auto_detect
        self.model_variant = model_variant
        
        # GPU detection
        self.gpu_detector = GPUDetector()
        self.model = None
        self.device = None
        self.is_initialized = False
        
        # Performance tracking
        self.frame_count = 0
        self.processing_times = []
        self.max_time_samples = 60
        self.current_fps = 0.0
        
    def initialize(self) -> bool:
        """
        Initialize depth processor with appropriate model.
        
        Returns:
            True if initialization successful, False otherwise
        """
        try:
            # Check CUDA availability
            if not torch.cuda.is_available():
                logger.error("CUDA not available for depth processing")
                return False
            
            self.device = torch.device("cuda:0")
            logger.info(f"Using device: {self.device}")
            
            # Detect GPU and recommend model
            if self.auto_detect and self.model_variant is None:
                if not self.gpu_detector.detect_gpu():
                    logger.warning("GPU detection failed, using DA3-Small as default")
                    self.model_variant = DA3ModelVariant.SMALL
                else:
                    self.model_variant = self.gpu_detector.recommend_model((self.width, self.height))
                    gpu_info = self.gpu_detector.get_gpu_info()
                    logger.info(f"GPU Info: {gpu_info}")
            
            # Default to Small if not specified
            if self.model_variant is None:
                self.model_variant = DA3ModelVariant.SMALL
            
            logger.info(f"Loading DA3-{self.model_variant.value.upper()} model")
            
            # Load DA3 model
            self._load_da3_model()
            
            self.is_initialized = True
            logger.info("Depth processor initialized successfully")
            return True
            
        except Exception as e:
            logger.error(f"Failed to initialize depth processor: {e}", exc_info=True)
            return False
    
    def _load_da3_model(self):
        """
        Load DA3 model. Fails hard if DA3 is not available or dependencies are missing.
        """
        # Try to import DA3
        # First check if DA3 is in a local subdirectory
        import sys
        from pathlib import Path
        da3_path = Path(__file__).parent.parent / "Depth-Anything-3" / "src"
        if da3_path.exists() and str(da3_path) not in sys.path:
            sys.path.insert(0, str(da3_path))
            logger.debug(f"Added DA3 path to sys.path: {da3_path}")
        
        try:
            from depth_anything_3.api import DepthAnything3
        except ImportError as import_err:
            # Try alternative import path (if installed as package)
            logger.debug(f"Failed to import from local path: {import_err}")
            if str(da3_path) in sys.path:
                sys.path.remove(str(da3_path))
            try:
                from depth_anything_3.api import DepthAnything3
                logger.debug("Successfully imported DA3 from system path")
            except ImportError:
                logger.error(f"Failed to import Depth Anything 3: {import_err}")
                logger.error("Install DA3 and all dependencies. Missing dependencies must be installed.")
                raise
        
        # Map model variant to DA3 model name
        model_name_map = {
            DA3ModelVariant.SMALL: "da3-small",
            DA3ModelVariant.BASE: "da3-base",
            DA3ModelVariant.LARGE: "da3-large",
            DA3ModelVariant.GIANT: "da3-giant",
        }
        
        hf_model_name_map = {
            DA3ModelVariant.SMALL: "depth-anything/DA3-SMALL",
            DA3ModelVariant.BASE: "depth-anything/DA3-BASE",
            DA3ModelVariant.LARGE: "depth-anything/DA3-LARGE",
            DA3ModelVariant.GIANT: "depth-anything/DA3-GIANT",
        }
        
        model_name = model_name_map.get(self.model_variant, "da3-small")
        hf_model_name = hf_model_name_map.get(self.model_variant, "depth-anything/DA3-SMALL")
        
        logger.info(f"Loading DA3 model: {model_name} (or HuggingFace: {hf_model_name})")
        
        # Try to load model - first try with model_name parameter
        try:
            self.model = DepthAnything3(model_name=model_name)
            logger.info(f"Successfully loaded DA3 model using model_name='{model_name}'")
        except Exception as e_local:
            logger.debug(f"Failed to load with model_name ({model_name}): {e_local}")
            # Try HuggingFace from_pretrained
            try:
                logger.info(f"Trying HuggingFace model: {hf_model_name}")
                self.model = DepthAnything3.from_pretrained(hf_model_name)
                logger.info(f"Successfully loaded DA3 model from HuggingFace: {hf_model_name}")
            except Exception as e_hf:
                logger.error(f"Failed to load DA3 model with both methods:")
                logger.error(f"  model_name='{model_name}': {e_local}")
                logger.error(f"  from_pretrained('{hf_model_name}'): {e_hf}")
                raise
        
        self.model = self.model.to(device=self.device)
        self.model.eval()
        logger.info(f"Successfully loaded DA3-{self.model_variant.value.upper()} model")
    
    def process_frame(self, frame: torch.Tensor) -> Optional[torch.Tensor]:
        """
        Process a single frame to generate depth map.
        
        Args:
            frame: Input frame as torch.Tensor on GPU (shape: [H, W, 3] or [3, H, W])
            
        Returns:
            Depth map as torch.Tensor on GPU (shape: [H, W, 1] or [H, W]) or None if error
        """
        if not self.is_initialized or self.model is None:
            logger.error("Depth processor not initialized")
            return None
        
        try:
            import time
            start_time = time.time()
            
            # Ensure frame is on correct device
            if frame.device != self.device:
                frame = frame.to(self.device)
            
            # Normalize input format
            # DA3 expects [B, C, H, W] format
            if frame.dim() == 3:
                # [H, W, C] -> [1, C, H, W]
                if frame.shape[2] == 3:
                    frame = frame.permute(2, 0, 1).unsqueeze(0)
                else:
                    frame = frame.unsqueeze(0)
            elif frame.dim() == 4:
                # Already [B, C, H, W]
                pass
            else:
                logger.error(f"Unexpected frame shape: {frame.shape}")
                return None
            
            # Normalize to [0, 1] if needed
            if frame.max() > 1.0:
                frame = frame / 255.0
            
            # Process with DA3 model
            with torch.no_grad():
                # DA3 model - use inference API
                # DA3 expects list of numpy arrays (uint8, RGB, [H, W, 3])
                # Convert from torch tensor [B, C, H, W] to numpy [H, W, 3]
                if frame.dim() == 4:
                    frame_single = frame.squeeze(0)  # [C, H, W]
                else:
                    frame_single = frame
                
                # Permute to [H, W, C] and convert to numpy
                frame_np = frame_single.permute(1, 2, 0).cpu().numpy()
                
                # Ensure values are in [0, 255] range
                if frame_np.max() <= 1.0:
                    frame_np = (frame_np * 255).astype(np.uint8)
                else:
                    frame_np = frame_np.astype(np.uint8)
                
                # DA3 inference expects list of images
                try:
                    # Check CUDA memory before inference
                    if torch.cuda.is_available():
                        torch.cuda.synchronize()
                        memory_allocated = torch.cuda.memory_allocated(self.device) / 1024**3
                        memory_reserved = torch.cuda.memory_reserved(self.device) / 1024**3
                        logger.debug(f"CUDA memory before inference: allocated={memory_allocated:.2f}GB, reserved={memory_reserved:.2f}GB")
                    
                    prediction = self.model.inference([frame_np])
                    
                    # Synchronize CUDA operations to catch any errors
                    if torch.cuda.is_available():
                        torch.cuda.synchronize()
                except RuntimeError as e:
                    if "out of memory" in str(e).lower():
                        logger.error(f"CUDA out of memory during DA3 inference: {e}")
                        logger.error("Try reducing capture resolution or using DA3-SMALL model")
                        torch.cuda.empty_cache()
                        return None
                    else:
                        logger.error(f"RuntimeError during DA3 inference: {e}", exc_info=True)
                        raise
                except Exception as e:
                    logger.error(f"Unexpected error during DA3 inference: {e}", exc_info=True)
                    raise
                
                # Extract depth map from prediction
                # prediction.depth is [N, H, W] float32 numpy array
                if prediction is None:
                    logger.error("DA3 inference returned None")
                    return None
                    
                if not hasattr(prediction, 'depth'):
                    logger.error(f"DA3 prediction object missing 'depth' attribute. Available attributes: {dir(prediction)}")
                    return None
                    
                if prediction.depth is None:
                    logger.error("DA3 prediction.depth is None")
                    return None
                
                try:
                    depth_map_np = prediction.depth[0]  # [H, W] float32
                except (IndexError, TypeError) as e:
                    logger.error(f"Failed to extract depth map from prediction.depth: {e}. prediction.depth type: {type(prediction.depth)}, shape: {getattr(prediction.depth, 'shape', 'no shape attribute')}")
                    return None
                
                # Log raw depth map from DA3 before any processing
                raw_min = float(depth_map_np.min())
                raw_max = float(depth_map_np.max())
                raw_mean = float(depth_map_np.mean())
                raw_std = float(depth_map_np.std())
                logger.info(f"Raw DA3 depth map (before processing): shape={depth_map_np.shape}, "
                           f"min={raw_min:.6f}, max={raw_max:.6f}, mean={raw_mean:.6f}, std={raw_std:.6f}")
                
                depth_map = torch.from_numpy(depth_map_np).to(self.device).float()
                
                # Resize depth map to match input frame resolution
                original_shape = depth_map.shape
                if depth_map.shape[0] != self.height or depth_map.shape[1] != self.width:
                    logger.debug(f"Resizing depth map from {original_shape} to ({self.height}, {self.width})")
                    depth_map = depth_map.unsqueeze(0).unsqueeze(0)  # [1, 1, H, W]
                    depth_map = F.interpolate(
                        depth_map,
                        size=(self.height, self.width),
                        mode='bilinear',
                        align_corners=True
                    )
                    depth_map = depth_map.squeeze(0).squeeze(0)  # [H, W]
                    
                    # Log depth map stats after resizing
                    resized_min = depth_map.min().item()
                    resized_max = depth_map.max().item()
                    resized_mean = depth_map.mean().item()
                    resized_std = depth_map.std().item()
                    logger.debug(f"Depth map after resize: min={resized_min:.6f}, max={resized_max:.6f}, "
                               f"mean={resized_mean:.6f}, std={resized_std:.6f}")
            
            # Apply depth enhancement to increase variation
            # DA3 outputs normalized depth in narrow range, we need to enhance it
            depth_min = depth_map.min()
            depth_max = depth_map.max()
            depth_range = depth_max - depth_min
            
            if depth_range > 0.001:  # Only enhance if there's some variation
                # Apply contrast stretching: map [min, max] to [0, 1]
                depth_map_enhanced = (depth_map - depth_min) / depth_range
                
                # Apply power curve (gamma correction) to enhance mid-tones and increase variation
                # Lower gamma (< 1.0) enhances darker regions, higher gamma (> 1.0) enhances brighter regions
                # Use gamma < 1.0 to stretch the narrow range more
                gamma = 0.5  # Enhance variation by applying square root
                depth_map_enhanced = torch.pow(depth_map_enhanced, gamma)
                
                # Re-normalize to [0, 1] after gamma correction
                enhanced_min = depth_map_enhanced.min()
                enhanced_max = depth_map_enhanced.max()
                if enhanced_max > enhanced_min:
                    depth_map_enhanced = (depth_map_enhanced - enhanced_min) / (enhanced_max - enhanced_min)
                
                depth_map = depth_map_enhanced
                enhanced_std = depth_map.std().item()
                logger.info(f"Applied depth enhancement: gamma={gamma}, original range=[{depth_min:.6f}, {depth_max:.6f}] "
                           f"(std={depth_range:.6f}), enhanced range=[{depth_map.min():.6f}, {depth_map.max():.6f}] "
                           f"(std={enhanced_std:.6f})")
            else:
                logger.warning(f"Depth map has no variation (range={depth_range:.6f}), cannot enhance")
            
            # Log depth map statistics after enhancement (use INFO for visibility)
            depth_min = depth_map.min().item()
            depth_max = depth_map.max().item()
            depth_mean = depth_map.mean().item()
            depth_std = depth_map.std().item()
            logger.info(f"Depth map stats (after enhancement): shape={depth_map.shape}, min={depth_min:.3f}, max={depth_max:.3f}, "
                        f"mean={depth_mean:.3f}, std={depth_std:.3f}")
            
            # Warn if depth map is still too flat (low variation)
            if depth_std < 0.05:
                logger.warning(f"Depth map has low variation (std={depth_std:.3f}) after enhancement, parallax effect may be minimal")
            
            # Update performance metrics
            processing_time = time.time() - start_time
            self.processing_times.append(processing_time)
            if len(self.processing_times) > self.max_time_samples:
                self.processing_times.pop(0)
            
            if len(self.processing_times) > 0:
                avg_time = sum(self.processing_times) / len(self.processing_times)
                self.current_fps = 1.0 / avg_time if avg_time > 0 else 0.0
            
            self.frame_count += 1
            
            return depth_map
            
        except Exception as e:
            logger.error(f"Error processing depth frame: {e}", exc_info=True)
            return None
    
    def get_fps(self) -> float:
        """
        Get current processing FPS.
        
        Returns:
            Current FPS
        """
        return self.current_fps
    
    def get_frame_count(self) -> int:
        """
        Get total frames processed.
        
        Returns:
            Frame count
        """
        return self.frame_count
    
    def cleanup(self):
        """Clean up resources."""
        if self.model is not None:
            del self.model
            self.model = None
        
        torch.cuda.empty_cache()
        self.is_initialized = False
        logger.info("Cleaned up depth processor")
