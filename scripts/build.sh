#!/usr/bin/env bash
# 本地构建脚本：构建 moleculedrawer Debug APK
# 用法：./scripts/build.sh [debug|release]
set -euo pipefail

cd "$(dirname "$0")/.."

TYPE="${1:-debug}"
case "$TYPE" in
  debug)   TASK=":moleculedrawer:assembleDebug" ;;
  release) TASK=":moleculedrawer:assembleRelease" ;;
  *) echo "用法: $0 [debug|release]"; exit 1 ;;
esac

echo "▶ 构建 $TYPE APK ..."
./gradlew "$TASK" --no-daemon

OUT="moleculedrawer/build/outputs/apk/$TYPE"
echo ""
echo "✓ 构建完成，产物:"
ls -lh "$OUT"/*.apk 2>/dev/null || echo "  (未找到 APK，请检查构建日志)"
