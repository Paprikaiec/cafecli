#!/usr/bin/env bash
# 调用 multi-swe-bench-env 官方 harness 评分。云机执行。
set -euo pipefail
PRED="${1:?usage: score.sh <gold|predictions.jsonl> <run_id>}"
RUN_ID="${2:?run_id required}"
WORKERS="${MAX_WORKERS:-8}"

python -m swebench.harness.run_evaluation \
  --dataset_name Daoguang/Multi-SWE-bench \
  --predictions_path "$PRED" \
  --run_id "$RUN_ID" \
  --max_workers "$WORKERS" \
  --cache_level env
# 阶段 1 先验环境: ./score.sh gold validate-gold
