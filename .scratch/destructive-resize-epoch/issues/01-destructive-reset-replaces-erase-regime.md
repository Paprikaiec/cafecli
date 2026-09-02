# Destructive reset replaces the erase-distance regime

Status: done
Type: AFK
Delivered: 2026-08-17, offline gate green (`ScreenTruthRegressionTest` 19/19), commit `6b6646c`.
Real terminal: first pass FAILED 3/3 (`replay=0/60` — an `InlineRenderer.emit` routing defect kept
streamed output out of `ReplayLog`); fixed 2026-08-18, re-check clean 3/3
(`spinner=1 dock=1 replay=60/60`). Only the `fullscreen` pattern has been run — see issue 11.

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A1, A3, A3a, A9, A16, A17

## What to build

A single settled geometry change becomes a [[destructive resize reset]]: erase the visible screen,
erase the terminal's saved scrollback, home the cursor, then repaint the [[bounded replay window]]
rendered at the new geometry. Nothing from the previous [[geometry epoch]] is trusted to survive.

This slice deliberately covers only a *single* change at a time. Animated [[geometry turbulence]] —
where changes keep arriving — is the next slice. A single resize needs no gate, which is what makes
this the thinnest complete path through the whole mechanism.

It is a **replacement, not an addition**. The erase-distance regime is deleted in the same change:
relative erase and bottom-edge pinning, the reflow row arithmetic, the ghost-line display tracking
that existed to keep that arithmetic correct across height-only changes, the zero-delay resize frame,
and the stale-geometry retry gates whose job was to avoid writing a layout composed at a stale width.
A dual regime is explicitly rejected — the codebase's central difficulty has been not knowing which
answer to "where is the live region" was in force. The plain renderer remains the escape hatch.

Every observed dimension change ends the epoch, width and height alike. There is no reflow-relevant
subset: a height-only change was measured shifting the cursor by one row through a ghost line, and
that silent shift is what made each *subsequent* width change erase one row short.

Two seams are introduced here because the rest of the work depends on them. Geometry and time are
**injected** rather than reached for, following the existing manual-frame-ticker precedent. The
row-budgeted view becomes a **pure function** of blocks, width, and budget.

The bound is a row budget in screenfuls, default ×3, rendered backwards from the newest item until
the budget fills. Measured: the budget is advisory, because whole blocks render — a 44-row budget
produced 104 rows and a 132-row budget produced 207. Either accept the overshoot or render the oldest
block partially, but pin the chosen behaviour by test.

The preservation tests are **wrong under this contract, not obsolete**. They assert that no transcript
row is destroyed; rows outside the replay window are now supposed to be destroyed. They are replaced
by their inverse. The storm script's check is rewritten likewise — its blindness to integrity is what
let an earlier fix pass while silently destroying transcript rows.

## Acceptance criteria

- [ ] A single width change leaves the screen containing exactly the bounded replay window rendered at
      the new width, and nothing else
- [ ] A single height change leaves the same, with no residual rows
- [ ] A simultaneous width-and-height change leaves the same
- [ ] The gating assertion is made against the offline screen model via the existing screen-truth rig,
      and references no erase distance, cursor arithmetic, or frame internals
- [ ] The row-budgeted view is a pure function of blocks, width, and budget, testable with no terminal
- [ ] Block-quantization behaviour is pinned by an explicit test — a budget smaller than the newest
      block yields a documented, asserted outcome
- [ ] The default budget is three screenfuls of the current terminal height
- [ ] Geometry source and clock are injected; tests drive both without a real terminal or real time
- [ ] Relative erase, bottom-edge pinning, reflow row arithmetic, ghost-line display tracking, the
      zero-delay resize frame, and the stale-geometry retry gates are gone from the tree
- [ ] No flag, system property, or fallback preserves the old erase regime
- [ ] Slow-drag preservation tests are replaced by their inverse: exactly the replay window survives
- [ ] The storm script asserts replay-window completeness, not only copy counts
- [ ] Text typed but not submitted survives a resize
- [ ] The input box remains editable throughout
- [ ] Repository inline-rendering conventions and known-boundaries documentation reflect the new
      contract, including that scrollback does not survive a resize

## Blocked by

None - can start immediately
