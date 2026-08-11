#!/usr/bin/env bash
# 提交脚本：同步 codegraph 索引 → git add → commit → push
# 用法：./scripts/commit.sh "提交信息" [--no-push]
set -euo pipefail

cd "$(dirname "$0")/.."

MSG="${1:?用法: $0 \"提交信息\" [--no-push]}"
PUSH=1
[[ "${2:-}" == "--no-push" ]] && PUSH=0

# 1. 提交前先同步 codegraph 索引（若存在索引）
if [ -d .codegraph ] && command -v codegraph >/dev/null 2>&1; then
  echo "▶ 同步 codegraph 索引 ..."
  codegraph sync . || echo "  ⚠ codegraph sync 失败（不影响提交）"
else
  echo "⏭ 跳过 codegraph 同步（无索引或未安装 codegraph）"
fi

# 2. 检查是否有未提交改动
if git diff --cached --quiet && git diff --quiet; then
  echo "ℹ 没有待提交的改动"
  exit 0
fi

# 3. 提交
echo "▶ git add -A && git commit ..."
git add -A
git commit -m "$MSG"

# 4. 推送（post-commit hook 会自动再同步一次 codegraph）
if [ "$PUSH" -eq 1 ]; then
  echo "▶ git push ..."
  git push origin "$(git branch --show-current)"
fi

echo "✓ 完成"
