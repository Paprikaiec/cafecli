# 04: v2 headless answers a plain question

**Parent:** `.scratch/v2-architecture/PRD.md` (milestone 1; layers `ai`, `core`, `harness`) · ADR 0020 D1–D3, D5, D8
**Blocked by:** None (can start immediately)
**Status:** in-progress (04a done; 04b, 04c to go)

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

## Comments

**2026-10-20 — split.** This ticket is done in three reviewed sub-steps, one commit each: **04a** `ai` layer + fake
provider + ArchUnit scaffolding; **04b** `core` (agent log, `Agent`, `AgentLoop`, callbacks, events, typed outcome);
**04c** `harness` + composition root + `-Dcafecli.v2=true` entry + CLI. Boxes are ticked when 04c lands.

**2026-10-20 — 04a done (leader review).**

- `com.cafecli.ai`: `Provider` (stateless; never throws — failures are a terminal `StreamEvent.Failed`),
  `AssistantStream` (pull-based, iterate once, `AutoCloseable`, `complete()` throws `ProviderException` carrying the
  `ErrorKind`; exactly one terminal event; cancel → `Failed(ABORTED)` whose partial is exactly what was delivered),
  `StreamEvent` (14 records), `ErrorKind`, `CancellationToken` (with `onCancel` for adapters), `Model` (record +
  `Feature` flags — not "Capability", that is a harness term), `Context`, `ToolDefinition`, `StreamOptions`.
  `ai.message`: `Message` (sealed: `UserMessage` / `AssistantMessage` / `ToolResultMessage`, JSON id `role`),
  `ContentBlock` (sealed: `TextBlock` / `ThinkingBlock` / `ToolCall` / `ImageBlock`, JSON id `type`; role-restricted
  via `UserContent` / `AssistantContent`), `Usage`, `StopReason`. Tool-call arguments stay the raw JSON string.
- `ai.fake`: `FakeProvider.scripted(list)` / `.responding(fn)` + `requests()`; `FakeResponse` builder (≤3 deterministic
  deltas per text); `FakeScript.load(path)` — v1-expressible JSON format documented in its javadoc.
- `V2ArchitectureTest` (ArchUnit 1.5.1, test scope) in `-Pquick`, 4 rules: ai → nothing else in Cafecli; core → core
  + ai; no v2 class uses the v1 spine (`agent` / `llm` / `cli` / `hitl` / `runtime` packages + `memory.{MemoryManager,
  ContextCompressor, ConversationHistoryCompactor, ConversationMemory, TokenBudget}` + `tool.{ToolRegistry,
  ToolBatchExecutor}`); no property / env reads in v2 except `V2_CONFIG_LOADER` (empty until 04c). Each rule proven
  red by a planted probe (leader re-probed the first two).
- Review fixes applied: `ToolCallBlock` → `ToolCall` (glossary: 工具块 is render's call+result); `Emitter.abort()`;
  `Emitter.thinkingSignature(...)` so a signed thinking block can be produced; blank tool-call id/name rejected by
  the script loader (v1 drops them silently).
- Deferred with notes on the owning tickets: the `Emitter`'s one-open-block tool-call shape vs OpenAI index-keyed
  deltas (06); retry after streamed content doesn't reset the partial (22); fake-script limits for parity (28).
- Leader verified: `-Pquick` 1404/0/0/0.
