# 05: Tool calls through the loop

**Parent:** `.scratch/v2-architecture/PRD.md` (`core` tool interface and Tool Batch; Capability SPI) · ADR 0020 D5–D7
**Blocked by:** 01, 02, 04
**Status:** done (2026-10-21)

**What to build:** A v2 run can use tools: the scripted model calls `read_file`, the result comes back, and the
model answers from it.

- `core`: the tool interface (name, description, JSON schema, execute with call id, JsonNode input, cancellation, and
  a progress callback → result with content blocks, structured details, and a typed status — the six statuses of ADR
  0019); Tool Batch scheduling (preflight every call in source order, then execute in parallel up to 4, append results
  in source order); tool start / update / end events.
- `harness`: tool registration in the Capability SPI plus the per-agent scope; a workspace capability that exposes
  `read_file` by adapting the leaf tool, so the Guard still runs inside its execution; mapping of the leaf tool output
  (text, image parts, status) to the tool result.

- [x] Seam 1: `read_file` then an answer; the second recorded request contains the tool result.
- [x] Seam 1: two `read_file` calls in one batch overlap in time, and their results land in the log in source order.
- [x] Seam 1: reading outside the project root yields `DENIED` from the Guard and no file content.
- [x] Every log entry from these runs survives a serialization round-trip unchanged.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** Replace `core.ToolBatch` (package-private placeholder: every call answered as
an unknown tool, `ToolStart` / `ToolEnd` around each, results in call order). Keep: one result per call in call order;
`AgentLoop` appends the answer and its results **together after the batch** (so an exception never leaves a tool call
without its result); `ToolEnd` carries the whole `ToolResultEntry`. Add `beforeToolCall` / `afterToolCall` to
`AgentCallbacks` and status / details to `ToolResultEntry`. Open point: the unknown-tool text `未知工具: <name>` is
v1's exact text (kept for parity) but it is product wording living in `core` — and v1 shows tool results on screen too.
Decide whether core keeps it as model-facing text or the harness supplies it.

**2026-10-21 — done (leader review).**

- Core owns `AgentTool`, `ToolResult` (six statuses, neutral content, structured details), and batch scheduling. Agent-held tools supply request definitions; `prepareRequest` may filter them. `beforeToolCall` runs for all calls in source order before any tool events/execution; workers run up to four calls, while the loop publishes progress/completion and runs `afterToolCall`. Results are logged in source order together with the assistant entry. Cancellation and batch deadlines close every call; late progress cannot escape its batch.
- `AgentScope.tool` and pre/post-call participants compose in declared capability order, with fresh state per agent. `WorkspaceCapability` registers `read_file` through `LeafToolAdapter` and the behavior-preserving public `tool.WorkspaceTools` factory. The existing leaf guard remains inside execution. Composition enables the workspace in the real v2 entry.
- Product failure wording moved out of the placeholder core batch into harness `ToolFailures`; the core default is content-free typed failure. Leaf text/images/status and complete structured output are retained. Tool-result log details normalize JSON number representations and decode exact decimals only on that property; custom-kind serialization retains its existing behavior.
- Red evidence included placeholder unknown-tool/read failure, serialized reads failing the overlap barrier, start-before-preflight, continuous progress evading the batch deadline, retained inline progress after ToolEnd, swallowed subscriber exceptions, decimal precision loss, and unintended custom DoubleNode-to-DecimalNode conversion. The last two review findings were fixed and rechecked; Standards: no remaining findings. Spec: one numeric-scope finding resolved, no remaining findings.
- Leader independently verified `env -u NO_COLOR TERM=xterm mvn test -Pquick`: **1493/0/0/0**, including all 8 architecture rules; named `ToolRegistryTest`: **78/0/0/0**; `mvn clean package`; real packaged fake-provider CLI with read_file then answer: expected stdout, exit 0. Implementer final targeted suites: **148/0/0/0**. No render code changed.
- Deferred explicitly on tickets 07/08: live single-command helper-thread output, typed command/batch timeout configuration and cancellation bridging, and approval argument rewriting. No v1 execution path changed.
