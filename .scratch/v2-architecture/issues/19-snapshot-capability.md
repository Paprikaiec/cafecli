# 19: Snapshot capability

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` run lifecycle participants) · ADR 0020 D6 · ADR 0018
**Blocked by:** 07
**Status:** ready-for-agent

**What to build:** Snapshots work in v2 as in v1: every run is anchored before any tool runs, `revert_turn` restores
files, and a project whose `.git` was hijacked is detected and left alone.

- Reuse the snapshot service **unchanged** — bare creation, per-call work-tree binding, the restore fence, and
  file-mode branching are leaf behavior; this ticket only wires it.
- Anchoring is a run lifecycle participant (synchronous, at run start, before the first iteration); add that
  registration type to the SPI if it doesn't exist yet.
- `revert_turn` and the snapshot slash commands.
- Hijacked-project detection at session start disables snapshots for the session and emits the repair guidance, as
  today — read-only, never auto-repair.

- [x] `SnapshotDoesNotTouchProjectGitTest` and `SnapshotRestoreStaysInsideProjectRootTest` pass unchanged.
- [x] Seam 1: a run writes a file, `revert_turn` restores it, and anchoring happened before any tool start event
      (prior art: `SnapshotTimelineAnchoringTest`).
- [x] Seam 1: in a project whose `.git` is a gitfile pointing into the snapshot home, snapshots are disabled, the
      guidance is emitted, and the `.git` file's bytes are unchanged.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-21 (from ticket 08 review).** Audited tool adapters stamp `harness.ToolExecution` in result details: RETURNED (including business ERROR), POLICY_DENIED, or FAILED. Preserve execution provenance for `revert_turn`; audit must not sniff text prefixes or infer thrown failure from a business ERROR.


**2026-10-23 (approved integration shape; implementation/acceptance pending).** The existing lifecycle SPI gains a
compatible raw-input-aware runStart overload; anchoring still precedes every ordinary writer, including freshness
auxiliary tools. Main Snapshot lifecycle declares after tasks so post scheduling follows Attached cleanup. An earlier
session-only ownership capability registers that same snapshot instance for final drain/close after task resources;
no priority framework or service lookup. Default children retain shared revert_turn access but never anchor their
runs. Hijack guidance is session-start once and headless stderr. SnapshotConfig may gain an explicit-input overload
sharing its existing parsing/defaults; SnapshotService, SideGitManager and restore/fence logic stay unchanged.
Actual v1 HeadlessRunner omits anchoring, whereas ticket19 explicitly requires every main run. Follow that requested
contract under the maintainer's delegated implementation scope and record the difference as PRD exception11; do not
claim v1 headless already behaved this way or alter its implementation.


**2026-10-23 (leader acceptance).** Snapshot capability is wired into actual Composition: input-aware main lifecycle
anchors synchronously before ordinary run-start writers, including auxiliary tool work; children share the same
restore service without their own anchors. The feature declares after tasks for post scheduling after Attached
cleanup; its earlier session-only ownership contribution closes the writer after task/MCP users stop. Close waits
for real pending post I/O despite an earlier resource's interruption, then restores the interrupt flag and retains
suppressed cleanup failures. Snapshot command payload grammar, formatting and offset clamp match v1. Startup hijack
warning has a separate headless stderr sink; the configured and historical snapshot-root cases never rewrite the
gitfile or create the hijacked target. SnapshotService, SideGitManager, HijackedProjectGit and restore/fence/mode logic
remain unchanged. SnapshotConfig's explicit-input parser shares legacy defaults; SnapshotTools extracts only the
existing restore definition/body with SessionTools delegation. Present blank properties still shadow env, relative
roots bind to captured project paths and the existing numeric/exclude rules are retained.

Observed missing-anchor, command-registration, ignored-config, actual-composition restore and stderr-warning reds
turned green. A controlled omission of writer draining let session.close return while real post I/O remained held;
restored code waited, retained interruption and committed exact post-blob bytes after release. Eleven new tests cover
session, Composition/CLI and configuration. Both independent full tracked/untracked Standards and Spec reviews are
clean. Implementer focused: **155/0/0/0** plus final strengthened Composition **4/0/0/0**; full suite:
**1920/0/0/0**, BUILD SUCCESS. Independent leader quick226 suites: **1816/0/0/0**; named115 suites:
**941/0/0/0**, including unchanged snapshot safety leaves, old main timeline/command parser, registry, tasks,
core/harness/app, architecture and prior capability contracts. Clean package passed.

`/tmp/cafecli19-jar-check.py` compiled against the jar and exercised actual Composition and CLI using a native Git
linked worktree: pre-anchor before tools, exact write/revert bytes and typed RETURNED provenance, snapshot commands,
child restore with two main-only anchors, drained post snapshots, unchanged project gitfile bytes and HEAD, absent
core.worktree, unchanged outside-victim bytes and durable restore audit. The fixture checks the accepted runtime
core.bare=false (creation itself remains bare); its earlier true assumption was corrected before execution without
changing product code. Actual headless CLI restored original bytes, created no project gitfile and left durable
post snapshots. Both configured-root and historical-home hijacked fixtures emitted repair guidance on stderr,
preserved exact gitfile bytes, kept snapshot targets absent and audited normally returned business errors correctly.
Fixture root: `/var/folders/xb/g7dmhmz13bb4b0_j1fkdnn1m0000gn/T/cafecli-v2-ticket19-qgmk45_r`.
Packaged08/10/10-session/11/12/13/14/15/16/17-retained/18 regressions all passed against the same jar.

The requested headless anchoring correction is explicit in PRD exception11 and the parity checkpoint; v1 remains
unchanged. Logs: `/tmp/cafecli19-{focused,child-final,full-final,red6-drain}.log` and
`/tmp/cafecli-ticket19-leader-{quick,named,package}.log`. No push, real keys/external services, render change,
real-terminal claim, AGENTS edit or unrelated scratch change.
