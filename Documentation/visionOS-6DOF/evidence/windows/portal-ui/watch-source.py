"""Capture three active SBS desktop frames; read-only, no pose/input traffic."""
import ctypes as c
from ctypes import wintypes as w
import datetime
import json
from pathlib import Path
import struct
import time
import zlib
from PIL import ImageGrab

repo = Path(__file__).resolve().parents[5]
out = repo / 'External/local-validation/portal-ui-live' / datetime.datetime.now().strftime('source-watch-%H%M%S')
out.mkdir(parents=True)
log = Path('C:/Users/josh2/AppData/Roaming/UnrealVRMod/HogwartsLegacy/log.txt')
u = c.windll.user32
u.SetProcessDpiAwarenessContext.argtypes = [w.HANDLE]
u.SetProcessDpiAwarenessContext(w.HANDLE(-4))
u.FindWindowW.argtypes = [w.LPCWSTR, w.LPCWSTR]
u.FindWindowW.restype = w.HWND
u.IsWindowVisible.argtypes = [w.HWND]
u.GetWindowRect.argtypes = [w.HWND, c.POINTER(w.RECT)]

def metadata(image):
    image = image.convert('RGB')
    width, height = image.size
    eye_width, tags = width // 2, []
    cell = eye_width // 176
    for eye in range(2):
        raw = bytearray(44)
        for bit in range(352):
            x = eye * eye_width + (bit % 176) * cell + cell // 2
            y = height - 16 + (bit // 176) * 8 + 4
            if image.getpixel((x, y))[1] > 127:
                raw[bit // 8] |= 1 << (7 - bit % 8)
        tags.append(bytes(raw))
    raw = tags[0]
    return dict(eyeTagsEqual=raw == tags[1], magic=raw[:4].decode('ascii', errors='replace'),
                version=raw[4], flags=raw[5], crcValid=zlib.crc32(raw[:40]) == struct.unpack_from('<I', raw, 40)[0],
                session=str(struct.unpack_from('<Q', raw, 8)[0]), epoch=struct.unpack_from('<Q', raw, 16)[0],
                geometry=struct.unpack_from('<Q', raw, 24)[0], frame=struct.unpack_from('<Q', raw, 32)[0])

deadline = time.monotonic() + 180
captures = []
while time.monotonic() < deadline and len(captures) < 3:
    hwnd = u.FindWindowW('UEVRPortalSBS', 'UEVR Portal SBS')
    rect = w.RECT()
    if hwnd and u.IsWindowVisible(hwnd) and u.GetWindowRect(hwnd, c.byref(rect)):
        bounds = [rect.left, rect.top, rect.right, rect.bottom]
        # Capture only the known dedicated display, never an arbitrary desktop.
        if bounds == [2560, 0, 5120, 736]:
            with log.open('rb') as f:
                f.seek(max(0, log.stat().st_size - 12000))
                lines = f.read().decode(errors='replace').splitlines()
            outputs = [line for line in lines if '[Portal] output frame=' in line]
            latest = outputs[-1] if outputs else ''
            if 'status=none detail=none' in latest:
                logged_at = datetime.datetime.strptime(latest[1:24], '%Y-%m-%d %H:%M:%S.%f')
                if 0 <= (datetime.datetime.now() - logged_at).total_seconds() < 2:
                    image = ImageGrab.grab(bbox=tuple(bounds), all_screens=True)
                    path = out / f'source-{len(captures)}.png'
                    image.save(path)
                    captures.append(dict(time=datetime.datetime.now().astimezone().isoformat(),
                                         image=str(path), latestOutput=latest, metadata=metadata(image)))
                    (out / 'captures.json').write_text(json.dumps(captures, indent=2) + '\n')
                    time.sleep(2)
    time.sleep(.25)
(out / 'complete.json').write_text(json.dumps(dict(captureCount=len(captures),
    timedOut=len(captures) < 3, path=str(out)), indent=2) + '\n')
print(json.dumps(dict(captureCount=len(captures), path=str(out))))
