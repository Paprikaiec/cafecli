#!/bin/bash
# Canonical band wrapper (ADR 0013 / 2026-07-20 safe-edit decision): edit_file + 80 turns + safe_edit ON, hooks OFF.
# Container-side solver for band v1 runs. Derived from the safe-edit A/B TREATMENT arm
# (csolve_paicli_safe_edit_ab.sh with PAICLI_SAFE_EDIT_ARM=safe); no arm switching —
# this wrapper IS the canonical configuration. Lifecycle hooks stay OFF (no hook -D flags).
set -u

unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY 2>/dev/null || true
git config --global --add safe.directory '*' 2>/dev/null || true
cd /testbed || { echo "no /testbed" >/out/agent.err; echo "exit=3" >/out/exit.txt; exit 3; }

# Keep task framing byte-for-byte identical to Phase-0 / builtin-hook A/B / safe-edit A/B.
{
  printf '%s\n\n' "You are fixing a real issue in the Java repository at /testbed. Directly edit the source code (and tests if needed) to implement the fix NOW. Do not stop to ask for confirmation, do not only produce a plan — make the actual code changes."
  printf 'ISSUE:\n'
  sed -n '1,$p' /tmp/issue.md
} > /tmp/issue_wrapped.md

# Launch the Java-21 jar with the mounted portable JDK; leave the container's default
# java/JAVA_HOME untouched for the agent's own build subprocesses.
PAICLI_JAVA=""
if [ -x /opt/jdk21/bin/java ]; then
  PAICLI_JAVA=/opt/jdk21/bin/java
else
  _jv=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
  if { [ "${_jv:-0}" -ge 21 ]; } 2>/dev/null; then PAICLI_JAVA=java; fi
fi
[ -z "$PAICLI_JAVA" ] && { echo "no JDK>=21 to launch jar" >/out/agent.err; echo "exit=66" >/out/exit.txt; exit 66; }

mkdir -p /out/audit
{
  printf 'experiment=band-v1\n'
  printf 'arm=canonical\n'
  printf 'run_tag=%s\n' "${PAICLI_RUN_TAG:-unset}"
  printf 'safe_edit=true\n'
  printf 'hooks=off\n'
  printf 'hard_iterations=80\n'
  printf 'model=%s\n' "${DEEPSEEK_MODEL:-deepseek-v4-pro}"
  printf 'java=%s\n' "$($PAICLI_JAVA -version 2>&1 | head -1)"
  if command -v sha256sum >/dev/null 2>&1; then
    printf 'jar_sha256=%s\n' "$(sha256sum /opt/paicli.jar | awk '{print $1}')"
  fi
} >/out/experiment.txt

timeout 2700 "$PAICLI_JAVA" \
  -Dpaicli.react.hard.max.iterations=80 \
  -Dpaicli.safe_edit.enabled=true \
  -Dpaicli.audit.dir=/out/audit \
  -jar /opt/paicli.jar run \
  --file /tmp/issue_wrapped.md --trace-file /out/trace.jsonl \
  >/out/answer.txt 2>/out/agent.err
SOLVE_EXIT=$?
printf 'exit=%s\n' "$SOLVE_EXIT" >/out/exit.txt

# Capture all tracked and untracked changes as the submitted patch.
git add -A
git --no-pager diff --cached --text >/out/model.patch
wc -l </out/model.patch >/out/patch-lines.txt 2>/dev/null
