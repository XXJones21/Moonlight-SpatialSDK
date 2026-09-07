"""Inspect game windows; optionally bring only its Unreal desktop window to DISPLAY1."""
import argparse
import ctypes as c
from ctypes import wintypes as w
import json

p = argparse.ArgumentParser()
p.add_argument('--pid', type=int, required=True)
p.add_argument('--move', action='store_true')
a = p.parse_args()
u = c.windll.user32
u.SetProcessDpiAwarenessContext.argtypes = [w.HANDLE]
u.SetProcessDpiAwarenessContext(w.HANDLE(-4))
callback = c.WINFUNCTYPE(w.BOOL, w.HWND, w.LPARAM)
u.EnumWindows.argtypes = [callback, w.LPARAM]
u.GetWindowThreadProcessId.argtypes = [w.HWND, c.POINTER(w.DWORD)]
u.GetWindowTextW.argtypes = [w.HWND, w.LPWSTR, c.c_int]
u.GetClassNameW.argtypes = [w.HWND, w.LPWSTR, c.c_int]
u.GetWindowRect.argtypes = [w.HWND, c.POINTER(w.RECT)]
u.IsWindowVisible.argtypes = [w.HWND]
u.ShowWindow.argtypes = [w.HWND, c.c_int]
u.SetWindowPos.argtypes = [w.HWND, w.HWND, c.c_int, c.c_int, c.c_int, c.c_int, w.UINT]
u.SetForegroundWindow.argtypes = [w.HWND]
windows = []
@callback
def collect(hwnd, _):
    pid = w.DWORD()
    u.GetWindowThreadProcessId(hwnd, c.byref(pid))
    if pid.value != a.pid:
        return True
    title, cls, rect = c.create_unicode_buffer(512), c.create_unicode_buffer(256), w.RECT()
    u.GetWindowTextW(hwnd, title, 512)
    u.GetClassNameW(hwnd, cls, 256)
    u.GetWindowRect(hwnd, c.byref(rect))
    windows.append(dict(hwnd=hwnd, pid=pid.value, title=title.value, cls=cls.value,
                        visible=bool(u.IsWindowVisible(hwnd)), rect=[rect.left,rect.top,rect.right,rect.bottom]))
    return True
u.EnumWindows(collect, 0)
print(json.dumps({'before': windows}, indent=2))
if a.move:
    candidates = [x for x in windows if x['cls'] == 'UnrealWindow' and x['visible']]
    if len(candidates) != 1:
        raise SystemExit('Expected exactly one visible Unreal game window; no changes made')
    target = candidates[0]['hwnd']
    u.ShowWindow(target, 9)  # Restore before positioning, including a maximized game.
    # SWP_NOZORDER | SWP_NOACTIVATE; portal output HWND is deliberately excluded.
    if not u.SetWindowPos(target, None, 80, 80, 1600, 1000, 0x14):
        raise SystemExit('SetWindowPos failed')
    foreground = bool(u.SetForegroundWindow(target))
    rect = w.RECT()
    u.GetWindowRect(target, c.byref(rect))
    print(json.dumps({'movedGameWindow': target, 'rect':[rect.left,rect.top,rect.right,rect.bottom],
                      'foregroundActivated':foreground}))
