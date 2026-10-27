# 18: Background tasks

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` task management) · ADR 0020 D7 · ADR 0005, 0006
**Blocked by:** 17
**Status:** ready-for-agent

**What to build:** Background tasks work in v2 as in v1: spawn in the background, keep working, get notified when the
task finishes, continue an idle task, and cancel the right things.

- Background spawn returns a task id immediately. Task registry, admission control, the idle reaper, and the bounded
  message mirror (leaf reuse where it fits). `send_message` continues an idle task; `task_output` reads results.
- Lifecycle: running → idle (history kept, continuable) → reaped (state gone, including the agent's result scope).
- A completion while the parent's run is in flight lands as a task-notification entry through the parent's boundary
  inbox. The idle-time push (an autonomous run) is ticket 23.
- Cancel scope: the cancel path cancels the current run and its **Attached** tasks; **Detached** tasks stop only via
  `/task cancel`.
- Auto-background of slow foreground spawns is a session-mode setting: on in interactive mode, off in headless (as
  today).
- `/task` commands.

- [x] Seam 1: a background spawn returns at once; its completion during the parent's run appears at the next
      iteration boundary.
- [x] Seam 1: `send_message` continues an idle task with its history; a reaped task's state is gone (prior art:
      `SendMessageTaskOutputTest`).
- [x] Seam 1: cancelling the parent run cancels an attached child and leaves a detached one running.
- [x] Seam 1: with interactive mode simulated, a slow foreground spawn auto-backgrounds; in headless it doesn't
      (prior art: `AutoBackgroundSpawnTest`).
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-22 (ticket 17 integration checkpoint).** Foreground spawning now uses one explicitly injected factory,
child virtual threads and one-way linked tokens, with parent-token/generation admission. Generic runStart lifecycle
participants initialize before ordinary writers; runEnd participants execute in core's finally before execution
admission releases, including callback/subscriber failures. Current foreground-only runEnd closes registration,
cancels/awaits admitted children and closes their per-agent resources; adapt that ownership here to retain idle
children and preserve Detached work, rather than cancelling every task or reusing an already closed agent. Publish
idle/completion only after child.run's finally completes, not merely on its RunEnd event. Session connections stay
alive; reaping closes child scope resources. TaskSpawner is the shared leaf port, with v1 compatibility interface;
TaskTools currently registers only spawn in v2. Current headless background=true follows v1 synchronous fallback;
make intentional session-mode behavior explicit as this ticket adds the actual background path. Preserve result-only
legacy presentation separately from typed run/task state and outer ToolOutput status, including failure-looking
normal answers, blank-final fallback excluding intermediate tool narration, and child freshness silence.

**2026-10-20 (from ticket 04b review).** A task notification posted to an agent's inbox after the run's last boundary
stays queued in `core.Agent` until the next run starts, unseen — see the note on ticket 09: the inbox needs a take-back
at run end so the session (ticket 23) can start an autonomous run for a completion that missed the boundary.

**2026-10-21 (from ticket 13 review).** A reaped task must close its idle core Agent after cancelling/awaiting any active run; AgentScope.onClose releases MCP scope files and prompt invalidators, and attempts all resources with suppressed failures. Keep session connections open until session close. Canonical result scopes are task_N, not generation-suffixed; persistence/close admission prevents late cancelled workers recreating closed scope files. Session close removes the entire unique session result tree after agent and connection cleanup.


**2026-10-22 (approved ownership refinement; implementation/acceptance pending).** Task lifetime is session-owned;
retained core Agents and their capability resources survive idle completion until reap/session close. Publish idle
and completion only after run-finally cleanup. Attached work follows its waiting parent's cancellation; explicit
background and auto-background work are Detached. Session shutdown cancels/joins all work and attempts all cleanup,
including callback failures. Captured task settings default headless; interactive background/auto-background can be
simulated at seam1 here, while executor/idle push remain23. Preserve actual v1 grammar/text and headless fallback.
The three independent admission/LRU/mirror policies may move to neutral leaves with v1 fromProperties compatibility
shims; the harness owns its typed lifecycle registry and bounded task presentation. This deliberately replaces17's
immediate foreground close: MCP scopes remain available while idle and are released on reap/session close.


**2026-10-22 (source/spec timing conflict; maintainer choice pending).** Actual v1
`agent.AgentSession.onBackgroundTaskDone` posts a TaskDone event to its serial queue; its executor drains that queue
only after the current run finishes, then runTurn presents the notifications in a new run. Ticket18 instead asks
for a completion during the parent's run at its next iteration boundary, and ticket23 repeats the busy-inbox rule.
This timing change is not in the current PRD parity whitelist. The leader asked the maintainer asynchronously whether
to approve the specified next-iteration timing as an explicit new intentional change, or preserve v1's later-run
behavior and revise the affected ticket expectations. No answer or new whitelist approval is assumed. Current
next-iteration implementation/test evidence remains provisional while independent lifecycle work continues.


**2026-10-23 (leader verification complete; timing choice blocks acceptance/commit).** Session-owned retained Agents
now support foreground/headless fallback, simulated interactive background and auto-background, idle continuation,
bounded projected trajectories, admission/depth limits, LRU reaping, Attached/Detached cancellation and `/task`.
Three independent policy leaves moved to the neutral task package with v1 compatibility subclasses; old assertions
remain unchanged. Captured property precedence and Integer.decode / Long.decode semantics preserve v1 validation.
Controls can inspect/cancel during a busy main run, and close/awaitIdle drain their separate admission. Clear keeps
the installed ready inbox until replacement binding succeeds, migrates pending task notifications and rolls back
failed candidates. Retained MCP payloads survive idle and close on reap/session shutdown. Every task execution stays
tracked through final cleanup, including callback/notification failures and reaped executions.

Independent review found three defects and the same implementer corrected all three. Timeout during blocked victim
cleanup previously lost the completion (expected one notification, actual zero); final detach/delivery/finished now
share one atomic transition after cleanup. Concurrent nested foreground workers could queue two acquisitions for one
parent lease; wait registration now precedes launch under the task lock, one pending owner restores the lease and
completion prevents late restoration. That exact private scheduling path is source-validated, not experimentally
reproduced; the public regression passes40 barrier-controlled nested parallel rounds with concurrency1, cancellation
of occupied/queued work and fresh subsequent admission. Active continuation inspection previously omitted its current
user; the generic factory observer now publishes immutable pure projected snapshots on the owner at runStart,
iteration boundary/overflow and iterationEnd. First-run inspection retains v1 expiration until its first completion.
No mutable-log reads from inspectors, event-subscriber projection work, custom-kind sniffing or new core product hook.
Both final full-scope Standards and Spec rereviews are clean on implementation, with the timing dependency separate.

Implementer final focused: **61/0/0/0**; final full suite: **1909/0/0/0**, BUILD SUCCESS. The earlier full run
**1898/1/0/0** was a missing enumerated TaskNotification round-trip fixture, subsequently added; it is not reported as
green evidence. Independent leader quick223 suites: **1805/0/0/0**; named106 suites: **873/0/0/0**, including the
extracted policies' old tests, task/spawn/cancellation contracts, architecture8 and core/harness/app/tool/MCP/Web/
RAG/browser/ai/images coverage. Clean package passed. `/tmp/cafecli18-jar-check.py` compiled against the jar and
verified actual Composition headless background fallback, retained continuation/trajectory, stable task IDs, LRU
expiration, `/task`, and round-trip. Its simulated-interactive packaged fixture cancelled one Detached child while
the main run stayed busy, then cancelled the parent while another child remained alive, then explicitly cancelled
that child and joined on close. Its actual CLI/local stdio MCP fixture read exact task payload bytes while idle,
confirmed task_1 scope removal on LRU reap with task_2 scope/session connection intact, and final session cleanup.
Packaged08/10/10-session/11/12/13/14/15/16 all passed. Retention-compatible17 checks also passed configured Pre/Post
session/task identities, child writes, exact result-only text, typed status, separate logs, audit and freshness silence;
18's MCP fixture supersedes17's deliberately temporary immediate foreground-scope-close assertion.

Logs: `/tmp/cafecli18-{focused-post-review,full-final,review-parallel,review-green2}.log` and
`/tmp/cafecli-ticket18-leader-{quick,named,package}.log`. Package fixture root:
`/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket18-qff6a6jf`.
The maintainer has said to continue work but has not selected the timing policy; no parity exception is inferred.
The first acceptance box, whitelist update and local commit remain pending that choice. No19 work has begun.
No push, real keys/external services, render changes or real-terminal claim; unrelated scratch remains untouched.


**2026-10-23 (maintainer approval and leader acceptance).** In response to the concrete approval question about
keeping next-iteration delivery, documenting a parity exception, committing18 and continuing19–28, the maintainer
answered “可以，按你的想法做吧”. This explicitly approves the implemented next-eligible-iteration timing. PRD whitelist
exception10 and ADR0020 now record it; the pending timing dependency above is resolved. All acceptance boxes are
satisfied and the already completed full/focused/independent/package evidence remains valid: only documentation
changed after verification. Both review axes have zero remaining implementation findings. Proceed sequentially
through28, with no push or AGENTS edit and no29 / real-terminal acceptance without the later approval.
