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

- [ ] `SnapshotDoesNotTouchProjectGitTest` and `SnapshotRestoreStaysInsideProjectRootTest` pass unchanged.
- [ ] Seam 1: a run writes a file, `revert_turn` restores it, and anchoring happened before any tool start event
      (prior art: `SnapshotTimelineAnchoringTest`).
- [ ] Seam 1: in a project whose `.git` is a gitfile pointing into the snapshot home, snapshots are disabled, the
      guidance is emitted, and the `.git` file's bytes are unchanged.
- [ ] `mvn test -Pquick` and the architecture tests are green.
