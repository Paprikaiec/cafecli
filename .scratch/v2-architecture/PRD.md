# PRD: v2 architecture — ai / core / harness / ui

Status: ready-for-agent
Date: 2026-10-20
Branch: TBD (suggest `feat/v2-architecture`)
Decision record: **ADR 0020** (to be written from this PRD; until it exists, this PRD is the record of the decisions
and the rejected options below). Respects ADR 0001 (per-file mutation lock), 0003 (tool input JsonNode channel),
0005 (event loop), 0006 (virtual threads + admission), 0008 / 0012 (lifecycle hooks, guards absolute), 0009 (MCP result
referencing), 0010 / 0014 / 0016 / 0017 (render — reused untouched), 0015 (Plan-and-Execute and Lanterna stay retired),
0018 (snapshot binding), 0019 (typed tool outcome). **Reshapes ADR 0007**: per-task services move from per-task
`ToolRegistry` instances to agent-scoped capability state (see "Capability SPI").

## Vocabulary used in this PRD

Terms from `CONTEXT.md` keep their meaning (Tool Batch, Guard, Lifecycle Hook, Hook Tenant, Read-before-Write Ledger,
Finish Gate, Capability Closure, Task, Foreground / Background spawn, Attached / Detached, Cancel key, Live region,
Approval response).

**Glossary terms that describe designs, not current behavior** (verified against the code on 2026-10-20 — code wins,
per AGENTS.md's priority order):

- **Capability Closure** — not implemented: every tool group is registered unconditionally, and a tool whose
  collaborator is missing returns an "unavailable" message when called (the glossary's _Avoid_ form). v2 makes it
  real as a side effect of the attach model; listed under "Intentional behavior changes".
- **Deferred MCP Tool / Activated Tool Set / `tool_search`** — not implemented (design only, in
  `docs/superpowers/specs/`): every connected MCP tool is exposed eagerly. v2 keeps eager exposure; out of scope.
- **Skill Instruction Injection** — not implemented as defined: skill bodies are prepended to the **next real user
  input** (the glossary's _Avoid_ form). v2 ports today's behavior as a run-start log writer; the glossary's
  position is a later feature.

New terms:

- **Run** — one user submission (or one autonomous push) processed until the final answer. This is CONTEXT.md's
  「回合」. pi calls it an "agent run".
- **Iteration** — one LLM request plus the Tool Batch it returns. pi calls this a "turn"; this PRD never uses "turn"
  for it, to avoid colliding with 「回合」.
- **Agent log** — the append-only, typed record of everything that happened in an agent's conversation. It is the
  source of truth; it is **never** the request itself. Not the render package's ordered commit log.
- **Projection** (CONTEXT.md: 上下文投影 / Context projection) — the request built from the agent log for one
  iteration. Not the render package's width-dependent layout, which CONTEXT.md also loosely calls 投影.
- **Custom message kind** — an agent-log entry type defined outside `core` (memory recall, LSP diagnostics, task
  notification, compaction summary, …), which declares how (and whether) it is shown to the model.
- **Projection stage** — a pure function from agent log to request view, run on every iteration, never writing.
- **Log writer** — a component that runs at a defined moment and **appends** entries to the agent log.
- **Boundary inbox** — one thread-safe queue of agent-log entries drained at the iteration boundary.
- **Harness** — the product layer: everything that makes the generic agent a coding CLI.
- **Auxiliary invocation** — a harness- or tool-initiated tool call using the same executable snapshot and permission
  route, with its own completion observation but no independent model tool-result entry (ticket 15 refinement).
- **Capability** — one harness area (MCP, skills, memory, tasks, …) that plugs in through the internal Capability SPI.
  Capability Closure (CONTEXT.md) becomes: a capability that is not attached to an agent contributes nothing to it.

## Problem Statement

I want to keep developing Cafecli in six directions — context, compaction, memory, tools, multi-agent, and the agent
loop — and use it to catch up with how mainstream agent CLIs (pi, Claude Code, Codex) are built. Today every one of
those directions runs through the same class, so I can't change one without touching the others:

1. **One class is the loop, the context assembler, the memory client, the hook dispatcher, and a renderer.** The core
   `Agent` is ~1400 lines. Its run method is the ReAct loop, and about 370 lines of it are a stream renderer. It
   returns user-facing strings (cancelled / failed messages with emoji) and calls the renderer directly, so the engine
   can't be tested without a renderer and the UI can't be swapped.
2. **Context engineering is nine hand-wired, in-place edits of the request.** Image pruning, rewriting the system
   prompt with memory recall **every run**, prepending skill bodies, the freshness web search, LSP diagnostics, hook
   context, steering, compaction, and Finish Gate stop-blocks each have their own injection method inside the loop.
   Adding a stage means editing the loop. Rewriting the system prompt every run changes the request prefix, which
   defeats prompt caching on every provider that has it.
3. **Two histories, two compressors.** The conversation history (what is sent) and a short-term memory store (read
   only by memory recall) are kept in parallel, each with its own compressor. AGENTS.md has to warn readers that they
   are different things.
4. **The LLM layer can't carry what context engineering needs.** Messages are OpenAI-shaped. Streaming is a blocking
   call with two callbacks (reasoning and content deltas); tool calls don't stream. There are no retries — one 429 or
   5xx ends the run — and no context-overflow detection. Model metadata (context window) is hard-coded in each client
   class, and switching models pushes a new client into four separate objects.
5. **Tool execution is one fat registry.** Batching, approvals, hooks, guards, MCP result budgeting and per-task state
   live together. Inside a Tool Batch, tools start in parallel and each blocks for approval inside its own execution,
   so approval cards appear in lock-contention order and one tool can already be running while a sibling waits for
   y/n.
6. **Wiring and configuration are implicit.** A ~1900-line `Main` assembles everything by hand, and 37 files outside
   the config package read system properties or environment variables directly. These hidden inputs are what already
   made test results depend on the host terminal.
7. **Nothing is persisted.** There is no record that a session could be resumed or forked from.

## Solution

Rebuild the **spine** of Cafecli as four layers — `ai`, `core`, `harness`, `ui` — in a parallel v2 package tree,
selected at startup by a flag, while the **leaves** (tool implementations, guards, MCP client, snapshots, LSP, RAG, web,
browser, skills, the long-term memory store, and the whole render package) are reused through adapters. When v2
passes a named contract test set and a v1/v2 parity check, switch over and delete v1 in one commit.

Structure comes first. The refactor reproduces today's user-visible behavior, except for a short, explicit list of
**intentional behavior changes** that the architecture itself requires (see "Intentional behavior changes"). New
features — resumable sessions, instruction-file loading, auto-memory, an RPC mode — come afterwards, built on the new
seams.

The acceptance test for the whole effort is **isolation**: after switch-over, any one of context, compaction, memory,
tools, multi-agent, or the loop can be rewritten by touching only its own package plus the composition root.

## User Stories

### Maintainer: developing context and memory

1. As the maintainer, I want every piece of context engineering to be a separately registered stage, so that adding a
   new kind of context means one new class and one registration, with zero edits to the loop.
2. As the maintainer, I want the agent log to be append-only and typed, so that I can reason about "what happened"
   separately from "what the model sees this iteration".
3. As the maintainer, I want the request for each iteration to be a projection of the agent log, so that I can change
   how history is presented to the model without destroying the history itself.
4. As the maintainer, I want projection stages to be pure functions, so that I can unit-test them without an LLM and
   rerun them deterministically.
5. As the maintainer, I want compaction to append a summary entry that covers a range instead of deleting messages, so
   that compaction is reversible in the log and a future resume or fork can see what was summarized.
6. As the maintainer, I want memory recall to append its result once at the start of a run, so that the recalled text
   is stable for the rest of the run and doesn't break prompt caching.
7. As the maintainer, I want the system prompt to change only on `/clear`, a model switch, or a change in the skill or
   MCP set, so that the request prefix stays cacheable.
8. As the maintainer, I want to define new message kinds (LSP diagnostics, task notification, hook feedback, memory
   recall, compaction summary) in the harness without editing `core`, so that core never learns product concepts.
9. As the maintainer, I want each custom message kind to declare its own presentation to the model (shown as tagged
   user content, or hidden), so that "how the model sees X" lives next to X.
10. As the maintainer, I want one boundary inbox for everything that arrives between iterations (steering, LSP
    diagnostics, hook context, task notifications), so that core never needs to know why
    a message arrived.
11. As the maintainer, I want the long-term memory store to be a standalone module that doesn't know the loop exists,
    so that I can rewrite storage or retrieval without touching context code.
12. As the maintainer, I want a defined memory-extraction seam (end of run, before compaction, end of session) that is
    switched off by default, so that I can experiment with auto-memory later without changing the architecture.
13. As the maintainer, I want the short-term memory store and its compressor removed, so that there is exactly one
    history and one compaction path.
14. As the maintainer, I want context budgets and compaction thresholds derived from the model's metadata, so that a
    model switch automatically changes them.
15. As the maintainer, I want every agent-log entry to serialize and deserialize losslessly from day one, so that
    resumable and tree-shaped sessions can be added later without migrating the format.

### Maintainer: tools, permissions, and hooks

16. As the maintainer, I want a single tool interface in `core` (name, description, schema, execute with cancellation
    and progress updates, typed result), so that built-in, MCP, and future tools are interchangeable.
17. As the maintainer, I want `core` to schedule the Tool Batch (parallel by default, results kept in source order,
    cancellation honored), so that batch mechanics are written once.
18. As the maintainer, I want Guards to run inside each tool's execution, so that "guards are absolute" holds because
    of where the code is, not because of the order callbacks happen to run in.
19. As the maintainer, I want hooks and approvals composed into one pre-tool callback in the harness, so that the
    permission chain is one readable composition.
20. As the maintainer, I want the typed tool outcome (ADR 0019: `OK` / `ERROR` / `DENIED` / `BLOCKED` / `CANCELLED` /
    `TIMEOUT`) carried on the tool
    result, so that no layer reads success or failure from text prefixes.
21. As the maintainer, I want the tool list sent with each request computed per agent from the capabilities attached
    to it, so that Capability Closure holds by construction and Deferred MCP Tools can be added later without
    touching the loop.

### Maintainer: multi-agent

22. As the maintainer, I want a sub-agent to be just another core agent built from a profile (model, system prompt,
    attached capabilities, budget), so that multi-agent work reuses the main loop instead of a parallel one.
23. As the maintainer, I want per-agent capability state (Read-before-Write Ledger, skill context buffer, Finish Gate
    counters, MCP result scope) created fresh for each agent, so that state cannot leak across `/clear` or task
    reaping.
24. As the maintainer, I want sub-agent events tagged with the task they come from, so that the UI and the trace can
    show who is doing what.
25. As the maintainer, I want sub-agent profiles to be able to choose a different model, so that cheap models can be
    used for side tasks.

### Maintainer: LLM providers

26. As the maintainer, I want a provider-neutral message model (text, thinking with signature, tool call, image
    blocks), so that adding a native non-OpenAI provider later doesn't reshape anything above `ai`.
27. As the maintainer, I want each provider adapter to own its wire quirks (including the DeepSeek / Kimi
    `reasoning_content` round-trip), so that the loop never branches on provider.
28. As the maintainer, I want streaming as a pull-based event stream that includes tool-call deltas, usage, and stop
    reason, so that consumers can iterate it on a virtual thread and tests can script it.
29. As the maintainer, I want a model to be a data record in a registry (with config overrides), so that switching
    models is changing a value, not swapping clients in four places.
30. As the maintainer, I want every LLM failure classified (retryable, context overflow, aborted, fatal), so that each
    layer handles exactly the failures that belong to it.
31. As the maintainer, I want a fake provider that replays scripted responses and records every request it received,
    so that context engineering can be tested by asserting what the model would have seen.

### Maintainer: structure and wiring

32. As the maintainer, I want an internal Capability SPI through which each area contributes its tools, stages,
    writers, inbox producers, permission participants, iteration-end participants, commands, listeners, and message
    kinds, so that no area is hand-wired into `Main`.
33. As the maintainer, I want capabilities wired by explicit constructor injection in a declared order, so that the
    dependency graph is visible and testable.
34. As the maintainer, I want one typed configuration object loaded once at the composition root, from the same sources
    and in the same precedence as today, so that v2 code has no hidden inputs.
35. As the maintainer, I want architecture tests that fail on forbidden cross-layer imports and on direct
    property/environment reads in v2, so that the layering can't silently erode.
36. As the maintainer, I want the event trace to be a capability listening to the event stream, so that adding a trace
    field doesn't touch the loop.
37. As the maintainer, I want v1 and v2 runnable from the same jar behind a flag, so that I can compare them on my own
    machine.
38. As the maintainer, I want v1 frozen to critical fixes while v2 is built, so that I don't port the same change
    twice.
39. As the maintainer, I want the switch-over to be a deletion rather than a rename, so that the final step is small
    and reviewable.

### CLI user

40. As a CLI user, I want every command, flag, config file, and environment variable I use today to keep working after
    the switch, so that the refactor is invisible to me (apart from `serve --http`, which is removed).
41. As a CLI user, I want a transient 429 or 5xx to be retried with a visible "retrying" status instead of ending my
    run, so that flaky providers don't waste my work.
42. As a CLI user, I want a run that hits the model's context limit to compact and continue once instead of failing,
    so that long sessions survive.
43. As a CLI user, I want approval prompts in a batch to appear in the order the model wrote the calls, and nothing in
    that batch to start running until I've answered, so that I know exactly what I'm approving.
44. As a CLI user, I want ESC, Ctrl+C, and Ctrl+D to behave exactly as they do today (cancel run and attached tasks,
    deny pending approvals, keep my typed input), so that my muscle memory still works.
45. As a CLI user, I want the input box, live region, tool blocks, and resize behavior to look and behave exactly as
    today, so that the refactor doesn't regress the terminal UI.
46. As a CLI user, I want Guard refusals to stay absolute even when a hook says ALLOW, so that my safety guarantees
    don't change.
47. As a CLI user, I want `/model` to switch models mid-session with budgets following the new model, so that I don't
    have to restart.
48. As a CLI user, I want `run --file` with `--trace-file` to keep producing traces the eval tooling can read, so that
    benchmark runs keep working.
49. As a CLI user, I want unknown slash commands to still be rejected at the CLI, and `/plan` to still print its
    retirement notice, so that command behavior doesn't drift.
50. As a CLI user, I want background tasks, auto-backgrounding of slow foreground tasks, `/task` inspection, and task
    notifications to work as today, so that multi-agent workflows are unchanged.

### Implementing agent and test author

51. As an implementing agent, I want a milestone order where every milestone ends with something runnable, so that
    design mistakes in lower layers surface before anything depends on them.
52. As an implementing agent, I want an explicit list of intentional behavior changes, so that parity differences can
    be classified as expected or as regressions.
53. As an implementing agent, I want an explicit list of contracts, so that I know which existing tests must be
    re-bound to v2 rather than deleted.
54. As a test author, I want one high-level session seam driven by the fake provider, so that most behavior tests
    survive internal rewrites.
55. As a test author, I want provider adapters tested against a local HTTP server with recorded fixtures, so that wire
    formats are pinned without network access.
56. As a test author, I want screen-truth tests fed by agent events, so that render invariants stay pinned once the
    renderer is driven by events instead of direct calls.
57. As a test author, I want a temporary parity harness that replays one scenario through v1 and v2, so that
    switch-over is decided by evidence.

## Implementation Decisions

### Layers and dependency direction

- Four spine layers under their **final** package names: `ai`, `core`, `harness`, `ui`. They don't clash with the v1
  spine packages, so switch-over deletes v1 instead of renaming v2.
- Allowed dependencies: `ai` → nothing in Cafecli. `core` → `ai`. `harness` → `core`, `ai`, and leaves. `ui` →
  `harness` (the session facade) plus `core`'s event and message **data types**, and render; `ui` never reaches into
  `Agent` or `AgentLoop` internals.
- No v2 package imports the v1 spine (today's agent, llm, memory manager, HITL registry, and CLI assembly). v2 may
  import leaves, through adapters where their types are v1-shaped.
- Engine and agent are **two classes in one layer** (`core`), as pi does it, not two layers: the stateless loop has
  exactly one real caller.
- Rejected: four layers with the "agent layer" absorbing tools, memory, context, hooks, MCP, and tasks (it becomes the
  next god class); separate engine and agent layers (an interface with one implementation); a Maven multi-module split
  now (today's import graph has cycles, e.g. agent → render and HITL → render, so the split would stall immediately —
  promote to modules once the architecture tests show a clean graph).

### `ai` layer

- **Message model**: provider-neutral roles (user, assistant, tool result) with content blocks: text, thinking (with
  provider signature), tool call, image. System prompt is passed separately from messages.
- **Model registry**: a model is a data record — id, provider, API family, context window, max output, and capability
  flags (reasoning, image input, prompt caching, tool-call streaming). A bundled table supplies defaults; config can
  override or add entries. This replaces per-client hard-coded context windows.
- **Providers are stateless adapters**: the model record and the request context are passed on every call. Each
  adapter converts neutral messages to its wire format and back and owns its quirks; the `reasoning_content`
  round-trip for DeepSeek V4 / Kimi thinking mode is the adapter's job.
- **Streaming**: a pull-based event stream, iterated on the caller's (virtual) thread, cancellable with a token.
  Events: start; text start/delta/end; thinking start/delta/end; tool-call start/delta/end; usage; done with stop
  reason; error with kind; retry (attempt n of m). A `complete()` convenience drains the stream into one assistant
  message.
- **Error classification**: retryable (429, 5xx, network) — retried inside `ai` with backoff and a cap, surfacing retry
  events; context overflow — each adapter maps its provider's specific error to this kind; aborted; fatal.
- **Fake provider**: part of `ai` (main code, not test-only) so both tests and the parity harness can select it.
  Replays scripted responses — fixed, or chosen by a function of the incoming request — and records every request it
  receives.
- **Scope of providers**: port the five existing OpenAI-compatible providers. A native Anthropic or Gemini adapter is a
  later feature that this model makes possible.

### `core` layer

- **Agent log**: append-only. Entry type is a sealed hierarchy of user, assistant, tool result, and a **non-sealed
  custom** branch. Core switches exhaustively over the three types it understands and never interprets custom kinds.
- **Serialization**: Jackson polymorphic typing; the harness fills a kind registry. Every entry must round-trip from
  day one, even though nothing persists during this refactor.
- **`Agent`** holds the agent log, current model, system prompt, tools, the boundary inbox, and subscribers. Not
  thread-safe except the inbox and cancellation; the session's serial executor is the only writer of the main agent's
  log (ADR 0005 unchanged).
- **`AgentLoop`** holds only per-run state and is driven entirely by callbacks:
  - input preparation (ticket16 refinement) — before appending the source user, returns source input, optional cached
    user presentation, prepared custom entries and the count of prefixes already applied. Harness stages prepare
    skills then parse combined image input once; source user and raw skill metadata remain separate. Existing
    run-start writers still see the appended user and receive the original input. Pure projection transforms both
    source and cached message views without repeating file/clipboard access or changing the log.
  - `transformContext` — the projection-stage pipeline over the agent log.
  - `convertToLlm` — agent log entries → neutral `ai` messages. Core supplies a default that passes user, assistant,
    and tool result through and drops custom entries; the harness supplies the real one. This is deliberately **not**
    in `ai`: it needs product semantics that live in the harness. The wire conversion stays in each `ai` adapter.
  - boundary inbox drain — at every iteration boundary.
  - `beforeToolCall` / `afterToolCall`.
  - iteration-end callback (pi: `finishTurn`) — after every iteration, with the assistant message and tool results;
    returns continue or end, optionally with entries to append. Finish Gate stop-blocks and budget / stagnation checks
    are participants here.
  - `prepareRequest` — last-minute request shaping, including the per-agent tool list.
  - context-overflow callback — on an overflow error, core calls it once (the harness runs compaction) and retries the
    request once.
- **Hard iteration cap** stays in core as the only budget core knows about; token budget and stagnation detection move
  to harness participants.
- **Tool interface** (`AgentTool`): name, description, JSON schema, and execute(call id, JsonNode input, cancellation
  token, progress callback) → tool result (content blocks, structured details, typed status). Tool input stays on the
  JsonNode channel (ADR 0003).
- **Tool Batch scheduling** belongs to core: run `beforeToolCall` for **every** call in source order first; then
  execute approved calls in parallel (default concurrency 4); calls that don't pass preflight get their result in
  their slot without executing — `DENIED` for an approval refusal, `BLOCKED` for a hook `BLOCK` (ADR 0019 keeps the
  two apart: one is a security refusal, the other model-level feedback); `afterToolCall` runs as each finishes; results are appended to the log in source order. Siblings in a
  batch still can't see each other's results.
- **API style**: blocking on the caller's virtual thread; explicit cancellation token per run; events delivered to
  subscribers synchronously on the loop thread. Subscribers must only enqueue (the RenderLoop single-writer rule
  already requires this for the UI).
- **Events**: run start/end, iteration start/end, message start/update/end (assistant deltas by block), tool
  start/update/end (update carries progress such as running-command output; end carries the result including
  details such as diffs), retry, compaction. Every event carries the agent or task id.
- **Results are typed**: a run ends with a typed outcome (completed, cancelled, budget exhausted with reason, failed
  with error kind). Core never produces user-facing strings.

### `harness` layer

- **`AgentSession` facade** — the only thing `ui` and headless talk to: submit input, cancel, subscribe to events,
  dispatch slash commands, await idle. It owns the interactive event loop (ADR 0005: serial executor, run-boundary
  drain, push of task completions as autonomous runs). Headless `run --file` uses it synchronously with no event loop,
  pump, or push (unchanged rule). The `UserPromptSubmit` hook runs at submit.
- **`AgentFactory` + profiles** — builds every agent, main or sub-agent, from a profile: model, system prompt, attached
  capabilities, budget, context pipeline. `spawn_agent` uses a sub-agent profile.
- **Task management** stays in the harness: task registry, admission control (ADR 0006), idle reaper, spawn /
  send_message / task_output tools, foreground and background spawn, auto-background after ~2 min in interactive mode
  only, Attached / Detached cancel semantics, run → idle (continuable) → reaped lifecycle.
- **Capability SPI** (internal; not a plugin system — no loading, no isolation, no external API promises). Two hooks:
  - session hook, run once — receives a registrar for session-scoped contributions;
  - agent hook, run for every agent the factory builds — receives that agent's scope, where per-agent state lives.
  A profile lists which capabilities attach to an agent; unattached capabilities contribute nothing (Capability
  Closure). Registration types: tools; pure source-range normalization followed by view projection stages (ordered
  by registration within each phase; ticket 12 refinement, ADR 0020 D4); log writers with a trigger (run
  start, iteration boundary, context overflow); inbox producers; permission participants (before / after tool);
  iteration-end participants; run lifecycle participants (run start / run end, synchronous — e.g. snapshot
  anchoring); slash commands; event listeners; custom message kinds (type id, serialization, presentation to the
  model).
- Capabilities are wired by **explicit constructor injection in a declared order** at the composition root. No service
  locator, no DI container.
  Ticket17 refinement: one explicitly constructed AgentFactory is injected into session and subagent capability,
  then bound once after session hooks to that session's declared capabilities/event stream. Generic run-start
  lifecycle initialization precedes ordinary writers; run-finally cleanup runs before releasing execution admission,
  including exceptional exits, and preserves original failures. Foreground children have their own virtual threads,
  one-way linked cancellation tokens and fresh per-agent resources. Explicit provider routing selects the adapter by
  profile model; unsupported providers fail typed rather than reusing an unrelated endpoint. Background retention,
  continuation/reaping and remaining provider adapters remain their later tickets.
- Session-level built-ins (`/clear`, `/model`, `/save`, …) are one more capability; `ui` gets the slash-command list
  from the harness command registry for completion and dispatch.
- **Permission chain**: hooks (strictest of BLOCK > ASK > ALLOW > CONTINUE across tenants) then the `Approver` port,
  composed into `beforeToolCall`. Guards are not in this chain — they run inside tool execution. Hook ALLOW still only
  skips approval. The `Approver` is a blocking, cancellable port whose interactive implementation lives in `ui` and is
  injected by the composition root; it serializes concurrent requests from different agents. Cancelling a run
  releases its pending approvals as denied. `AuditLog` becomes a listener on permission-chain events.
  Auxiliary invocations use that same route: nested leaves execute inline on their worker, while preflight and
  completion return to the agent owner; standalone writer/idle-command invocations use a cancellable worker.
  Invocation ports expire with their lexical owner, and auxiliary completions feed PostToolUse/audit without
  ordinary model events or Stop-summary entries. Browser checks decorate executable snapshots; sensitive-page
  notices affect only the normal enabled approval route, preserving v1's Hook ALLOW/disabled-HITL behavior.
  Hooks attach before Web so run-start freshness has initialized hook state. Idle command handlers reserve admission
  under the session lock but execute outside it; cancel/close/awaitIdle cover the command reservation.
- **Context package** (separate from memory): the projection pipeline, log writers, compaction, and the token profile
  derived from the model record. Placement of today's nine injection points:
  - projection stages: historical image stripping, hiding UI-only entries, compaction-range replacement;
  - input preparation before source append: skill bodies and one-time combined image parsing; prepared entries append
    immediately after the user, preserving raw skill metadata and cached model presentation (ticket16 refinement);
  - log writers at run start: memory recall and freshness web search;
  - log writer at iteration boundary or on overflow: compaction, which appends a summary entry covering a range;
  - boundary inbox: steering, LSP diagnostics, hook context, task notifications;
  - iteration-end participant: Finish Gate (continue plus an appended stop-block entry);
  - system prompt (rebuilt only on `/clear`, model switch, skill-set or MCP-set change): templates, skill index, MCP
    resource index.
  The compaction log writer is new code; it may reuse today's summarization prompt.
- **Memory package**: long-term store only — save, recall, list, delete — with no knowledge of the loop. Memory recall
  is a context log writer that reads from it. Writes still happen only via `/save` or an explicit user request. The
  extraction seam (end of run, before compaction, end of session) is defined and off by default.
- **Short-term memory** and its compressor are removed.
- **MCP**: connections and the discovered tools are session-scoped and, as today, every connected MCP tool is exposed
  eagerly (including `list_changed` refresh). The per-agent tool list in `prepareRequest` is where Deferred MCP Tools
  and `tool_search` will plug in later. MCP result referencing stays with the MCP tool adapter; the result scope
  directory is per agent.
- **Configuration**: one typed configuration tree loaded once, from the same sources and in the same precedence as
  today (user and project config homes, `CAFECLI_*` environment, `-Dcafecli.*` properties, legacy `paicli` fallback),
  grown from the existing provider config. Each capability receives its own slice. v2 code reads no properties or
  environment outside the loader; leaves keep their own reads until a later change touches them.
- **Trace**: a capability that listens to the full event stream and writes JSONL. Kept separate from the agent log
  (the log is what the model sees and what resume needs; the trace is everything that happened).

### `ui` layer

- An event → render adapter maps core events onto the existing typed render calls (reasoning, answer, tool calls,
  tool results, code block, diff, command output). The render package itself is reused unchanged.
- The answer splitter (markdown and fenced code-block detection) moves from the agent into `ui`. It parses the
  **source** delta stream into typed blocks, which satisfies ADR 0017 ("block types come from typed events", never
  from rendered text).
- The interactive `Approver` implementation, the resident input box, cancel keys, modal leasing, the session-level
  signal guard, the status dock, and slash-command completion live here.
- The CI rule that the REPL and entry classes contain zero direct terminal reads and zero raw-mode entries extends to
  the v2 interactive entry classes.

### Migration

- **Parallel v2 tree** selected by `-Dcafecli.v2=true`; v1 stays the default until switch-over.
- **Spine is new; leaves are reused** through adapters and stay in their current packages until switch-over, so both
  v1 and v2 can use them. Leaf rewrites are later features.
- **v1 is frozen** to critical fixes for the duration. One exception: **behavior-preserving prefactors that unhook
  leaves from spine types** (tickets 01–03). Several leaves are typed with v1 spine types — tool groups with the
  registry's nested tool and parameter types, tool outputs and MCP results with the LLM client's content part, the
  renderer's tool-call API with the LLM client's tool call and the registry's execution result — so v2 can't reuse
  them without importing v1, and v1 can't be deleted while they still do.
- **Switch-over criteria**, all required: (1) the contract test set (below) passes against v2; (2) the parity harness
  shows no unexplained differences, where differences listed under "Intentional behavior changes" count as explained;
  (3) a real interactive smoke run by the maintainer.
- **Switch-over** is one commit: make v2 the default, delete the v1 spine and v1-only tests, remove `serve --http` with
  its thread store and runtime task runner, then update the CLI parser, README, AGENTS.md, and CONTEXT.md.

### Milestones (each ends runnable)

1. **Walking skeleton**: `ai` with the neutral model, event stream, model registry, one OpenAI-compatible adapter, and
   the fake provider; `core` with agent, loop, log, events, tool interface, and batch scheduling; a minimal `harness`
   with session (synchronous), factory, config loader, and one capability providing `read_file`; headless
   `run --file` under the v2 flag with plain output; architecture tests in place.
2. **Tools and permission chain**: all built-in tools adapted, guards inside tools, file mutation locks, typed status
   mapping, hooks capability, `Approver` port, source-order preflight.
3. **Context and memory**: agent-log kinds and serialization round-trip, static system prompt, projection stages, log
   writers, boundary inbox, compaction, overflow recovery, memory recall, short-term memory removed.
4. **Remaining capabilities**, one at a time: MCP (connections, eager tool exposure, `list_changed`, result
   referencing, resource index), skills, LSP, snapshot, web and freshness search, browser, RAG, tasks and sub-agents,
   trace.
5. **All providers and the error taxonomy**: remaining four adapters, retries with backoff, overflow mapping per
   provider.
6. **Interactive mode**: event loop, event → render adapter, splitter in `ui`, interactive `Approver`, command registry
   and completion, status dock, cancel keys.
7. **Contract and parity tests** re-bound and passing.
8. **Switch-over**.

Tickets (`issues/`, 29 total, each sized for one fresh context): prefactors `01`–`03` come first; milestone 1 →
`04`–`06`, 2 → `07`, `08`, `10`, 3 → `09`, `11`, `12`, 4 → `13`–`20`, 5 → `21`, `22`, 6 → `23`–`26`, 7 → `27`, `28`,
8 → `29`. Each ticket's `Blocked by` line is the source of truth for ordering.

### Contracts that must survive (re-bound to v2, not deleted)

- Guards absolute; hook decision semantics and strictest-wins composition; four hook events off by default and enabled
  by properties; project hook trust pinned by content fingerprint (and project `mcp.json` still not trust-gated — the
  known asymmetry is preserved, not fixed here).
- Snapshot: bare creation, per-call work-tree binding, hijacked-project detection, restore fenced by the guard's
  real-path check, restore branches on git file mode.
- Cancel key table (ESC / Ctrl+C / Ctrl+D) and the signal guard.
- Live region single writer, one key consumer, typed block events, destructive resize reset.
- Typed tool outcome, including: `CANCELLED` / `BLOCKED` count as usable for the StepSearch fallback; non-zero exit
  code is `OK`.
- Tool Batch: parallel default 4, results in source order, siblings invisible to each other.
- Skill bodies loaded by `load_skill` reach the model ahead of the next user input (today's position, not the
  glossary's — see "Glossary terms that describe designs").
- Reasoning content round-trip for DeepSeek V4 / Kimi thinking mode.
- Memory written only via `/save` or explicit request.
- Unknown `/xxx` rejected at the CLI; `/plan` prints its retirement notice.
- CLI and config surface: commands, flags, config file locations and precedence including the `paicli` fallback,
  environment variables, and `-Dcafecli.*` properties (including `edit_file.disabled` and hook enable flags).
- `--trace-file` JSONL: the record types and fields the eval normalizer reads (one `assistant` record per assistant
  message with its tool calls' id, name and arguments; the token totals record). Richer event records are allowed
  **only** under new type values — the normalizer skips unknown types but counts every `assistant` record as an
  iteration, so per-delta `assistant` records would corrupt eval results.
- Headless runs have no event loop, pump, or push.
- Task semantics: foreground / background spawn, auto-background in interactive mode only, Attached / Detached cancel
  scope, run → idle → reaped lifecycle with continue.

### Intentional behavior changes (the parity whitelist)

1. The system prompt is stable per session; memory recall and freshness search appear as entries near the end of the
   request instead of inside the system prompt.
2. Approvals in a Tool Batch are asked in source order, and nothing in that batch executes until every approval is
   answered.
3. Retryable LLM failures are retried with backoff and a visible retry status.
4. A context-overflow error triggers compaction and one retry instead of failing the run.
5. Compaction appends a summary entry and projection replaces the covered range, so the compacted request differs from
   v1's in-place compaction.
6. The short-term memory store is gone; memory recall reads only the long-term store.
7. `serve --http` is removed (an RPC mode replaces it later as a separate feature).
8. Capability Closure becomes real: a tool whose capability isn't attached to an agent is not offered to the model
   (v1 offers it and answers "unavailable" when called). This also changes the tool list — and, for v1, its
   incidental hash-map ordering — so tool order in the request is now the declared capability order.
9. Cancelling a run aborts the model request in flight instead of letting the streamed answer finish first (v1:
   "取消是协作式的，落在回合边界而非流式回答中途", CONTEXT.md 取消键). The end state is the same — the cancelled answer is
   not kept in the history, and the next request is identical — but the run stops sooner, and the screen may show a
   half-streamed answer before the cancellation notice. *(Added 2026-10-20 in the ticket 04b review, as a
   consequence of `ai` streaming being "cancellable with a token"; cheap to reverse: give the request a token of its
   own and check the run's token only after the stream ends.)*

10. A background-task completion received while the parent run is in flight is presented at its next eligible
    iteration boundary. Actual v1 queues that completion for a new run after the current run ends. This lets the
    parent use a completed result during its current work; a completion that misses the last boundary still needs
    the run-end handoff and idle-time push in ticket23. Headless mode retains synchronous spawning without push.
    *(Approved by the maintainer on 2026-10-23 after ticket18's implementation, independent reviews and packaged
    verification; this exception covers notification timing only.)*

11. Ticket19's main-run snapshot contract includes synchronous headless runs: pre-turn anchoring precedes any tool
    work, and post-turn work drains before the owned snapshot writer closes. Actual v1 anchors interactive runs via
    its external runTurn wrapper but calls agent.run directly in HeadlessRunner. This implements the explicitly
    requested snapshot contract in the new session facade; it can make headless revert_turn succeed where v1 had no
    anchor. Children share restore access and never create independent run anchors.

12. Compatible trace records retain reasoning on every retained assistant message and total completed MAIN model
    responses even if the run later exhausts budget, fails or cancels. Actual v1 drops reasoning from ordinary final
    history entries and updates lastRunUsage only on a natural successful return, producing zero or stale result
    totals otherwise. Partial ERROR/ABORTED responses, children and internal compaction calls are excluded from these
    trace totals. This corrects trace observability only: field names/record types and model history are not changed.

## Testing Decisions

**What makes a good test here**: it drives the system through a public seam and asserts on externally observable
behavior — events emitted, the request the model received, the agent log, files on disk, the screen — never on which
internal class did the work. A test that breaks when compaction's algorithm is swapped but the model still sees the
same thing is a bad test.

**Seam 1 — session (primary)**: the v2 `AgentSession`, driven by the fake provider, against a temporary project
directory. Assertions on emitted events, the agent log (including serialization round-trip), filesystem effects, and —
the key one for context engineering — **the requests the fake provider recorded**. Covers core, harness, every
capability, the permission chain (source-order preflight; Guard refusal survives hook ALLOW), sub-agents and tasks,
overflow recovery, iteration-end participants, and the static system prompt. Prior art: `AgentSessionTest`, which
already drives the session headlessly with a dispatching stub LLM client; `AgentFinishGateTest`,
`AgentLspDiagnosticsTest`, `AgentSteeringTest`, `AgentReadLedgerTest` (behaviors to re-express at this seam).

**Seam 2 — provider adapters**: each `ai` adapter against a local HTTP server with recorded fixtures: the request body
on the wire (including the reasoning content round-trip and image encoding), raw SSE bytes in → event stream out, error
classification, and retry behavior. Needed because seam 1 replaces adapters with the fake provider. Prior art:
`AbstractOpenAiCompatibleClientImageInputTest` (local `HttpServer`).

**Seam 3 — screen**: the existing `TerminalScreenModel` screen-truth tests, fed by core events through the event →
render adapter instead of direct renderer calls. Existing render tests that call the renderer directly stay as they
are. Prior art: `ScreenTruthRegressionTest`, `InputBoxLiveRegionTest`, `ToolCallBlockTest`,
`CaptureDisplayInterceptionGuardTest`, `HardTerminationCouplingTest`. Interactive REPL behavior (cancel key table,
signal guard) is re-bound using the existing piped-stream rig: `InteractiveReplTest`, `CancelKeyTableTest`,
`SessionInterruptGuardTest`.

**Seam 4 — parity (temporary)**: one scenario script replayed through v1 headless (stub LLM client) and v2 headless
(fake provider); transcripts and trace records compared, with differences classified against the intentional-changes
list. Deleted with v1 at switch-over.

**Architecture tests** (structure, not behavior): the allowed-dependency rules above; no v2 import of the v1 spine; no
property or environment reads in v2 outside the config loader. Prior art: the existing CI tests that read source files
and assert zero terminal reads in the REPL entry classes.

**Contract test set**: the tests pinning each item under "Contracts that must survive", re-bound to v2 entry points.
Snapshot tests (`SnapshotDoesNotTouchProjectGitTest`, `SnapshotRestoreStaysInsideProjectRootTest`) and guard tests are
leaf tests and keep running unchanged; tests that construct the v1 agent, registry, or LLM client are re-expressed at
seam 1 or 2.

**Test isolation rules from AGENTS.md apply to every new test**: no writes to the global color property; strip ANSI or
measure display columns instead; `TERM` pinned in CI; host-terminal probes pinned via the surefire arg line; REPL rigs
run `repl.run()` on a background thread and wait for the turn guard before the closing Ctrl+D — except the cancel key
table, which deliberately presses Ctrl+D mid-turn.

## Out of Scope

- Persisted sessions: resume, tree-shaped sessions, fork / rewind (the first feature after switch-over; this PRD only
  guarantees the log format serializes).
- Loading project instruction files (AGENTS.md / CLAUDE.md) into context.
- Any auto-memory policy (the extraction seam exists, switched off).
- An RPC / stream-JSON mode replacing `serve --http`.
- MCP tool retrieval and on-demand activation (Deferred MCP Tools, Activated Tool Set, `tool_search`).
- Moving Skill Instruction Injection to the glossary's position (after the tool result, before the next LLM call).
- A user-facing extension or plugin API (the internal Capability SPI is its future base).
- Native Anthropic / Gemini adapters.
- Rewriting any leaf: tool implementations, guards, MCP client, snapshot, LSP, RAG, web, browser, skills, memory store
  internals, render.
- Purging property / environment reads from leaves.
- A Maven multi-module split.
- Fixing the project `mcp.json` trust asymmetry, the `NO_COLOR` test red, or any other known issue listed in AGENTS.md.

## Further Notes

- **Write ADR 0020** from this PRD before or during milestone 1: the four-layer decision, the agent log + projection
  model, the callback set, guards-inside-tools, the Capability SPI, and every rejected option named above.
- **CONTEXT.md** gains: Run vs Iteration (and the mapping to pi's "agent run" / "turn"), Agent log, Projection,
  Projection stage, Log writer, Boundary inbox, Custom message kind, Harness, Capability (and its relation to
  Capability Closure). Add these when ADR 0020 is written, since v2 code will use them from milestone 1.
- **AGENTS.md** changes only at switch-over, because v1 stays live until then. Lines that will need rewriting: the
  single execution path description; the interception order (becomes hooks → approval in the pre-tool callback, then
  guards inside execution); "parallel calls always go through the registry's batch executor" (becomes core batch
  scheduling); the "two compressions" memory invariant (short-term memory is gone); the doc-sync table entries for
  tools and models.
- **Reference design**: pi's `pi-agent-core` puts the loop and the stateful agent in one package and exposes the same
  callback family (`convertToLlm`, `transformContext`, `beforeToolCall`, `afterToolCall`, `prepareRequest`,
  `finishTurn`, steering / follow-up queues); `pi-coding-agent` is the harness; persistence lives in a separate
  package. Cafecli deviates where its invariants require it: guards are absolute and run inside tools; approvals are a
  first-class port; the event loop and task substrate (ADR 0005–0007) are harness concerns pi doesn't have.
