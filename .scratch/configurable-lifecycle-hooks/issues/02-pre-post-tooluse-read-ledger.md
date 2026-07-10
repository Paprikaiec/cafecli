# 02: PreToolUse/PostToolUse wiring + Read-before-Write Ledger

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012)

## What to build

Wire the two tool-lifecycle events and land the second builtin tenant, the Read-before-Write Ledger (读前写台账), behind a system property.

`PreToolUse` dispatches at the top of the HITL registry's execution path, before approval routing. This slice implements only the BLOCK path (ALLOW/ASK routing is slice 05): a BLOCK returns the reason as a clearly-marked tool result without executing the tool, before any approval is requested, and is audited. `PostToolUse` dispatches after tool execution inside the tool registry execution path so it covers all call paths including parallel tool batches; `additionalContext` from PostToolUse aggregates into the existing next-turn injection channel (LSP diagnostics precedent).

Ledger tenant: per-agent scope (aligned with per-task tool registries). Successful reads record path + observed file state. On write-tool execution: unread existing target → BLOCK "read the file first"; target changed since last read → BLOCK stale, "re-read"; new-file creation → pass; own successful write refreshes the entry. Guards are unaffected — ledger blocks happen at the hook layer, PathGuard still runs at execution time.

## Acceptance criteria

- [ ] PreToolUse BLOCK: tool does not execute, no approval prompt appears, reason comes back as a marked tool result, audit entry written
- [ ] PostToolUse fires on all call paths including parallel batches; `additionalContext` reaches the next LLM request
- [ ] Ledger semantics (seam 5, `ToolRegistryTest` + `@TempDir`): blind write blocked / read-then-write passes / external modification between read and write blocks as stale / new-file write passes / own write refreshes
- [ ] Ledger scope is per agent: a subagent's reads do not satisfy the main agent's ledger and vice versa
- [ ] Tenant off by default, enabled via system property
- [ ] HITL-boundary BLOCK behavior covered at seam 4 (`HitlToolRegistryTest` precedent)
- [ ] New system properties documented

## Blocked by

- 01 (`01-dispatcher-stop-finish-gate.md`) — contract + dispatcher
