# 12: Compaction, overflow recovery, and budgets

**Parent:** `.scratch/v2-architecture/PRD.md` (`harness` context; `core` overflow callback) · ADR 0020 D4, D5
**Blocked by:** 09
**Status:** ready-for-agent

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

- [ ] Seam 1: past the threshold, the next recorded request shows the summary instead of the covered range, the log
      still holds every covered entry, and a compaction event was emitted.
- [ ] Seam 1: a fake-provider overflow causes compaction and exactly one retry; a second overflow ends the run with a
      typed overflow failure.
- [ ] Seam 1: stagnation and token-budget exits behave as in `AgentBudgetTest`, now as typed outcomes.
- [ ] The summary kind round-trips.
- [ ] `mvn test -Pquick` and the architecture tests are green.

## Comments

**2026-10-20 (from ticket 04b review).** Core's overflow handling: `onContextOverflow(log, token)` is called once per
iteration; core retries the request once **only if the callback returned at least one entry** (a recovery that
appends nothing changed nothing, so a retry would overflow again) — a refinement of the PRD's "calls it once … and
retries once". The retry does not count against the hard cap; a second overflow ends `Failed(CONTEXT_OVERFLOW)`. The
iteration-boundary writer seam and `IterationDecision`'s typed budget stop for this ticket's participants were added
in the 04b review.
