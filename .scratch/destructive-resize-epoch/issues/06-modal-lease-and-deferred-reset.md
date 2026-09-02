# Modal lease and deferred reset on return

Status: done
Type: AFK
Delivered: 2026-08-19 (ADR 0010 A12), commit `31a79bb`. Three requirements the amendment did not
state, found by building it: the handover needs a frame boundary (`RenderLoop.atFrameBoundary`), a
leased frame must not self-request the next one, and the modal's erase is `CSI 2 J` only — never
`3 J`, whose scrollback belongs to the primary screen underneath. Offline only.

## Parent

`.scratch/destructive-resize-epoch/PRD.md` — ADR 0010 amendment A12

## What to build

While a modal owns the terminal — the [[转写浏览层]], the slash palette — the render loop writes
**nothing**. A geometry change is still observed and still ends the [[geometry epoch]], but the
[[destructive resize reset]] is *deferred*: exactly one fires on return if the geometry changed while
the modal was open, and none fires if it did not. The modal handles its own geometry change
internally, re-measuring and re-rendering at the new size.

This extends the single-writer invariant rather than bending it: one writer owns the terminal at a
time, and the modal is that writer for its duration.

It also closes a defect that predates this work. The overlay installs no suspend/restore pair,
reasoning that its fullscreen display already owns the screen — which holds only because the overlay
is idle-only, so no producer events means no frames. **A resize posts a frame regardless of
idleness**, so resizing during an open overlay already paints live-region rows onto the alternate
screen, over it. The overlay meanwhile samples its page size and display geometry once at open and
never re-measures. Under the reset the same path would erase the screen and saved scrollback while the
alternate screen is active, then restore a primary screen it had just erased.

Not trusting the terminal's own restore across a geometry change is deliberate: it reflowed that
content while we were not looking, which is the situation this whole change exists to stop trusting.

The modal's own resize is the *easy* case — it owns the whole alternate screen, which has no
scrollback to preserve, so it clears and repaints unconditionally.

## Acceptance criteria

- [ ] No live-region bytes reach the terminal while a modal is open
- [ ] A geometry change during a modal is recorded and ends the epoch
- [ ] Exactly one reset fires on return when the geometry changed while the modal was open
- [ ] No reset fires on return when the geometry did not change
- [ ] The overlay re-measures its page size and redraws correctly when resized while open
- [ ] The slash palette receives the same lease treatment
- [ ] Resizing during an open overlay no longer paints live-region rows over it
- [ ] The screen after returning from a modal that spanned a resize contains exactly the bounded
      replay window at the new geometry, asserted against the offline screen model
- [ ] Nested or rapid open/close across a resize does not leave a reset pending indefinitely

## Blocked by

- `.scratch/destructive-resize-epoch/issues/02-turbulence-gate-eager-erase-deferred-redraw.md`
