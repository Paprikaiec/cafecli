# 05: Tool calls through the loop

**Parent:** `.scratch/v2-architecture/PRD.md` (`core` tool interface and Tool Batch; Capability SPI) · ADR 0020 D5–D7
**Blocked by:** 01, 02, 04
**Status:** ready-for-agent

**What to build:** A v2 run can use tools: the scripted model calls `read_file`, the result comes back, and the
model answers from it.

- `core`: the tool interface (name, description, JSON schema, execute with call id, JsonNode input, cancellation, and
  a progress callback → result with content blocks, structured details, and a typed status — the six statuses of ADR
  0019); Tool Batch scheduling (preflight every call in source order, then execute in parallel up to 4, append results
  in source order); tool start / update / end events.
- `harness`: tool registration in the Capability SPI plus the per-agent scope; a workspace capability that exposes
  `read_file` by adapting the leaf tool, so the Guard still runs inside its execution; mapping of the leaf tool output
  (text, image parts, status) to the tool result.

- [ ] Seam 1: `read_file` then an answer; the second recorded request contains the tool result.
- [ ] Seam 1: two `read_file` calls in one batch overlap in time, and their results land in the log in source order.
- [ ] Seam 1: reading outside the project root yields `DENIED` from the Guard and no file content.
- [ ] Every log entry from these runs survives a serialization round-trip unchanged.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** Replace `core.ToolBatch` (package-private placeholder: every call answered as
an unknown tool, `ToolStart` / `ToolEnd` around each, results in call order). Keep: one result per call in call order;
`AgentLoop` appends the answer and its results **together after the batch** (so an exception never leaves a tool call
without its result); `ToolEnd` carries the whole `ToolResultEntry`. Add `beforeToolCall` / `afterToolCall` to
`AgentCallbacks` and status / details to `ToolResultEntry`. Open point: the unknown-tool text `未知工具: <name>` is
v1's exact text (kept for parity) but it is product wording living in `core` — and v1 shows tool results on screen too.
Decide whether core keeps it as model-facing text or the harness supplies it.
