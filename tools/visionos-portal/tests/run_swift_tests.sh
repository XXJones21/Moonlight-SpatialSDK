#!/bin/bash
set -euo pipefail
portal_repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
portal_test_build="$(mktemp -d "${TMPDIR:-/tmp}/moonlight-swift-tests.XXXXXX")"
trap 'rm -rf "$portal_test_build"' EXIT
portal_sdk="$(xcrun --sdk macosx --show-sdk-path)"
portal_arch="$(uname -m)"
portal_app="$portal_repo_root/Moonlight-6Dof-Vision/Moonlight-6Dof-Vision"
portal_tests="$portal_repo_root/tools/visionos-portal/tests/swift"
compile_and_run() {
    local name="$1"
    shift
    xcrun --sdk macosx swiftc -sdk "$portal_sdk" -target "$portal_arch-apple-macosx15.0" \
        -module-cache-path "$portal_test_build/modules" "$@" "$portal_tests/$name.swift" -o "$portal_test_build/$name"
    "$portal_test_build/$name"
}
compile_and_run StreamPreferencesTests "$portal_app/Streaming/StreamPreferences.swift"
compile_and_run AnnexBTests "$portal_app/Streaming/VideoAnnexB.swift"
compile_and_run FrameGateTests "$portal_app/Portal/PortalFrameGate.swift" "$portal_app/Portal/PortalState.swift"
