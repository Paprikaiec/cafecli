# 03: ShellHook executor + hooks.json loading (user-level)

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012)

## What to build

The first external tenant (Hook 租户): user-configured shell commands loaded from `hooks.json` and executed through the same dispatcher contract as the builtin tenants. This slice wires **user-level config only**; project-level configs are parsed but skipped with a trace note (trust is slice 04).

Config: `hooks.json` schema mirrors Claude Code — per-event list of `{matcher, hooks: [{type: "command", command, timeout, statusMessage}]}`. Matcher is a tool-name regex evaluated before spawning (absent = all occurrences); only `type: "command"` is accepted. Merge precedence and `${VAR}` expansion reuse the `mcp.json` machinery.

Executor: spawn the command with the payload JSON on stdin and `PAICLI_PROJECT_DIR` in the environment. Exit 0 = CONTINUE, stdout may carry a JSON decision superset (parsed into the same `HookDecision`); exit 2 = BLOCK with stderr as reason; any other exit = user-visible warning, never blocks. Per-hook timeout (default 60s, cap 10min); timeout and crash both **fail open** (CONTINUE + visible warning). The process registers with the current turn's cancellation token so ESC kills it. Hooks run synchronously on the calling agent's thread, never read the terminal; `statusMessage` renders through the renderer stream.

Hook commands are exempt from CommandGuard and per-execution HITL (accepted in ADR 0012 — do not "fix" this).

## Acceptance criteria

- [ ] Executor unit seam (seam 2, temp scripts only — never real build tools): exit 0 / exit 2 + stderr reason / JSON stdout superset / non-zero-non-2 warning / timeout fail-open with warning / kill on cancellation
- [ ] Config loader seam (seam 3, `McpConfigLoaderTest` precedent): user-level load, `${VAR}` expansion, matcher regex filtering, malformed entries rejected with a clear message, project-level entries parsed but inert
- [ ] A user-level exit-2 hook on a `PreToolUse` matcher demonstrably blocks the matched tool with the script's stderr as the model-visible reason
- [ ] Payload arrives on stdin as the JSON shape defined in slice 01; `PAICLI_PROJECT_DIR` set
- [ ] `statusMessage` appears via the renderer during execution; no bare stdout writes
- [ ] Matcher evaluated before spawn — non-matching tools spawn nothing
- [ ] `hooks.json` format documented (README/AGENTS.md)

## Blocked by

- 01 (`01-dispatcher-stop-finish-gate.md`) — contract + dispatcher
- 02 (`02-pre-post-tooluse-read-ledger.md`) — PreToolUse/PostToolUse wiring
