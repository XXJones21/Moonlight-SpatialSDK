"""Read-only process/window observer. Does not send poses or control the game."""
import ctypes
from ctypes import wintypes
import datetime
import json
from pathlib import Path
import sys
import time

game_pid=int(sys.argv[1])
duration=300
kernel=ctypes.windll.kernel32
user=ctypes.windll.user32
kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD]
kernel.OpenProcess.restype=wintypes.HANDLE
kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD]
kernel.CloseHandle.argtypes=[wintypes.HANDLE]
user.FindWindowW.argtypes=[wintypes.LPCWSTR,wintypes.LPCWSTR]
user.FindWindowW.restype=wintypes.HWND
user.IsWindowVisible.argtypes=[wintypes.HWND]
user.GetWindowRect.argtypes=[wintypes.HWND,ctypes.POINTER(wintypes.RECT)]
user.GetWindowThreadProcessId.argtypes=[wintypes.HWND,ctypes.POINTER(wintypes.DWORD)]
handle=kernel.OpenProcess(0x00100000,False,game_pid)
if not handle:
    raise SystemExit('Cannot open game process for synchronization-only observation')
out=Path(__file__).parent/f'live-output-{game_pid}.jsonl'
start=time.monotonic()
previous=None
last_written=0
try:
    with out.open('x',buffering=1) as log:
        while time.monotonic()-start<duration:
            state={'gamePid':game_pid,'gameAlive':kernel.WaitForSingleObject(handle,0)==258}
            window=user.FindWindowW('UEVRPortalSBS','UEVR Portal SBS')
            rect=wintypes.RECT()
            owner=wintypes.DWORD()
            if window:
                user.GetWindowRect(window,ctypes.byref(rect))
                user.GetWindowThreadProcessId(window,ctypes.byref(owner))
            state.update(windowExists=bool(window),windowVisible=bool(window and user.IsWindowVisible(window)),
                         windowOwner=owner.value,rect=[rect.left,rect.top,rect.right,rect.bottom])
            now=time.monotonic()
            if state!=previous or now-last_written>=5:
                log.write(json.dumps({'time':datetime.datetime.now().astimezone().isoformat(),**state})+'\n')
                previous=state
                last_written=now
            if not state['gameAlive']:
                break
            time.sleep(.25)
        log.write(json.dumps({'time':datetime.datetime.now().astimezone().isoformat(),'observerFinished':True})+'\n')
finally:
    kernel.CloseHandle(handle)
