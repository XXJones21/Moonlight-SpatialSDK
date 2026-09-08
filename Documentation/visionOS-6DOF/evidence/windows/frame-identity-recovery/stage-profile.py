"""Back up the closed game's profile and stage the Native Stereo Fix reference."""
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

repo = Path(__file__).resolve().parents[5]
package = repo / 'External/local-validation/UEVR-portal-c34eaa2'
profile = Path('C:/Users/josh2/AppData/Roaming/UnrealVRMod/HogwartsLegacy')

def require_closed():
    result = subprocess.run([
        'powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
        "@(Get-Process -Name HogwartsLegacy,UEVRInjector -ErrorAction SilentlyContinue) | ForEach-Object { $_.Id }"
    ], check=True, capture_output=True, text=True)
    if result.stdout.strip():
        raise RuntimeError('Game or injector is running; leave the profile untouched')

require_closed()
manifest = json.loads((package / 'manifest.json').read_text())
assert manifest['sourceRevision'] == 'c34eaa24894a240a97b7eaefbc092c8303cd37a1'
for entry in manifest['files']:
    assert hashlib.sha256((package / entry['file']).read_bytes()).hexdigest() == entry['sha256'], entry['file']
assert manifest['sourceRevision'].encode() in (package / 'UEVRBackend.dll').read_bytes()

backup = repo / 'External/local-validation' / datetime.datetime.now().strftime('pre-native-fix-c34eaa2-%Y%m%d-%H%M%S')
backup.mkdir(exist_ok=False)
records = []
for source in profile.rglob('*'):
    if not source.is_file():
        continue
    assert not source.is_symlink(), f'Unexpected profile symlink: {source}'
    relative = source.relative_to(profile)
    target = backup / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    shutil.copy2(source, target)
    assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
    records.append({'file': relative.as_posix(), 'sha256': digest})

settings = {'Frontend_RequestedRuntime': 'openvr_api.dll', 'VR_RenderingMethod': '0', 'VR_NativeStereoFix': 'true', 'WindowMode_Enabled': 'false', 'WindowMode_PortalOutput': 'false', 'WindowMode_PortalDiagnostics': 'false', 'WindowMode_PortalDesktopX': '2560.000000', 'WindowMode_PortalDesktopY': '0.000000', 'WindowMode_PortalEyeWidth': '1280.000000', 'WindowMode_PortalEyeHeight': '720.000000'}
config = profile / 'config.txt'
before = config.read_bytes()
assert before == (backup / 'config.txt').read_bytes(), 'Profile changed during backup'
lines = before.decode('utf-8-sig').splitlines()
previous = dict(line.split('=', 1) for line in lines if '=' in line)
merged, seen = [], set()
for line in lines:
    key = line.split('=', 1)[0]
    if key in settings:
        if key not in seen:
            merged.append(f'{key}={settings[key]}')
        seen.add(key)
    else:
        merged.append(line)
merged.extend(f'{key}={value}' for key, value in settings.items() if key not in seen)
require_closed()
assert config.read_bytes() == before, 'Profile changed before write'
changes = {key: {'before': previous.get(key), 'after': value} for key, value in settings.items() if previous.get(key) != value}
if changes:
    config.write_text('\n'.join(merged) + '\n', encoding='utf-8')
actual = dict(line.split('=', 1) for line in config.read_text(encoding='utf-8-sig').splitlines() if '=' in line)
assert all(actual.get(key) == value for key, value in settings.items())
result = {
    'timestamp': datetime.datetime.now().astimezone().isoformat(),
    'sourceRevision': manifest['sourceRevision'], 'package': str(package),
    'backupPath': str(backup), 'backupFiles': records, 'appliedSettings': settings,
    'changes': changes, 'packageFilesVerified': len(manifest['files']),
    'injected': False,
}
Path(__file__).with_name('profile-transition.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({key: result[key] for key in ('backupPath', 'changes', 'packageFilesVerified', 'injected')}, indent=2))
