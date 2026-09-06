"""Preserve the closed game's profile, then merge the tested baseline settings."""
import datetime
import argparse
import hashlib
import json
from pathlib import Path
import shutil

repo=Path('D:/Tools/Moonlight-SpatialSDK')
parser=argparse.ArgumentParser()
parser.add_argument('--revision',choices=('d700ced','4e519e6','67d9785'),default='d700ced')
args=parser.parse_args()
package=repo/f'External/local-validation/UEVR-portal-{args.revision}'
profile=Path('C:/Users/josh2/AppData/Roaming/UnrealVRMod/HogwartsLegacy')
evidence=Path(__file__).parent
manifest=json.loads((package/'manifest.json').read_text())
for entry in manifest['files']:
    assert hashlib.sha256((package/entry['file']).read_bytes()).hexdigest()==entry['sha256'], entry['file']
assert manifest['sourceRevision'].encode() in (package/'UEVRBackend.dll').read_bytes()
config=profile/'config.txt'
assert config.is_file()
backup=repo/'External/local-validation'/datetime.datetime.now().strftime(f'pre-{args.revision}-%Y%m%d-%H%M%S')
assert not backup.exists()
backup.mkdir()
records=[]
for source in profile.rglob('*'):
    if not source.is_file():
        continue
    assert not source.is_symlink(), f'Unexpected profile symlink: {source}'
    relative=source.relative_to(profile)
    target=backup/relative
    target.parent.mkdir(parents=True,exist_ok=True)
    digest=hashlib.sha256(source.read_bytes()).hexdigest()
    shutil.copy2(source,target)
    assert hashlib.sha256(target.read_bytes()).hexdigest()==digest
    records.append({'file':relative.as_posix(),'sha256':digest})
settings=dict(line.split('=',1) for line in (package/'staged-config.txt').read_text().splitlines() if '=' in line)
existing=config.read_text(encoding='utf-8-sig').splitlines()
merged=[]
seen=set()
for line in existing:
    key=line.split('=',1)[0]
    if key in settings:
        if key not in seen:
            merged.append(f'{key}={settings[key]}')
        seen.add(key)
    else:
        merged.append(line)
merged.extend(f'{key}={value}' for key,value in settings.items() if key not in seen)
config.write_text('\n'.join(merged)+'\n',encoding='utf-8')
actual=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line)
assert all(actual.get(key)==value for key,value in settings.items())
suffix='' if args.revision=='d700ced' else f'-{args.revision}'
shutil.copy2(config,evidence/f'config-before-injection{suffix}.txt')
result={'timestamp':datetime.datetime.now().astimezone().isoformat(),'backupPath':str(backup),
        'backupFiles':records,'profilePath':str(profile),'appliedKeys':settings,
        'package':str(package),'sourceRevision':manifest['sourceRevision'],'packageFilesVerified':len(manifest['files'])}
(evidence/f'profile-transition{suffix}.json').write_text(json.dumps(result,indent=2))
print(json.dumps({'backup':str(backup),'filesPreserved':len(records),'settingsVerified':len(settings),'packageFilesVerified':len(manifest['files'])}))
