# 04: v2 headless answers a plain question

**Parent:** `.scratch/v2-architecture/PRD.md` (milestone 1; layers `ai`, `core`, `harness`) · ADR 0020 D1–D3, D5, D8
**Blocked by:** None (can start immediately)
**Status:** done (2026-10-20)

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

- [x] A CLI run with the flag and a fake-provider script prints the scripted answer.
- [x] Seam 1: the recorded request holds the system prompt and the user's message; events arrive as run start →
      iteration start → message start / update / end → iteration end → run end; the outcome is typed "completed".
- [x] Seam 1: a script that never stops ends at the hard cap with a typed "budget exhausted" outcome.
- [x] Architecture tests run in `-Pquick` and pass; v1 tests are untouched and green.

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

**2026-10-20 — 04b done (leader review).**

- `com.cafecli.core`: `Agent` (builder: id, provider, model, system prompt, hard cap — no default, callbacks;
  `run(UserMessage, ai.CancellationToken) → RunOutcome`, `post(CustomEntry)` thread-safe, `subscribe(...)`, `log()`
  snapshot), package-private `AgentLoop` (one per run) and `ToolBatch` (placeholder: every call → `未知工具: <name>`
  error result, v1's exact text; ticket 05 replaces it), `AgentCallbacks` (all defaulted: `onRunStart`,
  `onIterationBoundary`, `transformContext`, `convertToLlm`, `prepareRequest`, `onIterationEnd`, `onContextOverflow`),
  `IterationDecision` (sealed: `Proceed` / `End` / `Exhausted(BudgetReason)`), `RunOutcome` (sealed: `Completed` /
  `Cancelled` / `BudgetExhausted(reason)` / `Failed(kind, detail)`), `BudgetReason` (open; core's own:
  `IterationCap`), `AgentEvent` (sealed; every event carries `Origin(agentId)` — task id goes there in 17).
  `core.log`: sealed `LogEntry` (`UserEntry` / `AssistantEntry` / `ToolResultEntry` wrap the `ai` messages; non-sealed
  `CustomEntry`), `AgentLogJson(Map<typeId, Class>)` with JSON id `kind`; unregistered or ambiguous kinds rejected.
- Decisions: reuses `ai.CancellationToken` (no separate core token — nothing needs different semantics); the run's
  token goes straight into the request, so **cancel aborts mid-stream** (PRD intentional change 9, added in this
  review); only core writes the built-in kinds; a failed or cancelled answer is never logged (even one that finished
  streaming), and an answer is appended together with its tool results after the batch, so the log is always valid
  to send; overflow → callback once per iteration, retry once only if it appended something, retry not counted
  against the cap; inbox drained before every request including the first.
- Review fixes applied: iteration-boundary writer seam (ADR D4); typed budget stop for iteration-end participants;
  cancel after a finished stream → `Cancelled`, nothing logged; `Failed.detail` may be shown by the UI.
- Deferred with notes: undelivered inbox entries at run end (09 / 18 / 23); unknown-tool text ownership (05);
  skill-body placement vs `onRunStart` (14); autonomous runs need a `run` variant (23); final model / system prompt
  (11 / 21); render must show a half-streamed answer on cancel (25). The `AgentLoop` ↔ `Agent` accessor coupling
  (8 package-private accessors) was judged acceptable for now; tighten it if 05 grows the loop.
- Leader verified: `-Pquick` 1431/0/0/0; `core` has no print / property / env / render use.

**2026-10-20 — 04c done; ticket closed (leader review).**

- `com.cafecli.harness`: `AgentSession` (sync facade: `submit(String) → RunOutcome`, `subscribe`, `cancel`,
  `awaitIdle`, `log`; `MAIN_AGENT_ID = "main"`), `AgentFactory` (every agent from an `AgentProfile`; capabilities
  attach in the session's declared order; unattached contribute nothing), `AgentProfile`, Capability SPI
  (`Capability.attachSession(SessionRegistrar)` / `attachAgent(AgentScope)`; implemented registrations: session event
  listeners, run-start log writers). `harness.config`: `ConfigLoader.load(ConfigSources) → HarnessConfig` — inputs are
  explicit maps / paths, no globals.
- `com.cafecli.app` (composition root; nothing depends on it; it may not use the v1 spine): `SystemEntry` — the
  **only** v2 class that reads JVM globals; it installs v1's `cafecli.* → paicli.*` property bridge (reused leaves
  read only `paicli.*`), builds `ConfigSources`, and calls `Cli` (all user-facing text; returns an exit code).
  `RunArgs` copies v1's `RunCommand` parsing (v2 may not import `cli`). `Composition` wires provider → capabilities →
  session.
- Flag: v1 `Main.main` delegates to `SystemEntry` first thing when `-Dcafecli.v2=true`. Under the flag: `run --file`
  works with the fake provider (`-Dcafecli.provider=fake` / `CAFECLI_PROVIDER`, `-Dcafecli.fake.script` /
  `CAFECLI_FAKE_SCRIPT`; documented in `docs/agents-reference.md`); outcomes print **exactly as v1** (answer or v1's
  `❌` / `⏹️` text on stdout, exit 0); `--trace-file`, other commands, bare launch → "not available in v2 yet" (stderr,
  exit 2); `serve --http` → "removed in v2". Fake model: `Model("fake-model","fake","fake",128_000,8_192,{})`.
- Config precedence matches v1 (differential-tested by the implementer: 100 cafecli×paicli combinations, 7
  `config.json` shapes). The cafecli → paicli fallback now has one implementation: pure overloads in
  `config/ConfigHome` (`env(Map,…)`, `property(Map,…)`, `installPropertyAliases(Properties)`) that v1's methods
  share — behavior-preserving.
- System prompt built once per agent from `prompt/PromptAssembler` with `PromptContext.empty()` — byte-identical to
  v1's `buildSystemPrompt("")`. Remaining hidden input in that leaf: the date / default time zone.
- `V2ArchitectureTest`: 8 rules — ai alone; core → ai; harness not → ui / app; nothing → app; ui → only
  ui/harness/core/ai/render and never the core engine (`Agent` / `AgentCallbacks` / `IterationDecision`); no v1
  spine in v2 (incl. app); no property / env reads except `V2_SYSTEM_ENTRY`. All proven red by probes.
- New glossary entry: 「Agent 配置档（Agent profile）」.
- Leader verified: `-Pquick` 1479/0/0/0; `-Pphase16-smoke` 456/0/0/0; real jar: scripted answer (exit 0, empty
  stderr), hard cap printed on stdout with exit 0, v1 path without the flag unchanged.
- Deferred with notes: submit result shape for steering / hook block (09 / 10); factory reachability for `/clear` and
  `spawn_agent`, provider per model (11 / 17); v1's provider fallback-by-API-key and `.env` reading (06).
