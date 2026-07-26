#!/bin/bash
# run_band_baseline.sh : band v1 baseline (ADR 0013) — 2 repetitions over the tested
# instances + the ADMITTED fresh instances (3/3 clean gold in band-gold-admission.tsv).
# Roster is derived AT START TIME from band.v1.txt: entries before the "# fresh" marker
# are tested (always run); entries after it are fresh candidates and run only if all
# three gold reps are 'ok' in band-gold-admission.tsv (ADR 0013 admission).
# Canonical config via csolve_paicli_band.sh (edit_file + 80 turns + safe_edit ON, hooks OFF).
# Serial, nohup-friendly, resumable: (iid, rep) rows already in band-baseline-results.tsv
# are never rerun. Rep 1 runs in roster order, rep 2 in REVERSE order (time-drift
# mitigation, same spirit as the safe-edit A/B arm-order reversal).
# Run ids: band-base-r<rep>-<iid>; artifacts: ~/swebench/band-baseline/r<rep>/<iid>/.
set -u
cd ~/swebench || exit 2

TSV=~/swebench/band-baseline-results.tsv
LOG=~/swebench/band-baseline-driver.log
ADMIT=~/swebench/band-gold-admission.tsv
BAND=~/swebench/band.v1.txt
MODEL="${MODEL:-deepseek-v4-pro}"
[ -s "$BAND" ] || { echo "missing $BAND" >&2; exit 2; }
touch "$TSV" "$LOG"
if [ ! -s "$TSV" ]; then
  printf 'instance_id\trep\tresolved\tF2P\tP2P_fail\tpatch_lines\tturns\n' >>"$TSV"
fi

TESTED=$(awk '/^# fresh/{f=1; next} /^[[:space:]]*#/{next} NF{if (!f) print $1}' "$BAND")
FRESH_CAND=$(awk '/^# fresh/{f=1; next} /^[[:space:]]*#/{next} NF{if (f) print $1}' "$BAND")

# Candidates go via argv: with `python3 -` the heredoc occupies stdin.
FRESH=$(python3 - "$ADMIT" $FRESH_CAND <<'PY'
import sys, collections
ok = collections.defaultdict(set)
try:
    for line in open(sys.argv[1]):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 6 and p[0] != "instance_id" and p[5] == "ok":
            ok[p[0]].add(p[1])
except OSError:
    pass
for iid in sys.argv[2:]:
    if {"1", "2", "3"} <= ok.get(iid, set()):
        print(iid)
PY
)

for CAND in $FRESH_CAND; do
  case " $(printf '%s ' $FRESH) " in
    *" $CAND "*) ;;
    *) echo "$(date '+%F %T') fresh candidate NOT ADMITTED, excluded from baseline: $CAND" >>"$LOG" ;;
  esac
done

ROSTER=$(printf '%s\n%s\n' "$TESTED" "$FRESH" | sed '/^[[:space:]]*$/d')
ROSTER_REV=$(printf '%s\n' "$ROSTER" | tac)
N=$(printf '%s\n' "$ROSTER" | wc -l | tr -d ' ')

echo "$(date '+%F %T') ===== band v1 baseline START: $N instances x 2 reps, model=$MODEL =====" >>"$LOG"
printf '%s\n' "$ROSTER" | sed 's/^/roster: /' >>"$LOG"

already_recorded() {
  awk -F'\t' -v i="$1" -v r="$2" 'NR>1 && $1==i && $2==r {found=1} END{exit !found}' "$TSV"
}

run_case() {
  local IID="$1" REP="$2"
  local RUN_TAG="band-base-r$REP"
  local ARCH=~/swebench/band-baseline/r"$REP"/"$IID"
  local REPORT=~/SWE-bench-Live/logs/"$RUN_TAG-$IID"/"$IID"/report.json
  local SOLVE_LOG=~/swebench/solve-"$RUN_TAG"-"$IID".log
  local EVAL_LOG=~/swebench/eval-"$RUN_TAG"-"$IID".log

  if already_recorded "$IID" "$REP"; then
    echo "$(date '+%F %T') skip r$REP/$IID (already recorded)" >>"$LOG"
    return 0
  fi

  echo "$(date '+%F %T') >>> r$REP/$IID SOLVE" >>"$LOG"
  bash solve_one_band.sh "$IID" "$RUN_TAG" "$ARCH" "$MODEL" >"$SOLVE_LOG" 2>&1
  local SOLVE_RC=$?

  echo "$(date '+%F %T') >>> r$REP/$IID EVAL" >>"$LOG"
  bash eval_one_live.sh "$IID" "$RUN_TAG" >"$EVAL_LOG" 2>&1
  local EVAL_RC=$?

  mkdir -p "$ARCH"
  cp -f "$REPORT" "$ARCH"/report.json 2>/dev/null || true
  cp -f "$SOLVE_LOG" "$EVAL_LOG" "$ARCH"/ 2>/dev/null || true

  local LINE
  LINE=$(python3 - "$IID" "$REP" "$REPORT" "$ARCH" <<'PY'
import json, sys
iid, rep, report_path, arch = sys.argv[1:]
try:
    d = json.load(open(report_path))
    resolved = str(bool(d.get("resolved")))
    f2p = "%d/%d" % (
        len((d.get("FAIL_TO_PASS") or {}).get("success") or []),
        len((d.get("FAIL_TO_PASS") or {}).get("failure") or []),
    )
    p2pf = len((d.get("PASS_TO_PASS") or {}).get("failure") or [])
except Exception:
    resolved, f2p, p2pf = "ERROR", "0/0", "-"
patch_lines = "0"
try:
    patch_lines = open(arch + "/patch-lines.txt").read().strip() or "0"
except Exception:
    pass
turns = 0
try:
    for raw in open(arch + "/trace.jsonl"):
        try:
            e = json.loads(raw)
        except ValueError:
            continue
        if isinstance(e, dict) and e.get("type") == "assistant":
            turns += 1
except Exception:
    turns = "-"
print("\t".join(map(str, [iid, rep, resolved, f2p, p2pf, patch_lines, turns])))
PY
)
  printf '%s\n' "$LINE" >>"$TSV"
  echo "$(date '+%F %T') DONE solve_rc=$SOLVE_RC eval_rc=$EVAL_RC $LINE" >>"$LOG"
}

for IID in $ROSTER; do
  run_case "$IID" 1
done
for IID in $ROSTER_REV; do
  run_case "$IID" 2
done

echo "$(date '+%F %T') ===== band v1 baseline ALL DONE =====" >>"$LOG"
