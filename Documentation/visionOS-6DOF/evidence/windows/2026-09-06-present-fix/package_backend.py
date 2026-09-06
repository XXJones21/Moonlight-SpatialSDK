"""Create a new reviewable local package; never overwrite an injected package."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

repo=Path(sys.argv[1]).resolve()
fork=repo/'External/UEVR-6DOF-Window'
git=['git','-c',f'safe.directory={fork.as_posix()}','-C',str(fork)]
assert not subprocess.check_output(git+['status','--porcelain']).strip(), 'Commit source before packaging'
revision=subprocess.check_output(git+['rev-parse','HEAD']).decode().strip()
previous=repo/'External/local-validation/UEVR-portal-bb7e3ef'
destination=repo/f'External/local-validation/UEVR-portal-{revision[:7]}'
assert not destination.exists(), f'Refuse to overwrite {destination}'
manifest=json.loads((previous/'manifest.json').read_text(encoding='utf-8-sig'))
backend=fork/'build/bin/uevr/UEVRBackend.dll'
assert revision.encode() in backend.read_bytes(), 'Backend does not embed committed revision'
destination.mkdir()
files=[]
for entry in manifest['files']:
    name=entry['file']
    source=backend.parent/name if name in ('UEVRBackend.dll','UEVRBackend.pdb') else previous/name
    target=destination/name
    source_hash=hashlib.sha256(source.read_bytes()).hexdigest()
    if name not in ('UEVRBackend.dll','UEVRBackend.pdb'):
        assert source_hash==entry['sha256'], f'Previous companion changed: {name}'
    shutil.copy2(source,target)
    assert hashlib.sha256(target.read_bytes()).hexdigest()==source_hash
    files.append({'file':name,'bytes':target.stat().st_size,'sha256':source_hash,'source':str(source),'role':entry['role']})
shutil.copy2(previous/'staged-config.txt',destination/'staged-config.txt')
result={'sourceRevision':revision,'configuration':'Release x64','backendEmbeddedRevisionVerified':True,
        'injected':False,'packagePath':str(destination),'files':files}
(destination/'manifest.json').write_text(json.dumps(result,indent=2))
Path(__file__).with_name(f'package-manifest-{revision[:7]}.json').write_text(json.dumps(result,indent=2))
print(json.dumps({'revision':revision,'package':str(destination),'verifiedFiles':len(files),'backendSHA256':next(f['sha256'] for f in files if f['file']=='UEVRBackend.dll')},indent=2))
