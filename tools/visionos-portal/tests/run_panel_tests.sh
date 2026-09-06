#!/bin/bash
set -euo pipefail
portal_repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
portal_test_build="$(mktemp -d "${TMPDIR:-/tmp}/moonlight-panel-tests.XXXXXX")"
trap 'rm -rf "$portal_test_build"' EXIT
portal_sdk="$(xcrun --sdk macosx --show-sdk-path)"
xcrun --sdk macosx swiftc -parse-as-library -sdk "$portal_sdk" \
    -target "$(uname -m)-apple-macosx15.0" -module-cache-path "$portal_test_build/modules" \
    "$portal_repo_root/Moonlight-6Dof-Vision/Moonlight-6Dof-Vision/Portal/Panels/"*.swift \
    "$portal_repo_root/tools/visionos-portal/tests/swift/PanelEntityTests.swift" \
    -o "$portal_test_build/panels"
"$portal_test_build/panels"
