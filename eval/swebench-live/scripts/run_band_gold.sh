#!/bin/bash
# run_band_gold.sh : fresh-5 admission for band v1 (ADR 0013).
# For each fresh candidate: skip-if-cached docker pull, then 3 independent gold-patch
# evaluations through the official harness (--patch_dir gold = the instance's own
# `patch` field, same convention as build_whitelist.sh). Run ids: band-gold-r<rep>-<iid>.
# Admission rule (ADR 0013): all 3 runs resolved=True AND zero PASS_TO_PASS regressions.
# Serial, nohup-friendly, resumable: (iid, rep) rows already in band-gold-admission.tsv
# are never rerun. A failed pull is NOT recorded, so a relaunch retries it.
set -u
cd ~/swebench || exit 2

TSV=~/swebench/band-gold-admission.tsv
LOG=~/swebench/band-gold-driver.log
touch "$TSV" "$LOG"
if [ ! -s "$TSV" ]; then
  printf 'instance_id\trep\tresolved\tF2P\tP2P_fail\tverdict\n' >>"$TSV"
fi

IIDS="
alibaba__nacos-14917
alibaba__nacos-14778
spring-projects__spring-boot-50119
spring-projects__spring-framework-36550
strimzi__strimzi-kafka-operator-12590
"

echo "$(date '+%F %T') ===== band v1 gold admission START =====" >>"$LOG"

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
    timeout 3600 docker pull "$IMG" > ~/swebench/pull-band-"$IID".log 2>&1
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
    RUN_ID="band-gold-r$REP-$IID"
    echo "$(date '+%F %T') >>> $IID r$REP GOLD ($RUN_ID)" >>"$LOG"
    ( cd ~/SWE-bench-Live
      unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
      ~/swebench/venv/bin/python -m evaluation.evaluation \
        --dataset ~/swebench/live-java.jsonl --instance_ids "$IID" --platform linux \
        --patch_dir gold --output_dir "logs/$RUN_ID" --workers 1 --overwrite 1 \
        > ~/swebench/goldband-"$RUN_ID".log 2>&1 )
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

# Per-instance admission verdict summary (ADR 0013: 3/3 'ok' required).
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
    admitted = {"1", "2", "3"} <= ok.get(iid, set())
    print(f"VERDICT\t{iid}\t{'ADMITTED' if admitted else 'REJECTED'}\t{len(ok.get(iid, set()))}/3 clean")
PY
echo "$(date '+%F %T') ===== band v1 gold admission COMPLETE =====" >>"$LOG"
