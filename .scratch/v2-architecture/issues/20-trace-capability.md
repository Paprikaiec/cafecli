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

- [x] A v2 trace of a scripted run has the same record types and fields as v1's trace writer output for the
      equivalent run (prior art: `AgentTraceWriterTest`).
- [x] The eval normalizer over a v2 trace yields the expected iteration count, tool breakdown, and token totals.
- [x] No record type other than v1's uses the `assistant` type value.
- [x] `mvn test -Pquick` and the architecture tests are green.

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


**2026-10-23 (approved trace composition; implementation/acceptance pending).** MAIN events enqueue immutable
usage/timing only; MAIN runEnd captures immutable scope.messages(), cached prompt and model on the owner. The owned
writer lazily writes the final presented history, keeping it separate from the source log. This naturally uses
prepared skills/images/Summary views and finalized batches, with no image re-read, duplicate assistant or child
compatibility records. CLI flushes/closes before printing the answer; trace I/O failure does not cancel model work
and reports v1's failure stream/exit semantics. No file is opened for blocked UPS/unreadable input.
Source verification found v1 Agent.java:348 drops ordinary final reasoning and :366 assigns lastRunUsage only on
natural success. Under the maintainer's delegated implementation judgment, retain available reasoning and count
completed MAIN responses on all exits; exclude partial ERROR/ABORTED, children and internal compaction calls. PRD
exception12 names these isolated trace differences. No provider/core history behavior changes are implied.

**2026-10-23 (leader acceptance).** Actual headless Composition now attaches an owned TraceCapability when
`--trace-file` is present. MAIN listeners enqueue timing/completed-response usage; runEnd captures immutable final
presented history, the already cached prompt and actual model on the owner. The lazy writer serializes compatible
system/user/assistant/tool_result/result records at close, before answer output. It preserves original tool arguments,
prepared Skill/image/Summary presentation and finalized source-order batch results without source-log mutation,
image re-reads, duplicate assistants or child/internal-compaction usage. Pending prompt invalidation remains pending:
trace never rebuilds an unsent future prompt. Missing input and UPS BLOCK leave an existing trace untouched; I/O
failure occurs after model work and reports v1 stderr/exit1 semantics without the final answer or stacktrace.

Observed unavailable-flag and unsent-future-prompt reds turned green. Added the approved checked WriterOpener I/O
adapter seam while retaining lazy UTF-8 Path behavior. Its real-file session test passed the already correct close
drain; a controlled mutation returning early on interruption failed specifically because session.close returned
while output was held. Restored code waits for release, leaves complete exact JSONL durable and retains interruption.
The test finally always releases its gate and closes the real writer, including on cleanup failure.

Both independent final tracked/untracked Standards and Spec reviews are clean. Implementer full suite:
**1930/0/0/0**, 234 suites, BUILD SUCCESS; it preceded the cleanup-only test-finally hardening. Final focused
trace/CLI/architecture run after that edit: **40/0/0/0**. Independent leader quick229 suites:
**1826/0/0/0**; named120 suites: **957/0/0/0**; clean package passed. No global render-state writes or weakened
architecture tests. Logs: `/tmp/cafecli20-{full,resume-postcleanup,drain-mutation}.log`, fresh full XML in
`/tmp/cafecli20-full-reports`, and `/tmp/cafecli-ticket20-leader-{quick,named,package}.log`.

`/tmp/cafecli20-jar-check.py` passed against that actual jar with the real eval Python normalizer: four MAIN
assistants, read/edit/exec breakdown 1/1/1 and tokens in/out/cache **40/14/10**; original arguments, reasoning and
compatible result fields; child exclusion; prepared image text/image_parts after source deletion; six finalized MCP
results in source order, including aggregate references, without duplicates or leaked scopes; trace failure after
marker-file model work; unreadable input and blocked UPS preserving exact old trace bytes. Fixture root:
`/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket20-6_ic43_q`.
Packaged08/10/10-session/11/12/13/14/15/16/17-retained/18/19 regressions all passed against the same jar.

PRD exception12 and the parity checkpoint isolate the approved reasoning/usage correction. Ticket21 now records
that model switching must update actual trace model/prompt metadata. At29 retire/rebind the temporary legacy writer
reference in TraceCompatibilityTest and the legacy UserMessages image expectation in TraceSessionTest. No push,
real-terminal claim, AGENTS edit or unrelated scratch change.
