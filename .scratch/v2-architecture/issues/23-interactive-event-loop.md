# 23: Interactive event loop

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` AgentSession) · ADR 0020 D7 · ADR 0005
**Blocked by:** 18
**Status:** ready-for-agent

**What to build:** The session's interactive mode: user submissions and task completions are events on one serial
executor, and a task that finishes while the session is idle starts an autonomous run — ADR 0005's event loop, moved
into the harness, unchanged in substance. Still no terminal (that's ticket 24).

- One serial executor is the only writer of the main agent's log.
- User submits and task completions are events; at a run boundary every pending event is drained into one run
  (several completions → one run).
- A task completion while idle starts an autonomous run; while busy it goes through the inbox (ticket 18), and a
  submit while busy is steering (ticket 09).
- Busy ⇄ idle activity notifications for subscribers.
- Auto-background is on in this mode.

- [ ] Seam 1: two completions while busy are drained into one autonomous run containing both.
- [ ] Seam 1: a submit while busy lands as steering at the next iteration boundary.
- [ ] Seam 1: busy / idle notifications fire on both transitions.
- [ ] `AgentSessionTest`'s cases, re-expressed against the v2 session, pass.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** `core.Agent.run(UserMessage, token)` takes a user message. Autonomous runs
(task-completion push) have no user input: add a `run` variant that starts from custom entries (e.g. a task
notification kind), or render the notification as user content in the harness — decide here, and keep the
v1 request bytes for the push case.

**2026-10-21 (from ticket 09 review).** Synchronous `submit` accepts concurrent input as `SubmitResult.SteeringAccepted`; completed submissions carry `RunOutcome`. Pending Steering is removed at run end atomically with closing acceptance: cancelled steering is discarded; otherwise `takeFollowUpInputs()` drains ordered ordinary inputs, including after a callback/subscriber throws. Drain only after submit returns/throws or awaitIdle (RunEnd precedes this finally-owned handoff), then post each as UserSubmit through this event loop. Ticket09 deliberately has no auto-runs. Nonsteering inbox entries survive cancellation; landed steering remains append-only history. Preserve these seams when moving ownership to the serial executor.

**2026-10-21 (input parity gap found during ticket 13).** Source verification contradicts the earlier ticket10 handoff: v1 `InteractiveRepl` prepares input before attempting steering; `Main` prepares MCP mentions, then local `@path`, then UserPromptSubmit. Current v2 only prepares ordinary submissions and has no local-path expansion. Reconcile this in the composed interactive submission path here: busy input must receive the same expansion / UPS gate, BLOCK must not reach the inbox/provider, and a submission racing run completion must not be lost or prepared twice. Preserve explicit cancellation, preparation outside the session lock, and atomic admission/late-input ownership. Make the existing `cli/LocalPathMentionExpander` a reusable leaf without importing the v1 CLI into harness/UI. Add actual-composition seam tests for ordinary, busy, blocked, and boundary-racing input. Ticket27 must include them; this gap is not an intentional behavior change.

**2026-10-22 (ticket 15 integration checkpoint).** Idle command dispatch now reserves session admission and its
cancellation token before running the handler outside the lock; cancel/close/awaitIdle cover that reservation.
Submission during a command rejects rather than steering into an idle agent. Preserve these guarantees when moving
work to the serial executor. Browser `/tabs` invokes tools through an owner-only auxiliary route with hooks,
approval and audit; do not replace it with raw MCP execution or publish auxiliary model results. A command owner may
perform `/clear` within its own reservation; arbitrary run callbacks may not.

**2026-10-22 (ticket 16 preparation checkpoint).** Input preparation inside core now resolves combined skill/image
input once before source append and retains cached presentation; ordinary run-start writers still receive raw input
after that append. Keep submission expansion/UPS ordering outside this seam and do not parse images twice when
moving session ownership to the executor. Busy-input preparation and autonomous runs must preserve image lifetime
and actual run boundaries; historical-image projection currently keys its cutoff to actual source UserEntry values,
so an autonomous run without one needs an explicit compatible cutoff rather than synthetic projection users.
