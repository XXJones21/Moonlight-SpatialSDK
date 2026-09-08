"""Record offline qualification and prepare (but do not apply) the live profile transition."""
import hashlib
import json
from pathlib import Path

here = Path(__file__).resolve().parent
repo = here.parents[4]
revision = '4007df45c6017eccc3354df603b0a97043fda7ae'
package = repo / 'External/local-validation/UEVR-portal-4007df4'
manifest = json.loads((package / 'manifest.json').read_text())
assert manifest['sourceRevision'] == revision
for entry in manifest['files']:
    assert hashlib.sha256((package / entry['file']).read_bytes()).hexdigest() == entry['sha256']
assert revision.encode() in (package / 'UEVRBackend.dll').read_bytes()
(here / 'package-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')

settings = {
    'Frontend_RequestedRuntime': 'openvr_api.dll',
    'VR_RenderingMethod': '0',
    'VR_NativeStereoFix': 'true',
    'WindowMode_Enabled': 'false',
    'WindowMode_PortalOutput': 'false',
    'WindowMode_PortalDiagnostics': 'false',
    'WindowMode_PortalDesktopX': '2560.000000',
    'WindowMode_PortalDesktopY': '0.000000',
    'WindowMode_PortalEyeWidth': '1280.000000',
    'WindowMode_PortalEyeHeight': '720.000000',
}
(package / 'staged-config.txt').write_text(''.join(f'{k}={v}\n' for k, v in settings.items()))
stage = (here.parent / 'portal-ui/stage-profile.py').read_text()
stage = stage.replace('stage the fixed portal UI baseline', 'stage the Native Stereo Fix reference')
stage = stage.replace('UEVR-portal-e0d3fa8', 'UEVR-portal-4007df4')
stage = stage.replace('e0d3fa877f4a9bcf8eb66a8ae7b6b1833a06e904', revision)
stage = stage.replace('pre-ui-e0d3fa8-', 'pre-native-fix-4007df4-')
start = stage.index('settings = dict(')
end = stage.index("config = profile / 'config.txt'", start)
stage = stage[:start] + 'settings = ' + repr(settings) + '\n' + stage[end:]
compile(stage, 'stage-profile.py', 'exec')
(here / 'stage-profile.py').write_text(stage)

logs = {}
for name in ('tests', 'ui-tests', 'cache-tests', 'regressions'):
    source = repo / f'External/local-validation/native-stereo-fix-{name}.log'
    data = source.read_bytes()
    # PowerShell redirection may emit UTF-16; normalize tracked evidence.
    decoded = data.decode('utf-16') if data.startswith((b'\xff\xfe', b'\xfe\xff')) else data.decode('utf-8-sig')
    (here / f'{name}.log').write_text(decoded, encoding='utf-8')
    logs[name] = hashlib.sha256((here / f'{name}.log').read_bytes()).hexdigest()
validation = {
    'date': '2026-09-08', 'sourceRevision': revision,
    'releaseBuild': 'passed; rebuilt after commit; embedded revision verified',
    'copyAndCombinedUI': '231 checks; 36 copy frames + 6 combined frames; D3D12 debug layer enabled',
    'pairStateMachine': '20 checks passed',
    'productionFrameCache': '50 checks passed',
    'existingUI': '24 WARP frames passed with D3D12 debug layer',
    'regressions': 'output lifetime including dual-source retention; 32 secondary Present; 72 projection cases passed',
    'review': 'independent review and follow-up found no remaining code blockers',
    'packageFilesVerified': len(manifest['files']), 'patchReverseCheck': 'passed',
    'liveQualification': 'pending; package not injected; profile not staged',
    'logsSHA256': logs,
}
(here / 'verification.json').write_text(json.dumps(validation, indent=2) + '\n')
patch_manifest = repo / 'Documentation/visionOS-6DOF/patches/UEVR-portal.json'
export = json.loads(patch_manifest.read_text())
assert export['authoredCommit'] == revision
assert hashlib.sha256(patch_manifest.with_suffix('.patch').read_bytes()).hexdigest() == export['patchSHA256']
export['validation'] = 'Release build, embedded revision, eight package hashes, offline WARP/cache/lifetime/projection tests, independent review and patch reverse-check passed; Native Stereo Fix portal live qualification pending'
export['validationEvidence'] = '../evidence/windows/native-stereo-fix/verification.json'
patch_manifest.write_text(json.dumps(export, indent=2) + '\n')
print(json.dumps(validation, indent=2))
