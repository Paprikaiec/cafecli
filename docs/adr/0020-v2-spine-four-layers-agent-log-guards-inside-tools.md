# v2 spine: four layers, an append-only agent log, and guards inside tools

> Status: **accepted** (2026-10-20, decided; not yet implemented). Implementation and progress:
> `.scratch/v2-architecture/` (PRD + issues). Reshapes ADR 0007; leaves ADR 0005, 0010–0019 in force.

Every direction Cafecli wants to grow in — context, compaction, memory, tools, multi-agent, the loop — runs through one
~1400-line `Agent` that is at once the ReAct loop, the context assembler, the memory client, the hook dispatcher, and a
stream renderer. Context engineering is nine hand-wired, in-place edits of the request (including a system-prompt
rewrite every run, which defeats prompt caching); there are two histories with two compressors; the LLM layer is
OpenAI-shaped with no retries or overflow detection; wiring lives in a ~1900-line `Main`. We rebuild the spine as four
layers, modeled on pi (`pi-ai` / `pi-agent-core` / `pi-coding-agent` / `pi-tui`), in a parallel tree, and switch over
once v2 passes the contract and parity tests. The acceptance test is isolation: any one of the six areas can be
rewritten by touching only its own package plus the composition root.

## Decisions

1. **Four layers: `ai` / `core` / `harness` / `ui`.** `ai` depends on nothing in Cafecli; `core` on `ai`; `harness` on
   `core`, `ai`, and leaves; `ui` on the harness session facade plus `core`'s event and message data types. The
   stateless loop (`AgentLoop`) and the stateful `Agent` are **two classes in one layer**, as in `pi-agent-core`. The
   rules are enforced by architecture tests, not Maven modules.
2. **The agent log is append-only and typed; every request is a projection of it.** The log is never the request.
   Entry types are a sealed hierarchy (user, assistant, tool result) with one **non-sealed** custom branch that the
   harness extends. Every entry serializes losslessly from day one. The short-term memory store and its compressor are
   removed — there is one history.
3. **There are two conversions, owned by different layers.** Agent-log entries → provider-neutral messages
   (`convertToLlm`) needs product semantics (how an LSP diagnostic or a task notification is shown to the model), so
   it is a `core` callback implemented in the `harness`. Neutral messages → provider wire format belongs to each `ai`
   adapter, which also owns provider quirks such as the DeepSeek / Kimi `reasoning_content` round-trip.
4. **Context work is split into projection stages and log writers.** Projection stages are pure, run every iteration,
   and never write. Log writers append entries at defined moments (run start, iteration boundary, context overflow).
   Compaction is a log writer: it appends a summary entry covering a range, and projection replaces that range — it
   never deletes. Memory recall is a log writer at run start. The system prompt changes only on `/clear`, a model
   switch, or a skill-set / MCP-set change; everything dynamic is a log entry near the tail.
   **Ticket 12 refinement (2026-10-21):** source-range normalization runs on the immutable source before ordinary
   view projection; stages retain registration order within each phase. Otherwise a preceding filter changes the
   meaning of a summary's source indices and can remove retained history. Both phases remain pure and never write.
5. **`core` exposes pi's callback family and nothing product-specific**: `transformContext`, `convertToLlm`, a single
   boundary inbox drained between iterations, `beforeToolCall` / `afterToolCall`, an iteration-end callback (pi's
   `finishTurn`), `prepareRequest`, and a context-overflow callback (compact, then retry once). Core keeps only a hard
   iteration cap; token budget, stagnation, and the Finish Gate are harness participants. Steering, LSP diagnostics,
   hook context, and task notifications all arrive through the one inbox, told apart by message kind. (Skill
   Instruction Injection belongs there too once it is implemented as the glossary defines it; today skill bodies are
   prepended to the next user input, and v2 ports that as a run-start log writer.)
   **Ticket 12 refinement (2026-10-21):** the harness folds run-budget participants after ordinary iteration-end
   participants, only when their combined decision requests continuation. This preserves v1's natural final answer
   even when its token usage crosses the configured limit, while a Stop-blocked answer must pass the budget before
   another request. Entries already supplied by iteration-end participants remain logged when a budget wins.
6. **Guards run inside tool execution; core schedules the Tool Batch.** Core runs `beforeToolCall` (hooks, then the
   `Approver` port) for every call **in source order**, then executes approved calls in parallel and appends results
   in source order. Guards are invoked from inside each tool's `execute`, so no callback composition can skip them.
7. **The harness is built from Capabilities through an internal SPI.** Each area (MCP, skills, memory, tasks, LSP,
   snapshot, web, browser, RAG, trace, session built-ins) contributes tools, projection stages, log writers, inbox
   producers, permission and iteration-end participants, run lifecycle participants, slash commands, event listeners,
   and custom message kinds. A capability has a session hook (once) and an agent hook (per agent, for per-agent state).
   A profile decides which capabilities attach to an agent — Capability Closure falls out of that. Capabilities are
   wired by explicit constructor injection in declared order.
8. **`ai` is data-first and blocking.** Provider-neutral content blocks (text, thinking with signature, tool call,
   image); a model is a registry record (context window, capabilities) passed with each request to a stateless adapter;
   streaming is a pull-based event stream iterated on a virtual thread; failures are classified retryable (retried
   inside `ai` with backoff), overflow, aborted, or fatal. `core` is blocking too, with explicit cancellation tokens and
   synchronous subscribers that only enqueue.
9. **Migration is a parallel tree with final package names**, selected by `-Dcafecli.v2=true`. Only the spine is new;
   leaves (tools, guards, MCP client, snapshot, LSP, RAG, web, browser, skills, memory store, render) are reused
   through adapters and stay where they are until switch-over. v1 is frozen to critical fixes, except for behavior-preserving prefactors that move
   leaves off spine types (leaves typed with the registry's or the LLM client's nested types can be reused by neither
   v2 nor anything after v1's deletion). Switch-over deletes v1
   in one commit. `serve --http` is not carried over.

## Considered options

- **Four layers with an "agent layer" = engine + state, and no harness layer** (the original proposal). Rejected: tools,
  guards, hooks, MCP, memory, context policy, skills, snapshots, and tasks have no home, so the agent layer absorbs
  them and becomes the next god class. pi's own four packages are not those four layers — its loop and stateful agent
  share a package, and `pi-coding-agent` is the layer the proposal was missing.
- **Engine and agent as separate layers.** Rejected: the stateless loop has one real caller; a layer boundary there is
  an interface with one implementation.
- **Keep "history == request" and add more mutating stages.** Rejected: every new stage would edit the record it
  depends on, compaction stays destructive, prompt caching stays broken, and nothing can be resumed.
- **`convertToLlm` in `ai`.** Rejected: `ai` would have to import harness message kinds, inverting the layering.
- **Closed sealed hierarchy listing every message kind in `core`**, or a generic kind-plus-JSON record. Rejected: the
  first makes `core` know product concepts; the second throws away compile-time types in the harness.
- **One kind of context stage that may both transform and write.** Rejected: an LLM-backed compaction re-run on every
  request is expensive and unstable, and a log that projection can mutate is not replayable.
- **Apply source-indexed summaries after arbitrary view stages.** Rejected in ticket 12: filtering even one hidden
  entry shifts coverage into retained entries. Carrying source-position wrappers through every stage would also
  solve this, but enlarges every stage's interface; inferring identity from equal/transformed entries is ambiguous.
  Normalize source ranges first, then compose ordinary view stages in their declared order.
- **Guards as `beforeToolCall` participants.** Rejected: then "guards are absolute" depends on callback order, which
  is exactly the kind of property that silently breaks. Inside `execute` it holds by construction.
- **Approvals inside each tool's parallel execution, serialized by a lock** (today). Rejected: cards appear in
  lock-contention order and a tool can run while its sibling's approval is pending.
- **All capability state session-scoped, in maps keyed by agent id.** Rejected: that is how per-agent state leaks
  across `/clear` and task reaping. A service locator or DI container was rejected because it hides the dependency
  graph the architecture tests are meant to pin.
- **A user-facing extension API now** (pi's extensions). Deferred: the internal SPI is its base; external promises come
  after switch-over.
- **Async APIs (`CompletableFuture`, `Flow.Publisher`).** Rejected: on Java 21 virtual threads they add no capability,
  only debugging cost.
- **Big-bang rewrite, or strangler-fig in place.** Big-bang rejected: the render and snapshot invariants are only
  visible to dedicated tests and would regress silently. Strangler was the recommendation; the maintainer chose the
  parallel tree to change the overall structure first and the detailed features afterwards.
- **A Maven multi-module split now.** Deferred: today's import graph has cycles (agent → render, HITL → render); the
  split would stall immediately. Promote once the architecture tests show a clean graph.

## Consequences

- **Behavior changes come with the architecture** and are the parity whitelist: a stable system prompt with recall as
  a tail entry; approvals asked in source order before anything in the batch runs; retries with backoff; overflow →
  compaction + one retry; non-destructive compaction; recall reads only the long-term store; `serve --http` removed;
  Capability Closure becomes real (the glossary defined it, but v1 registers every tool and answers "unavailable" at
  call time).
  Everything else user-visible is a contract (see the PRD's contract list), including the `--trace-file` record types
  the eval normalizer reads.
- **ADR 0007 is reshaped**: per-task services move from per-task `ToolRegistry` instances to agent-scoped capability
  state created by the agent hook. ADR 0005's event loop moves into the harness session unchanged in substance.
- **AGENTS.md changes at switch-over, not before**: the single execution path, the interception order (becomes hooks →
  approval before execution, guards inside it), the batch executor rule (becomes core scheduling), and the
  "two compressions" invariant (gone). The guard semantics themselves — absolute; hook ALLOW only skips approval — do
  not change.
- **Vocabulary**: pi's "turn" is Cafecli's **iteration**; Cafecli's 「回合」 is a **run**. The iteration-end callback is
  named for that reason. New glossary terms are in `CONTEXT.md` under 「Agent 架构」.
- **Testing moves to one primary seam**: the harness session driven by a fake provider that records every request it
  receives, so context engineering is tested by asserting what the model saw. Provider adapters get a wire-level seam;
  render keeps its screen-truth seam, fed by events.
