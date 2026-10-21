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
