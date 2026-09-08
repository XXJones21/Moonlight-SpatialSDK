"""Keep the live SBS source above overlapping windows without taking input focus."""
import argparse
import ctypes as c
from ctypes import wintypes as w
import json

parser = argparse.ArgumentParser()
parser.add_argument('--pid', type=int, required=True)
args = parser.parse_args()
u = c.windll.user32
u.SetProcessDpiAwarenessContext.argtypes = [w.HANDLE]
u.SetProcessDpiAwarenessContext(w.HANDLE(-4))
u.FindWindowW.argtypes = [w.LPCWSTR, w.LPCWSTR]; u.FindWindowW.restype = w.HWND
u.GetWindowThreadProcessId.argtypes = [w.HWND, c.POINTER(w.DWORD)]
u.GetWindowRect.argtypes = [w.HWND, c.POINTER(w.RECT)]
u.GetForegroundWindow.restype = w.HWND
u.GetWindowLongPtrW.argtypes = [w.HWND, c.c_int]; u.GetWindowLongPtrW.restype = c.c_ssize_t
u.SetWindowPos.argtypes = [w.HWND, w.HWND, c.c_int, c.c_int, c.c_int, c.c_int, w.UINT]
hwnd = u.FindWindowW('UEVRPortalSBS', 'UEVR Portal SBS')
assert hwnd, 'Portal window absent; no change made'
pid, rect = w.DWORD(), w.RECT()
u.GetWindowThreadProcessId(hwnd, c.byref(pid))
assert pid.value == args.pid, 'Unexpected portal process; no change made'
assert u.GetWindowRect(hwnd, c.byref(rect))
bounds = [rect.left, rect.top, rect.right, rect.bottom]
assert bounds == [2560, 0, 5120, 736], 'Unexpected capture bounds; no change made'
before = u.GetForegroundWindow()
style = u.GetWindowLongPtrW(hwnd, -20)
assert style & 0x08000000, 'Expected nonactivating portal window'
# HWND_TOPMOST; NOSIZE | NOMOVE | NOACTIVATE. Do not show a hidden/invalid source.
assert u.SetWindowPos(hwnd, w.HWND(-1), 0, 0, 0, 0, 0x13), 'SetWindowPos failed'
after = u.GetForegroundWindow()
result = dict(hwnd=hwnd, pid=pid.value, bounds=bounds,
              topmost=bool(u.GetWindowLongPtrW(hwnd, -20) & 8),
              foregroundBefore=before, foregroundAfter=after, focusPreserved=before == after)
print(json.dumps(result, indent=2))
assert result['topmost'] and result['focusPreserved']
