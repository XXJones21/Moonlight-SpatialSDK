@echo off
cd /d "%~dp0"

echo Desktop Stereoscopic Client
echo ============================
echo.

REM Check Python 3.13
py -3.13 --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python 3.13 not found
    pause
    exit /b 1
)

echo Using Python 3.13...
echo.

REM Install requirements if needed
echo Checking dependencies...
py -3.13 -c "import torch, colorlog, cv2, numpy, yaml, win32api, OpenGL" >nul 2>&1
if errorlevel 1 (
    echo Installing requirements...
    py -3.13 -m pip install -r requirements.txt
    echo.
)

REM Install DA3 if directory exists
if exist "Depth-Anything-3" (
    echo Checking DA3...
    py -3.13 -c "from depth_anything_3.api import DepthAnything3" >nul 2>&1
    if errorlevel 1 (
        echo Installing DA3...
        cd Depth-Anything-3
        py -3.13 -m pip install xformers "torch>=2" torchvision
        py -3.13 -m pip install "numpy>=1.24.0,<2.0"
        py -3.13 -m pip install pre-commit trimesh einops huggingface_hub imageio opencv-python fastapi uvicorn requests typer pillow omegaconf evo e3nn moviepy==1.0.3 plyfile pillow_heif safetensors pycolmap addict matplotlib
        py -3.13 -m pip install --no-deps -e .
        cd ..
        echo.
    )
)

REM Note: Triton is not available for Windows via pip (Linux-only)
REM The xformers warning about Triton is harmless - it will use fallback optimizations

REM Run the application
echo Starting application...
echo.
py -3.13 -u main.py 2>&1
set EXIT_CODE=%ERRORLEVEL%

echo.
if %EXIT_CODE% NEQ 0 (
    echo ========================================
    echo ERROR: Application exited with code %EXIT_CODE%
    echo ========================================
    echo.
    echo Check the error messages above for details.
    echo.
) else (
    echo Application exited normally.
    echo.
)

echo Window will stay open for monitoring.
echo Press Ctrl+C to close.
echo.
:loop
timeout /t 1 >nul
goto :loop
