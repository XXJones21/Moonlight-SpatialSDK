"""Bounded live renderer status/reset/expiry probe; restores neutral input."""
import ctypes
from ctypes import wintypes
import json
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time

sys.dont_write_bytecode = True
repo = Path(sys.argv[1]).resolve()
out = Path(__file__).resolve().parent
if len(sys.argv) < 4:
    raise SystemExit('Usage: probe_renderer.py <repo> <actual-game-pid> <new-run-name>')
game_pid = int(sys.argv[2])
run_name = sys.argv[3]
if not run_name.replace('-', '').replace('_', '').isalnum():
    raise SystemExit('Run name must contain only letters, numbers, hyphens or underscores')
out = out / run_name
out.mkdir(exist_ok=False)
sys.path.insert(0, str(repo / 'tools/visionos-portal'))
from send_pose import State, encode_state, encode_reset, parse_status

user32 = ctypes.windll.user32
user32.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
user32.FindWindowW.restype = wintypes.HWND
user32.IsWindowVisible.argtypes = [wintypes.HWND]
user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
kernel32 = ctypes.windll.kernel32
kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel32.OpenProcess.restype = wintypes.HANDLE
kernel32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
game_handle = kernel32.OpenProcess(0x00100000, False, game_pid) # SYNCHRONIZE only
if not game_handle:
    raise SystemExit('Cannot monitor the selected game process')
def game_alive():
    return kernel32.WaitForSingleObject(game_handle, 0) == 258 # WAIT_TIMEOUT
if not game_alive():
    raise SystemExit('Selected game has already exited')
def window():
    handle = user32.FindWindowW('UEVRPortalSBS', 'UEVR Portal SBS')
    rect = wintypes.RECT()
    if handle: user32.GetWindowRect(handle, ctypes.byref(rect))
    return {'exists': bool(handle), 'visible': bool(handle and user32.IsWindowVisible(handle)),
            'rect': [rect.left, rect.top, rect.right, rect.bottom]}

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as check:
    check.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    check.bind(('127.0.0.1', 4243))
session = secrets.randbits(64) or 1
records = []
sequence = 0
epoch = 1
phases = []
failure = None
with (out / 'host.txt').open('w') as hostlog, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    host = subprocess.Popen([str(repo / 'PortalHost/build/Release/portal_host.exe'), '--peer', '127.0.0.1', '--trace-poses'],
        stdout=hostlog, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    sock.connect(('127.0.0.1', 4243)); sock.setblocking(False)
    def phase(name, seconds, send=True, reset=False, neutral=False, monitor=True):
        global sequence, epoch
        start = time.perf_counter_ns(); next_reset = 0.; next_window = 0.
        phase_record = {'name':name, 'startNs':start, 'endNs':start}
        phases.append(phase_record)
        while (elapsed := (time.perf_counter_ns()-start)/1e9) < seconds:
            phase_record['endNs'] = time.perf_counter_ns()
            if monitor and not game_alive():
                raise RuntimeError('Game exited; remaining renderer test phases aborted')
            stamp = time.perf_counter_ns()
            if reset and epoch == 1 and elapsed >= next_reset:
                sock.send(encode_reset(session, 1, 2)); next_reset = elapsed + .1
                records.append({'kind':'resetSent', 'phase':name, 'ns':stamp})
            if send:
                sequence += 1
                head = (0.,0.,0. if neutral else 2.,0.,0.,0.,1.)
                portal = (0.,0.,-2. if neutral else 0.,0.,0.,0.,1.)
                sock.send(encode_state(State(session=session, epoch=epoch, sequence=sequence,
                    sample_ns=stamp, target_ns=stamp, head=head, portal=portal)))
            for _ in range(64):
                try: data = sock.recv(2048)
                except (BlockingIOError, ConnectionResetError): break
                status = parse_status(data, session)
                records.append({'kind':'status', 'phase':name, 'ns':time.perf_counter_ns(), 'status':status,
                    'raw':None if status else data.decode('utf-8',errors='replace')})
                if status and reset and status['trackingEpoch']=='2': epoch=2
            if elapsed >= next_window:
                records.append({'kind':'window', 'phase':name, 'ns':time.perf_counter_ns(), **window()})
                next_window = elapsed + .05
            time.sleep(1/90)
        phase_record['endNs'] = time.perf_counter_ns()
    try:
        time.sleep(.3)
        assert host.poll() is None
        phase('static',4)
        phase('reset',4,reset=True)
        phase('stale',1,send=False)
        phase('recovery',3)
    except Exception as error:
        failure = str(error)
    finally:
        if host.poll() is None:
            phase('neutral',.8,neutral=True,monitor=False)
            phase('final-stale',.8,send=False,monitor=False)
            host.terminate(); host.wait(timeout=3)
        with (out/'observations.jsonl').open('w') as log:
            for record in records: log.write(json.dumps(record)+'\n')
        (out/'phases.json').write_text(json.dumps(phases,indent=2))

statuses = [r for r in records if r['kind']=='status' and r['status']]
result = {'session':str(session), 'gamePid':game_pid, 'failure':failure,
          'gameAliveAtEnd':game_alive(), 'hostTerminatedAfterNeutral':True, 'senderEpoch':epoch, 'phases':{}}
kernel32.CloseHandle(game_handle)
for p in phases:
    name=p['name']; ss=[r['status'] for r in statuses if r['phase']==name]
    ww=[r for r in records if r['kind']=='window' and r['phase']==name and r['ns']>p['startNs']+350_000_000]
    result['phases'][name]={'statuses':len(ss), 'errors':sorted(set(s['errorCode'] for s in ss)),
        'modes':sorted(set(s['outputMode'] for s in ss)), 'epochs':sorted(set(s['trackingEpoch'] for s in ss)),
        'healthyStatuses':sum(s['trackingValid'] and s['errorCode']=='none' for s in ss),
        'maxRenderFrameID':max([int(s['renderFrameID']) for s in ss] or [0]),
        'maxAcceptedSequence':max([int(s['acceptedSequence']) for s in ss] or [0]),
        'visibleWindowSamplesAfter350ms':sum(r['visible'] for r in ww),'windowSamplesAfter350ms':len(ww),
        'rectangles':sorted(set(tuple(r['rect']) for r in ww if r['visible']))}
result['passed'] = failure is None and result['gameAliveAtEnd'] and all(result['phases'][p]['healthyStatuses']>0 for p in ('static','reset','recovery')) and any(
    r['phase']=='reset' and r['status']['trackingEpoch']=='2' and r['status']['trackingValid'] for r in statuses) and all(
    'staleTracking' in result['phases'][p]['errors'] and result['phases'][p]['visibleWindowSamplesAfter350ms']==0 for p in ('stale','final-stale'))
(out/'results.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
if not result['passed']: sys.exit(2)
