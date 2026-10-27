# 08: Permission chain and `Approver`

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` permission chain) · ADR 0020 D6 · ADR 0019
**Blocked by:** 07
**Status:** done (2026-10-21)

**What to build:** Calls that need approval are asked about **in the order the model wrote them**, nothing in the
batch runs until every answer is in, and a refusal reaches the model as a `DENIED` result — the same approval policy
as v1.

- An `Approver` port: blocking, cancellable, and serializing concurrent requests from different agents. Tests use a
  scripted approver; headless uses the same approval behavior as v1 headless.
- The approval policy ported from v1's HITL registry into a permission participant in `beforeToolCall`: which calls
  need approval, the session's "always allow" memory, approval on / off. (The browser check joins in ticket 15.)
- Cancelling a run releases its pending approvals as denied.
- The audit log becomes a listener on permission-chain events.
- Guards stay out of this chain — they run inside tool execution.

- [x] Seam 1: a three-call batch is asked about in source order, and no tool start event occurs before the third
      answer.
- [x] Seam 1: a refusal produces `DENIED`, and the next request carries the same refusal text v1 produces.
- [x] Seam 1: cancelling while an approval is pending releases it as denied and ends the run cancelled.
- [x] Seam 1: "always allow" skips later approvals for that tool in the session.
- [x] Audit entries for allow and deny match v1's.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-21 (from ticket 05 review).** `AgentScope.beforeToolCall` / `AgentCallbacks.beforeToolCall` currently return null to continue or a `ToolResult` to short-circuit execution; all calls are preflighted in source order before any ToolStart. `afterToolCall` transformations run on the loop thread before ToolEnd and logging. The approval reply contract includes `m {json}` argument rewriting, which the current null/result preflight shape cannot express. Add a typed preflight decision/adjusted-call return here, before porting that behavior, rather than hiding rewritten arguments in shared state. Policy/Approver composition itself was deliberately not implemented in 05.

**2026-10-21 (leader acceptance).** Implemented cancellable session-serialized `Approver`, source-order preflight before any execution, per-tool/server remembered approvals, disable-clears-grants, v1 refusal/skip wording, and typed permission events. `ToolPreflight` carries effective calls through subsequent participants; original assistant calls remain in the log, while leaves/ToolStart/ToolEnd/afterToolCall see edited arguments. Guard refusal remains absolute after edits. Headless remains automatically approved with no terminal reader/event loop.

Audit uses typed execution provenance (`ToolExecution`) rather than status/text guessing: returned business ERROR is v1 allow; thrown exception is error; guard exception is policy deny; malformed JSON for a registered mutating tool is error; unknown/disabled/preflight failures have no execution audit. Listeners enqueue immutable events and observation timestamps; one owned writer builds/sanitizes entries and writes files. `SessionRegistrar.onClose` plus `AgentSession.close` provide reverse cleanup, partial-construction cleanup, cancel/await before drain, idempotence, and preserved interruption. CLI owns the session with try-with-resources. Audit directory is explicitly loaded with current/legacy property/env precedence.

Review fixes have red-to-green evidence: four stale pending always-allow replies across disable/re-enable; blocked synchronous audit writer stalling the run; delayed queue timestamps. New regressions also cover blocked disk versus cancellation/ToolEnd/other approvals, interrupted close/flush draining exactly once, cleanup after failures, cross-agent approval serialization and queued cancellation. Standards and Spec rereviews both clean. Implementer focused23 suites: **274/0/0/0**. Independent leader `env -u NO_COLOR TERM=xterm mvn test -Pquick`: **1574/0/0/0**, including8 architecture rules. `mvn clean package` passed. Packaged fake-provider run verified automatic write with exact bytes, root-external victim unchanged on guard refusal, malformed-input error audit, unknown-tool audit exclusion, and all3 JSONL records durable immediately at process return. Audit records follow observation/completion order, while tool results retain source order. No real provider/network/keys used. No render implementation changed; real-terminal acceptance remains deferred. Local commit only, no push.
