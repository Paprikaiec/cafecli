# 20: Trace capability

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` trace; contracts — `--trace-file`) · ADR 0020 D6
**Blocked by:** 05
**Status:** ready-for-agent

**What to build:** `run --file --trace-file trace.jsonl` works under v2 and the eval tooling reads the result exactly
as it reads a v1 trace.

- The trace is a capability that listens to the event stream; it is not the agent log.
- **Contract**: the record types and fields the eval normalizer reads — one `assistant` record per assistant message
  with its tool calls' `id`, `name`, and `arguments`; `system`, `user`, and `tool_result` records as v1 writes them;
  the token-totals record with provider, model, and wall time. Match v1's field names and record set.
- Richer records (deltas, timings, retries, tool durations) only under **new** `type` values: the normalizer skips
  unknown types but counts every `assistant` record as an iteration.

- [ ] A v2 trace of a scripted run has the same record types and fields as v1's trace writer output for the
      equivalent run (prior art: `AgentTraceWriterTest`).
- [ ] The eval normalizer over a v2 trace yields the expected iteration count, tool breakdown, and token totals.
- [ ] No record type other than v1's uses the `assistant` type value.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-22 (ticket 16 preparation checkpoint).** UserEntry now separates actual source input from optional cached
combined skill/image presentation, with a prepared-prefix count to prevent duplicate merging. Trace's v1 user text
and image_parts describe presented messages, not raw @image source references. Tool-image presentation adds a user
message after all tool-result messages in that batch; source tool results still retain their neutral ImageBlocks.
Preserve those v1 records without counting cached preparation or view-only image users as assistant iterations, and
without repeating image file/clipboard reads. Historical placeholders are a pure view; the source log keeps payloads.

**2026-10-21 (from ticket 08 review).** Permission decisions have a separate typed `PermissionEvent` subscription; core events retain effective execution arguments while the assistant log preserves the original model call. Synchronous listeners only enqueue. `AuditCapability` demonstrates an owned writer with captured observation times and drain-on-close; use `SessionRegistrar.onClose(AutoCloseable)` to keep headless trace records durable before CLI return.

**2026-10-21 (from ticket 12 review).** Ticket 12 adds AgentEvent.Compaction(origin, fromInclusive, toExclusive) through the session event stream. Compaction summary provider requests are internal writer work and do not emit ordinary assistant iterations; preserve the normalizer contract of one assistant record per assistant message with its tool calls, without emitting per-delta or compaction-summary assistant records. BudgetExit.Tokens/Stagnation are typed harness reasons; CLI formatting remains outside core.

**2026-10-21 (from ticket 13 review).** `ToolEnd` reports immediate execution completion, before the optional generic batch normalization. MCP's reused aggregate budget can replace result content afterward; changed batches emit `ToolBatchEnd` with final source-order entries. Publish v1-compatible tool_result records from finalized batch content (collect immediate results, replace on ToolBatchEnd, flush at IterationEnd); do not emit duplicate tool_result records. Audit still uses immediate typed execution provenance. Assistant record count remains unchanged. Request-time tools are snapshotted for both schemas and execution.
