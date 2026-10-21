# 07: All workspace tools

**Parent:** `.scratch/v2-architecture/PRD.md` (milestone 2; `core` tool interface) · ADR 0020 D6 · ADR 0001, 0019
**Blocked by:** 05
**Status:** done (2026-10-21)

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

- [x] Seam 1: a write outside the project root is `DENIED` and the file is not created.
- [x] Seam 1: two `edit_file` calls on the same file serialize; on different files they overlap.
- [x] Seam 1: a command exiting 1 is `OK` with the exit code in its text; a long command emits update events before
      its end event.
- [x] Seam 1: a `write_file` end event carries the diff details.
- [x] `edit_file` is absent from the recorded tool list when disabled.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-21 (from ticket 05 review).** Core now schedules tools and the workspace capability exposes `read_file` through `tool.WorkspaceTools` + `harness.workspace.LeafToolAdapter`. Extend that leaf-side factory rather than constructing a v1 registry. Three concerns belong here:

- Single calls currently execute inline, without a batch deadline; helper-thread progress is buffered until execution returns. Command output must be delivered while a single command is still running. A one-worker queue pump without a single-call deadline is an option; keep callbacks/events on the loop thread and discard retained callbacks after ToolEnd. The current core single-call test pins inline execution and will need adjusting if the mechanism changes.
- Core's batch deadline defaults to 90 seconds and has a builder override. Wire the typed command configuration into the factory here: v1 uses `max(commandTimeout + 5, 90)` (default command timeout 60 seconds). Core must not read properties itself.
- Leaves still use v1 `runtime.CancellationContext`. Do not start its global run token from v2 or import the v1 runtime into v2; bridge cancellation through a leaf-owned seam/thread interruption as needed. Preserve guard and mutation-lock behavior inside execution.

**2026-10-21 — done (leader review).**

- WorkspaceCapability now exposes all eight workspace tools through the existing leaves: read/write/edit/list/scaffold/glob/grep/command. `WorkspaceTools.execute` creates invocation-scoped leaf environments with explicit cancellation and captured command/write observers; concurrent calls have no shared current-call slot. FileTools gained cancellation and edit-disable overloads whose existing defaults preserve v1 behavior; guards and mutation locks remain inside execution.
- Single calls now use the same worker/queue pump as parallel batches, at one worker and without a batch deadline. Helper-thread command output reaches subscribers live on the loop thread; subscriber failures propagate, late callbacks are discarded, and source-order preflight/logging remain intact. Structured write details add `diff.path/before/after` while retaining the full leaf output.
- Typed WorkspaceConfig holds edit disable and constructor-supplied command timeout (default 60, no new property). The loader preserves trimmed true/1/yes and cafecli/paicli precedence. The factory injects max(commandTimeout+5,90) as the multi-call deadline. Disabled edit is neither offered nor executable.
- Red evidence: outside-write wrong status, absent diff, unknown command/search tools, buffered single-command output causing a gated command timeout, and Boolean.parseBoolean rejecting legacy flag spellings. Bypassing FileMutationLocks produced a same-file concurrency failure at the inside-lock observer; restored code passes same-file serialization and different-file overlap. Additional session checks pin cancellation while waiting for a lock (no ghost write), simultaneous command attribution, command cancellation/next-run isolation, and leaf timeout. Standards: no findings. Spec: no findings.
- Leader independently verified quick **1545/0/0/0**, including architecture 8; named ToolRegistryTest **78/0/0/0**; clean package; real packaged write/edit/grep/command scenario with exact resulting file bytes; all eight workspace names/descriptions/schemas equal v1 by name (declared v2 order is intentional change 8). Implementer final targeted **227/0/0/0**. No render changes.
- The ticket 05 deferrals for live single-command progress, typed batch timeout wiring, and leaf cancellation bridging are resolved. Approval argument rewriting remains on 08. The old v1-only FileTools cancellation-constructor bridge is noted for cleanup on 29; no v1 behavior was changed.
