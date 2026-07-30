#!/bin/bash
# csolve_cafecli_noedit_trace.sh  — reproduce the no-edit / 50-turn regime, trace-only (no eval),
# to quantify "ended-without-testing" INSTANCE-AGNOSTICALLY for ADR 0012.
# Uses the SAME cafecli-hooks.jar (guaranteed --trace-file support) but:
#   -Dcafecli.edit_file.disabled=true  (write_file only, no surgical edit)
#   default hard iteration cap 50      (NO -Dcafecli.react.hard.max.iterations override)
#   hooks OFF
# This faithfully reproduces the tight regime the 2026-06-08 A/B measured, on the current agent.
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY 2>/dev/null || true
git config --global --add safe.directory '*' 2>/dev/null || true
cd /testbed || { echo "no /testbed" >/out/agent.err; echo "exit=3" >/out/exit.txt; exit 3; }
{ printf '%s\n\n' "You are fixing a real issue in the Java repository at /testbed. Directly edit the source code (and tests if needed) to implement the fix NOW. Do not stop to ask for confirmation, do not only produce a plan — make the actual code changes."; printf 'ISSUE:\n'; cat /tmp/issue.md; } > /tmp/issue_wrapped.md
CAFECLI_JAVA=/opt/jdk21/bin/java
[ -x "$CAFECLI_JAVA" ] || CAFECLI_JAVA=java
echo "CAFECLI_JAVA=$CAFECLI_JAVA :: $($CAFECLI_JAVA -version 2>&1 | head -1)" >/out/cafecli-java.txt
# no-edit regime: edit_file disabled, default 50-turn cap, hooks off.
timeout 2700 "$CAFECLI_JAVA" -Dcafecli.edit_file.disabled=true \
  -jar /opt/cafecli.jar run \
  --file /tmp/issue_wrapped.md --trace-file /out/trace.jsonl \
  >/out/answer.txt 2>/out/agent.err
echo "exit=$?" >/out/exit.txt
git add -A
git --no-pager diff --cached --text >/out/model.patch
wc -l </out/model.patch >/out/patch-lines.txt 2>/dev/null
