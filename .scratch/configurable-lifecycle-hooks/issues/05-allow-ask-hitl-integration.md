# 05: ALLOW/ASK permission integration + subagent attribution

Status: ready-for-human
Type: AFK

## Parent

`.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012)

## What to build

Activate the two permission-flavored outcomes at the HITL boundary — the most delicate integration in the subsystem, and the seat of the accepted security decision: **guards stay absolute**.

`ALLOW` skips the HITL approval prompt and nothing else — PathGuard/CommandGuard still run at execution time, so a hook-allowed call into a guard-denied path is still denied (deliberate deviation from Claude Code; the invariant "no one can approve a policy-denied request" now covers hooks). `ASK` forces an approval prompt even for tools that normally run unattended and even under a session-wide approve-all. Composition across multiple hooks is most-restrictive: any BLOCK short-circuits; ASK outranks ALLOW.

Shell hooks express these outcomes through the JSON-stdout superset (slice 03's parser already produces the decision; this slice makes the registry honor it). Child registries (subagents) inherit the parent's dispatcher; hook decisions and audit entries for subagent tool calls carry the task attribution, consistent with how approval requests already label their requester.

## Acceptance criteria

- [x] ALLOW on a normally-approval-requiring tool: no prompt, tool executes, audit records the hook allow
- [x] ALLOW + guard-denied target: still policy-denied — test proves a hook cannot lift a PathGuard/CommandGuard denial
- [x] ASK on a normally-unattended tool: approval prompt appears (through the pump, main-thread rendering only); ASK wins over an active approve-all
- [x] Multiple hooks on one event compose most-restrictive (BLOCK > ASK > ALLOW > CONTINUE)
- [x] A shell hook returning `{"outcome": "allow"}` / `{"outcome": "ask"}` via stdout JSON drives the above end-to-end
- [x] Subagent tool calls dispatch the same hooks; their decisions/audits are attributed to the task id
- [x] Covered at seam 4 (`HitlToolRegistryTest` + `ApprovalPumpTest` precedents)
- [x] AGENTS.md HITL/policy section updated (hard rule 5.4)

## Blocked by

- 02 (`02-pre-post-tooluse-read-ledger.md`) — PreToolUse wiring
- 03 (`03-shellhook-config-loading.md`) — JSON stdout decision parsing
