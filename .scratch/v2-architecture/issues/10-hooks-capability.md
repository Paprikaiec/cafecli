# 10: Hooks capability

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` permission chain; contracts) · ADR 0020 D5, D6 · ADR 0012
**Blocked by:** 08, 09
**Status:** ready-for-agent

**What to build:** Lifecycle Hooks work in v2 exactly as in v1 — including the rule that a hook's ALLOW only skips
approval and never lifts a Guard refusal.

- Reuse the hook dispatcher and tenants. PreToolUse / PostToolUse become permission participants that run before the
  `Approver` (strictest of BLOCK > ASK > ALLOW > CONTINUE across tenants; ALLOW skips approval only; ASK forces it;
  BLOCK yields `BLOCKED` with the reason fed back). Stop becomes an iteration-end participant. UserPromptSubmit runs
  at session submit.
- The Read-before-Write Ledger and Finish Gate tenants live in per-agent state.
- The four events stay off by default and are enabled by today's properties. Project hook trust stays pinned by
  content fingerprint, with the confirmer as a port (headless behaves as v1).
- Hook context arrives through the boundary inbox as its own kind; a Finish Gate block appends a stop-block entry.

- [ ] Seam 1: a write outside the root is refused by the Guard even when a hook tenant returns ALLOW.
- [ ] Seam 1: PreToolUse BLOCK yields `BLOCKED` with the reason in the next request; ASK forces the `Approver` even
      when the policy wouldn't ask.
- [ ] Seam 1: the four Finish Gate cases pinned by `AgentFinishGateTest`, re-expressed here.
- [ ] Seam 1: the ledger blocks a blind write and a stale write; a fresh agent starts with an empty ledger.
- [ ] Seam 1: a UserPromptSubmit BLOCK aborts a headless run as v1 does.
- [ ] With no hook properties set, recorded requests equal those of a run without the capability.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04c review).** `harness.AgentSession.submit(String) → RunOutcome` has no way to say "this
submission was blocked by UserPromptSubmit" (v1 headless: exits 1 with `UserPromptSubmit hook 拦截，run 中止: …`, and
the hook may also rewrite the prompt). The spot is marked inside `submit`. Widen the facade here (e.g. a submit result
that is either a run outcome or a blocked submission) — only `app.Cli` calls it today, so the change is cheap.

**2026-10-21 (from ticket 08 review).** `ToolPreflight.Allow` carries the effective call, and `Complete` carries both that call and the result; composition preserves argument edits while the assistant log retains the original call. Plain Allow means continue, not skip subsequent permission participants. Add explicit harness routing for Hook ALLOW/ASK before `PermissionCapability` asks; do not represent ALLOW by returning generic Allow and assuming the later approver is bypassed. Guards remain in leaf execution. Hook audit records should enqueue through the owned audit writer, never write files on a loop callback.
