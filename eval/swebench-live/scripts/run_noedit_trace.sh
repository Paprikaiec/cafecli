#!/bin/bash
# run_noedit_trace.sh  — solve-only (no eval) over the 6 Phase-0 instances with the OLD no-edit jar,
# default 50-turn cap, to quantify "ended-without-testing" for the historical regime.
# Archives traces to ~/swebench/ab-hooks/noedit/<iid>/trace.jsonl. Resumable.
set -u
cd ~/swebench
LOG=~/swebench/noedit-trace-driver.log; touch "$LOG"
IIDS="floci-io__floci-164 floci-io__floci-248 floci-io__floci-88 floci-io__floci-299 floci-io__floci-352 grpc__grpc-java-12735"
KEY=$(cat ~/swebench/.deepseek_key)
echo "$(date +%T) ===== no-edit trace batch start =====" >> "$LOG"
for IID in $IIDS; do
  ARCH=~/swebench/ab-hooks/noedit/$IID
  if [ -s "$ARCH/trace.jsonl" ]; then echo "$(date +%T) skip $IID (trace exists)" >> "$LOG"; continue; fi
  IMG=$(python3 -c "import json;[print(json.loads(l)['docker_image']) for l in open('live-java.jsonl') if json.loads(l)['instance_id']=='$IID']")
  OUT="/tmp/out-noedit-$IID"; rm -rf "$OUT"; mkdir -p "$OUT"
  ISSUE="/tmp/issue-$IID.md"
  python3 -c "import json;[open('$ISSUE','w').write(json.loads(l)['problem_statement']) for l in open('live-java.jsonl') if json.loads(l)['instance_id']=='$IID']"
  echo "$(date +%T) >>> $IID SOLVE(noedit)" >> "$LOG"
  docker run --rm --network host -e DEEPSEEK_API_KEY="$KEY" -e DEEPSEEK_MODEL="deepseek-v4-pro" \
    -v ~/swebench/cafecli-hooks.jar:/opt/cafecli.jar:ro -v "$ISSUE":/tmp/issue.md:ro \
    -v ~/swebench/jdk21:/opt/jdk21:ro \
    -v ~/swebench/csolve_cafecli_noedit_trace.sh:/csolve.sh:ro -v "$OUT":/out "$IMG" bash /csolve.sh \
    > ~/swebench/solve-noedit-"$IID".log 2>&1
  mkdir -p "$ARCH"; cp -f "$OUT/trace.jsonl" "$OUT/model.patch" "$OUT/exit.txt" "$OUT/patch-lines.txt" "$ARCH/" 2>/dev/null
  echo "$(date +%T) DONE $IID exit=$(cat $OUT/exit.txt 2>/dev/null) patch-lines=$(cat $OUT/patch-lines.txt 2>/dev/null)" >> "$LOG"
done
echo "$(date +%T) ===== no-edit trace batch ALL DONE =====" >> "$LOG"
