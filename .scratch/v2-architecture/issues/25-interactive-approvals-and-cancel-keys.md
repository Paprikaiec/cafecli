# 25: Interactive approvals and cancel keys

**Parent:** `.scratch/v2-architecture/PRD.md` (`ui` Approver; contracts — cancel keys) · ADR 0020 D6
**Blocked by:** 08, 10, 24
**Status:** ready-for-agent

**What to build:** In v2 interactive mode, approval cards appear in source order and are answered from the input box,
and ESC / Ctrl+C / Ctrl+D behave exactly as the CONTEXT.md Cancel key table says.

- The interactive `Approver` built on the existing renderer-backed approval handler. An approval arriving while the
  transcript overlay is open closes the overlay, as today.
- Cancel keys and the session-level signal guard call the session's cancel; cancelling releases pending approvals as
  denied and keeps the input box text.
- The interactive project-hook trust confirmer.

- [ ] REPL rig (`repl.run()` on a background thread; wait on the run guard before a closing Ctrl+D):
      `InteractiveReplTest`, `CancelKeyTableTest` (including "Ctrl+D mid-run does nothing"),
      `SessionInterruptGuardTest`, and `CancellationScopeTest` pass against the v2 entry.
- [ ] Seam 3: a three-call batch shows its approval cards one at a time in source order.
- [ ] Seam 1: cancel during a pending approval ends the run cancelled with the approval released as denied.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 03 review).** After ticket 03, `render` still imports `hitl.ApprovalRequest`,
`ApprovalResult` and `ApprovalPolicy` (for `Renderer.promptApproval` and `PlainRenderer`). That is the last v1-shaped
surface in render's API; the interactive `Approver` in `ui` will need render-owned approval types the same way 03 did
for tool events.

**2026-10-20 (from ticket 04b review).** v2 cancels **mid-stream** (PRD intentional change 9; v1 cancels only at the
boundary). The screen must handle a half-streamed answer followed by the cancellation notice, and CONTEXT.md 取消键 /
转向消息 ("落在回合边界而非流式回答中途", "流式回答不可中途打断") need rewording at switch-over (ticket 29).

**2026-10-21 (from ticket 08 review).** `Approver.approve(ApprovalRequest, CancellationToken)` is blocking and must release when the token cancels. `PermissionCapability` serializes session requests, rechecks queued cancellation/grants, and rejects pending/queued calls on cancellation. `setEnabled(false)` clears grants and invalidates replies from the old enabled period so late always-allow replies cannot restore them. Interactive UI must use this policy rather than maintaining another grant set. Close the session after the run to drain owned listeners; do not close it on its own loop callback.
