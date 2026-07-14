#!/bin/bash
# solve_one_ab.sh <instance_id> <arm:ctrl|hooks> [model]
# One PaiCLI solve on the A/B (builtin-hooks) experiment. Same paicli-hooks.jar for both arms.
# Writes /tmp/out-<arm>-<iid> (eval_one_live.sh reads it) and archives trace/audit/patch to
# ~/swebench/ab-hooks/<arm>/<iid>/ (persistent).
set -u
IID="$1"; ARM="${2:-ctrl}"; MODEL="${3:-deepseek-v4-pro}"
cd ~/swebench
IMG=$(python3 -c "import json;[print(json.loads(l)['docker_image']) for l in open('live-java.jsonl') if json.loads(l)['instance_id']=='$IID']")
[ -z "$IMG" ] && { echo "no image for $IID"; exit 2; }
OUT="/tmp/out-$ARM-$IID"; rm -rf "$OUT"; mkdir -p "$OUT"
ISSUE="/tmp/issue-$IID.md"
python3 -c "import json;[open('$ISSUE','w').write(json.loads(l)['problem_statement']) for l in open('live-java.jsonl') if json.loads(l)['instance_id']=='$IID']"
KEY=$(cat ~/swebench/.deepseek_key)
if docker image inspect "$IMG" >/dev/null 2>&1; then echo "[cached $IMG]"; else echo "[pull $IMG]"; timeout 1800 docker pull "$IMG" 2>&1 | tail -3; fi
docker run --rm --network host -e DEEPSEEK_API_KEY="$KEY" -e DEEPSEEK_MODEL="$MODEL" -e PAICLI_ARM="$ARM" \
  -v ~/swebench/paicli-hooks.jar:/opt/paicli.jar:ro -v "$ISSUE":/tmp/issue.md:ro \
  -v ~/swebench/jdk21:/opt/jdk21:ro \
  -v ~/swebench/csolve_paicli_ab.sh:/csolve.sh:ro -v "$OUT":/out "$IMG" bash /csolve.sh
# archive persistent copies of trace + audit + patch
ARCH=~/swebench/ab-hooks/$ARM/$IID; mkdir -p "$ARCH"
cp -f "$OUT/trace.jsonl" "$ARCH/" 2>/dev/null
cp -f "$OUT/model.patch" "$ARCH/" 2>/dev/null
cp -rf "$OUT/audit" "$ARCH/" 2>/dev/null
cp -f "$OUT/exit.txt" "$OUT/patch-lines.txt" "$OUT/arm.txt" "$ARCH/" 2>/dev/null
echo "[$ARM $IID done] exit=$(cat $OUT/exit.txt 2>/dev/null) patch-lines=$(cat $OUT/patch-lines.txt 2>/dev/null) audit=$(cat $OUT/audit/audit-*.jsonl 2>/dev/null | wc -l) lines"
