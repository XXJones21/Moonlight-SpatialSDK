#!/usr/bin/env python3
"""Copy recent Moonlight .ips reports using Xcode, without attaching a debugger."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True, help='Paired device identifier from xcrun devicectl list devices')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--limit', type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.limit <= 50:
        parser.error('--limit must be between 1 and 50')
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='moonlight-crash-index-') as tmp:
        index = Path(tmp) / 'index.json'
        subprocess.run(['xcrun', 'devicectl', 'device', 'info', 'files', '--device', args.device,
                        '--domain-type', 'systemCrashLogs', '--search', 'Moonlight-6Dof-Vision',
                        '--json-output', str(index), '--timeout', '30'], check=True)
        files = json.loads(index.read_text())['result']['files']
        reports = [f for f in files if Path(f['relativePath']).name.startswith('Moonlight-6Dof-Vision-')
                   and f['relativePath'].endswith('.ips') and not f['resources']['isDirectory']]
        reports.sort(key=lambda f: f['metadata']['lastModDate'], reverse=True)
        for report in reports[:args.limit]:
            destination = args.output / Path(report['relativePath']).name
            subprocess.run(['xcrun', 'devicectl', 'device', 'copy', 'from', '--device', args.device,
                            '--domain-type', 'systemCrashLogs', '--source', report['relativePath'],
                            '--destination', str(destination), '--timeout', '30'], check=True)
            print(destination)
        if not reports:
            print('No Moonlight crash reports are currently available on this device.')


if __name__ == '__main__':
    main()
