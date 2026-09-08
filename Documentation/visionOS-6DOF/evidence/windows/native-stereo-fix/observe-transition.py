"""Passively sample the known SBS source and log during a user-operated transition."""
import ast
import argparse
import ctypes as c
from ctypes import wintypes as w
import datetime
import json
from pathlib import Path
import struct
import time
import zlib
from PIL import ImageGrab

parser = argparse.ArgumentParser()
parser.add_argument('--samples', type=int, choices=range(1, 61), default=40)
args = parser.parse_args()

repo = Path(__file__).resolve().parents[5]
out = repo / 'External/local-validation/native-stereo-fix-transition-source'
out.mkdir(exist_ok=True)
log = Path('C:/Users/josh2/AppData/Roaming/UnrealVRMod/HogwartsLegacy/log.txt')
# Reuse the existing wire decoder without running that module's observer loop.
tree = ast.parse((Path(__file__).parent.parent / 'portal-ui/watch-source.py').read_text())
decoder = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'metadata')
exec(compile(ast.Module(body=[decoder], type_ignores=[]), '<existing metadata decoder>', 'exec'))
u = c.windll.user32
u.SetProcessDpiAwarenessContext.argtypes = [w.HANDLE]
u.SetProcessDpiAwarenessContext(w.HANDLE(-4))
u.FindWindowW.argtypes = [w.LPCWSTR, w.LPCWSTR]; u.FindWindowW.restype = w.HWND
u.IsWindowVisible.argtypes = [w.HWND]
u.GetWindowRect.argtypes = [w.HWND, c.POINTER(w.RECT)]
samples = []
for index in range(args.samples):
    sample = {'time': datetime.datetime.now().astimezone().isoformat()}
    hwnd = u.FindWindowW('UEVRPortalSBS', 'UEVR Portal SBS')
    sample['visible'] = bool(hwnd and u.IsWindowVisible(hwnd))
    rect = w.RECT()
    if hwnd and u.GetWindowRect(hwnd, c.byref(rect)):
        bounds = [rect.left, rect.top, rect.right, rect.bottom]
        sample['bounds'] = bounds
        if sample['visible'] and bounds == [2560, 0, 5120, 736]:
            image = ImageGrab.grab(bbox=tuple(bounds), all_screens=True)
            sample['metadata'] = metadata(image)
            if index % 10 == 0:
                sample['image'] = f'source-{index}.png'; image.save(out / sample['image'])
    with log.open('rb') as stream:
        stream.seek(max(0, log.stat().st_size - 12000))
        outputs = [line for line in stream.read().decode(errors='replace').splitlines() if '[Portal] output frame=' in line]
        sample['output'] = outputs[-1] if outputs else None
    samples.append(sample)
    (out / 'samples.json').write_text(json.dumps(samples, indent=2))
    time.sleep(1)
print(json.dumps({'samples': len(samples), 'visible': sum(x['visible'] for x in samples), 'path': str(out)}))
