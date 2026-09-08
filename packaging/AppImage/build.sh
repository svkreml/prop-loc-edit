#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONTEXT_DIR="$SCRIPT_DIR"

IMG_TAG=${IMG_TAG:-prop-loc-edit-appimage}
OUT_DIR="${REPO_ROOT}/target/appimage"
APPIMAGE_NAME="prop-loc-edit-2.0-x86_64.AppImage"

cd "$REPO_ROOT"

echo "==> Упаковка исходников (pom.xml + src) в source.tar.gz"
tar czf "$CONTEXT_DIR/source.tar.gz" \
    --exclude target --exclude .git --exclude .idea \
    --exclude ai-config.json \
    pom.xml src
trap 'rm -f "$CONTEXT_DIR/source.tar.gz"' EXIT

echo "==> Docker build ($IMG_TAG)"
docker build -f "$CONTEXT_DIR/Dockerfile" -t "$IMG_TAG" "$CONTEXT_DIR"

mkdir -p "$OUT_DIR"
echo "==> Копирование AppImage в $OUT_DIR"
docker run --rm -v "$OUT_DIR:/out" "$IMG_TAG" cp "/work/$APPIMAGE_NAME" /out/

echo
echo "Готово: $OUT_DIR/$APPIMAGE_NAME"
ls -lh "$OUT_DIR/$APPIMAGE_NAME"