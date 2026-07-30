#!/bin/bash
# run_band_tested_audit.sh : uniform-standard gold audit of the band v1 TESTED members
# (ADR 0013 amendment, 2026-07-26).
#
# Why: the 15 tested members were admitted on 1x gold validation or less -- 7 have no
# recorded gold run at all -- while fresh candidates face the 3x bar. checkstyle-19487
# showed PASS_TO_PASS failures in BOTH arms on 2026-06-08, the same flakiness signature
# that rejected nacos-14778 during fresh admission. This driver puts every band member on
# one admission standard so the regression guards are trustworthy.
#
# Rule (same as run_band_gold.sh): 3 independent gold evaluations; a member is CLEAN only
# if all 3 are resolved=True with zero PASS_TO_PASS regressions. Failures are recorded,
# not silently dropped -- ejection is a roster decision made in the main session.
#
# Serial, nohup-friendly, resumable: (iid, rep) rows already in band-tested-audit.tsv are
# never rerun. A failed pull is NOT recorded, so a relaunch retries it.
# Run ids: band-audit-r<rep>-<iid>. Intended to run AFTER the baseline completes.
set -u
cd ~/swebench || exit 2

TSV=~/swebench/band-tested-audit.tsv
LOG=~/swebench/band-tested-audit.log
BAND=~/swebench/band.v1.txt
touch "$TSV" "$LOG"
if [ ! -s "$TSV" ]; then
  printf 'instance_id\trep\tresolved\tF2P\tP2P_fail\tverdict\n' >>"$TSV"
fi

# Tested members only: everything in band.v1.txt before the "# fresh" marker.
IIDS=$(sed '/^# fresh/,$d' "$BAND" | grep -v '^#' | grep -v '^[[:space:]]*$')

echo "$(date '+%F %T') ===== band v1 tested-member gold audit START =====" >>"$LOG"
printf '%s\n' "$IIDS" | sed 's/^/audit roster: /' >>"$LOG"

for IID in $IIDS; do
  [ -z "$IID" ] && continue
  IMG=$(python3 -c "import json;[print(json.loads(l)['docker_image']) for l in open('live-java.jsonl') if json.loads(l)['instance_id']=='$IID']")
  if [ -z "$IMG" ]; then
    echo "$(date '+%F %T') $IID NO IMAGE FIELD IN DATASET" >>"$LOG"
    awk -F'\t' -v i="$IID" '$1==i && $2=="0" {found=1} END{exit !found}' "$TSV" \
      || printf '%s\t0\tERROR\t0/0\t-\tNOIMAGE\n' "$IID" >>"$TSV"
    continue
  fi
  if docker image inspect "$IMG" >/dev/null 2>&1; then
    echo "$(date '+%F %T') $IID image cached ($IMG)" >>"$LOG"
  else
    echo "$(date '+%F %T') $IID PULL $IMG" >>"$LOG"
    timeout 3600 docker pull "$IMG" > ~/swebench/pull-audit-"$IID".log 2>&1
    if docker image inspect "$IMG" >/dev/null 2>&1; then
      echo "$(date '+%F %T') $IID PULL OK" >>"$LOG"
    else
      echo "$(date '+%F %T') $IID PULL FAILED (not recorded; relaunch retries)" >>"$LOG"
      continue
    fi
  fi

  for REP in 1 2 3; do
    if awk -F'\t' -v i="$IID" -v r="$REP" '$1==i && $2==r {found=1} END{exit !found}' "$TSV"; then
      echo "$(date '+%F %T') skip $IID r$REP (already recorded)" >>"$LOG"
      continue
    fi
    RUN_ID="band-audit-r$REP-$IID"
    echo "$(date '+%F %T') >>> $IID r$REP GOLD ($RUN_ID)" >>"$LOG"
    ( cd ~/SWE-bench-Live
      unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
      ~/swebench/venv/bin/python -m evaluation.evaluation \
        --dataset ~/swebench/live-java.jsonl --instance_ids "$IID" --platform linux \
        --patch_dir gold --output_dir "logs/$RUN_ID" --workers 1 --overwrite 1 \
        > ~/swebench/goldaudit-"$RUN_ID".log 2>&1 )
    R=~/SWE-bench-Live/logs/"$RUN_ID"/"$IID"/report.json
    LINE=$(python3 - "$IID" "$REP" "$R" <<'PY'
import json, sys
iid, rep, path = sys.argv[1:]
try:
    d = json.load(open(path))
    res = bool(d.get("resolved"))
    f2ps = len((d.get("FAIL_TO_PASS") or {}).get("success") or [])
    f2pf = len((d.get("FAIL_TO_PASS") or {}).get("failure") or [])
    p2pf = len((d.get("PASS_TO_PASS") or {}).get("failure") or [])
    verdict = "ok" if (res and p2pf == 0) else "bad"
    print(f"{iid}\t{rep}\t{res}\t{f2ps}/{f2pf}\t{p2pf}\t{verdict}")
except Exception:
    print(f"{iid}\t{rep}\tERROR\t0/0\t-\tNOREPORT")
PY
)
    printf '%s\n' "$LINE" >>"$TSV"
    echo "$(date '+%F %T') DONE $LINE" >>"$LOG"
  done
done

# Per-member verdict summary (3/3 'ok' = CLEAN under the uniform standard).
python3 - "$TSV" <<'PY' >>"$LOG"
import sys, collections
ok = collections.defaultdict(set)
seen = []
for line in open(sys.argv[1]):
    p = line.rstrip("\n").split("\t")
    if len(p) < 6 or p[0] == "instance_id":
        continue
    if p[0] not in seen:
        seen.append(p[0])
    if p[5] == "ok":
        ok[p[0]].add(p[1])
for iid in seen:
    clean = {"1", "2", "3"} <= ok.get(iid, set())
    print(f"AUDIT\t{iid}\t{'CLEAN' if clean else 'SUSPECT'}\t{len(ok.get(iid, set()))}/3 clean")
PY
echo "$(date '+%F %T') ===== band v1 tested-member gold audit COMPLETE =====" >>"$LOG"
