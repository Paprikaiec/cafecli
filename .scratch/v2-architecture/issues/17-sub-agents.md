# 17: Sub-agents

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` AgentFactory and profiles) · ADR 0020 D7 · ADR 0007 (reshaped)
**Blocked by:** 07, 09
**Status:** ready-for-agent

**What to build:** `spawn_agent` in foreground mode works in v2: the child runs as an ordinary core agent with its own
history and fresh per-agent state, and the parent sees its result.

- `AgentFactory` builds a sub-agent from a profile: model (may differ from the main agent's), system prompt, attached
  capabilities, budget. Each child gets fresh per-agent capability state — this replaces per-task registry instances
  (ADR 0007).
- `spawn_agent` (foreground): blocks the current run; several foreground spawns in one batch run in parallel through
  the Tool Batch.
- Child events reach session subscribers tagged with the task id.
- Background spawn, `send_message`, `task_output`, and the task lifecycle are ticket 18.

- [x] Seam 1: a foreground spawn runs a child with its own log, and the parent's next request contains the child's
      result (prior art: `SpawnAgentToolTest`).
- [x] Seam 1: two foreground spawns in one batch overlap (prior art: `ParallelSpawnAgentTest`).
- [x] Seam 1: child events arrive tagged with the task id.
- [x] Seam 1: a profile that omits a capability yields a child without its tools.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-22 (approved factory/lifecycle refinement; acceptance pending).** Composition constructs one factory and
injects it into session and subagent capability; after session hooks, the session opens it once with its declared
capabilities/event stream and rejects cross-session reuse. The same factory builds default children without the
spawning capability and injected custom profiles with their own model/prompt/capabilities/budget. Immutable provider
routing chooses by model provider; unknown adapters fail typed FATAL, without routing to an unrelated endpoint or
porting ticket21's remaining adapters. A neutral TaskSpawner leaf port with old-interface compatibility preserves
TaskTools schema/validation/text; only spawn_agent registers here. In current headless mode, background=true keeps
v1's synchronous fallback; actual background retention/continuation/reaping belongs to18.

Children run on their own virtual threads and distinct one-way linked tokens. Generic lifecycle start initializes
spawn scope before ordinary run-start writers, and run-finally closes registration/cancels/awaits admitted children
before parent admission is released, including callback/subscriber exceptions and abandoned workers. Token identity
and generation prevent an old worker entering a later run; failed attachment/child close cleans per-agent resources
while session MCP connections remain. All cleanup participants run and original failures retain suppressed cleanup
failures. Blocking cleanup stays outside event listeners. Child result presentation preserves v1 task labels and
exact failure/budget/cancellation text separately from typed outcome/outer normal-return tool status; child freshness
notices must not leak into main stdout. ADR0020/PRD record the decisions. No new parity exception is approved.

**2026-10-20 (from ticket 04c review).** `harness.AgentFactory` is built as a local inside `AgentSession`'s
constructor and holds a **single** `ai.Provider`; the SPI can't reach the factory. `spawn_agent` needs the factory
(through the capability that provides it — constructor-injected at the composition root, not looked up), and a
sub-agent profile whose model differs needs either a provider per profile or a provider that routes by
`Model.provider()`. Events: `core.AgentEvent.Origin(agentId)` is where the task id goes.

**2026-10-21 (from ticket 10 review).** Ticket10 now captures the session identity in each agent hook state; Pre/Post payloads must keep the parent sessionId and use the sub-agent taskId. Current configured-shell regression covers two runs / two sessions; add child payload assertions when this ticket exposes the child-agent seam. Built-in ledger / Finish Gate state remains per-agent, not shared with the parent.

**2026-10-21 (from ticket 13 review).** AgentScope.onClose owns reverse-order per-agent cleanup; core Agent is AutoCloseable and rejects close during a run. Cancel/await before closing a child. Session-owned MCP connections stay alive; each child attachment owns its result scope (canonical task_N), fresh budget adapters and prompt invalidator. Failed attachment cleans acquired resources. Dynamic tool definitions and executable adapters are snapshotted together per request. ToolEnd is immediate execution; changed normalized batches additionally emit ToolBatchEnd before atomic log publication.

**2026-10-22 (ticket 16 preparation checkpoint).** Core's generic prepareInput runs once before source append;
capability preparation stages drain the child-local skill buffer then parse combined image input, retaining raw
source/SkillBody provenance and cached presentation. Children should use their factory-built agent's preparation,
not preparse input in the spawning tool or share a parent cache. Ordinary memory/Hooks/freshness run-start timing
remains after the source user append. Preserve prepared metadata in child log round-trips and clear/reap isolation.


**2026-10-22 (leader acceptance).** Foreground spawning is wired into actual Composition through one explicitly
injected factory. Profiles select model/provider, literal or assembled prompt, capabilities and hard iteration cap;
children have independent logs and capability resources and omit recursive spawning by default. Two foreground
spawns overlap through the ordinary Tool Batch, tagged task_N events reach session subscribers, and only exact
result-only legacy text reaches the parent. Typed outcomes/outer tool status remain independent of task labels.
Fresh Hook ledger/Finish Gate, parent sessionId + child taskId in Pre/Post payloads, local skill/image preparation,
failed attachment cleanup, canonical MCP result scopes, cross-session factory rejection and stale-worker admission
are covered. Default headless background=true retains synchronous v1 fallback; retention/continuation/reaping remain18.

The handoff's newest test first failed to compile; correcting Emitter.done made the original fixture green even
before the production fix, so that is not behavioral red evidence. A strengthened session fixture required lifecycle
cleanup itself to interrupt an independently waiting child: before the correction it timed out instead of returning
the original subscriber failure, then passed after cancel/interrupt were separated. A second controlled waiter
mutation published the foreground result while the child still executed; restored production joins before rethrowing
callback errors. All admitted children receive cancellation/interruption and join attempts, parent admission remains
held until they stop, and original failures retain suppressed cleanup errors. Stable production was restored before
both fresh independent Standards and Spec reviews; both full tracked/untracked reviews were clean.

Implementer focused: **21/0/0/0**; full end-suite227 classes: **1893/0/0/0**, BUILD SUCCESS. Independent leader
quick222 suites: **1789/0/0/0**; named103 suites: **850/0/0/0**, including architecture8, core/harness/app,
ai/images, Web/RAG/browser/tools/MCP, registry/HITL shims and old spawn/task/cancellation contracts. Clean package
passed. `/tmp/cafecli17-jar-check.py` compiled against the jar and verified actual Composition child writes, tagged
events, exact parent text and typed status, separate history/round-trip, configured Pre/Post identities and durable
audit; actual CLI kept child freshness out of stdout. Its local stdio MCP fixture checked exact raw payloads in
canonical task_1, foreground child cleanup before parent continuation, continued session connection use and final
session cleanup. Earlier packaged08/10/11/12/13/14/15/16 regression fixtures all passed against the same jar.
The fixture's initial identity assertion incorrectly included Stop payloads (whose existing contract differs);
restricting it to the specified Pre/Post contract required no product change. Logs:
`/tmp/cafecli17-{cleanup-focused,full-final,cancel-listener-isolated-red,cancel-listener-isolated-green,waiter-cleanup-red}.log`
and `/tmp/cafecli-ticket17-leader-{quick,named,package}.log`. No real keys/external services, render changes,
real-terminal claim or push. Unrelated scratch directories remain untouched.
