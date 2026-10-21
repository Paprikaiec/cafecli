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

- [ ] Seam 1: a foreground spawn runs a child with its own log, and the parent's next request contains the child's
      result (prior art: `SpawnAgentToolTest`).
- [ ] Seam 1: two foreground spawns in one batch overlap (prior art: `ParallelSpawnAgentTest`).
- [ ] Seam 1: child events arrive tagged with the task id.
- [ ] Seam 1: a profile that omits a capability yields a child without its tools.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04c review).** `harness.AgentFactory` is built as a local inside `AgentSession`'s
constructor and holds a **single** `ai.Provider`; the SPI can't reach the factory. `spawn_agent` needs the factory
(through the capability that provides it — constructor-injected at the composition root, not looked up), and a
sub-agent profile whose model differs needs either a provider per profile or a provider that routes by
`Model.provider()`. Events: `core.AgentEvent.Origin(agentId)` is where the task id goes.

**2026-10-21 (from ticket 10 review).** Ticket10 now captures the session identity in each agent hook state; Pre/Post payloads must keep the parent sessionId and use the sub-agent taskId. Current configured-shell regression covers two runs / two sessions; add child payload assertions when this ticket exposes the child-agent seam. Built-in ledger / Finish Gate state remains per-agent, not shared with the parent.
