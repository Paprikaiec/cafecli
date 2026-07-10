# 08: Eval A/B — builtin tenants on the SWE-bench-Live band

Status: ready-for-human
Type: HITL (needs cloud machine access + human judgment on band selection)

## Parent

`.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012)

## What to build

The narrative payoff: measure what the two builtin tenants (Finish Gate + Read-before-Write Ledger) do to the resolve rate, using the Phase 0 rebaseline as the comparison arm.

Arms: Phase 0 configuration (edit_file + 80 iterations) vs the same plus `-D`-enabled tenants, on the Phase 0 band (6 core-fail instances) and optionally a wider clean band. Per-instance, pull hook fire/block counts from traces/audit to attribute any delta. Also backfill the blocked quantitative claim: count "ended without testing" (writes with no subsequent agent-run verification command) across the existing historical traces — this number belongs in ADR 0012's evidence section.

Anti-cheating constraint (user red line, restated): the Finish Gate detects only commands the agent itself ran in the workspace; nothing in the harness or the gate may be parameterized per instance or reference the scoring test set.

## Acceptance criteria

- [ ] Both tenants enabled via system properties in the eval solve script; jar built from the merged slices
- [ ] Before/after resolve rate on the Phase 0 band, with per-instance hook fire/block counts
- [ ] "Ended without testing" count from historical traces (no-edit baseline + A/B runs)
- [ ] Report under the eval reports directory; ADR 0012 evidence section updated with the quantitative numbers
- [ ] Explicit statement in the report that gate detection was instance-agnostic

## Blocked by

- 01 (`01-dispatcher-stop-finish-gate.md`) + 02 (`02-pre-post-tooluse-read-ledger.md`) — tenants must exist; slices 03–07 NOT required
- Cloud machine (100.126.237.3) reachability — SSH down as of 2026-07-10
