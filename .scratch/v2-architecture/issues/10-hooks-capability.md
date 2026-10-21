# 10: Hooks capability

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` permission chain; contracts) · ADR 0020 D5, D6 · ADR 0012
**Blocked by:** 08, 09
**Status:** done (2026-10-21)

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

- [x] Seam 1: a write outside the root is refused by the Guard even when a hook tenant returns ALLOW.
- [x] Seam 1: PreToolUse BLOCK yields `BLOCKED` with the reason in the next request; ASK forces the `Approver` even
      when the policy wouldn't ask.
- [x] Seam 1: the four Finish Gate cases pinned by `AgentFinishGateTest`, re-expressed here.
- [x] Seam 1: the ledger blocks a blind write and a stale write; a fresh agent starts with an empty ledger.
- [x] Seam 1: a UserPromptSubmit BLOCK aborts a headless run as v1 does.
- [x] With no hook properties set, recorded requests equal those of a run without the capability.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04c review).** `harness.AgentSession.submit(String) → RunOutcome` has no way to say "this
submission was blocked by UserPromptSubmit" (v1 headless: exits 1 with `UserPromptSubmit hook 拦截，run 中止: …`, and
the hook may also rewrite the prompt). The spot is marked inside `submit`. Widen the facade here (e.g. a submit result
that is either a run outcome or a blocked submission) — only `app.Cli` calls it today, so the change is cheap.

**2026-10-21 (from ticket 08 review).** `ToolPreflight.Allow` carries the effective call, and `Complete` carries both that call and the result; composition preserves argument edits while the assistant log retains the original call. Plain Allow means continue, not skip subsequent permission participants. Add explicit harness routing for Hook ALLOW/ASK before `PermissionCapability` asks; do not represent ALLOW by returning generic Allow and assuming the later approver is bypassed. Guards remain in leaf execution. Hook audit records should enqueue through the owned audit writer, never write files on a loop callback.

**2026-10-21 (from ticket 09 review).** `AgentSession.submit` now returns `SubmitResult.Completed(RunOutcome)` or `SteeringAccepted`; add a blocked-submission branch for UserPromptSubmit and update CLI handling. `AgentScope.messageKind` declares typed serialization/presentation and `AgentScope.inbox()` supplies an agent-local enqueue-only producer. Register hook/stop-block kinds and extend `MessageKindsRoundTripTest` fixtures: it discovers actual headless registrations and fails when a registered kind has no fixture. Do not mutate the log from an inbox producer.

**2026-10-21 (leader acceptance).** Implemented the four lifecycle events with the existing dispatcher / tenants, a dedicated typed hook config slice, invocation-local strictest routing before approval, and per-agent dispatcher / ledger / Finish Gate state. Generic preflight and approval participants retain registration order and effective argument edits. ALLOW skips only interactive approval; ASK forces it even with approval disabled or remembered grants; headless retains v1 base-registry behavior (BLOCK only, no hook-allow audit). PostToolUse follows typed normal-return provenance, including business ERROR but excluding throws / guard refusal / unknown tools. Finish summaries fold source order and effective targets; command verification still reads the leaf's existing exit-code data. Ledger survives runs; summary / stop-block counter reset each run. Hook context and stop-block entries declare their serialization / presentation and are covered by the actual-composition round-trip fixture.

Ordinary UserPromptSubmit runs after admission outside the session lock, before core logging / provider access; steering bypasses it. Blocking is a typed SubmitResult branch and preserves the exact headless stderr / exit 1 behavior. Shell leaves accept explicit cancellation with old v1 overloads unchanged; no v2 thread-local bridge. A generic core check after the iteration-end callback makes cancellation during blocking Stop return Cancelled while preserving atomic assistant/result logging and callback exception propagation. Loader interpolation shares one env > property > project .env > home .env algorithm with lazy v1 process access and explicit captured v2 sources. Project hooks retain fingerprint trust and headless skip behavior. Hook observations only enqueue immutable data; the owned audit writer constructs / sanitizes / persists entries and session close drains it.

Red-to-green evidence from the resumed implementer: both ticket 08 effective-call regressions were red (2/2) then green; strengthened cancellation cases were 4/1 (Stop returned Completed) then 4/0; configured-shell session identity fixture was 1/1 (missing identifiers) then 1/0. Fresh Standards / Spec reviews found config slice coupling, naming / duplicate resolution smells, and missing Pre/Post session identity; all corrected and both rereviews clean. SessionRegistrar.sessionId now supplies one session-UUID, captured by hook agent state; Pre/Post are stable across runs and differ across sessions; UPS / Stop retain v1 null identity.

Final implementer focused suites: **354/0/0/0**. Independent leader final quick: **1623/0/0/0**; independent named permission / hook lifecycle / headless / config / round-trip / core / hook leaf / registry / architecture suites: **237/0/0/0**, including 8 architecture rules. Clean package passed. Actual packaged fake-provider checks passed PreToolUse BLOCK, events default-off, headless ALLOW plus Guard (outside victim bytes unchanged), UserPromptSubmit BLOCK with exact stderr / exit 1, stable Pre/Post session identifiers within each process and distinct identifiers between processes, automatic write bytes, malformed-input audit, unknown-tool audit exclusion, and durable audit JSONL at process return. No real provider / network / API keys used. No render implementation changes or real-terminal claim. Local commit only, no push.
