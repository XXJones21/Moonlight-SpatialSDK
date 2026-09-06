#!/usr/bin/env python3
"""Restore pinned binary dependencies; does not build or run the application."""
from pathlib import Path
import hashlib, json, shutil, subprocess

ROOT = Path(__file__).resolve().parents[2]
SHA = 'fb349830ac980ab73dbd653b5b9c813c3b249198'
donor = ROOT / 'External/moonlight-ios-vision'
destination = ROOT / 'Moonlight-6Dof-Vision/Dependencies'
def git(*args):
    return subprocess.check_output(['git','-c',f'safe.directory={donor.as_posix()}',*args], text=True).strip()
if not (donor / '.git').exists():
    donor.parent.mkdir(parents=True,exist_ok=True)
    git('clone','--no-checkout','https://github.com/RikuKunMS2/moonlight-ios-vision.git',str(donor))
    git('-C',str(donor),'checkout','--detach',SHA)
if git('-C',str(donor),'rev-parse','HEAD') != SHA:
    raise SystemExit('Donor checkout differs from the pinned version. Preserve its work and restore a separate pinned checkout.')
if git('-C',str(donor),'status','--porcelain','--untracked-files=no'):
    raise SystemExit('Donor has tracked modifications; restore the pinned binary sources before bootstrap.')
destination.mkdir(parents=True,exist_ok=True)
shutil.copytree(donor/'OpenSSL.xcframework',destination/'OpenSSL.xcframework',dirs_exist_ok=True)
shutil.copytree(donor/'OpenSSL.xcframework/xros-arm64/OpenSSL.framework/Headers',destination/'Headers/openssl',dirs_exist_ok=True)
shutil.copytree(donor/'libs/opus/include',destination/'opus/include',dirs_exist_ok=True)
for platform in ['visionOS','visionOS-Sim']:
    shutil.copytree(donor/f'libs/opus/lib/{platform}',destination/f'opus/{platform}',dirs_exist_ok=True)
files={str(p.relative_to(destination)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in destination.rglob('*') if p.is_file() and p.name!='restored-manifest.json'}
(destination/'restored-manifest.json').write_text(json.dumps({'sourceCommit':SHA,'files':files},indent=2))
print(f'Restored {len(files)} dependency files from {SHA}; no build performed.')
