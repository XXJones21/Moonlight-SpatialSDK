"""Live SteamVR diagnostic. One pattern per invocation, then neutral restoration.

Uses built PortalHost and the shipped sender for static/sweep/roll. The additional
axis holds cover forward/back and pitch absent from the shipped patterns.
Only run with user ready, portal output off, and the tracking firewall active.
"""
import bisect
import csv
import json
import math
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time

sys.dont_write_bytecode = True
repo = Path(sys.argv[1]).resolve()
pattern = sys.argv[2]
assert pattern in ('static', 'sweep', 'roll', 'axes')
out = Path(__file__).resolve().parent
sys.path.insert(0, str(repo / 'tools/visionos-portal'))
from send_pose import State, encode_state

duration = 12 if pattern == 'axes' else 10
creation = {'creationflags': subprocess.CREATE_NO_WINDOW}
events = []
def hold(sock, seconds, head, label):
    session = secrets.randbits(64) or 1
    start = time.perf_counter_ns()
    sequence = 0
    while (time.perf_counter_ns() - start) / 1e9 < seconds:
        sequence += 1
        stamp = time.perf_counter_ns()
        sock.sendto(encode_state(State(session=session, sequence=sequence,
            sample_ns=stamp, target_ns=stamp, head=head,
            portal=(0., 0., -2., 0., 0., 0., 1.))), ('127.0.0.1', 4243))
        time.sleep(1 / 90)
    events.append({'label': label, 'startNs': start, 'endNs': time.perf_counter_ns(), 'head': head})

for port in (4243, 4244):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as check:
        check.bind(('127.0.0.1', port))

host = probe = sender = None
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock, \
        (out / f'{pattern}-host.txt').open('w') as hostlog, \
        (out / f'{pattern}-runtime.csv').open('w') as runtime, \
        (out / f'{pattern}-probe-errors.txt').open('w') as errors:
    try:
        probe = subprocess.Popen([str(repo / 'External/local-validation/sample_runtime.exe'), str(duration + 7)],
            stdout=runtime, stderr=errors, **creation)
        host = subprocess.Popen([str(repo / 'PortalHost/build/Release/portal_host.exe'),
            '--peer', '127.0.0.1', '--trace-poses'], stdout=hostlog, stderr=subprocess.STDOUT, **creation)
        time.sleep(.7)
        assert host.poll() is None and probe.poll() is None, 'host/probe failed to start'
        if pattern == 'axes':
            for axis in range(6):
                for sign in (1, -1):
                    head = [0., 0., 2., 0., 0., 0., 1.]
                    if axis < 3:
                        head[axis] += sign * .1
                    else:
                        head[axis] = math.sin(sign * .15 / 2)
                        head[6] = math.cos(.15 / 2)
                    hold(sock, 1., tuple(head), f'{("x", "y", "z", "pitch", "yaw", "roll")[axis]}{sign:+}')
        else:
            args = [sys.executable, str(repo / 'tools/visionos-portal/send_pose.py'),
                '--pattern', pattern, '--duration', str(duration), '--csv', str(out / f'{pattern}-sent.csv')]
            if pattern == 'roll': args += ['--reset-at', '3']
            sender = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, **creation)
            stdout, stderr = sender.communicate(timeout=duration + 5)
            assert sender.returncode == 0, stderr
            (out / f'{pattern}-sender.txt').write_text(stdout + stderr)
        # Observe stale-input behavior before returning the HMD to its zero-input pose.
        time.sleep(.7)
    finally:
        if sender and sender.poll() is None:
            sender.terminate(); sender.wait(timeout=3)
        if host and host.poll() is None:
            hold(sock, .7, (0., 0., 0., 0., 0., 0., 1.), 'restore-neutral')
            host.terminate(); host.wait(timeout=3)
        if probe:
            try: probe.wait(timeout=8)
            except subprocess.TimeoutExpired:
                probe.terminate(); probe.wait(timeout=3)
        (out / f'{pattern}-events.json').write_text(json.dumps(events, indent=2))

assert probe.returncode == 0, 'runtime sampler failed'
rows = list(csv.DictReader((out / f'{pattern}-runtime.csv').open()))
assert rows and all(r['connected'] == '1' and r['valid'] == '1' and r['result'] == '200' for r in rows)

def matrix(head):
    x, y, z, w = head[3:]
    return [1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w),
        2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w),
        2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]

targets = []
if pattern != 'axes':
    for sent in csv.DictReader((out / f'{pattern}-sent.csv').open()):
        elapsed = float(sent['elapsedSeconds']); angle = .5 * math.sin(elapsed)
        head = (0., 0., 2., 0., 0., 0., 1.)
        if pattern == 'sweep': head = (.4*math.sin(elapsed), .15*math.sin(elapsed*.7), 2., 0., math.sin(angle/2), 0., math.cos(angle/2))
        if pattern == 'roll': head = (0., 0., 2., 0., 0., math.sin(angle/2), math.cos(angle/2))
        targets.append((int(sent['sampleTimeNs']), head))
times = [t[0] for t in targets]
comparisons = []
for r in rows:
    stamp = int(r['sampleNs'])
    label = pattern
    if pattern == 'axes':
        event = next((e for e in events if e['label'] != 'restore-neutral' and e['startNs'] + 350_000_000 < stamp < e['endNs']), None)
        if not event: continue
        head = event['head']; label = event['label']
    else:
        if stamp < times[0] + 350_000_000 or stamp > times[-1]: continue
        head = targets[max(0, bisect.bisect_right(times, stamp) - 1)][1]
    # Measured stationary baseline: raw +1 m Y; standing +2 m Y.
    base_y = 1. if r['origin'] == '2' else 2.
    pos = [float(r[k]) for k in ('x', 'y', 'z')]
    pos_error = max(abs(a-b) for a,b in zip(pos, (head[0], head[1]+base_y, head[2])))
    rot = [float(r[f'm{i}{j}']) for i in range(3) for j in range(3)]
    rot_error = max(abs(a-b) for a,b in zip(rot, matrix(head)))
    comparisons.append((label, r['origin'], pos_error, rot_error))
assert comparisons, 'no aligned runtime observations'
steady = pattern in ('static', 'axes')
pos_limit, rot_limit = (.005, .01) if steady else (.06, .08)
groups = []
for label, origin in sorted(set((r[0],r[1]) for r in comparisons)):
    selected = [r for r in comparisons if r[0] == label and r[1] == origin]
    pe = sorted(r[2] for r in selected); re = sorted(r[3] for r in selected)
    groups.append({'label':label, 'origin':origin, 'samples':len(selected),
        'maxPositionErrorM':max(pe), 'p95PositionErrorM':pe[int(.95*(len(pe)-1))],
        'maxRotationMatrixError':max(re), 'p95RotationMatrixError':re[int(.95*(len(re)-1))]})
neutral = []
restore = events[-1]
for origin in ('2', '1'):
    selected = [r for r in rows if r['origin'] == origin and int(r['sampleNs']) > restore['endNs']+200_000_000]
    assert selected, 'no neutral restoration observations'
    expected = (0., 1. if origin == '2' else 2., 0.)
    err = max(max(abs(float(r[k])-v) for k,v in zip(('x','y','z'),expected)) for r in selected)
    neutral.append({'origin':origin,'samples':len(selected),'maxPositionErrorM':err})
result = {'pattern':pattern,'passed':all(g['p95PositionErrorM']<pos_limit and g['p95RotationMatrixError']<rot_limit for g in groups)
    and all(n['maxPositionErrorM']<.005 for n in neutral),
    'runtimeSamples':len(rows),'allPosesConnectedValidRunningOK':True,'groups':groups,
    'comparison':'contemporaneous preceding sent pose, no fitted latency; steady axes exclude first 350 ms',
    'positionToleranceM':pos_limit,'rotationMatrixTolerance':rot_limit,'neutralRestoration':neutral,
    'portalOutputEnabled':False,'hostShutdown':'terminated by harness after neutral pose',
    'rendererStatusValidated':False}
(out / f'{pattern}-results.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
if not result['passed']: sys.exit(2)
