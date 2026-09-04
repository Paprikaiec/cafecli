# 13 — Reuse DiffBlock for approval-time file previews

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md` and the HITL/policy documentation chain required by
`AGENTS.md` section 5.4.

## What to build

Show an optional diff preview inside the render-thread-owned approval UI for file mutations:

- For `write_file`, derive the existing and proposed file contents only after the request has
  passed policy checks, then render the preview with the existing `DiffBlock` model.
- Compose the preview with the ephemeral `ApprovalBlock`; do not fork the LCS/hunk algorithm or
  introduce a second ANSI diff renderer.
- Preserve the approval invariant: workers park on `ApprovalPump`, the render thread only draws,
  and the main/input thread alone reads keys or line input.
- Keep non-file approvals and unreadable/new/deleted file cases usable with concise fallbacks.
- Apply path-policy limits, content-size limits, sensitive-data handling, and audit behavior before
  exposing any preview. Update the HITL/policy docs required by `AGENTS.md` section 5.4.

## Acceptance criteria

- [ ] `write_file` approval shows the proposed change through `DiffBlock`
- [ ] new/deleted/unreadable files use correct coordinates or a safe fallback
- [ ] no duplicate diff algorithm or raw ANSI renderer is introduced
- [ ] render thread never reads input; input thread never draws the preview
- [ ] non-file approval decisions and approve-all semantics are unchanged
- [ ] policy, HITL, audit, child-registry, and render-thread regression tests pass
- [ ] required HITL/policy documentation is updated

## Blocked by

- `08-diff-block.md`
- `11-hitl-approval-render-thread.md`
