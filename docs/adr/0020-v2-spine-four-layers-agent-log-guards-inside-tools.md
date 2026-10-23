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
   **Ticket 14 refinement (2026-10-22):** a custom kind can declare a user-prefix presentation. Its run-start entry
   remains physically after the user entry; pure harness conversion merges its prefix into that actual preceding
   UserEntry's content, preserving other content blocks and v1's exact single-message text bytes. Tagged recall or
   steering messages cannot become the target. Skills use this to retain today's next-user placement without
   mutating source history or adding a product-specific core writer.
   **Ticket 16 refinement (2026-10-22):** a generic input-preparation callback runs before the source UserEntry is
   appended. Ordered harness preparation stages return the actual source input, optional cached user presentation,
   prepared custom entries and the number of prefix entries already applied. Skills drain into a separate unchanged
   SkillBody during preparation; Images runs after those prefix contributors and parses the combined prospective
   text once. The source retains actual user text and only user-origin attachments; the cached presentation holds
   the exact combined parser output, including prefix-origin attachments. A behavior-preserving detailed parser
   result supplies successful reference offsets from that same parse to distinguish the two origins. Pure conversion
   uses the prepared presentation and consumes only its recorded prefix count; later prefixes remain eligible.
   Existing UserEntry construction defaults to no prepared presentation. This is recorded preparation data, not the
   whole request; source and cached presentation both survive serialization and neither is rewritten by projection.
   Storing the full cached message repeats image bytes in serialized form; accept that simple representation before
   introducing a reference system. Other run-start writers still execute after the user is appended and receive the
   original input, preserving memory recall, Hooks and freshness lifecycle ordering.
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
   **Ticket 17 refinement (2026-10-22):** generic run-finally participants execute before Agent releases execution
   admission, including on cancellation and callback/subscriber failure. Harness run-start lifecycle participants
   initialize execution ownership before ordinary run-start writers can invoke auxiliary work. Blocking cleanup
   belongs to those lifecycle callbacks, never event subscribers. Cleanup attempts every participant and preserves
   an original failure with cleanup failures suppressed.
6. **Guards run inside tool execution; core schedules the Tool Batch.** Core runs `beforeToolCall` (hooks, then the
   `Approver` port) for every call **in source order**, then executes approved calls in parallel and appends results
   in source order. Guards are invoked from inside each tool's `execute`, so no callback composition can skip them.
   **Ticket 13 refinement (2026-10-21):** each request snapshots its executable tools together with their advertised
   definitions; a later discovery notification affects the next request. `ToolEnd` remains immediate execution
   completion, including when a sibling is still running. A generic `afterToolBatch` callback may normalize result
   content before atomic assistant/result append; cardinality, call identity, order, status and `isError` cannot
   change. Changed batches emit `ToolBatchEnd` with the final source-order entries. The harness preserves execution
   provenance while reusing MCP's existing largest-first aggregate budget. Trace/UI consumers use finalized content
   for publication; execution completion still drives audit and immediate lifecycle observers.
   **Ticket 15 refinement (2026-10-22):** harness writers, idle commands and executing tools may invoke auxiliary
   tools through an invocation-scoped port over the same immutable executable snapshot. Auxiliary preflight and
   completion observers run on the agent owner, through the ordinary hook/approval route; execution guards remain
   inside the tool. A nested leaf executes on its current worker so four outer calls cannot exhaust their own pool.
   Standalone calls use a cancellable worker while the owner services callback rendezvous. Ports expire with their
   writer/command or parent execution; cancellation/close reject pending and late work. Auxiliary completions carry
   an explicit invocation token and optional parent identity, feeding PostToolUse and the owned audit writer without
   ordinary ToolStart/ToolEnd, model results, trace iterations, or model-only Stop summaries. The initiating writer or
   outer tool owns result presentation. Core remains unaware of Web, MCP and browser semantics.
   Browser checks decorate the completed tool snapshot, including dynamically discovered tools, so execution-time
   refusal remains absolute. A separate approval notice forces sensitive-page approval only on the normal enabled
   approval route; Hook ALLOW and disabled HITL retain v1's preview bypass while still reaching execution guards.
7. **The harness is built from Capabilities through an internal SPI.** Each area (MCP, skills, memory, tasks, LSP,
   snapshot, web, browser, RAG, trace, session built-ins) contributes tools, projection stages, log writers, inbox
   producers, permission and iteration-end participants, run lifecycle participants, slash commands, event listeners,
   and custom message kinds. A capability has a session hook (once) and an agent hook (per agent, for per-agent state).
   A profile decides which capabilities attach to an agent — Capability Closure falls out of that. Capabilities are
   wired by explicit constructor injection in declared order.
   **Ticket 13 refinement (2026-10-21):** agent attachment can register owned resources as well as session resources.
   Agent close is idempotent and idle-only; the session cancels and awaits its run before releasing its agent, then
   closes session resources. Cleanup attempts every resource in reverse order and retains suppressed failures.
   Failed attachment closes partially acquired resources. `/clear` assembles a replacement before installing it;
   failure before installation closes the unused replacement, while successful installation closes the old agent.
   MCP connections belong to the session; payload scopes and prompt invalidators belong to individual agents.
   **Ticket 15 refinement (2026-10-22):** idle command dispatch reserves the session's execution token and owner under
   its admission lock, then executes the handler outside that lock. Cancellation, close and awaitIdle cover this
   reservation; concurrent submission cannot become steering into an idle agent. Only the dispatch owner can invoke
   command tools or clear within its reservation. Browser commands reuse the extracted command leaf and the same
   auxiliary tool route. Hooks attach before Web so their run-start state exists before freshness invokes hooks.
   **Ticket 17 refinement (2026-10-22):** the composition root constructs one AgentFactory and injects it into both
   the session and foreground-subagent capability. After session hooks, the session binds the factory once to its
   declared capabilities/event stream; ownership is claimed before hooks so rejected reuse cannot acquire or close
   another session's resources. This breaks the factory/capability
   construction cycle without lookup or a service locator. The same factory builds main and child profiles; default
   children omit the spawning capability, while explicit profiles select their own model, prompt, capabilities and
   budget. Each foreground child runs on its own virtual thread with a distinct cancellation token linked one-way
   to the parent's invocation. Parent run-finally closes spawn admission and cancels/awaits admitted children before
   parent admission is released, covering construction and abandoned-worker races. Per-agent resources close only
   after child execution stops. Background retention, continuation and reaping remain ticket18. A shared TaskSpawner
   leaf port preserves TaskTools behavior; the old v1 interface remains a compatibility subtype.
   **Ticket 18 notification timing approval (2026-10-23):** the maintainer approved PRD parity exception10:
   a completion while the parent is busy enters its next eligible iteration, instead of v1's next-run queue.
   Completions missing the final boundary still transfer to ticket23's autonomous-run path. This approval does not
   waive other task/notification parity or the headless no-push rule.
   **Ticket 18 refinement (2026-10-22):** task lifetime belongs to the harness session, while each retained child
   owns its ordinary core Agent and capability resources. Finishing a run makes a child idle and continuable only
   after its run-finally cleanup has completed; reaping releases its retained agent resources. Attached tasks follow
   the waiting parent's cancellation; explicit background and auto-background tasks are Detached and remain until
   explicit task cancellation or session shutdown. Captured task settings distinguish headless synchronous fallback
   from simulated interactive background mode; the serial executor and idle-time push still belong to ticket23.
   Admission, idle-LRU and bounded-message policies move to neutral leaves with v1 compatibility shims rather than
   importing the v1 spine or duplicating their algorithms. The harness owns typed lifecycle state and bounded
   presentation; legacy task labels remain independent of typed execution outcomes. The factory can return the
   built agent with its existing pure projection, so bounded task trajectories use the same skill/image/summary
   presentation instead of recognizing custom kinds themselves. A generic factory observer publishes immutable
   owner snapshots at run start, iteration boundary/overflow and iteration end, keeping active continuation
   inspection current without concurrent mutable-log reads or work in event subscribers. First-run inspection
   remains unavailable until that run completes, preserving the existing retained-task behavior. Main replacement
   keeps the installed ready inbox
   until successful old-agent close, then takes pending task entries back through a generic scope operation and
   migrates them to the bound replacement; failed candidates retain the original target. Task inspection/cancellation
   uses a generic control-command mode that may run while the main run is busy, with separate admission drained by
   close/awaitIdle; a control owner cannot borrow the idle tool-command lease, clear the main agent or close its
   owning session.
   **Ticket 19 refinement (2026-10-23):** input-aware generic run-start lifecycle participants retain original
   input for synchronous snapshot anchoring before ordinary writers may invoke tools. Snapshot agent lifecycle is
   declared after tasks so its post-turn scheduling follows Attached-child cleanup. A small earlier session-only
   ownership contribution registers the same snapshot instance for final drain/close after task and connection
   resources, keeping resource dependencies separate from agent lifecycle order without priorities or lookup.
   The explicitly requested main-run contract includes headless anchoring (PRD exception11 records the actual v1
   omission); children share restore access without independent anchors. SnapshotService/SideGitManager restore
   and repository behavior remain unchanged.
   **Ticket 20 refinement (2026-10-23):** the trace's session listener only queues MAIN timing/usage observations.
   A generic MAIN run-end owner observation queues immutable presented messages, cached prompt and current model;
   the owned writer lazily serializes that final history before CLI answer output. This preserves prepared inputs,
   pure custom-kind/Summary/image views and finalized batch content without serializing source log entries or doing
   projection/I/O in subscribers. Blocked or unreadable input leaves an existing trace untouched. Writer failures
   are reported before answer stdout and do not cancel model work; every owned resource still closes.
   PRD exception12 records the isolated correction of v1's dropped final reasoning and zero/stale non-natural-run
   usage. Totals count completed MAIN responses only, excluding partial ERROR/ABORTED, children and compaction calls.
8. **`ai` is data-first and blocking.** Provider-neutral content blocks (text, thinking with signature, tool call,
   image); a model is a registry record (context window, capabilities) passed with each request to a stateless adapter;
   streaming is a pull-based event stream iterated on a virtual thread; failures are classified retryable (retried
   inside `ai` with backoff), overflow, aborted, or fatal. `core` is blocking too, with explicit cancellation tokens and
   synchronous subscribers that only enqueue.
   **Ticket 17 refinement (2026-10-22):** an explicit immutable routing provider selects an injected adapter by the
   profile model's provider identity. Unsupported identities produce typed FATAL rather than sending another
   provider's model to the main adapter. Production only registers currently available/configured adapters;
   remaining provider ports and model switching still belong to ticket21.
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
- **Rewrite the logged user entry when a skill body is drained.** Rejected in ticket14: it destroys source input
  provenance. A separate user-like body message also changes v1's single-message placement. Declare a custom
  prefix kind and merge only in pure model presentation; its serialized source entry remains separate.
- **`convertToLlm` in `ai`.** Rejected: `ai` would have to import harness message kinds, inverting the layering.
- **Closed sealed hierarchy listing every message kind in `core`**, or a generic kind-plus-JSON record. Rejected: the
  first makes `core` know product concepts; the second throws away compile-time types in the harness.
- **One kind of context stage that may both transform and write.** Rejected: an LLM-backed compaction re-run on every
  request is expensive and unstable, and a log that projection can mutate is not replayable.
- **Apply source-indexed summaries after arbitrary view stages.** Rejected in ticket 12: filtering even one hidden
  entry shifts coverage into retained entries. Carrying source-position wrappers through every stage would also
  solve this, but enlarges every stage's interface; inferring identity from equal/transformed entries is ambiguous.
  Normalize source ranges first, then compose ordinary view stages in their declared order.
- **Parse image references before merging skill text, or parse the two pieces separately.** Rejected in ticket16:
  v1 parses the combined text. An image-only user input must not gain the parser's default analysis sentence when a
  skill prefix already supplies text, and prefix references must become attachments with one combined disclaimer.
- **Load images during projection/conversion, or mutate an already appended UserEntry.** Rejected in ticket16:
  repeated file/clipboard reads make replay depend on current external state, while source mutation loses provenance.
  Prepare once before append, serialize the source-bound presentation, then transform immutable views.
- **Move all run-start writers before the user append to accommodate images.** Rejected in ticket16: memory writers
  already read the current user from agent.messages(). Only input/prefix preparation moves earlier; existing writers
  retain their lifecycle. Attributing prefix-origin attachments to the source user was also rejected; offsets from
  the one parse retain provenance without reconstructing the parser's fallback and whitespace algorithms.
- **Guards as `beforeToolCall` participants.** Rejected: then "guards are absolute" depends on callback order, which
  is exactly the kind of property that silently breaks. Inside `execute` it holds by construction.
- **Approvals inside each tool's parallel execution, serialized by a lock** (today). Rejected: cards appear in
  lock-contention order and a tool can run while its sibling's approval is pending.
- **Delay every ToolEnd until aggregate result budgeting finishes.** Rejected in ticket13: a completion subscriber
  can unblock a sibling, so delaying it until all siblings finish deadlocks. A separate finalized-batch event keeps
  immediate execution and source-order model publication explicit. Progressive per-call budgeting was rejected
  because it changes the existing largest-first aggregate allocation; shared current-call maps lose parallel identity.
- **Submit nested tool execution to the same four-worker pool.** Rejected in ticket15: four StepSearch outer calls
  can occupy every worker while awaiting their children. Inline nested execution plus owner callback rendezvous
  keeps permission ownership without increasing the batch's execution concurrency.
- **Call StepSearch or shared-browser tools directly, or publish nested calls as model results.** Rejected in
  ticket15: direct calls bypass hooks, approvals and audit; ordinary model events introduce unmatched results and
  duplicate UI/trace blocks. An explicit auxiliary completion observer preserves both permission and provenance.
- **Run freshness network I/O on the owner, or keep an invoker alive until the whole batch ends.** Rejected in
  ticket15: the first prevents timely cancellation, and the second lets a finished parent's retained port trigger
  effects while a sibling is still running. Standalone work is cancellable; nested ports end with their parent.
- **Treat sensitive-page approval as a Hook ASK route.** Rejected in ticket15: v1 skips browser preview on Hook
  ALLOW and with HITL disabled. Separate normal-route approval notices preserve that behavior, while browser refusal
  still runs inside execution and cannot be approved away.
- **Put a generation suffix in MCP result scopes to simplify failed-clear cleanup.** Rejected in ticket13: canonical
  scopes remain `main` / `task_N`. An unused replacement owns no files; old-scope cleanup completes before the new
  agent executes. Reference/close synchronization prevents late cancelled workers from recreating a closed scope.
- **All capability state session-scoped, in maps keyed by agent id.** Rejected: that is how per-agent state leaks
  across `/clear` and task reaping. A service locator or DI container was rejected because it hides the dependency
  graph the architecture tests are meant to pin.
- **Let the spawning capability look up a session factory, or construct an unrelated factory per child.** Rejected
  in ticket17: lookup hides the graph, while separate factories lose the shared session event/capability ownership.
  Explicit construction followed by one session binding keeps injection visible and child attachment identical.
- **Rely only on worker finally blocks to clean foreground children.** Rejected in ticket17: ToolBatch can abandon
  a worker on cancellation/timeout before it unwinds. Parent run-finally closes registration, cancels and awaits
  admitted children; old workers cannot enter a newer run's scope. Sharing the parent's token was rejected because
  a child must not cancel its parent or siblings.
- **Defer every busy background completion until another run, as actual v1 does.** Rejected with maintainer approval
  in ticket18: the parent should consume finished work at its next iteration. PRD exception10 names that timing
  change; final-boundary take-back and idle push remain required so late completions are not stranded.
- **Keep ticket17's close-on-foreground-return ownership for retained tasks.** Rejected in ticket18: continuation
  would reuse an already closed agent and MCP scope. Idle retention must preserve those resources until reaping or
  session shutdown. Closing the main agent alone must not orphan session-owned Detached work.
- **Import v1 task policies into the v2 harness, or duplicate their algorithms.** Rejected in ticket18: the former
  crosses the prohibited spine boundary, the latter splits admission/LRU/mirror semantics. Extract the independent
  policies as neutral leaves and keep legacy configuration-reading shims for v1; task lifecycle stays in harness.
- **Serialize source log entries or ordinary per-delta events directly as compatible trace records.** Rejected in
  ticket20: source input differs from its prepared presentation, source tools can still hold images/full MCP data,
  and normalizers would count duplicate assistants. Use immutable owner-presented history and a separate writer.
- **Imitate dropped final reasoning and lastRunUsage's natural-return-only assignment in traces.** Rejected within
  the delegated ticket20 implementation scope: it discards available observability and yields wrong or stale run
  totals. Exception12 isolates the correction instead of silently claiming v1 metric parity.
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
