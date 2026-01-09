"""
Configuration management for desktop stereoscopic client.
"""

import logging
import yaml
from pathlib import Path
from typing import Optional, Dict, Any
from dataclasses import dataclass, asdict

# Import DA3ModelVariant - handle both relative and absolute imports
try:
    from depth.gpu_detector import DA3ModelVariant
except ImportError:
    from ..depth.gpu_detector import DA3ModelVariant

logger = logging.getLogger(__name__)


@dataclass
class Config:
    """Configuration for desktop stereoscopic client."""
    
    # Capture settings
    capture_width: int = 2560
    capture_height: int = 1440
    capture_fps: int = 60
    capture_monitor: int = 0
    
    # Virtual display settings
    virtual_display_width: int = 5120
    virtual_display_height: int = 1440
    virtual_display_fps: int = 60
    
    # Depth estimation settings
    depth_model_variant: Optional[str] = None  # None = auto-detect
    depth_auto_detect: bool = True
    depth_width: int = 2560
    depth_height: int = 1440
    
    # SBS generation settings
    parallax_strength: float = 2.0  # Increased from 1.0 for testing visibility
    convergence: float = 0.5
    
    # Performance settings
    performance_target_fps: float = 60.0
    performance_fallback_threshold: float = 45.0  # FPS threshold for auto-fallback
    
    # Sunshine integration
    sunshine_output_name: Optional[str] = None  # Virtual display identifier
    sunshine_resolution: str = "5120x1440"
    
    # Logging
    log_level: str = "INFO"
    log_file: Optional[str] = None
    
    @classmethod
    def load(cls, config_path: Optional[Path] = None) -> 'Config':
        """
        Load configuration from YAML file.
        
        Args:
            config_path: Path to config file (default: config.yaml in current directory)
            
        Returns:
            Config instance
        """
        if config_path is None:
            config_path = Path("config.yaml")
        
        if not config_path.exists():
            logger.info(f"Config file not found at {config_path}, using defaults")
            return cls()
        
        try:
            with open(config_path, 'r') as f:
                data = yaml.safe_load(f)
            
            if data is None:
                return cls()
            
            # Convert dict to Config
            return cls(**data)
        except Exception as e:
            logger.error(f"Failed to load config from {config_path}: {e}")
            return cls()
    
    def save(self, config_path: Optional[Path] = None):
        """
        Save configuration to YAML file.
        
        Args:
            config_path: Path to save config (default: config.yaml in current directory)
        """
        if config_path is None:
            config_path = Path("config.yaml")
        
        try:
            data = asdict(self)
            # Remove None values for cleaner YAML
            data = {k: v for k, v in data.items() if v is not None}
            
            with open(config_path, 'w') as f:
                yaml.dump(data, f, default_flow_style=False, sort_keys=False)
            
            logger.info(f"Saved configuration to {config_path}")
        except Exception as e:
            logger.error(f"Failed to save config to {config_path}: {e}")
    
    def get_depth_model_variant(self) -> Optional[DA3ModelVariant]:
        """
        Get depth model variant enum.
        
        Returns:
            DA3ModelVariant or None if auto-detect
        """
        if self.depth_model_variant is None:
            return None
        
        try:
            return DA3ModelVariant[self.depth_model_variant.upper()]
        except KeyError:
            logger.warning(f"Unknown model variant: {self.depth_model_variant}")
            return None
    
    def set_depth_model_variant(self, variant: Optional[DA3ModelVariant]):
        """
        Set depth model variant.
        
        Args:
            variant: DA3ModelVariant or None for auto-detect
        """
        if variant is None:
            self.depth_model_variant = None
        else:
            self.depth_model_variant = variant.value
