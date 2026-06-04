#!/usr/bin/env bash
# 在某实例的官方 Docker 镜像内跑一个 agent，产出 raw trace。
# model_patch 提取（git diff BASE_COMMIT）留云机最终化（见末尾说明）。
# 本脚本在 x86 云机执行；本地仅做 bash -n 语法检查。
set -euo pipefail

INSTANCE_ID="$1"; IMAGE="$2"; ARM="$3"
ISSUE_FILE="$4"; BASE_COMMIT="$5"; OUT_DIR="$6"   # BASE_COMMIT 供云机 git diff 取 patch
REPO_DIR="${REPO_DIR:-/workspace/repo}"   # 镜像内仓库路径，按 harness 镜像实际调整（云机确认）

mkdir -p "$OUT_DIR"
TRACE="$OUT_DIR/${INSTANCE_ID}.jsonl"

run_paicli() {
  docker run --rm --platform linux/amd64 \
    -e DEEPSEEK_API_KEY -e DEEPSEEK_MODEL=deepseek-v4-pro \
    -v "$PAICLI_JAR:/opt/paicli.jar:ro" \
    -v "$ISSUE_FILE:/tmp/issue.md:ro" \
    "$IMAGE" bash -lc \
    "cd \"$REPO_DIR\" && java -jar /opt/paicli.jar run --file /tmp/issue.md --trace-file /tmp/trace.jsonl >/tmp/answer.txt 2>/tmp/agent.err; \
     cat /tmp/trace.jsonl" > "$TRACE" || true
}

run_claude() {
  docker run --rm --platform linux/amd64 \
    -e ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic \
    -e ANTHROPIC_AUTH_TOKEN="$DEEPSEEK_API_KEY" \
    -e ANTHROPIC_MODEL=deepseek-v4-pro \
    -e CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
    -v "$ISSUE_FILE:/tmp/issue.md:ro" \
    "$IMAGE" bash -lc \
    "npm i -g @anthropic-ai/claude-code >/dev/null 2>&1; cd \"$REPO_DIR\" && \
     claude -p \"\$(cat /tmp/issue.md)\" --output-format stream-json --verbose --dangerously-skip-permissions > /tmp/trace.jsonl 2>/tmp/agent.err; \
     cat /tmp/trace.jsonl" > "$TRACE" || true
}

case "$ARM" in
  paicli) run_paicli ;;
  claude) run_claude ;;
  *) echo "unknown arm: $ARM" >&2; exit 2 ;;
esac

echo "[skeleton] $INSTANCE_ID/$ARM done -> $TRACE (patch extraction finalized on cloud box)"
