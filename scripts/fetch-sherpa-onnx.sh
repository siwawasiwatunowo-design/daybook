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
  curl -fSL -o "$tmp/android.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VER}/sherpa-onnx-v${VER}-android.tar.bz2"
  tar xjf "$tmp/android.tar.bz2" -C "$tmp"
  mkdir -p "$JNI_DIR"
  cp -r "$tmp/jniLibs/." "$JNI_DIR/"
  rm -rf "$tmp"
fi

# 2) Kotlin API sources (same tag as the native libs)
mkdir -p "$KT_DIR"
BASE="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v${VER}/kotlin-api-examples"
for f in FeatureConfig OfflineRecognizer OfflineStream; do
  curl -fSL -o "$KT_DIR/$f.kt" "$BASE/$f.kt"
done
# Present only in some versions; OfflineRecognizer.kt needs them when they exist.
for f in QnnConfig HomophoneReplacerConfig; do
  curl -fSL -o "$KT_DIR/$f.kt" "$BASE/$f.kt" 2>/dev/null || rm -f "$KT_DIR/$f.kt"
done

echo "sherpa-onnx v${VER} ready:"
ls -lh "$JNI_DIR"/*/ "$KT_DIR"
