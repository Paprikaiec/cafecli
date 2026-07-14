#!/bin/bash
# run_ab_hooks.sh   A/B over the 6 Phase-0 core-fail instances, both arms (ctrl + hooks).
# Same paicli-hooks.jar for both; only difference = the 5 builtin-hook -D flags (treatment).
# Resumable, nohup-friendly. Solves + official-harness eval per (arm,iid).
set -u
cd ~/swebench
RESULTS=~/swebench/ab-hooks-results.tsv
LOG=~/swebench/ab-hooks-driver.log
touch "$RESULTS" "$LOG"

IIDS="
floci-io__floci-164
floci-io__floci-248
floci-io__floci-88
floci-io__floci-299
floci-io__floci-352
grpc__grpc-java-12735
"
ARMS="ctrl hooks"

record() {
  local ARM="$1" IID="$2"
  local R=~/SWE-bench-Live/logs/$ARM-"$IID"/"$IID"/report.json
  local AUD=~/swebench/ab-hooks/$ARM/$IID/audit
  local hb=$(cat $AUD/audit-*.jsonl 2>/dev/null | grep -c '"outcome":"hook_block"')
  python3 -c "import json;d=json.load(open('$R'));print('$IID\t$ARM\tresolved=%s\tF2P=%d/%d\tP2Pfail=%d\thook_block=$hb'%(d['resolved'],len(d['FAIL_TO_PASS']['success']),len(d['FAIL_TO_PASS']['failure']),len(d['PASS_TO_PASS']['failure'])))" 2>/dev/null \
    || echo "$IID	$ARM	ERROR(no-report/no-patch)	hook_block=$hb"
}

echo "$(date +%T) ===== A/B builtin-hooks batch start =====" >> "$LOG"
for ARM in $ARMS; do
  for IID in $IIDS; do
    [ -z "$IID" ] && continue
    if grep -q "^$IID	$ARM	" "$RESULTS"; then
      echo "$(date +%T) skip $ARM/$IID (already in results)" >> "$LOG"; continue
    fi
    echo "$(date +%T) >>> $ARM/$IID SOLVE" >> "$LOG"
    bash solve_one_ab.sh "$IID" "$ARM" > ~/swebench/solve-$ARM-"$IID".log 2>&1
    echo "$(date +%T) >>> $ARM/$IID EVAL" >> "$LOG"
    bash eval_one_live.sh "$IID" "$ARM" > ~/swebench/eval-$ARM-"$IID".log 2>&1
    line=$(record "$ARM" "$IID")
    echo "$line" >> "$RESULTS"
    echo "$(date +%T) DONE $line" >> "$LOG"
  done
done
echo "$(date +%T) ===== A/B builtin-hooks batch ALL DONE =====" >> "$LOG"
