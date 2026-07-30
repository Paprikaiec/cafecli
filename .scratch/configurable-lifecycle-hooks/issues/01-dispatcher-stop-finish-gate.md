# 01: HookDispatcher + full decision contract + Stop wiring + Finish Gate

Status: ready-for-human
Type: AFK

## Parent

`.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012)

## What to build

The first tracer bullet: the complete hook contract and dispatcher, wired to exactly one event (`Stop`), carrying one builtin tenant (the Finish Gate / 收尾门), demoable end-to-end behind a system property.

The contract is the expensive part — define it completely now even though `Stop` only uses BLOCK: all four outcomes (`CONTINUE` / `BLOCK(reason)` / `ALLOW` / `ASK`), `additionalContext`, `statusMessage`, and reserved fields (`updatedInput`, `updatedToolOutput`, async) that the dispatcher warns about and ignores. `HookPayload` is JSON-serializable (event, session id, task id, project root, tool name, raw tool input, turn tool summary for Stop, current block count).

Dispatcher semantics: per-event ordered tenant list (builtin → user → project), first BLOCK short-circuits, ASK outranks ALLOW, feedback/context aggregate, zero-cost no-op when empty.

Stop dispatch sits on the agent's natural finish branch (no tool calls). A BLOCK injects the reason as a clearly-marked message — same channel precedent as LSP diagnostics injection — and continues the loop. Blocks consume iterations; the hard iteration cap always wins.

Finish Gate tenant: fires only when the run had at least one successful write tool AND no successful verification command executed after the last write. Verification detection is a configurable command-regex heuristic (default covers mvn/mvnw/gradle/gradlew/npm test/pytest/go test and similar) over the agent's own `execute_command` calls — **never references any external scoring test set, never parameterized per eval instance**. Block cap defaults to 2.

## Acceptance criteria

- [x] Contract types cover all four outcomes plus `additionalContext`/`statusMessage`; reserved fields present in the schema and warn-ignored by the dispatcher
- [x] `HookPayload` serializes to JSON (shell tenant slice depends on this shape)
- [x] Dispatcher unit seam (seam 1, `AgentBudgetTest`-style): registration order, BLOCK short-circuit, ASK > ALLOW composition, context aggregation, empty-registry no-op
- [x] Stop BLOCK injects a marked reason message and the loop runs an extra iteration; hook blocks never bypass the hard iteration cap
- [x] Finish Gate: silent on read-only turns; silent when a matching verification command succeeded after the last write; blocks with an actionable reason otherwise; respects the block cap
- [x] Verification regex configurable via system property; gate and tenant off by default, enabled via system property (safe-edit precedent)
- [x] Hook blocks recorded in the audit log with a hook-specific outcome type
- [x] Agent-level seam (seam 6, `AgentLspDiagnosticsTest` precedent): scripted client write → finish attempt → blocked → test command → finish; asserts extra iteration, injected content, block cap; disabled/no-write/already-verified variants finish normally
- [x] New system properties documented (AGENTS.md/README per hard rules)

## Blocked by

None - can start immediately
