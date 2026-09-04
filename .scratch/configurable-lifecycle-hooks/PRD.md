# PRD: User-Configurable Lifecycle Hook Subsystem (配置化生命周期 Hook)

Status: ready-for-agent
Date: 2026-07-10
Decision record: ADR 0012 (supersedes ADR 0008's sequencing; ADR 0008's contract/layering conclusions retained)
Supersedes: `.scratch/lifecycle-hook-seam/PRD.md` (eval-driven, compile-gate-centric — Solution obsolete, Testing Decisions precedents retained)
Suggested branch: `feat/configurable-hooks` (do NOT build on `feat/async-task-substrate` — it carries three unpushed workstreams)

## Problem Statement

Two users share one missing capability.

**The SWE-bench-Live eval engineer**: failure attribution shows Cafecli's losses against Claude Code on the anti-contamination Java band are scaffolding, not model. Two recurring self-harm shapes remain unaddressed after safe-edit (Guard) landed:

1. **Ships without verifying.** Cafecli almost never runs tests (2026-06-08 A/B: 0 test runs on jackson/floci-164/floci-248 vs Claude's 3–8 per instance; jackson-5837 trajectory: "delivers on an assumption that turned out wrong, and nothing intercepted it"). The agent reaches the natural finish path and returns with unverified work.
2. **Writes files it never read.** Blind writes and stale edits produce failed `edit_file` matches and whole-file overwrites, burning the turn budget that decides instances like floci-164.

**The Cafecli user**: there is no way to attach their own policy or feedback to the agent's lifecycle — no "run my linter after every edit", no "always ask before git push", no "block writes containing secrets". Claude Code ships a full hook subsystem for this; Cafecli has only hardcoded internal behaviors (per-edit LSP diagnostics injection) with no user-facing extension point.

Both are the same gap: the ReAct main loop has no unified, extensible checkpoint at its lifecycle points.

## Solution

A **lifecycle hook subsystem (生命周期 Hook)**: a unified dispatcher at four lifecycle points — `PreToolUse`, `PostToolUse`, `Stop`, `UserPromptSubmit` — where **hook tenants (Hook 租户)** render model-feedback-level decisions: `CONTINUE`, `BLOCK(reason)` (reason fed back to the model, loop continues), `ALLOW` (skip HITL approval only), `ASK` (force HITL approval).

Tenants come in two forms with equal standing and one contract:

- **Builtin tenants** (in-process, session-state access). Wave 1 ships two, each targeting a measured failure shape: the **Read-before-Write Ledger (读前写台账)** blocks blind and stale writes at `PreToolUse`; the **Finish Gate (收尾门)** blocks finishing when the turn wrote files but ran no verification command afterward. Verification means commands the agent itself runs in its workspace — the gate never references any external scoring test set.
- **Shell tenants** (user-configured commands in `hooks.json`): exit 0 = continue, exit 2 = block with stderr as the reason, richer JSON on stdout as a strict superset. Two lines of contract turn any script into a policy or feedback hook, zero SDK.

**Guards stay absolute (策略守卫)**: a hook's ALLOW never bypasses PathGuard/CommandGuard — a deliberate deviation from Claude Code that extends the existing invariant "the user cannot approve a policy-denied request" to hooks. Project-level hook configs are untrusted until the user approves them, pinned by content fingerprint (**项目 Hook 信任**). Every hook execution is audited.

## User Stories

1. As an LLM agent, I want the Finish Gate to block my finish when I modified files but ran no verification command after the last write, so that I self-verify instead of delivering on an unchecked assumption.
2. As an LLM agent, I want the Finish Gate's block reason to tell me concretely what to do (run relevant tests/build in the workspace), so that I can recover within the same run.
3. As a SWE-bench eval engineer, I want the Finish Gate's verification detection to match only commands the agent itself ran in the workspace, never dataset scoring tests, so that pass-rate improvements are defensible against cheating suspicion.
4. As an LLM agent, I want the Finish Gate to block at most a bounded number of times per run, so that an unfixable failure cannot loop me forever — the hard iteration cap remains the ultimate backstop.
5. As an LLM agent, I want the Finish Gate to stay silent on read-only or pure-Q&A turns, so that finishes without writes are never gated.
6. As an LLM agent, I want the Read-before-Write Ledger to block my edit of a file I never read, with the reason fed back to me, so that I read first and my edits match real content.
7. As an LLM agent, I want a write to a file that changed since my last read to be blocked as stale, so that I re-read fresh content instead of clobbering concurrent changes.
8. As an LLM agent, I want writes creating new files to pass the ledger without a prior read, so that scaffolding work is unaffected.
9. As an LLM agent, I want my own successful write to refresh the ledger entry, so that consecutive edits to the same file don't force redundant re-reads.
10. As a SWE-bench eval engineer, I want both builtin tenants off by default and toggled by system properties (the safe-edit precedent), so that I can A/B their effect on the eval band before flipping any default.
11. As a SWE-bench eval engineer, I want audit/trace records of every hook firing, decision, and block count, so that I can attribute resolve-rate changes to specific tenants.
12. As a Cafecli user, I want to configure shell commands at lifecycle points via `hooks.json`, so that I can enforce my own workflow (lint after edit, protect paths, inject conventions) without waiting for Cafecli features.
13. As a Cafecli user, I want the exit-0/exit-2 contract to be sufficient for simple hooks, so that any two-line shell script works with zero SDK.
14. As a Cafecli user, I want to optionally emit JSON on stdout for richer decisions (ALLOW/ASK/additional context), so that advanced hooks aren't capped by exit codes.
15. As a Cafecli user, I want a PreToolUse hook to ALLOW whitelisted safe commands, so that I see fewer HITL prompts for operations I've already vetted.
16. As a Cafecli user, I want a PreToolUse hook to ASK on patterns I consider risky (e.g. `git push`), so that normally-unattended tools get an approval gate when I want one.
17. As a security auditor, I want hook ALLOW to never bypass PathGuard/CommandGuard, so that the invariant "no one can approve a policy-denied request" holds for hooks exactly as it does for users.
18. As a project maintainer, I want to commit a project-level `hooks.json`, so that everyone (and every agent) working in the repo gets the same policy hooks.
19. As a Cafecli user, I want project-level hook configs to be untrusted until I approve them, pinned by content fingerprint and re-prompted on any change, so that cloning a malicious repo cannot execute arbitrary commands on my machine.
20. As a Cafecli user, I want declining a project hook config to simply skip those hooks, so that I'm never locked out of my session.
21. As a security auditor, I want every hook execution recorded in the audit log (event, command, exit, decision, duration), so that hook-driven behavior is explainable after the fact.
22. As a Cafecli user, I want a hung or crashed hook to fail open (treated as CONTINUE) with a visible warning, so that a broken linter can't wedge my agent.
23. As a Cafecli user, I want ESC to kill a running hook process via the turn's cancellation token, so that cancellation stays responsive even mid-hook.
24. As a Cafecli user, I want hook activity shown via a status message through the renderer, so that I see what's running without hooks corrupting the transcript.
25. As a Cafecli user, I want `/hooks` to list loaded hooks with source, matcher, and trust state, so that I can inspect exactly what will run and why.
26. As a Cafecli user, I want a UserPromptSubmit hook to inject context or block my prompt before the agent runs, so that I can append team conventions automatically or stop an accidental secret paste.
27. As a Cafecli user, I want a PostToolUse hook to inject additional context after a tool runs (advisory, non-blocking), so that lint results reach the model the same way per-edit LSP diagnostics already do.
28. As a subagent (task), I want to inherit the parent's hooks through the child registry with my blocks attributed to my task id, so that spawned agents are governed identically and audits stay attributable.
29. As a future contributor, I want builtin and shell tenants to share one decision contract on one dispatcher, so that adding a tenant never touches the main loop.
30. As a future contributor, I want the contract schema to reserve `updatedInput`, `updatedToolOutput`, and async fields now (dispatcher warns-and-ignores), so that wave 2 is purely additive.
31. As a future contributor, I want the matcher to be a tool-name regex evaluated before spawning anything, so that non-matching hooks cost nothing.
32. As a Cafecli product, I want the dispatcher to be a zero-cost no-op when nothing is registered, so that the default interactive path pays nothing.
33. As a SWE-bench eval engineer, I want hooks to work identically in headless `run` mode, where unpinned project hooks are skipped rather than prompted, so that eval uses the same code path without interactive trust dialogs.
34. As a Cafecli user, I want user-level and project-level `hooks.json` merged with `${VAR}` expansion exactly like `mcp.json`, so that configuration behaves the way I already know.
35. As an LLM agent, I want BLOCK reasons delivered clearly marked as gate feedback (not tool facts), so that I treat them as instructions to continue working rather than as environment output.
36. As a Cafecli user, I want unknown `/hooks` subcommands rejected at the CLI layer per the repo convention, so that typos don't fall through to the agent.

## Implementation Decisions

**Architecture (ADR 0012, accepted — do not relitigate)**

- One `HookDispatcher` with per-event ordered tenant lists; wave-1 events: `PreToolUse`, `PostToolUse`, `Stop`, `UserPromptSubmit`. Execution order: builtin → user-level → project-level. Composition is most-restrictive-wins: first BLOCK short-circuits; ASK outranks ALLOW; feedback/context strings aggregate.
- `HookDecision` outcomes: `CONTINUE` / `BLOCK(reason)` / `ALLOW` / `ASK`, plus `additionalContext` and `statusMessage`. Single BLOCK verb — Claude Code's legacy dual channel (`decision: block` + `permissionDecision: deny`) is deliberately not replicated. Schema reserves `updatedInput`, `updatedToolOutput`, and async fields; the dispatcher warns and ignores them in wave 1.
- `HookPayload` is JSON-serializable: event, session id, task id (null = main agent), project root, tool name, raw tool input JSON (the established JsonNode channel), tool output (PostToolUse; oversized outputs delivered as digests consistent with MCP result persistence), turn tool summary (Stop: per-tool name + target), user prompt (UserPromptSubmit), and current block count (the Stop anti-loop flag).
- Guards absolute, both directions: ALLOW only skips the HITL approval prompt; PathGuard/CommandGuard always run at execution time. BLOCK returns before approval is ever requested.

**Dispatch points**

- PreToolUse dispatches at the top of the HITL registry's execution path, before approval routing; ALLOW/ASK map onto the existing approval short-circuits; child registries (subagents) share the parent's dispatcher and approval pump, with caller context labeling.
- PostToolUse dispatches after tool execution inside the tool registry execution path, covering all three call paths including parallel batches.
- Stop dispatches on the agent's natural finish branch (no tool calls); a BLOCK injects the reason as a marked message (same channel precedent as LSP diagnostics injection) and continues the loop; blocks consume iterations so the hard iteration cap always wins.
- UserPromptSubmit dispatches in the CLI main path before the agent run, after mention/path expansion.

**Shell tenant contract (CC-compatible)**

- Spawn command with payload JSON on stdin, `CAFECLI_PROJECT_DIR` in the environment. Exit 0 = CONTINUE, and stdout may carry a JSON decision superset; exit 2 = BLOCK with stderr as reason; any other exit = user-visible warning, never blocks.
- Per-hook timeout (default 60s, hard cap 10min). Timeout and crash both fail open: treated as CONTINUE plus a user-visible warning.
- Hook processes register with the current turn's cancellation token; ESC destroys the process.
- Hooks run synchronously on the calling agent's thread (main serial executor or task virtual thread) and never read the terminal; trust confirmation goes through the approval pump; status messages go through the renderer stream.

**Configuration and trust**

- `hooks.json` at user level and project level, merged with the same precedence and `${VAR}` expansion machinery as `mcp.json`. Schema shape mirrors Claude Code: per-event list of `{matcher, hooks: [{type: "command", command, timeout, statusMessage}]}`. Matcher is a tool-name regex; absent matcher = all occurrences of the event. Only `type: "command"` in wave 1.
- Hook commands are exempt from CommandGuard and per-execution HITL (accepted decision): guards defend against model-authored actions, hooks are user-signed configuration (trust level of `.git/hooks`).
- Project Hook Trust: on first load, the project config's canonical content is fingerprinted (SHA-256) and the user approves via the approval pump; the pin lives in a user-level trust file; any content change re-prompts; declining skips project hooks for the session. Headless `run` mode never prompts: unpinned project hooks are skipped with a trace note. Builtin and user-level tenants are trusted by default.
- Every execution writes an audit entry (event, hook identity/command, exit, decision, duration), with new audit outcome types for hook block/allow/ask.

**Builtin tenants**

- Read-before-Write Ledger: per-agent scope, naturally aligned with per-task tool registries. Records path + observed file state on successful reads; on write-tool execution, blocks unread targets ("read the file first") and stale targets ("file changed since last read; re-read"); new-file creation passes; successful writes refresh the entry.
- Finish Gate: tracks the run's successful write-tool set; on Stop, if writes exist and no successful verification command executed after the last write, BLOCK with an actionable reason. Verification detection is a configurable command-regex heuristic (default covers mvn/mvnw/gradle/gradlew/npm test/pytest/go test and similar); the default block cap is small (2). The gate is never parameterized per eval instance.
- Both tenants gated by system properties, default off (safe-edit precedent); eval harness enables them; defaults may flip later with evidence.

**CLI**

- `/hooks` lists loaded hooks (source, matcher, trust state, recent decisions). Command entry follows the repo's command-linkage rule (parser, main loop, completer, docs, tests). Unknown subcommands error at the CLI layer.

## Testing Decisions

Good tests assert **external behavior only**: which tool executed or didn't, the exact feedback string fed back to the model, whether the loop ran an extra iteration, what landed in the audit log, what the next LLM request history contains — never dispatcher internals or private tenant state.

Seven seams (4 existing, 3 new; confirmed with the user):

1. **`HookDispatcher` public API (new unit seam)** — registration order, most-restrictive composition (BLOCK short-circuit, ASK > ALLOW), aggregation, empty-registry no-op. Precedent: `AgentBudgetTest`-style pure unit tests of loop support classes.
2. **`ShellHook` executor (new unit seam)** — temp shell scripts exercising exit 0 / exit 2 + stderr / JSON stdout superset / timeout fail-open / cancellation kill. Never real build tools. Precedent: the prior PRD's controlled-script approach for gate commands.
3. **Hooks config loader + trust pinning (new unit seam)** — user+project merge, `${VAR}` expansion, matcher parsing; trust states: unpinned → approval requested, pinned → silent, changed → re-prompt, declined → project hooks skipped, headless → skipped without prompt. Precedent: `McpConfigLoaderTest`.
4. **HITL registry execution path (existing seam)** — PreToolUse BLOCK short-circuits without execution and audits; ALLOW skips the approval prompt but a Guard-denied path still denies; ASK forces approval on a normally-unattended tool; child registry inherits the dispatcher with task attribution. Precedent: `HitlToolRegistryTest` + `ApprovalPumpTest`.
5. **Tool registry execution path, read-ledger (existing seam)** — blind write blocked; read-then-write passes; external modification between read and write blocks as stale; new-file write passes; own write refreshes. Real temp files. Precedent: `ToolRegistryTest` with `@TempDir`.
6. **`Agent.run()` + scripted LLM client, finish-gate (existing seam, highest)** — script: write file → attempt finish → blocked with injected reason → run test command → finish; assert the extra iteration, the injected message content, and the block cap; disabled/no-write/already-verified variants finish normally. Give the recorded client enough responses to survive each block. Precedent: `AgentLspDiagnosticsTest` (`RecordingClient`, asserting Nth-round request history).
7. **CLI command parsing, `/hooks` (existing seam)** — subcommand parsing plus the unknown-command rule. Precedent: `CliCommandParserTest`.

Mode gating is asserted in seams 4/5/6 via `System.setProperty` in both on and off states (the `cafecli.react.*` precedent). Audit assertions fold into seams 4/5 (precedent: `AuditLogTest`). Determinism: hook commands in tests are always `true`/`false`/temp scripts.

## Out of Scope

- **`updatedInput` / `updatedToolOutput`** — schema-reserved, not implemented (wave 2; the latter shares a seam with MCP result persistence).
- **Async hooks (`async`/`asyncRewake`)** — wave 2; pairs naturally with the event loop's task-done push, deliberately cut now.
- **`once` / `if`-prefilter / non-command hook types (`prompt`, `agent`, `http`)** — `agent`-type verifier hooks (reusing `spawn_agent`) are the wave-2 north star, not wave 1.
- **SubagentStart/Stop events** — payload already carries task id; wiring is a later pure increment.
- **Migrating existing behaviors onto hooks** — safe-edit stays a PathGuard rule (the Guard-vs-Hook layering is deliberate); per-edit LSP diagnostics injection stays hardcoded (a candidate future tenant, not this wave).
- **Flipping builtin tenants on by default** (interactive or eval) — needs eval evidence first.
- **Compile-gate** — rejected by Phase 0 evidence; do not resurrect.
- **Hooks in `serve --http`** — wave 1 covers interactive and headless `run` only.
- **Managed/enterprise hook policy** (CC's `allowManagedHooksOnly` equivalent).

## Further Notes

- Decisions: ADR 0012 (accepted; four-outcome vocabulary, guards absolute, trust model, fail-open — user-confirmed 2026-07-10). Terminology: CONTEXT.md entries 生命周期 Hook / 策略守卫 / Hook 租户 / 项目 Hook 信任 / 读前写台账 / 收尾门.
- Evidence: 2026-06-08 A/B report (Cafecli runs ~0 tests vs Claude 3–8 per instance), 2026-06-07 jackson-5837 trajectory, 2026-06-26 Phase 0 rebaseline (kills compile-gate, motivates safe-edit — already shipped). A quantitative "ended without testing" count across existing traces is pending cloud-machine access; add it to ADR 0012's evidence when available.
- Resume narrative anchor: hook subsystem as mechanism, SWE-bench-Live failure taxonomy as demand-side evidence, one measured failure shape per tenant, before/after eval numbers on the Phase 0 band.
- Docs linkage on implementation (repo hard rules): AGENTS.md + README for the new subsystem and `/hooks`; ROADMAP Phase 23 status update; `.env.example` only if any env-based toggle is added.
- Code anchors drift; verify at implementation time rather than trusting this PRD: the HITL execution path, the agent finish branch, and the tool-registry execution entry were last verified 2026-07-10.
