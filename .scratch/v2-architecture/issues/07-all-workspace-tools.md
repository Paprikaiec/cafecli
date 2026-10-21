# 07: All workspace tools

**Parent:** `.scratch/v2-architecture/PRD.md` (milestone 2; `core` tool interface) · ADR 0020 D6 · ADR 0001, 0019
**Blocked by:** 05
**Status:** ready-for-agent

**What to build:** A v2 run can do real coding work in the project: write, edit, list, scaffold, find, grep, and run
commands — with the same results, statuses, and safety as v1.

- The workspace capability adds `write_file`, `edit_file`, `list_dir`, `create_project`, `glob_files`, `grep_code`,
  and `execute_command`, adapting the leaf tools. (`search_code`, web, browser, session, and task tools arrive with
  their own capabilities.)
- `edit_file` is not offered when config disables it (today: `-Dcafecli.edit_file.disabled=true`).
- Statuses carry over unchanged (ADR 0019); a command that ran with a non-zero exit code is `OK`.
- Guards and file mutation locks stay inside the tools.
- Running-command output flows out as tool update events; a write's diff travels as structured details on the tool
  end event.

- [ ] Seam 1: a write outside the project root is `DENIED` and the file is not created.
- [ ] Seam 1: two `edit_file` calls on the same file serialize; on different files they overlap.
- [ ] Seam 1: a command exiting 1 is `OK` with the exit code in its text; a long command emits update events before
      its end event.
- [ ] Seam 1: a `write_file` end event carries the diff details.
- [ ] `edit_file` is absent from the recorded tool list when disabled.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-21 (from ticket 05 review).** Core now schedules tools and the workspace capability exposes `read_file` through `tool.WorkspaceTools` + `harness.workspace.LeafToolAdapter`. Extend that leaf-side factory rather than constructing a v1 registry. Three concerns belong here:

- Single calls currently execute inline, without a batch deadline; helper-thread progress is buffered until execution returns. Command output must be delivered while a single command is still running. A one-worker queue pump without a single-call deadline is an option; keep callbacks/events on the loop thread and discard retained callbacks after ToolEnd. The current core single-call test pins inline execution and will need adjusting if the mechanism changes.
- Core's batch deadline defaults to 90 seconds and has a builder override. Wire the typed command configuration into the factory here: v1 uses `max(commandTimeout + 5, 90)` (default command timeout 60 seconds). Core must not read properties itself.
- Leaves still use v1 `runtime.CancellationContext`. Do not start its global run token from v2 or import the v1 runtime into v2; bridge cancellation through a leaf-owned seam/thread interruption as needed. Preserve guard and mutation-lock behavior inside execution.
