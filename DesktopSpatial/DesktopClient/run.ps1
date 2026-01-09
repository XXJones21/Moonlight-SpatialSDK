# Desktop Stereoscopic Client Launcher (PowerShell)
# Ensures Python 3.13 is used (required for Depth Anything 3)

Write-Host "Desktop Stereoscopic Client" -ForegroundColor Cyan
Write-Host "============================" -ForegroundColor Cyan
Write-Host ""

# Check if Python 3.13 is available
try {
    $pythonVersion = py -3.13 --version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Python 3.13 not found"
    }
    Write-Host "Using Python 3.13..." -ForegroundColor Green
    Write-Host ""
} catch {
    Write-Host "ERROR: Python 3.13 is not available." -ForegroundColor Red
    Write-Host "Please install Python 3.13 or ensure it's accessible via 'py -3.13'" -ForegroundColor Yellow
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

# Get the directory where this script is located
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# Check if requirements are installed
Write-Host "Checking Python dependencies..." -ForegroundColor Cyan
try {
    # Check multiple key packages to ensure requirements are installed
    $null = py -3.13 -c "import torch; import colorlog; import cv2; import numpy; import yaml; import win32api; import OpenGL" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dependencies not found"
    }
    Write-Host "Dependencies check passed." -ForegroundColor Green
    Write-Host ""
} catch {
    Write-Host ""
    Write-Host "Dependencies not found or incomplete. Installing requirements..." -ForegroundColor Yellow
    Write-Host ""
    
    py -3.13 -m pip install -r requirements.txt
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "ERROR: Failed to install requirements." -ForegroundColor Red
        Write-Host "Please install manually with: py -3.13 -m pip install -r requirements.txt" -ForegroundColor Yellow
        Write-Host ""
        Read-Host "Press Enter to exit"
        exit 1
    }
    
    Write-Host ""
    Write-Host "Requirements installed successfully." -ForegroundColor Green
    Write-Host ""
}

# Check and install DA3 if directory exists
if (Test-Path "Depth-Anything-3") {
    Write-Host "Checking DA3 installation..." -ForegroundColor Cyan
    $null = py -3.13 -c "from depth_anything_3.api import DepthAnything3" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "DA3 not installed. Installing DA3 dependencies..." -ForegroundColor Yellow
        Push-Location "Depth-Anything-3"
        py -3.13 -m pip install xformers "torch>=2" torchvision
        py -3.13 -m pip install "numpy>=1.24.0,<2.0"
        if (Test-Path "requirements.txt") {
            Write-Host "Installing DA3 requirements (open3d may fail on Windows, this is OK)..." -ForegroundColor Yellow
            py -3.13 -m pip install -r requirements.txt
            py -3.13 -m pip install "moviepy==1.0.3"
        }
        Write-Host "Installing DA3 package..." -ForegroundColor Yellow
        py -3.13 -m pip install -e .
        Pop-Location
        $null = py -3.13 -c "from depth_anything_3.api import DepthAnything3" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "WARNING: DA3 installation may be incomplete. Application will use placeholder model." -ForegroundColor Yellow
        } else {
            Write-Host "DA3 installation verified." -ForegroundColor Green
        }
        Write-Host ""
    }
}

# Run main.py with Python 3.13
py -3.13 main.py
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Application exited with an error." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Host ""
Write-Host "Application exited successfully." -ForegroundColor Green
Read-Host "Press Enter to exit"
exit 0
