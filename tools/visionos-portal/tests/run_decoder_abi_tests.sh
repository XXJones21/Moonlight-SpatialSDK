#!/bin/bash
set -euo pipefail
portal_repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
portal_test_build="$(mktemp -d "${TMPDIR:-/tmp}/moonlight-abi-tests.XXXXXX")"
trap 'rm -rf "$portal_test_build"' EXIT
portal_app="$portal_repo_root/Moonlight-6Dof-Vision/Moonlight-6Dof-Vision"
# Compile the decoder's actual module-level compatibility declarations with the
# native header. Native getter functions make any Swift shadowing observable.
python3 - "$portal_app/ThirdParty/Moonlight/Video/DrawableVideoDecoder.swift" "$portal_test_build/Constants.swift" <<'PY'
import pathlib, sys
source = pathlib.Path(sys.argv[1]).read_text()
pathlib.Path(sys.argv[2]).write_text(source.split('// MARK: - Constants Port', 1)[1])
PY
cat > "$portal_test_build/Native.h" <<'HEADER'
#include "Limelight.h"
int nativeSPS(void);
int nativePPS(void);
int nativeVPS(void);
HEADER
cat > "$portal_test_build/Native.c" <<'SOURCE'
#include "Native.h"
int nativeSPS(void) { return BUFFER_TYPE_SPS; }
int nativePPS(void) { return BUFFER_TYPE_PPS; }
int nativeVPS(void) { return BUFFER_TYPE_VPS; }
SOURCE
cat > "$portal_test_build/Test.swift" <<'SWIFT'
@main struct DecoderABITests {
    static func main() {
        precondition(BUFFER_TYPE_SPS == nativeSPS(), "Swift SPS differs from native ABI")
        precondition(BUFFER_TYPE_PPS == nativePPS(), "Swift PPS differs from native ABI")
        precondition(BUFFER_TYPE_VPS == nativeVPS(), "Swift VPS differs from native ABI")
        print("Decoder/native SPS, PPS, VPS ABI checks passed")
    }
}
SWIFT
portal_native="$portal_app/ThirdParty/Moonlight/Common/src"
xcrun --sdk macosx clang -I "$portal_native" -c "$portal_test_build/Native.c" -o "$portal_test_build/Native.o"
xcrun --sdk macosx swiftc -module-cache-path "$portal_test_build/modules" -I "$portal_native" \
    -import-objc-header "$portal_test_build/Native.h" "$portal_test_build/Constants.swift" \
    "$portal_test_build/Test.swift" "$portal_test_build/Native.o" -o "$portal_test_build/abi"
"$portal_test_build/abi"
