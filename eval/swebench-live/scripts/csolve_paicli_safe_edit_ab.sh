#!/bin/bash
# Container-side solver for the repeated safe-edit A/B.
# Both arms use the same jar, prompt, model, 80-turn cap, and hook defaults.
# The only treatment variable is paicli.safe_edit.enabled=false|true.
set -u

unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY 2>/dev/null || true
git config --global --add safe.directory '*' 2>/dev/null || true
cd /testbed || { echo "no /testbed" >/out/agent.err; echo "exit=3" >/out/exit.txt; exit 3; }

ARM="${PAICLI_SAFE_EDIT_ARM:-ctrl}"
REPEAT="${PAICLI_REPEAT:-1}"
case "$ARM" in
  ctrl) SAFE_EDIT_VALUE=false ;;
  safe) SAFE_EDIT_VALUE=true ;;
  *) echo "invalid PAICLI_SAFE_EDIT_ARM=$ARM" >/out/agent.err; echo "exit=64" >/out/exit.txt; exit 64 ;;
esac

# Keep task framing byte-for-byte identical to the Phase-0 and builtin-hook A/B.
{
  printf '%s\n\n' "You are fixing a real issue in the Java repository at /testbed. Directly edit the source code (and tests if needed) to implement the fix NOW. Do not stop to ask for confirmation, do not only produce a plan — make the actual code changes."
  printf 'ISSUE:\n'
  sed -n '1,$p' /tmp/issue.md
} > /tmp/issue_wrapped.md

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
  printf 'experiment=safe-edit-ab\n'
  printf 'arm=%s\n' "$ARM"
  printf 'repeat=%s\n' "$REPEAT"
  printf 'safe_edit=%s\n' "$SAFE_EDIT_VALUE"
  printf 'hard_iterations=80\n'
  printf 'model=%s\n' "${DEEPSEEK_MODEL:-deepseek-v4-pro}"
  printf 'java=%s\n' "$($PAICLI_JAVA -version 2>&1 | head -1)"
  if command -v sha256sum >/dev/null 2>&1; then
    printf 'jar_sha256=%s\n' "$(sha256sum /opt/paicli.jar | awk '{print $1}')"
  fi
} >/out/experiment.txt

timeout 2700 "$PAICLI_JAVA" \
  -Dpaicli.react.hard.max.iterations=80 \
  -Dpaicli.safe_edit.enabled="$SAFE_EDIT_VALUE" \
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

