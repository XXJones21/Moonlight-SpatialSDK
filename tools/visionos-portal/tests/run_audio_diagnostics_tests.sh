#!/bin/bash
set -euo pipefail
portal_repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
portal_test_build="$(mktemp -d "${TMPDIR:-/tmp}/moonlight-audio-tests.XXXXXX")"
trap 'rm -rf "$portal_test_build"' EXIT
portal_app="$portal_repo_root/Moonlight-6Dof-Vision/Moonlight-6Dof-Vision"
portal_tests="$portal_repo_root/tools/visionos-portal/tests"
xcrun --sdk macosx clang -fobjc-arc -framework AVFoundation -framework Foundation \
    -I "$portal_app/Streaming" "$portal_tests/AudioFormatTests.m" -o "$portal_test_build/audio"
"$portal_test_build/audio"
xcrun --sdk macosx clang -fobjc-arc -framework Foundation -framework MetricKit \
    -I "$portal_app/Diagnostics" "$portal_tests/DiagnosticsTests.m" \
    "$portal_app/Diagnostics/PortalDiagnostics.m" -o "$portal_test_build/diagnostics"
"$portal_test_build/diagnostics"
