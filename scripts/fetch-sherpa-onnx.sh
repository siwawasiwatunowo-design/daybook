#!/usr/bin/env bash
# Downloads sherpa-onnx (Whisper STT engine) into the Android project:
#   - native libs  -> app/src/main/jniLibs/<abi>/  (libsherpa-onnx-jni.so, libonnxruntime.so)
#   - Kotlin API   -> app/src/main/java/com/k2fsa/sherpa/onnx/
# The native libs and the Kotlin files MUST come from the same version, so both use $VER.
# Run from anywhere; run it BEFORE ./gradlew (locally and in CI).
set -euo pipefail

VER="${SHERPA_ONNX_VERSION:-1.13.8}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT/app/src/main/jniLibs"
KT_DIR="$ROOT/app/src/main/java/com/k2fsa/sherpa/onnx"

# 1) Native libs (release tarball contains a top-level jniLibs/ folder)
if [ ! -f "$JNI_DIR/arm64-v8a/libsherpa-onnx-jni.so" ]; then
  tmp="$(mktemp -d)"
  curl --retry 5 --retry-all-errors --retry-delay 5 -fSL -o "$tmp/android.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VER}/sherpa-onnx-v${VER}-android.tar.bz2"
  tar xjf "$tmp/android.tar.bz2" -C "$tmp"
  mkdir -p "$JNI_DIR"
  cp -r "$tmp/jniLibs/." "$JNI_DIR/"
  rm -rf "$tmp"
fi

# 2) Kotlin API sources (same tag as the native libs).
# NOTE: kotlin-api-examples/*.kt are Git symlinks (raw download returns only the link text),
# so fetch the real files from sherpa-onnx/kotlin-api/.
mkdir -p "$KT_DIR"
BASE="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v${VER}/sherpa-onnx/kotlin-api"

fetch_kt() {  # $1 = file base name; returns non-zero if download fails or is not Kotlin source
  local out="$KT_DIR/$1.kt"
  curl --retry 5 --retry-all-errors --retry-delay 5 -fSL -o "$out" "$BASE/$1.kt" 2>/dev/null || { rm -f "$out"; return 1; }
  if ! grep -q '^package com.k2fsa.sherpa.onnx' "$out"; then
    echo "ERROR: $1.kt is not Kotlin source (first line: $(head -n 1 "$out"))" >&2
    rm -f "$out"
    return 1
  fi
}

for f in FeatureConfig OfflineRecognizer OfflineStream; do
  fetch_kt "$f" || { echo "Failed to fetch required file $f.kt from $BASE" >&2; exit 1; }
done
# Present only in some versions; OfflineRecognizer.kt needs them when they exist.
for f in QnnConfig HomophoneReplacerConfig; do
  fetch_kt "$f" || echo "Skipping optional $f.kt"
done

echo "sherpa-onnx v${VER} ready:"
ls -lh "$JNI_DIR"/*/ "$KT_DIR"
