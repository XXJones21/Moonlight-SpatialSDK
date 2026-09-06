#!/usr/bin/env python3
"""Export the committed fork delta as bytes; never redirect a diff through PowerShell text encoding."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CHECKOUT = ROOT / "External/UEVR-6DOF-Window"
BASE = "fb31341e860b15e116a15123820c95f044ff0a0f"
DEST = ROOT / "Documentation/visionOS-6DOF/patches"

def git(*args):
    return subprocess.run(["git", "-c", f"safe.directory={CHECKOUT.as_posix()}", "-C", str(CHECKOUT), *args], check=True, stdout=subprocess.PIPE).stdout

def main():
    if git("status", "--porcelain").strip():
        raise SystemExit("Commit fork source changes before exporting")
    head = git("rev-parse", "HEAD").decode().strip()
    tree = git("rev-parse", "HEAD^{tree}").decode().strip()
    data = git("diff", "--binary", "--full-index", "--no-ext-diff", BASE, head, "--", ".")
    DEST.mkdir(parents=True, exist_ok=True)
    (DEST / "UEVR-portal.patch").write_bytes(data)
    manifest = {"repository": "https://github.com/elliotttate/UEVR-6DOF-Window", "baseCommit": BASE,
                "authoredCommit": head, "authoredTree": tree, "patch": "UEVR-portal.patch",
                "patchSHA256": hashlib.sha256(data).hexdigest(), "validation": "source authored; build, tests and runtime deferred",
                "portalCore": "Phase1 0b7f02d plus Phase2 FrameMetadata.hpp; normalized SHA256 pins in cmake/PortalCore.cmake"}
    (DEST / "UEVR-portal.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))

if __name__ == "__main__":
    main()
