#!/bin/sh
# Copies the proprietary NDI Advanced SDK headers + prebuilt .so libs into the project tree.
# Not committed to git (see .gitignore) — the NDI SDK license doesn't permit redistributing
# the SDK itself, so re-run this after a fresh checkout instead of vendoring it in git.
#
# Requires the *Advanced* SDK (application/approval via ndi.video), not the standard SDK —
# only the Advanced SDK exposes the compressed H.264/HEVC send path (NDI|HX) this app uses.
#
# Pinned to v5.6.1, not the newer v6, deliberately: Millumin bundles NDI runtime 5.0.0, whose
# receive path has no decoder for the wire format v6 sends (confirmed directly — v6 output
# decodes fine via v6's own runtime but Millumin errors "video decoder not found"). v5.6.1's
# NDIlib_compressed_packet_t is byte-identical to v6's, so sending from v5.6.1 targets what
# Millumin can actually already decode, unmodified. Revisit this pin once Millumin ships a
# newer bundled NDI runtime.
#
# Usage: ./scripts/vendor_ndi_sdk.sh [path to "NDI Advanced SDK v5 for Android"]
set -e

SDK_SRC="${1:-$HOME/Documents/NDI Advanced SDK v5 for Android}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -d "$SDK_SRC" ]; then
    echo "NDI Advanced SDK v5 for Android not found at: $SDK_SRC" >&2
    echo "Apply for + download NDI Advanced SDK v5.6.1 for Android, or pass its extracted path as \$1." >&2
    exit 1
fi

echo "Vendoring headers..."
mkdir -p "$PROJECT_ROOT/app/src/main/cpp/ndi-include"
cp "$SDK_SRC/include/"*.h "$PROJECT_ROOT/app/src/main/cpp/ndi-include/"

echo "Vendoring libs (libndi.so, v5.6.1 naming — not libndi_advanced.so, that's v6's name)..."
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    mkdir -p "$PROJECT_ROOT/app/src/main/jniLibs/$abi"
    cp "$SDK_SRC/lib/$abi/libndi.so" "$PROJECT_ROOT/app/src/main/jniLibs/$abi/"
done

echo "Done."
