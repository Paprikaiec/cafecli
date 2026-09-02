# Hard-terminated live rows: re-justify or remove

Status: done
Type: AFK
Decided: 2026-08-19 — **RETAINED** (ADR 0010 amendment A18). Removal passed the entire gate
(372/372), and that green was the finding: cell-level assertions cannot tell "the writer ended this
row" from "this row ran out of columns". JLine encodes soft-wrap continuation by *omitting* the
newline, so without terminators the 活区 becomes one logical line of n × width — measured via
`historyText()`, which is what a user's copy actually yields. Two new gates pin it. `LiveRegionStrip`
has zero executable change; `HardTerminationCouplingTest` retained and restated.

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A17

## What to build

Decide the fate of hard-terminating every [[活区]] row, and act on the decision.

Appending an explicit line terminator to each live-region row was introduced for two reasons. One was
presentational: without it the display library joins rows by auto-wrap on delayed-wrap terminals, so
the whole live region becomes a single logical line that the terminal re-flows as a unit, tearing
words across the fold. The other was arithmetic: it made the erase distance computable, because the
re-flow unit became the row again.

The second justification is **gone** — the erase-distance regime was deleted. The first may or may not
still hold now that a [[destructive resize reset]] repaints rather than repairs, and now that nothing
depends on predicting how the terminal re-flows the live region.

This is a decision to be settled by evidence, not inherited silently. Remove it, run the offline gate
and the conformance fixtures, and see what breaks. If nothing does, it stays removed. If something
does, it stays with a written justification that does not reference erase distance.

Note that it also interacts with a known coupling: a ghost line in the display's own row model, caused
by the trailing terminator, is what made height-only changes shift the cursor. That coupling is pinned
by an existing test, which should be revisited as part of this decision rather than left asserting a
behaviour that may no longer be wanted.

## Outcome

**RETAINED** (ADR 0010 amendment A18). Removal was implemented and measured first, per the method
above: the offline gate was **green with it removed** (targeted 120/120; `-Pphase16-smoke` 372/372,
2026-08-19). That green is blindness, not clearance — both regimes paint identical cells, and the
gate read only the cell grid. Extending the gate to read logical-line structure (`historyText()`,
what a Terminal.app copy yields) makes removal **red**: the live region comes back as one
soft-wrapped line instead of one line per row. The surviving justification is that a logical line is
the terminal's own unit for selection, copy, find, and re-flow. It appeals to no erase distance and
predicts no re-flow.

## Acceptance criteria

- [x] The outcome is one of: removed with the offline gate and conformance fixtures green, or retained
      with a written justification that does not appeal to erase-distance computability
      — retained; justification in ADR 0010 A18 and on `LiveRegionStrip.hardTerminated`
- [n/a] If removed, no word-tearing across a fold appears on a delayed-wrap terminal in the screen-truth
      assertions — not removed. Measured finding: this is **no longer observable offline** at all, because
      the destructive reset erases the re-flowed screen before any assertion reads it.
- [n/a] If removed, the ghost-line coupling test is retired or rewritten to match the new reality rather
      than left pinning a dead behaviour — not removed; the behaviour it pins is live
- [x] If retained, the justification is recorded in the decision record and the coupling test's purpose
      is restated in its own terms — `HardTerminationCouplingTest` now states its subject as the rule
      that a `Display` is never handed a geometry change, and disclaims justifying hard termination
- [x] The full offline gate passes either way — targeted 189/189, `-Pphase16-smoke` 372/372
- [x] Repository documentation no longer describes hard termination as the precondition for computable
      erase distance — `AGENTS.md`, `CONTEXT.md` (new 逻辑行 entry), ADR 0010 A17/A18

## Blocked by

- `.scratch/destructive-resize-epoch/issues/02-turbulence-gate-eager-erase-deferred-redraw.md`
