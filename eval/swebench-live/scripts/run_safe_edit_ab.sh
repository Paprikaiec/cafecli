#!/bin/bash
# Repeated paired safe-edit A/B over the six Phase-0 core-fail instances.
# Defaults: two stochastic repetitions per arm, two concurrent cases.
# Resume-safe: a completed result row is never rerun.
set -uo pipefail

cd ~/swebench || exit 2
REPEATS="${REPEATS:-2}"
PARALLELISM="${PARALLELISM:-2}"
MODEL="${MODEL:-deepseek-v4-pro}"
RESULTS=~/swebench/safe-edit-ab-results.tsv
LOG=~/swebench/safe-edit-ab-driver.log
LOCK=~/swebench/safe-edit-ab-results.lock

case "$REPEATS" in ''|*[!0-9]*) echo "REPEATS must be a positive integer"; exit 64 ;; esac
case "$PARALLELISM" in ''|*[!0-9]*) echo "PARALLELISM must be a positive integer"; exit 64 ;; esac
[ "$REPEATS" -gt 0 ] && [ "$PARALLELISM" -gt 0 ] || { echo "REPEATS and PARALLELISM must be positive"; exit 64; }

IIDS="${PAICLI_SAFE_EDIT_IIDS:-
floci-io__floci-164
floci-io__floci-248
floci-io__floci-88
floci-io__floci-299
floci-io__floci-352
grpc__grpc-java-12735
}"

touch "$RESULTS" "$LOG" "$LOCK"
if [ ! -s "$RESULTS" ]; then
  printf 'instance_id\trepeat\tarm\tresolved\tF2P_pass\tF2P_fail\tP2P_fail\tsolve_exit\tpatch_lines\tsafe_edit_blocks\n' >"$RESULTS"
fi

already_recorded() {
  local iid="$1" repeat="$2" arm="$3"
  awk -F '\t' -v i="$iid" -v r="$repeat" -v a="$arm" \
    'NR > 1 && $1 == i && $2 == r && $3 == a { found=1 } END { exit !found }' "$RESULTS"
}

append_result() {
  local line="$1"
  {
    flock 9
    printf '%s\n' "$line" >>"$RESULTS"
  } 9>"$LOCK"
}

run_case() {
  local iid="$1" repeat="$2" arm="$3"
  local run_id="safeedit-$arm-r$repeat"
  local arch=~/swebench/safe-edit-ab/"$arm"/r"$repeat"/"$iid"
  local report=~/SWE-bench-Live/logs/"$run_id-$iid"/"$iid"/report.json
  local solve_log=~/swebench/"solve-$run_id-$iid.log"
  local eval_log=~/swebench/"eval-$run_id-$iid.log"

  if already_recorded "$iid" "$repeat" "$arm"; then
    printf '%s skip %s/r%s/%s (already recorded)\n' "$(date +%T)" "$arm" "$repeat" "$iid" >>"$LOG"
    return 0
  fi

  printf '%s >>> %s/r%s/%s SOLVE\n' "$(date +%T)" "$arm" "$repeat" "$iid" >>"$LOG"
  bash solve_one_safe_edit_ab.sh "$iid" "$arm" "$repeat" "$MODEL" >"$solve_log" 2>&1
  local solve_rc=$?

  printf '%s >>> %s/r%s/%s EVAL\n' "$(date +%T)" "$arm" "$repeat" "$iid" >>"$LOG"
  bash eval_one_live.sh "$iid" "$run_id" >"$eval_log" 2>&1
  local eval_rc=$?

  mkdir -p "$arch"
  cp -f "$report" "$arch/report.json" 2>/dev/null || true
  cp -f "$solve_log" "$eval_log" "$arch/" 2>/dev/null || true

  local line
  line=$(python3 - "$iid" "$repeat" "$arm" "$report" "$arch" <<'PY'
import glob, json, os, sys
iid, repeat, arm, report_path, arch = sys.argv[1:]
try:
    with open(report_path) as f:
        d = json.load(f)
    resolved = str(bool(d.get("resolved"))).lower()
    f2p_pass = len((d.get("FAIL_TO_PASS") or {}).get("success") or [])
    f2p_fail = len((d.get("FAIL_TO_PASS") or {}).get("failure") or [])
    p2p_fail = len((d.get("PASS_TO_PASS") or {}).get("failure") or [])
except Exception:
    resolved, f2p_pass, f2p_fail, p2p_fail = "ERROR", 0, 0, 0
solve_exit = "missing"
try:
    with open(os.path.join(arch, "exit.txt")) as f:
        solve_exit = f.read().strip().split("=", 1)[-1]
except Exception:
    pass
patch_lines = "0"
try:
    with open(os.path.join(arch, "patch-lines.txt")) as f:
        patch_lines = f.read().strip() or "0"
except Exception:
    pass
blocks = 0
for path in glob.glob(os.path.join(arch, "audit", "audit-*.jsonl")):
    with open(path) as f:
        for raw in f:
            try:
                e = json.loads(raw)
            except Exception:
                continue
            if e.get("outcome") == "deny" and e.get("approver") == "policy" and "测试目录保护" in (e.get("reason") or ""):
                blocks += 1
print("\t".join(map(str, [iid, repeat, arm, resolved, f2p_pass, f2p_fail, p2p_fail, solve_exit, patch_lines, blocks])))
PY
  )
  append_result "$line"
  printf '%s DONE solve_rc=%s eval_rc=%s %s\n' "$(date +%T)" "$solve_rc" "$eval_rc" "$line" >>"$LOG"
}

wait_for_slot() {
  while [ "$(jobs -pr | wc -l | tr -d ' ')" -ge "$PARALLELISM" ]; do
    sleep 5
  done
}

printf '%s ===== repeated safe-edit A/B start repeats=%s parallelism=%s model=%s =====\n' \
  "$(date +%T)" "$REPEATS" "$PARALLELISM" "$MODEL" >>"$LOG"

repeat=1
while [ "$repeat" -le "$REPEATS" ]; do
  # Alternate arm order by repeat; each pair enters the queue adjacently.
  if [ $((repeat % 2)) -eq 1 ]; then ARMS="ctrl safe"; else ARMS="safe ctrl"; fi
  for iid in $IIDS; do
    [ -n "$iid" ] || continue
    for arm in $ARMS; do
      if [ "${DRY_RUN:-0}" = "1" ]; then
        printf '%s\tr%s\t%s\n' "$iid" "$repeat" "$arm"
        continue
      fi
      wait_for_slot
      run_case "$iid" "$repeat" "$arm" &
    done
  done
  repeat=$((repeat + 1))
done

wait
printf '%s ===== repeated safe-edit A/B ALL DONE =====\n' "$(date +%T)" >>"$LOG"

