"""
GPU detection module for automatic DA3 model selection.

Detects NVIDIA GPU model and recommends appropriate Depth Anything 3 variant
based on GPU capabilities and expected performance.
"""

import logging
import re
from enum import Enum
from typing import Optional, Tuple, Dict

try:
    import pynvml
    PYNVML_AVAILABLE = True
except ImportError:
    PYNVML_AVAILABLE = False
    logging.warning("pynvml/nvidia-ml-py not available, GPU detection will be limited")

logger = logging.getLogger(__name__)


class DA3ModelVariant(Enum):
    """Depth Anything 3 model variants."""
    SMALL = "small"
    BASE = "base"
    LARGE = "large"
    GIANT = "giant"


class GPUDetector:
    """
    GPU detection and model recommendation system.
    
    Automatically selects appropriate DA3 model variant based on GPU capabilities.
    """
    
    # GPU model to DA3 variant mapping
    # Based on performance benchmarks from feasibility report
    GPU_MODEL_MAP: Dict[str, DA3ModelVariant] = {
        # RTX 30 series
        "RTX 3060": DA3ModelVariant.SMALL,
        "RTX 3060 Ti": DA3ModelVariant.SMALL,  # May handle Base, but Small safer
        "RTX 3070": DA3ModelVariant.BASE,
        "RTX 3070 Ti": DA3ModelVariant.BASE,
        "RTX 3080": DA3ModelVariant.BASE,
        "RTX 3080 Ti": DA3ModelVariant.BASE,
        "RTX 3090": DA3ModelVariant.BASE,
        "RTX 3090 Ti": DA3ModelVariant.BASE,
        
        # RTX 40 series
        "RTX 4060": DA3ModelVariant.SMALL,
        "RTX 4060 Ti": DA3ModelVariant.SMALL,  # May handle Base, but Small safer
        "RTX 4070": DA3ModelVariant.BASE,
        "RTX 4070 Ti": DA3ModelVariant.BASE,
        "RTX 4080": DA3ModelVariant.BASE,
        "RTX 4090": DA3ModelVariant.BASE,
        
        # RTX 20 series (older, use Small)
        "RTX 2060": DA3ModelVariant.SMALL,
        "RTX 2070": DA3ModelVariant.SMALL,
        "RTX 2080": DA3ModelVariant.SMALL,
        "RTX 2080 Ti": DA3ModelVariant.SMALL,
        
        # GTX 10 series (use Small)
        "GTX 1060": DA3ModelVariant.SMALL,
        "GTX 1070": DA3ModelVariant.SMALL,
        "GTX 1080": DA3ModelVariant.SMALL,
        "GTX 1080 Ti": DA3ModelVariant.SMALL,
    }
    
    def __init__(self):
        """Initialize GPU detector."""
        self.gpu_name: Optional[str] = None
        self.gpu_model: Optional[str] = None
        self.compute_capability: Optional[Tuple[int, int]] = None
        self.recommended_variant: Optional[DA3ModelVariant] = None
        self.estimated_fps: Optional[float] = None
        
        if PYNVML_AVAILABLE:
            try:
                pynvml.nvmlInit()
            except Exception as e:
                logger.warning(f"Failed to initialize NVML: {e}")
    
    def detect_gpu(self) -> bool:
        """
        Detect GPU model and capabilities.
        
        Returns:
            True if GPU detected successfully, False otherwise
        """
        if not PYNVML_AVAILABLE:
            logger.warning("pynvml not available, using fallback detection")
            return self._detect_gpu_fallback()
        
        try:
            # Get first NVIDIA GPU
            handle = pynvml.nvmlDeviceGetHandleByIndex(0)
            gpu_name_bytes = pynvml.nvmlDeviceGetName(handle)
            # Handle both bytes and string returns (depending on pynvml version)
            if isinstance(gpu_name_bytes, bytes):
                self.gpu_name = gpu_name_bytes.decode('utf-8')
            else:
                self.gpu_name = gpu_name_bytes
            
            # Extract GPU model from name
            self.gpu_model = self._extract_gpu_model(self.gpu_name)
            
            # Get compute capability
            try:
                major, minor = pynvml.nvmlDeviceGetCudaComputeCapability(handle)
                self.compute_capability = (major, minor)
            except Exception:
                logger.warning("Could not get compute capability")
            
            logger.info(f"Detected GPU: {self.gpu_name}")
            if self.gpu_model:
                logger.info(f"GPU Model: {self.gpu_model}")
            if self.compute_capability:
                logger.info(f"Compute Capability: {self.compute_capability[0]}.{self.compute_capability[1]}")
            
            return True
            
        except Exception as e:
            logger.error(f"Failed to detect GPU: {e}")
            return self._detect_gpu_fallback()
    
    def _detect_gpu_fallback(self) -> bool:
        """
        Fallback GPU detection using torch.
        
        Returns:
            True if GPU available, False otherwise
        """
        try:
            import torch
            if not torch.cuda.is_available():
                logger.error("CUDA not available")
                return False
            
            self.gpu_name = torch.cuda.get_device_name(0)
            self.gpu_model = self._extract_gpu_model(self.gpu_name)
            logger.info(f"Detected GPU (fallback): {self.gpu_name}")
            return True
        except Exception as e:
            logger.error(f"Fallback GPU detection failed: {e}")
            return False
    
    def _extract_gpu_model(self, gpu_name: str) -> Optional[str]:
        """
        Extract GPU model from GPU name string.
        
        Args:
            gpu_name: Full GPU name (e.g., "NVIDIA GeForce RTX 4080")
            
        Returns:
            GPU model string (e.g., "RTX 4080") or None
        """
        if not gpu_name:
            return None
        
        # Try to match RTX/GTX patterns
        patterns = [
            r'(RTX \d{4}(?: Ti)?)',
            r'(GTX \d{4}(?: Ti)?)',
            r'(RTX \d{3}(?: Ti)?)',
            r'(GTX \d{3}(?: Ti)?)',
        ]
        
        for pattern in patterns:
            match = re.search(pattern, gpu_name, re.IGNORECASE)
            if match:
                return match.group(1).upper()
        
        return None
    
    def recommend_model(self, resolution: Tuple[int, int] = (2560, 1440)) -> DA3ModelVariant:
        """
        Recommend DA3 model variant based on detected GPU.
        
        Args:
            resolution: Target resolution (width, height) for performance estimation
            
        Returns:
            Recommended DA3ModelVariant
        """
        if not self.gpu_model:
            if not self.detect_gpu():
                # Default to Small if detection fails
                logger.warning("GPU detection failed, defaulting to DA3-Small")
                self.recommended_variant = DA3ModelVariant.SMALL
                return DA3ModelVariant.SMALL
        
        # Look up recommended variant
        self.recommended_variant = self.GPU_MODEL_MAP.get(self.gpu_model, DA3ModelVariant.SMALL)
        
        # Estimate FPS based on GPU and resolution
        self.estimated_fps = self._estimate_fps(self.recommended_variant, resolution)
        
        logger.info(f"Recommended model: DA3-{self.recommended_variant.value.upper()}")
        logger.info(f"Estimated FPS @ {resolution[0]}x{resolution[1]}: {self.estimated_fps:.1f}")
        
        return self.recommended_variant
    
    def _estimate_fps(
        self,
        variant: DA3ModelVariant,
        resolution: Tuple[int, int]
    ) -> float:
        """
        Estimate FPS for given model variant and resolution.
        
        Based on benchmarks from feasibility report:
        - DA3-Small: ~25-35 FPS on RTX 3060 @ 1440p, ~35-45 FPS on RTX 3070+
        - DA3-Base: ~30-40 FPS on RTX 3070+ @ 1440p, ~40-50+ FPS on RTX 4080
        
        Args:
            variant: DA3 model variant
            resolution: Target resolution (width, height)
            
        Returns:
            Estimated FPS
        """
        width, height = resolution
        pixels = width * height
        
        # Base FPS estimates from benchmarks (scaled for resolution)
        # Benchmarks were at 504x336, we need to scale for 2560x1440
        benchmark_res = 504 * 336
        scale_factor = pixels / benchmark_res
        
        # Performance estimates by GPU tier
        if self.gpu_model and "RTX 4080" in self.gpu_model or "RTX 4090" in self.gpu_model:
            # High-end RTX 40 series
            if variant == DA3ModelVariant.SMALL:
                base_fps = 50  # High estimate for Small on 4080/4090
            elif variant == DA3ModelVariant.BASE:
                base_fps = 45  # High estimate for Base on 4080/4090
            else:
                base_fps = 25
        elif self.gpu_model and "RTX 3070" in self.gpu_model or "RTX 3080" in self.gpu_model or "RTX 3090" in self.gpu_model:
            # Mid-high RTX 30 series
            if variant == DA3ModelVariant.SMALL:
                base_fps = 40
            elif variant == DA3ModelVariant.BASE:
                base_fps = 35
            else:
                base_fps = 20
        elif self.gpu_model and "RTX 3060" in self.gpu_model:
            # Mid-range RTX 30 series
            if variant == DA3ModelVariant.SMALL:
                base_fps = 30
            else:
                base_fps = 20  # Base may struggle on 3060
        else:
            # Conservative estimate for unknown/older GPUs
            if variant == DA3ModelVariant.SMALL:
                base_fps = 25
            else:
                base_fps = 15
        
        # Scale for resolution (inverse relationship)
        # Higher resolution = lower FPS
        fps = base_fps / (scale_factor ** 0.5)  # Square root scaling
        
        return max(fps, 15.0)  # Minimum 15 FPS estimate
    
    def get_gpu_info(self) -> Dict:
        """
        Get GPU information dictionary.
        
        Returns:
            Dictionary with GPU information
        """
        return {
            "gpu_name": self.gpu_name,
            "gpu_model": self.gpu_model,
            "compute_capability": self.compute_capability,
            "recommended_variant": self.recommended_variant.value if self.recommended_variant else None,
            "estimated_fps": self.estimated_fps,
        }
