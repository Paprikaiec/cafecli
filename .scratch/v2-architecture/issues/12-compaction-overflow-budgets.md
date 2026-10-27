# 12: Compaction, overflow recovery, and budgets

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` context; `core` overflow callback) · ADR 0020 D4, D5
**Blocked by:** 09
**Status:** done (2026-10-21)

**What to build:** Long runs keep going: when the context gets too big, older history is summarized and the run
continues; when the provider reports a context overflow, the run compacts and retries once instead of failing. The
history itself is never deleted.

- Compaction log writer: at the iteration boundary, when the projected size passes the threshold derived from the
  model's context window (today's ratio), append a summary entry covering a range of log entries; the covered entries
  stay in the log. It may reuse today's summarization prompt. Emit a compaction event.
- A projection stage replaces each covered range with its summary.
- The overflow callback runs the same writer; core retries the request once; a second overflow ends the run with a
  typed overflow failure.
- Token budget and stagnation detection become iteration-end participants (reuse the existing budget logic); core
  keeps only the hard cap.

- [x] Seam 1: past the threshold, the next recorded request shows the summary instead of the covered range, the log
      still holds every covered entry, and a compaction event was emitted.
- [x] Seam 1: a fake-provider overflow causes compaction and exactly one retry; a second overflow ends the run with a
      typed overflow failure.
- [x] Seam 1: stagnation and token-budget exits behave as in `AgentBudgetTest`, now as typed outcomes.
- [x] The summary kind round-trips.
- [x] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** Core's overflow handling: `onContextOverflow(log, token)` is called once per
iteration; core retries the request once **only if the callback returned at least one entry** (a recovery that
appends nothing changed nothing, so a retry would overflow again) — a refinement of the PRD's "calls it once … and
retries once". The retry does not count against the hard cap; a second overflow ends `Failed(CONTEXT_OVERFLOW)`. The
iteration-boundary writer seam and `IterationDecision`'s typed budget stop for this ticket's participants were added
in the 04b review.

**2026-10-21 (from ticket 11 review).** Ticket 11 adds AgentScope.model() (actual profile model), messages() (registered-kind presentation of an unchanged log snapshot), cached SystemPrompt invalidation, and SessionRegistrar.onClear. Memory hints consume visible message presentations; when adding projection stages, keep custom/hidden semantics and append-only source bytes intact. MemoryExtraction defines BEFORE_COMPACTION but remains unimplemented/off; do not silently enable extraction.


**2026-10-21 (leader acceptance).** Added pure projection phases, ordered boundary/overflow log writers, the shared compaction capability, typed Summary ranges and Compaction events, and continuation-aware harness budget participants. Compaction estimates actual-model 90% threshold from projected messages plus the cached system prompt; it preserves the last three actual user rounds and tool-call/result pairs. Summary entries append at the physical tail, projection restores their covered source position, repeated compaction supersedes prior coverage, and source entries/JSON bytes remain intact. Visible/hidden kinds are presented through the same pipeline; memory hints now consume projected chronology so an old summary URL cannot supersede a recent retained URL. Empty/failed/cancelled/tool-calling summaries claim no coverage; no eligible recovery means no retry, and a second overflow is typed CONTEXT_OVERFLOW failure. Subscriber exceptions propagate. /clear discards old source ranges. No memory extraction was enabled.

Actual v1 budget behavior is retained: unlimited default token hard limit (Integer.MAX_VALUE), explicit current/legacy property controls only, cached input not counted twice, raw source-order tool names/arguments independent of call ids, stagnation before token/hard cap, per-agent state reset at run start. Ordinary iteration-end participants fold first; budgets run only when continuation is requested and keep already supplied entries. A natural final answer can cross token threshold and complete; Stop-requested continuation must pass the budget. Stop text-only iterations leave the earlier tool-signature window intact. CLI maps typed token/stagnation reasons to exact v1 descriptions; no presentation strings were added to core.

Observed red-to-green evidence includes missing boundary summary, missing stagnation stop, missing Summary round-trip fixture, cached-prompt size omitted from threshold, old summary URL overriding retained URL, and Stop text clearing signatures. Fresh Standards and Spec reviews both caught source indices shifting after an earlier filter; a session regression proved retained UserMessage(two) disappeared, then passed after the source-normalization phase fix, including retained tool pairing/source bytes. Root and Spec also caught generic Java-record budget text; both public CLI regressions were red then green. The necessary internal refinement is recorded in ADR0020 D4/D5 and the PRD registration sentence: source-range normalization before ordinary view stages, registration order within each phase; the user-visible parity whitelist is unchanged. Metadata wrappers and inferred entry identity were rejected as unnecessary interface cost/ambiguity. Both rereviews clean.

Final implementer focused suites **76/0/0/0**, broad named suites **346/0/0/0**. Independent leader quick **1669/0/0/0**, independent named compaction/budget/memory/round-trip/config/CLI/hooks/permission/steering/v1 budget-compactor-token/registry/architecture suites **256/0/0/0**, including 8 architecture rules. Clean package passed. Actual packaged fake-provider checks passed exact stagnation/token exit text, completed write bytes before budget termination, final answer over threshold, Stop continuation budget, and unrecoverable first-run overflow without ineffective retry; all prior memory/hook/default-off/guard/session-identity/audit checks passed again. External services and real keys were not used. No v1 implementation/render changes or real-terminal claim. Local commit only, no push.
