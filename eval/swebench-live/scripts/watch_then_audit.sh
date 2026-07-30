#!/bin/bash
# watch_then_audit.sh : run the tested-member gold audit AFTER the gold->baseline chain
# finishes (ADR 0013 amendment 2026-07-26, "audit after baseline").
# Race-free: waits on the chain's own PID (passed as $1) rather than polling names, then
# double-checks no heavy driver remains before starting. Serial discipline: never two
# heavy drivers at once.
set -u
CHAIN_PID=${1:?chain pid required}
LOG=~/swebench/band-audit-watcher.log
echo "$(date '+%F %T') watcher start, waiting on chain PID $CHAIN_PID" >>"$LOG"
while kill -0 "$CHAIN_PID" 2>/dev/null; do sleep 300; done
echo "$(date '+%F %T') chain PID $CHAIN_PID exited" >>"$LOG"
while pgrep -f '[r]un_band_gold.sh' >/dev/null || pgrep -f '[r]un_band_baseline.sh' >/dev/null; do sleep 120; done
echo "$(date '+%F %T') no heavy driver running; launching tested-member audit" >>"$LOG"
bash ~/swebench/run_band_tested_audit.sh
echo "$(date '+%F %T') tested-member audit finished" >>"$LOG"
