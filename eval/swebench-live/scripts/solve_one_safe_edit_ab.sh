#!/bin/bash
# solve_one_safe_edit_ab.sh <instance_id> <arm:ctrl|safe> <repeat> [model]
# Produces /tmp/out-<run_id>-<iid> for eval_one_live.sh and archives solve artifacts.
set -u

IID="${1:?instance_id required}"
ARM="${2:?arm required}"
REPEAT="${3:?repeat required}"
MODEL="${4:-deepseek-v4-pro}"

case "$IID" in *[!A-Za-z0-9_.-]*) echo "invalid instance id: $IID"; exit 64 ;; esac
case "$ARM" in ctrl|safe) ;; *) echo "arm must be ctrl or safe"; exit 64 ;; esac
case "$REPEAT" in ''|*[!0-9]*) echo "repeat must be a positive integer"; exit 64 ;; esac
[ "$REPEAT" -gt 0 ] || { echo "repeat must be a positive integer"; exit 64; }

cd ~/swebench || exit 2
RUN_ID="safeedit-$ARM-r$REPEAT"
IMG=$(python3 -c "import json;[print(json.loads(l)['docker_image']) for l in open('live-java.jsonl') if json.loads(l)['instance_id']=='$IID']")
[ -n "$IMG" ] || { echo "no image for $IID"; exit 2; }

OUT="/tmp/out-$RUN_ID-$IID"
case "$OUT" in /tmp/out-safeedit-*) ;; *) echo "unsafe output path: $OUT"; exit 64 ;; esac
rm -rf -- "$OUT"
mkdir -p "$OUT"

ISSUE="/tmp/issue-safeedit-$ARM-r$REPEAT-$IID.md"
python3 -c "import json;[open('$ISSUE','w').write(json.loads(l)['problem_statement']) for l in open('live-java.jsonl') if json.loads(l)['instance_id']=='$IID']"
KEY=$(sed -n '1p' ~/swebench/.deepseek_key)

if docker image inspect "$IMG" >/dev/null 2>&1; then
  echo "[cached $IMG]"
else
  echo "[pull $IMG]"
  timeout 1800 docker pull "$IMG" 2>&1 | tail -3
fi

DEEPSEEK_API_KEY="$KEY" docker run --rm --network host \
  -e DEEPSEEK_API_KEY -e DEEPSEEK_MODEL="$MODEL" \
  -e PAICLI_SAFE_EDIT_ARM="$ARM" -e PAICLI_REPEAT="$REPEAT" \
  -v ~/swebench/paicli-hooks.jar:/opt/paicli.jar:ro \
  -v "$ISSUE":/tmp/issue.md:ro \
  -v ~/swebench/jdk21:/opt/jdk21:ro \
  -v ~/swebench/csolve_paicli_safe_edit_ab.sh:/csolve.sh:ro \
  -v "$OUT":/out "$IMG" bash /csolve.sh
DOCKER_EXIT=$?

ARCH=~/swebench/safe-edit-ab/"$ARM"/r"$REPEAT"/"$IID"
mkdir -p "$ARCH"
for artifact in trace.jsonl model.patch exit.txt patch-lines.txt experiment.txt answer.txt agent.err; do
  cp -f "$OUT/$artifact" "$ARCH/" 2>/dev/null || true
done
cp -rf "$OUT/audit" "$ARCH/" 2>/dev/null || true

printf '[%s %s r%s done] docker_exit=%s solve_%s patch_lines=%s audit=%s lines\n' \
  "$ARM" "$IID" "$REPEAT" "$DOCKER_EXIT" "$(sed -n '1p' "$OUT/exit.txt" 2>/dev/null)" \
  "$(sed -n '1p' "$OUT/patch-lines.txt" 2>/dev/null)" \
  "$(find "$OUT/audit" -type f -name 'audit-*.jsonl' -exec sed -n '$=' {} \; 2>/dev/null | awk '{s+=$1} END{print s+0}')"
exit "$DOCKER_EXIT"
