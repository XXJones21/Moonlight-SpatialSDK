"""
Virtual display rendering module.

Renders SBS frames to virtual display at 5120x1440 using OpenGL.
"""

import logging
import numpy as np
import torch
from typing import Optional
import time
import sys

logger = logging.getLogger(__name__)

# Windows-specific imports
try:
    import win32gui
    import win32con
    import win32api
    import pywintypes
    import ctypes
    from ctypes import wintypes
    from OpenGL.GL import *
    from OpenGL.GLU import *
    WINDOWS_AVAILABLE = True
except ImportError:
    WINDOWS_AVAILABLE = False
    logger.warning("Windows/OpenGL libraries not available. Virtual display rendering will not work.")


class VirtualDisplayRenderer:
    """
    Renders frames to virtual display using DirectX or OpenGL.
    
    Maintains 60 FPS rendering loop and handles virtual display initialization.
    """
    
    def __init__(
        self,
        width: int = 5120,
        height: int = 1440,
        target_fps: int = 60
    ):
        """
        Initialize virtual display renderer.
        
        Args:
            width: Virtual display width (5120 for SBS)
            height: Virtual display height (1440)
            target_fps: Target frame rate (default: 60)
        """
        self.width = width
        self.height = height
        self.target_fps = target_fps
        self.frame_time = 1.0 / target_fps
        
        self.is_initialized = False
        self.is_rendering = False
        
        # Performance tracking
        self.frame_count = 0
        self.last_frame_time = 0
        self.fps = 0.0
        self.frame_times = []
        self.max_time_samples = 60
        
        # OpenGL/Window resources
        self.hwnd = None
        self.hdc = None
        self.hglrc = None
        self.texture_id = None
        
        logger.info(f"Virtual display renderer initialized: {width}x{height} @ {target_fps} FPS")
    
    def initialize(self) -> bool:
        """
        Initialize virtual display rendering with OpenGL window.
        
        Returns:
            True if initialization successful, False otherwise
        """
        if not WINDOWS_AVAILABLE:
            logger.error("Windows/OpenGL libraries not available")
            return False
        
        try:
            # Find virtual display (look for display with target resolution)
            display_info = self._find_virtual_display()
            if display_info is None:
                logger.warning("Virtual display not found, creating window on primary display")
                display_x, display_y = 0, 0
                display_id = None
            else:
                display_x, display_y = display_info['x'], display_info['y']
                display_id = display_info.get('device_id')
                logger.info(f"Found virtual display at ({display_x}, {display_y})")
            
            # Log critical information for Sunshine configuration
            if display_id:
                logger.info("=" * 80)
                logger.info("SUNSHINE CONFIGURATION REQUIRED:")
                logger.info(f"  Virtual Display ID: {display_id}")
                logger.info(f"  Resolution: {self.width}x{self.height}")
                logger.info("  To configure Sunshine:")
                logger.info(f"    1. Edit sunshine.conf")
                logger.info(f"    2. Set: output_name = {display_id}")
                logger.info("    3. Restart Sunshine")
                logger.info("=" * 80)
            else:
                logger.warning("Could not determine virtual display ID. Use dxgi-info.exe to find it.")
            
            # Create fullscreen window on virtual display
            self.hwnd = self._create_window(display_x, display_y)
            if self.hwnd is None:
                logger.error("Failed to create window")
                return False
            
            # Initialize OpenGL context
            if not self._init_opengl():
                logger.error("Failed to initialize OpenGL")
                return False
            
            # Create texture for frame rendering
            self.texture_id = glGenTextures(1)
            if self.texture_id is None or self.texture_id == 0:
                logger.error("glGenTextures failed")
                return False
            
            glBindTexture(GL_TEXTURE_2D, self.texture_id)
            error = glGetError()
            if error != GL_NO_ERROR:
                logger.error(f"glBindTexture failed during initialization: {error}")
                return False
            
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            
            error = glGetError()
            if error != GL_NO_ERROR:
                logger.error(f"glTexParameteri failed: {error}")
                return False
            
            # Render initial test pattern and swap buffers to ensure window has visible content for DXGI capture
            glClearColor(0.0, 0.0, 0.0, 1.0)
            glClear(GL_COLOR_BUFFER_BIT)
            
            gdi32 = ctypes.windll.gdi32
            SwapBuffers = gdi32.SwapBuffers
            SwapBuffers.argtypes = [wintypes.HDC]
            SwapBuffers.restype = ctypes.c_bool
            
            if not SwapBuffers(self.hdc):
                logger.error("Initial SwapBuffers failed - window may not be ready for capture")
                return False
            
            # Verify window is actually visible and on correct display
            try:
                window_rect = win32gui.GetWindowRect(self.hwnd)
                window_x = window_rect[0]
                window_y = window_rect[1]
                window_width = window_rect[2] - window_rect[0]
                window_height = window_rect[3] - window_rect[1]
                
                logger.info(f"Window position verified: ({window_x}, {window_y}), size: {window_width}x{window_height}")
                
                if display_id and (window_x != display_x or window_y != display_y):
                    logger.warning(f"Window position mismatch: expected ({display_x}, {display_y}), got ({window_x}, {window_y})")
            except Exception as e:
                logger.warning(f"Could not verify window position: {e}")
            
            self.is_initialized = True
            logger.info("Virtual display renderer initialized successfully")
            return True
            
        except Exception as e:
            logger.error(f"Failed to initialize virtual display renderer: {e}", exc_info=True)
            return False
    
    def _find_virtual_display(self) -> Optional[dict]:
        """Find virtual display by checking for target resolution."""
        try:
            import win32api
            
            logger.info(f"Searching for virtual display with resolution {self.width}x{self.height}")
            
            # Method 1: Use EnumDisplayMonitors (finds active monitors)
            try:
                monitors = win32api.EnumDisplayMonitors()
                logger.info(f"Found {len(monitors)} monitor(s) via EnumDisplayMonitors")
                
                for i, monitor in enumerate(monitors):
                    monitor_info = win32api.GetMonitorInfo(monitor[0])
                    width = monitor_info['Monitor'][2] - monitor_info['Monitor'][0]
                    height = monitor_info['Monitor'][3] - monitor_info['Monitor'][1]
                    
                    logger.info(f"Monitor {i}: {width}x{height} at ({monitor_info['Monitor'][0]}, {monitor_info['Monitor'][1]})")
                    
                    # Check if this matches our target resolution
                    if width == self.width and height == self.height:
                        logger.info(f"Found virtual display via EnumDisplayMonitors: {width}x{height} at ({monitor_info['Monitor'][0]}, {monitor_info['Monitor'][1]})")
                        return {
                            'x': monitor_info['Monitor'][0],
                            'y': monitor_info['Monitor'][1],
                            'width': width,
                            'height': height
                        }
            except Exception as e:
                logger.warning(f"EnumDisplayMonitors failed: {e}")
            
            # Method 2: Use EnumDisplayDevices to find by device name pattern
            try:
                device_num = 0
                while True:
                    try:
                        display_device = win32api.EnumDisplayDevices(None, device_num)
                        device_name = display_device.DeviceName
                        device_string = display_device.DeviceString
                        
                        # Check if this looks like a virtual display (DISPLAY15, etc.)
                        if 'DISPLAY' in device_name.upper():
                            # Try to get monitor info for this device
                            try:
                                # Get device mode to check resolution
                                import win32con
                                devmode = win32api.EnumDisplaySettings(device_name, win32con.ENUM_CURRENT_SETTINGS)
                                width = devmode.PelsWidth
                                height = devmode.PelsHeight
                                
                                logger.info(f"Display device {device_name}: {width}x{height} ({device_string})")
                                
                                if width == self.width and height == self.height:
                                    # Found the virtual display device - try to get its position
                                    # First try to find in EnumDisplayMonitors
                                    monitors = win32api.EnumDisplayMonitors()
                                    for monitor in monitors:
                                        monitor_info = win32api.GetMonitorInfo(monitor[0])
                                        m_width = monitor_info['Monitor'][2] - monitor_info['Monitor'][0]
                                        m_height = monitor_info['Monitor'][3] - monitor_info['Monitor'][1]
                                        if m_width == width and m_height == height:
                                            logger.info(f"Found virtual display via EnumDisplayDevices: {device_name} {width}x{height} at ({monitor_info['Monitor'][0]}, {monitor_info['Monitor'][1]})")
                                            return {
                                                'x': monitor_info['Monitor'][0],
                                                'y': monitor_info['Monitor'][1],
                                                'width': width,
                                                'height': height,
                                                'device_id': device_name
                                            }
                                    
                                    # If not in EnumDisplayMonitors, try to get position from device mode
                                    # Virtual displays might be positioned at (0, 0) or after other displays
                                    try:
                                        # Try to get all display settings to find position
                                        # For now, default to (0, 0) or calculate based on other displays
                                        max_x = 0
                                        monitors = win32api.EnumDisplayMonitors()
                                        for monitor in monitors:
                                            monitor_info = win32api.GetMonitorInfo(monitor[0])
                                            max_x = max(max_x, monitor_info['Monitor'][2])
                                        
                                        # Place virtual display after all other displays
                                        display_x = max_x
                                        display_y = 0
                                        
                                        logger.info(f"Found virtual display via EnumDisplayDevices: {device_name} {width}x{height} (estimated position: ({display_x}, {display_y}))")
                                        return {
                                            'x': display_x,
                                            'y': display_y,
                                            'width': width,
                                            'height': height,
                                            'device_id': device_name
                                        }
                                    except Exception as e3:
                                        logger.debug(f"Could not determine position for {device_name}: {e3}")
                                        # Default to (0, 0) if we can't determine position
                                        logger.info(f"Found virtual display via EnumDisplayDevices: {device_name} {width}x{height} (using default position (0, 0))")
                                        return {
                                            'x': 0,
                                            'y': 0,
                                            'width': width,
                                            'height': height,
                                            'device_id': device_name
                                        }
                            except Exception as e2:
                                logger.debug(f"Could not get settings for {device_name}: {e2}")
                        
                        device_num += 1
                    except win32api.error:
                        break
            except Exception as e:
                logger.warning(f"EnumDisplayDevices failed: {e}")
            
            logger.warning(f"Virtual display with resolution {self.width}x{self.height} not found via Windows APIs")
            logger.warning("CRITICAL: Virtual display not detected. Sunshine will NOT capture from our virtual display.")
            logger.warning("ACTION REQUIRED: Configure Sunshine to use virtual display:")
            logger.warning("  1. Run: dxgi-info.exe (from Sunshine directory) to list displays")
            logger.warning("  2. Find virtual display (5120x1440) - note the display identifier (e.g., \\\\.\\DISPLAY15)")
            logger.warning("  3. Edit sunshine.conf: Set output_name = <virtual_display_identifier>")
            logger.warning("  4. Restart Sunshine")
            logger.info("Note: Virtual display may exist but not be detected. Window will be created on primary display.")
            return None
        except Exception as e:
            logger.warning(f"Error finding virtual display: {e}", exc_info=True)
            return None
    
    def _create_window(self, x: int, y: int):
        """Create fullscreen window on specified display."""
        try:
            hInstance = win32api.GetModuleHandle(None)
            className = 'VirtualDisplayRenderer'
            
            # Register window class
            wndClass = win32gui.WNDCLASS()
            wndClass.style = win32con.CS_HREDRAW | win32con.CS_VREDRAW | win32con.CS_OWNDC
            wndClass.lpfnWndProc = lambda h, m, w, l: win32gui.DefWindowProc(h, m, w, l)
            wndClass.hInstance = hInstance
            wndClass.hCursor = win32gui.LoadCursor(0, win32con.IDC_ARROW)
            wndClass.hbrBackground = win32con.COLOR_WINDOW
            wndClass.lpszClassName = className
            
            try:
                win32gui.RegisterClass(wndClass)
            except Exception:
                pass  # Class may already be registered
            
            # Create fullscreen window at specified position
            # Use WS_POPUP for borderless fullscreen
            # Removed WS_EX_TOOLWINDOW - it prevents DXGI Desktop Duplication from capturing the window
            # Removed WS_EX_NOACTIVATE - may prevent DXGI from seeing window as "active" for capture
            # Using only WS_EX_TOPMOST to ensure window is visible for capture
            hwnd = win32gui.CreateWindowEx(
                win32con.WS_EX_TOPMOST,
                className,
                "Virtual Display Renderer",
                win32con.WS_POPUP | win32con.WS_VISIBLE,
                x, y,
                self.width, self.height,
                None, None, hInstance, None
            )
            
            if hwnd:
                # Force window to be visible and topmost for capture compatibility
                try:
                    # Show window maximized to ensure it's visible
                    win32gui.ShowWindow(hwnd, win32con.SW_SHOWMAXIMIZED)
                except Exception:
                    # Fallback to normal show if maximize fails
                    try:
                        win32gui.ShowWindow(hwnd, win32con.SW_SHOW)
                    except Exception:
                        pass
                
                # Make window topmost to ensure it's in foreground
                try:
                    win32gui.SetWindowPos(
                        hwnd,
                        win32con.HWND_TOPMOST,  # Make topmost
                        0, 0, 0, 0,
                        win32con.SWP_NOMOVE | win32con.SWP_NOSIZE | win32con.SWP_SHOWWINDOW
                    )
                except Exception:
                    pass
                
                # Ensure window is visible and ready for capture
                # Don't force to foreground (may interfere with DXGI capture)
                # Just ensure it's shown and positioned correctly
                try:
                    # Verify window is actually visible
                    if not win32gui.IsWindowVisible(hwnd):
                        logger.warning("Window created but not visible, attempting to show")
                        win32gui.ShowWindow(hwnd, win32con.SW_SHOW)
                    
                    # Small delay to let window become ready
                    time.sleep(0.2)
                except (Exception, pywintypes.error) as e:
                    logger.warning(f"Window visibility check failed: {e}")
                
                # Ensure window position is correct
                try:
                    win32gui.SetWindowPos(
                        hwnd,
                        win32con.HWND_TOPMOST,
                        x, y,
                        self.width, self.height,
                        win32con.SWP_SHOWWINDOW
                    )
                except Exception:
                    pass
                
                logger.info(f"Created fullscreen window {self.width}x{self.height} at ({x}, {y})")
                return hwnd
            else:
                logger.error("CreateWindowEx returned None")
                return None
        except Exception as e:
            logger.error(f"Error creating window: {e}", exc_info=True)
            return None
    
    def _init_opengl(self) -> bool:
        """Initialize OpenGL context."""
        try:
            import ctypes
            from ctypes import wintypes
            
            # Define HGLRC type (OpenGL rendering context handle)
            # HGLRC is not in wintypes, define it as a void pointer
            HGLRC = ctypes.c_void_p
            
            # Get device context
            self.hdc = win32gui.GetDC(self.hwnd)
            if not self.hdc:
                return False
            
            # Define PIXELFORMATDESCRIPTOR using ctypes
            class PIXELFORMATDESCRIPTOR(ctypes.Structure):
                _fields_ = [
                    ('nSize', ctypes.c_ushort),
                    ('nVersion', ctypes.c_ushort),
                    ('dwFlags', ctypes.c_uint),
                    ('iPixelType', ctypes.c_ubyte),
                    ('cColorBits', ctypes.c_ubyte),
                    ('cRedBits', ctypes.c_ubyte),
                    ('cRedShift', ctypes.c_ubyte),
                    ('cGreenBits', ctypes.c_ubyte),
                    ('cGreenShift', ctypes.c_ubyte),
                    ('cBlueBits', ctypes.c_ubyte),
                    ('cBlueShift', ctypes.c_ubyte),
                    ('cAlphaBits', ctypes.c_ubyte),
                    ('cAlphaShift', ctypes.c_ubyte),
                    ('cAccumBits', ctypes.c_ubyte),
                    ('cAccumRedBits', ctypes.c_ubyte),
                    ('cAccumGreenBits', ctypes.c_ubyte),
                    ('cAccumBlueBits', ctypes.c_ubyte),
                    ('cAccumAlphaBits', ctypes.c_ubyte),
                    ('cDepthBits', ctypes.c_ubyte),
                    ('cStencilBits', ctypes.c_ubyte),
                    ('cAuxBuffers', ctypes.c_ubyte),
                    ('iLayerType', ctypes.c_ubyte),
                    ('bReserved', ctypes.c_ubyte),
                    ('dwLayerMask', ctypes.c_uint),
                    ('dwVisibleMask', ctypes.c_uint),
                    ('dwDamageMask', ctypes.c_uint),
                ]
            
            # Initialize pixel format descriptor
            # PFD constants (not in win32con, use numeric values)
            PFD_DRAW_TO_WINDOW = 0x00000004
            PFD_SUPPORT_OPENGL = 0x00000020
            PFD_DOUBLEBUFFER = 0x00000001
            PFD_TYPE_RGBA = 0x00000000
            PFD_MAIN_PLANE = 0x00000000
            
            pfd = PIXELFORMATDESCRIPTOR()
            pfd.nSize = ctypes.sizeof(PIXELFORMATDESCRIPTOR)
            pfd.nVersion = 1
            pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER
            pfd.iPixelType = PFD_TYPE_RGBA
            pfd.cColorBits = 32
            pfd.cDepthBits = 24
            pfd.iLayerType = PFD_MAIN_PLANE
            
            # Get GDI functions from gdi32.dll (ChoosePixelFormat, SetPixelFormat)
            gdi32 = ctypes.windll.gdi32
            ChoosePixelFormat = gdi32.ChoosePixelFormat
            ChoosePixelFormat.argtypes = [wintypes.HDC, ctypes.POINTER(PIXELFORMATDESCRIPTOR)]
            ChoosePixelFormat.restype = ctypes.c_int
            
            SetPixelFormat = gdi32.SetPixelFormat
            SetPixelFormat.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.POINTER(PIXELFORMATDESCRIPTOR)]
            SetPixelFormat.restype = ctypes.c_bool
            
            # Get OpenGL functions from opengl32.dll (wgl functions)
            opengl32 = ctypes.windll.opengl32
            wglCreateContext = opengl32.wglCreateContext
            wglCreateContext.argtypes = [wintypes.HDC]
            wglCreateContext.restype = HGLRC
            
            wglMakeCurrent = opengl32.wglMakeCurrent
            wglMakeCurrent.argtypes = [wintypes.HDC, HGLRC]
            wglMakeCurrent.restype = ctypes.c_bool
            
            # Choose and set pixel format
            pixel_format = ChoosePixelFormat(self.hdc, ctypes.byref(pfd))
            if pixel_format == 0:
                logger.error("ChoosePixelFormat failed")
                return False
            
            if not SetPixelFormat(self.hdc, pixel_format, ctypes.byref(pfd)):
                logger.error("SetPixelFormat failed")
                return False
            
            # Create OpenGL context
            self.hglrc = wglCreateContext(self.hdc)
            if not self.hglrc:
                logger.error("wglCreateContext failed")
                return False
            
            if not wglMakeCurrent(self.hdc, self.hglrc):
                logger.error("wglMakeCurrent failed")
                return False
            
            # Set up OpenGL state
            glEnable(GL_TEXTURE_2D)
            glClearColor(0.0, 0.0, 0.0, 1.0)
            glViewport(0, 0, self.width, self.height)
            
            # Render test pattern immediately to ensure window has visible content for capture
            try:
                # Clear with a visible color (red) to ensure window has content
                glClearColor(1.0, 0.0, 0.0, 1.0)  # Red background
                glClear(GL_COLOR_BUFFER_BIT)
                
                # Get SwapBuffers function
                gdi32 = ctypes.windll.gdi32
                SwapBuffers = gdi32.SwapBuffers
                SwapBuffers.argtypes = [wintypes.HDC]
                SwapBuffers.restype = ctypes.c_bool
                
                # Swap buffers to make test pattern visible
                SwapBuffers(self.hdc)
                
                # Reset clear color to black for normal rendering
                glClearColor(0.0, 0.0, 0.0, 1.0)
                logger.debug("Test pattern rendered to ensure window visibility for capture")
            except Exception as e:
                logger.warning(f"Could not render test pattern: {e}")
            
            return True
        except Exception as e:
            logger.error(f"Error initializing OpenGL: {e}")
            return False
    
    def render_frame(self, frame: torch.Tensor) -> bool:
        """
        Render a single frame to virtual display.
        
        Args:
            frame: SBS frame as torch.Tensor (shape: [H, W, 3] or [3, H, W])
            
        Returns:
            True if rendered successfully, False otherwise
        """
        if not self.is_initialized:
            logger.error("Virtual display renderer not initialized")
            return False
        
        try:
            start_time = time.time()
            
            # Convert frame to numpy if needed
            if isinstance(frame, torch.Tensor):
                # Ensure frame is on CPU
                if frame.is_cuda:
                    frame = frame.cpu()
                
                # Normalize format to [H, W, 3]
                if frame.dim() == 4:
                    frame = frame.squeeze(0)
                if frame.dim() == 3 and frame.shape[0] == 3:
                    frame = frame.permute(1, 2, 0)
                
                # Convert to numpy and ensure uint8
                frame_np = frame.numpy()
                if frame_np.max() <= 1.0:
                    frame_np = (frame_np * 255).astype(np.uint8)
                else:
                    frame_np = frame_np.astype(np.uint8)
            else:
                frame_np = np.asarray(frame)
            
            # Verify dimensions
            if frame_np.shape[0] != self.height or frame_np.shape[1] != self.width:
                logger.warning(
                    f"Frame size mismatch: expected {self.width}x{self.height}, "
                    f"got {frame_np.shape[1]}x{frame_np.shape[0]}"
                )
                # Resize if needed
                import cv2
                frame_np = cv2.resize(frame_np, (self.width, self.height), interpolation=cv2.INTER_LINEAR)
            
            # Render to virtual display using OpenGL
            if not WINDOWS_AVAILABLE or not self.hdc or not self.hglrc:
                logger.warning("OpenGL context not available, skipping render")
                return False
            
            try:
                import ctypes
                from ctypes import wintypes
                
                # Define HGLRC type
                HGLRC = ctypes.c_void_p
                
                # Get wglMakeCurrent from opengl32.dll
                opengl32 = ctypes.windll.opengl32
                wglMakeCurrent = opengl32.wglMakeCurrent
                wglMakeCurrent.argtypes = [wintypes.HDC, HGLRC]
                wglMakeCurrent.restype = ctypes.c_bool
                
                if not wglMakeCurrent(self.hdc, self.hglrc):
                    logger.error("wglMakeCurrent failed - OpenGL context may be lost")
                    return False
                
                # Check for OpenGL errors before rendering
                error = glGetError()
                if error != GL_NO_ERROR:
                    logger.error(f"OpenGL error before rendering: {error}")
                    return False
                
                # Validate texture ID
                if self.texture_id is None:
                    logger.error("Texture ID is None")
                    return False
                
                # Upload frame to OpenGL texture
                glBindTexture(GL_TEXTURE_2D, self.texture_id)
                error = glGetError()
                if error != GL_NO_ERROR:
                    logger.error(f"glBindTexture failed: {error}")
                    return False
                
                # Ensure frame data is contiguous
                if not frame_np.flags['C_CONTIGUOUS']:
                    frame_np = np.ascontiguousarray(frame_np)
                
                glTexImage2D(
                    GL_TEXTURE_2D, 0, GL_RGB,
                    frame_np.shape[1], frame_np.shape[0], 0,
                    GL_RGB, GL_UNSIGNED_BYTE, frame_np
                )
                error = glGetError()
                if error != GL_NO_ERROR:
                    logger.error(f"glTexImage2D failed: {error} (frame shape: {frame_np.shape})")
                    return False
                
                # Render fullscreen quad with texture
                glClear(GL_COLOR_BUFFER_BIT)
                error = glGetError()
                if error != GL_NO_ERROR:
                    logger.error(f"glClear failed: {error}")
                    return False
                
                glMatrixMode(GL_PROJECTION)
                glLoadIdentity()
                glOrtho(0, self.width, 0, self.height, -1, 1)
                glMatrixMode(GL_MODELVIEW)
                glLoadIdentity()
                
                glBegin(GL_QUADS)
                glTexCoord2f(0, 1)
                glVertex2f(0, 0)
                glTexCoord2f(1, 1)
                glVertex2f(self.width, 0)
                glTexCoord2f(1, 0)
                glVertex2f(self.width, self.height)
                glTexCoord2f(0, 0)
                glVertex2f(0, self.height)
                glEnd()
                
                error = glGetError()
                if error != GL_NO_ERROR:
                    logger.error(f"OpenGL rendering failed: {error}")
                    return False
                
                # Swap buffers
                import ctypes
                from ctypes import wintypes
                gdi32 = ctypes.windll.gdi32
                SwapBuffers = gdi32.SwapBuffers
                SwapBuffers.argtypes = [wintypes.HDC]
                SwapBuffers.restype = ctypes.c_bool
                
                if not SwapBuffers(self.hdc):
                    logger.error("SwapBuffers failed")
                    return False
                
                # Final error check
                error = glGetError()
                if error != GL_NO_ERROR:
                    logger.warning(f"OpenGL error after SwapBuffers: {error}")
                
            except Exception as e:
                logger.error(f"Error rendering frame: {e}")
                return False
            
            # Update performance metrics
            render_time = time.time() - start_time
            self.frame_times.append(render_time)
            if len(self.frame_times) > self.max_time_samples:
                self.frame_times.pop(0)
            
            if len(self.frame_times) > 0:
                avg_time = sum(self.frame_times) / len(self.frame_times)
                self.fps = 1.0 / avg_time if avg_time > 0 else 0.0
            
            self.frame_count += 1
            
            # Frame pacing: wait if rendering too fast
            elapsed = time.time() - start_time
            if elapsed < self.frame_time:
                time.sleep(self.frame_time - elapsed)
            
            return True
            
        except Exception as e:
            logger.error(f"Error rendering frame: {e}", exc_info=True)
            return False
    
    def start(self) -> bool:
        """
        Start rendering loop.
        
        Returns:
            True if started successfully
        """
        if not self.is_initialized:
            if not self.initialize():
                return False
        
        self.is_rendering = True
        logger.info("Started virtual display rendering")
        return True
    
    def stop(self):
        """Stop rendering loop."""
        self.is_rendering = False
        logger.info("Stopped virtual display rendering")
    
    def get_fps(self) -> float:
        """
        Get current rendering FPS.
        
        Returns:
            Current FPS
        """
        return self.fps
    
    def get_frame_count(self) -> int:
        """
        Get total frames rendered.
        
        Returns:
            Frame count
        """
        return self.frame_count
    
    def cleanup(self):
        """Clean up resources."""
        self.stop()
        
        if WINDOWS_AVAILABLE:
            try:
                import ctypes
                from ctypes import wintypes
                
                if self.texture_id:
                    glDeleteTextures([self.texture_id])
                    self.texture_id = None
                
                if self.hglrc:
                    # Define HGLRC type
                    HGLRC = ctypes.c_void_p
                    
                    opengl32 = ctypes.windll.opengl32
                    wglMakeCurrent = opengl32.wglMakeCurrent
                    wglMakeCurrent.argtypes = [wintypes.HDC, HGLRC]
                    wglMakeCurrent.restype = ctypes.c_bool
                    wglMakeCurrent(None, None)
                    
                    wglDeleteContext = opengl32.wglDeleteContext
                    wglDeleteContext.argtypes = [HGLRC]
                    wglDeleteContext.restype = ctypes.c_bool
                    wglDeleteContext(self.hglrc)
                    self.hglrc = None
                
                if self.hdc:
                    win32gui.ReleaseDC(self.hwnd, self.hdc)
                    self.hdc = None
                
                if self.hwnd:
                    win32gui.DestroyWindow(self.hwnd)
                    self.hwnd = None
            except Exception as e:
                logger.warning(f"Error during cleanup: {e}")
        
        self.is_initialized = False
        logger.info("Cleaned up virtual display renderer")
