# 08: Permission chain and `Approver`

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` permission chain) · ADR 0020 D6 · ADR 0019
**Blocked by:** 07
**Status:** ready-for-agent

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

- [ ] Seam 1: a three-call batch is asked about in source order, and no tool start event occurs before the third
      answer.
- [ ] Seam 1: a refusal produces `DENIED`, and the next request carries the same refusal text v1 produces.
- [ ] Seam 1: cancelling while an approval is pending releases it as denied and ends the run cancelled.
- [ ] Seam 1: "always allow" skips later approvals for that tool in the session.
- [ ] Audit entries for allow and deny match v1's.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-21 (from ticket 05 review).** `AgentScope.beforeToolCall` / `AgentCallbacks.beforeToolCall` currently return null to continue or a `ToolResult` to short-circuit execution; all calls are preflighted in source order before any ToolStart. `afterToolCall` transformations run on the loop thread before ToolEnd and logging. The approval reply contract includes `m {json}` argument rewriting, which the current null/result preflight shape cannot express. Add a typed preflight decision/adjusted-call return here, before porting that behavior, rather than hiding rewritten arguments in shared state. Policy/Approver composition itself was deliberately not implemented in 05.
