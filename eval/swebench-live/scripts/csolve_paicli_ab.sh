#!/bin/bash
# A/B PaiCLI solve inside a SWE-bench-Live image.
# Same jar (paicli-hooks.jar), same 80-turn cap, edit_file ON for BOTH arms.
# The ONLY difference is the 5 builtin-hook -D flags (treatment=hooks).
#   PAICLI_ARM=ctrl  -> Phase-0 config, hooks OFF (control)
#   PAICLI_ARM=hooks -> Phase-0 config + Finish Gate + Read-before-Write Ledger ON
# Repo at /testbed (HEAD==base_commit). Patch via `git diff HEAD`.
# Jar is Java-21 bytecode -> launch with mounted /opt/jdk21; leave default java/JAVA_HOME
# untouched for the agent's own build subprocesses. Audit -> /out/audit (mounted out).
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY 2>/dev/null || true
git config --global --add safe.directory '*' 2>/dev/null || true
cd /testbed || { echo "no /testbed" >/out/agent.err; echo "exit=3" >/out/exit.txt; exit 3; }

# Symmetric task framing (identical to Phase-0 / Claude arms).
{ printf '%s\n\n' "You are fixing a real issue in the Java repository at /testbed. Directly edit the source code (and tests if needed) to implement the fix NOW. Do not stop to ask for confirmation, do not only produce a plan — make the actual code changes."; printf 'ISSUE:\n'; cat /tmp/issue.md; } > /tmp/issue_wrapped.md

# Pick a JDK>=21 to LAUNCH the jar; prefer the mounted portable JDK.
PAICLI_JAVA=""
if [ -x /opt/jdk21/bin/java ]; then
  PAICLI_JAVA=/opt/jdk21/bin/java
else
  _jv=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
  if { [ "${_jv:-0}" -ge 21 ]; } 2>/dev/null; then PAICLI_JAVA=java; fi
fi
[ -z "$PAICLI_JAVA" ] && { echo "no JDK>=21 to launch jar" >/out/agent.err; echo "exit=66" >/out/exit.txt; exit 66; }
echo "PAICLI_JAVA=$PAICLI_JAVA :: $($PAICLI_JAVA -version 2>&1 | head -1)" >/out/paicli-java.txt

mkdir -p /out/audit

# Treatment flags: both builtin tenants need their event master switch + tenant switch.
HOOK_FLAGS=""
if [ "${PAICLI_ARM:-ctrl}" = "hooks" ]; then
  HOOK_FLAGS="-Dpaicli.hook.stop.enabled=true -Dpaicli.hook.finish_gate.enabled=true -Dpaicli.hook.pre_tool_use.enabled=true -Dpaicli.hook.post_tool_use.enabled=true -Dpaicli.hook.read_ledger.enabled=true"
fi
echo "PAICLI_ARM=${PAICLI_ARM:-ctrl} HOOK_FLAGS=[$HOOK_FLAGS]" >/out/arm.txt

# edit_file ON by default (no -Dpaicli.edit_file.disabled); hard iteration cap 80 (Phase-0).
timeout 2700 "$PAICLI_JAVA" -Dpaicli.react.hard.max.iterations=80 $HOOK_FLAGS \
  -Dpaicli.audit.dir=/out/audit \
  -jar /opt/paicli.jar run \
  --file /tmp/issue_wrapped.md --trace-file /out/trace.jsonl \
  >/out/answer.txt 2>/out/agent.err
echo "exit=$?" >/out/exit.txt

# capture all changes (incl. new files) as a HEAD diff
git add -A
git --no-pager diff --cached --text >/out/model.patch
wc -l </out/model.patch >/out/patch-lines.txt 2>/dev/null
