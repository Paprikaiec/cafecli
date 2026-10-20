# 04: v2 headless answers a plain question

**Parent:** `.scratch/v2-architecture/PRD.md` (milestone 1; layers `ai`, `core`, `harness`) · ADR 0020 D1–D3, D5, D8
**Blocked by:** None (can start immediately)
**Status:** ready-for-agent

**What to build:** With `-Dcafecli.v2=true`, `run --file issue.md` goes through the new `ai` → `core` → `harness`
spine and prints the model's answer as plain text. The model is the **fake provider**, selected in config together
with a script file, so the path can be demoed without an API key. Without the flag nothing changes.

- `ai`: provider-neutral messages (text, thinking with signature, tool call, and image blocks; system prompt passed
  separately); a pull-based event stream plus a `complete()` convenience; error kinds (retryable, overflow, aborted,
  fatal). The fake provider replays scripted responses (fixed, or a function of the incoming request), records every
  request it receives, and can emit any event or error kind.
- `core`: the agent log (sealed user / assistant / tool result plus a non-sealed custom branch); `Agent` and
  `AgentLoop`; every callback from the PRD with a default; the hard iteration cap; events that carry the agent id; a
  typed run outcome; core's own cancellation token. No user-facing strings anywhere in `core`.
- `harness`: `AgentSession` in synchronous mode; `AgentFactory` with the main-agent profile; a typed config loader
  covering only what this ticket needs, same sources and precedence as today; the Capability SPI skeleton (session
  hook and agent hook); the system prompt built once from the existing templates.
- Entry: the flag routes `run --file`; any other command under the flag prints a clear "not available in v2 yet"
  message and exits non-zero.
- Architecture tests: the PRD's allowed dependencies, no v2 import of the v1 spine, no property / environment reads in
  v2 outside the config loader. ArchUnit or a source-scanning test — pick one and use it for every rule.

- [ ] A CLI run with the flag and a fake-provider script prints the scripted answer.
- [ ] Seam 1: the recorded request holds the system prompt and the user's message; events arrive as run start →
      iteration start → message start / update / end → iteration end → run end; the outcome is typed "completed".
- [ ] Seam 1: a script that never stops ends at the hard cap with a typed "budget exhausted" outcome.
- [ ] Architecture tests run in `-Pquick` and pass; v1 tests are untouched and green.
