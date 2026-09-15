#!/usr/bin/env bash
# Downloads the on-device ML models both apps need. They are not checked in (≈125 MB).
#
#   iOS      -> ios/Metanav/Models/DepthAnythingV2SmallF16.mlpackage   (Apple, Apache-2.0)
#               ios/Metanav/Models/YOLOv3TinyInt8LUT.mlmodel            (Apple model gallery)
#   Android  -> android/app/src/main/assets/midas.tflite                 (Qualcomm AI Hub export of MiDaS v2.1 small, MIT)
#               android/app/src/main/assets/efficientdet_lite0.tflite    (MediaPipe, Apache-2.0)
#
# Usage: scripts/fetch-models.sh [ios|android|all]   (default: all)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-all}"

fetch() { # url dest
  if [ -s "$2" ]; then echo "  ✓ $(basename "$2") already present"; return; fi
  echo "  ↓ $(basename "$2")"
  mkdir -p "$(dirname "$2")"
  curl -L --fail --progress-bar -o "$2.part" "$1" && mv "$2.part" "$2"
}

if [ "$TARGET" = "ios" ] || [ "$TARGET" = "all" ]; then
  echo "iOS models"
  M="$ROOT/ios/Metanav/Models"
  HF="https://huggingface.co/apple/coreml-depth-anything-v2-small/resolve/main/DepthAnythingV2SmallF16.mlpackage"
  fetch "$HF/Manifest.json" "$M/DepthAnythingV2SmallF16.mlpackage/Manifest.json"
  fetch "$HF/Data/com.apple.CoreML/model.mlmodel" "$M/DepthAnythingV2SmallF16.mlpackage/Data/com.apple.CoreML/model.mlmodel"
  fetch "$HF/Data/com.apple.CoreML/weights/weight.bin" "$M/DepthAnythingV2SmallF16.mlpackage/Data/com.apple.CoreML/weights/weight.bin"
  fetch "https://ml-assets.apple.com/coreml/models/Image/ObjectDetection/YOLOv3Tiny/YOLOv3TinyInt8LUT.mlmodel" "$M/YOLOv3TinyInt8LUT.mlmodel"
fi

if [ "$TARGET" = "android" ] || [ "$TARGET" = "all" ]; then
  echo "Android models"
  A="$ROOT/android/app/src/main/assets"
  mkdir -p "$A"
  if [ ! -s "$A/midas.tflite" ]; then
    TMP="$(mktemp -d)"
    fetch "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/midas/releases/v0.62.2/midas-tflite-float.zip" "$TMP/midas.zip"
    unzip -q -o "$TMP/midas.zip" -d "$TMP/midas"
    cp "$(find "$TMP/midas" -name '*.tflite' | head -1)" "$A/midas.tflite"
    rm -rf "$TMP"
  else
    echo "  ✓ midas.tflite already present"
  fi
  fetch "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/latest/efficientdet_lite0.tflite" "$A/efficientdet_lite0.tflite"
fi
echo "Done."
