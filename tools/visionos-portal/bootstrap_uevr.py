#!/usr/bin/env python3
"""Apply the recorded binary-safe portal source patch to the pinned UEVR checkout.
No builds, generators, runtime launch, submodule installation, or remote push.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PINS = ROOT / "Documentation/visionOS-6DOF/patches/UEVR-portal.json"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=Path, default=ROOT / "External/UEVR-6DOF-Window")
    parser.add_argument("--clone", action="store_true", help="Explicitly clone public UEVR source if absent (no submodules)")
    args = parser.parse_args()
    pin = json.loads(PINS.read_text(encoding="utf-8"))
    checkout = args.checkout.resolve()
    if not checkout.exists():
        if not args.clone:
            parser.error("Checkout absent; explicitly use --clone for source-only network access")
        subprocess.run(["git", "clone", "--no-checkout", pin["repository"], str(checkout)], check=True)
        subprocess.run(["git", "-c", f"safe.directory={checkout.as_posix()}", "-C", str(checkout), "checkout", "--detach", pin["baseCommit"]], check=True)
    def git(*arguments, capture=True):
        return subprocess.run(["git", "-c", f"safe.directory={checkout.as_posix()}", "-C", str(checkout), *arguments], check=True, capture_output=capture, text=capture)
    head = git("rev-parse", "HEAD").stdout.strip()
    if head == pin["authoredCommit"]:
        print(f"Already at authored source commit {head}; no build/runtime validation implied.")
        return
    if head != pin["baseCommit"]:
        parser.error("Refusing to modify a checkout whose HEAD differs from the pinned base")
    if git("status", "--porcelain").stdout.strip():
        parser.error("Refusing to apply over local changes")
    patch = PINS.parent / pin["patch"]
    if hashlib.sha256(patch.read_bytes()).hexdigest() != pin["patchSHA256"]:
        parser.error("Patch SHA256 does not match manifest")
    git("apply", "--check", str(patch), capture=False)
    git("apply", "--index", str(patch), capture=False)
    tree = git("write-tree").stdout.strip()
    if tree != pin["authoredTree"]:
        raise RuntimeError("Applied tree differs from authored tree; leave checkout for inspection")
    print(f"Authored source tree applied and staged: {tree}. No build/test/run performed.")
    print(f"For later CMake setup use -DPORTAL_CORE_SOURCE_DIR={ROOT / 'PortalCore'}")

if __name__ == "__main__":
    main()
