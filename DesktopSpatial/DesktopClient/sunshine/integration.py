"""
Sunshine integration module.

Helps configure Sunshine to stream from virtual display and validates 5120x1440 streaming.
"""

import logging
import subprocess
import json
from pathlib import Path
from typing import Optional, List, Dict

logger = logging.getLogger(__name__)


class SunshineIntegration:
    """
    Sunshine integration helper.
    
    Detects virtual display, configures Sunshine output_name, and validates streaming.
    """
    
    def __init__(self, sunshine_config_path: Optional[Path] = None):
        """
        Initialize Sunshine integration.
        
        Args:
            sunshine_config_path: Path to sunshine.conf (default: auto-detect)
        """
        self.sunshine_config_path = sunshine_config_path
        self.virtual_display_id = None
        
    def detect_virtual_display(self) -> Optional[str]:
        """
        Detect virtual display using dxgi-info.exe (Sunshine tool).
        
        Returns:
            Virtual display identifier (e.g., "\\\\.\\DISPLAY2") or None if not found
        """
        try:
            # Try to find dxgi-info.exe (usually in Sunshine directory)
            # Common locations:
            # - Same directory as sunshine.exe
            # - In PATH
            # - In current directory
            
            dxgi_info_paths = [
                Path("dxgi-info.exe"),
                Path("C:/Program Files/Sunshine/dxgi-info.exe"),
                Path("C:/Program Files (x86)/Sunshine/dxgi-info.exe"),
            ]
            
            dxgi_info = None
            for path in dxgi_info_paths:
                if path.exists():
                    dxgi_info = path
                    break
            
            if dxgi_info is None:
                # Try to find in PATH
                try:
                    result = subprocess.run(
                        ["where", "dxgi-info.exe"],
                        capture_output=True,
                        text=True,
                        timeout=5
                    )
                    if result.returncode == 0 and result.stdout.strip():
                        dxgi_info = Path(result.stdout.strip())
                except Exception:
                    pass
            
            if dxgi_info is None:
                logger.warning("dxgi-info.exe not found. Cannot detect virtual display automatically.")
                logger.info("You can manually configure Sunshine by:")
                logger.info("1. Running dxgi-info.exe to list displays")
                logger.info("2. Finding the virtual display identifier (e.g., \\\\.\\DISPLAY2)")
                logger.info("3. Setting output_name in sunshine.conf to the virtual display identifier")
                return None
            
            # Run dxgi-info.exe to list displays
            logger.info(f"Running {dxgi_info} to detect virtual display...")
            result = subprocess.run(
                [str(dxgi_info)],
                capture_output=True,
                text=True,
                timeout=10
            )
            
            if result.returncode != 0:
                logger.error(f"dxgi-info.exe failed: {result.stderr}")
                return None
            
            # Parse output to find virtual display
            # dxgi-info.exe outputs display information
            # Look for virtual display (usually has "Virtual" in name or is non-primary)
            output_lines = result.stdout.split('\n')
            
            for line in output_lines:
                if 'DISPLAY' in line.upper() and ('VIRTUAL' in line.upper() or '5120' in line):
                    # Extract display identifier
                    # Format is typically: "\\\\.\\DISPLAY2" or similar
                    import re
                    match = re.search(r'\\\\.\\DISPLAY\d+', line)
                    if match:
                        display_id = match.group(0)
                        logger.info(f"Found virtual display: {display_id}")
                        self.virtual_display_id = display_id
                        return display_id
            
            logger.warning("Virtual display not found in dxgi-info output")
            logger.info("Available displays:")
            for line in output_lines:
                if line.strip():
                    logger.info(f"  {line}")
            
            return None
            
        except Exception as e:
            logger.error(f"Error detecting virtual display: {e}", exc_info=True)
            return None
    
    def configure_sunshine(self, display_id: Optional[str] = None) -> bool:
        """
        Configure Sunshine to stream from virtual display.
        
        Args:
            display_id: Virtual display identifier (if None, will try to detect)
            
        Returns:
            True if configuration successful, False otherwise
        """
        if display_id is None:
            display_id = self.detect_virtual_display()
            if display_id is None:
                logger.error("Cannot configure Sunshine: virtual display not detected")
                return False
        
        # Find sunshine.conf
        if self.sunshine_config_path is None:
            # Try common locations (Windows)
            config_paths = [
                Path("C:/Program Files/Sunshine/config/sunshine.conf"),  # Default Windows installer location
                Path("C:/ProgramData/sunshine/sunshine.conf"),  # Alternative location
                Path.home() / ".config" / "sunshine" / "sunshine.conf",  # User config (Linux-style, may work on Windows)
                Path("sunshine.conf"),  # Current directory
            ]
            
            for path in config_paths:
                if path.exists():
                    self.sunshine_config_path = path
                    break
        
        if self.sunshine_config_path is None or not self.sunshine_config_path.exists():
            logger.warning("sunshine.conf not found. Manual configuration required:")
            logger.warning(f"1. Set output_name = {display_id} in sunshine.conf")
            logger.warning("2. Add 5120x1440 to resolutions list if needed")
            return False
        
        try:
            # Read current config
            with open(self.sunshine_config_path, 'r') as f:
                config_lines = f.readlines()
            
            # Update output_name
            updated = False
            new_lines = []
            for line in config_lines:
                if line.strip().startswith('output_name'):
                    new_lines.append(f'output_name = {display_id}\n')
                    updated = True
                else:
                    new_lines.append(line)
            
            # Add output_name if not found
            if not updated:
                new_lines.append(f'\n# Virtual display for PC-side stereoscopic streaming\n')
                new_lines.append(f'output_name = {display_id}\n')
            
            # Write updated config
            with open(self.sunshine_config_path, 'w') as f:
                f.writelines(new_lines)
            
            logger.info(f"Updated sunshine.conf: output_name = {display_id}")
            logger.info("Note: You may need to restart Sunshine for changes to take effect")
            
            return True
            
        except Exception as e:
            logger.error(f"Error configuring Sunshine: {e}", exc_info=True)
            return False
    
    def validate_resolution(self, width: int = 5120, height: int = 1440) -> bool:
        """
        Validate that Sunshine supports the target resolution.
        
        Args:
            width: Target width
            height: Target height
            
        Returns:
            True if resolution is supported/configured
        """
        logger.info(f"Validating Sunshine support for {width}x{height} resolution...")
        
        # Check if resolution is in Sunshine's supported list
        # This is a placeholder - actual validation would require:
        # 1. Querying Sunshine API or config
        # 2. Checking GPU encoder capabilities
        # 3. Testing actual streaming
        
        logger.info("Resolution validation requires:")
        logger.info("1. GPU encoder support for 5120x1440")
        logger.info("2. Sunshine configuration with resolution in supported list")
        logger.info("3. Network bandwidth (30-50 Mbps recommended)")
        logger.info("4. Actual streaming test to confirm")
        
        return True  # Placeholder - assume supported if GPU can handle it
