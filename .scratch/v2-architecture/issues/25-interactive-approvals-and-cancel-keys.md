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
