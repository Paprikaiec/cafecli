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

- [ ] Seam 1: a background spawn returns at once; its completion during the parent's run appears at the next
      iteration boundary.
- [ ] Seam 1: `send_message` continues an idle task with its history; a reaped task's state is gone (prior art:
      `SendMessageTaskOutputTest`).
- [ ] Seam 1: cancelling the parent run cancels an attached child and leaves a detached one running.
- [ ] Seam 1: with interactive mode simulated, a slow foreground spawn auto-backgrounds; in headless it doesn't
      (prior art: `AutoBackgroundSpawnTest`).
- [ ] `mvn test -Pquick` and the architecture tests are green.
